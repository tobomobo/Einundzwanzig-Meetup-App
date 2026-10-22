# 09 — Meetups, Location & Einundzwanzig Portal (porting spec)

Target: native Android (Kotlin, Jetpack Compose, Material 3 Expressive).
Source of truth: Flutter app at the repo root (branch `t3/explain-flutter-choice`, commit `623bdc9`).

This spec covers the meetup directory (portal meetup list, favorites, details), location acquisition and
distance rules, the "nearby meetups" map search, the OSM location picker, geocoding, and the whole
Einundzwanzig portal REST client (login via Nostr kind 22242, organizer views, event CRUD, leader
management, RSVP, courses/lecturers, public leader list). Many other domains call into these services, so
the service layer here has to be specified precisely even where the consuming screens belong to other domains.

---

## 0. Source files and status

| File | Status | Notes |
|---|---|---|
| `lib/models/meetup.dart` | live | `Meetup` model + offline fallback list `allMeetups` / `fallbackMeetups`. `Meetup.fromJson` is **dead** (never called). |
| `lib/services/meetup_service.dart` | live | Portal meetup list fetch + in-memory cache + favorite/slug/name/organizer resolution. |
| `lib/services/meetup_location_service.dart` | live | GPS → nearest meetups, radius constants, distance. |
| `lib/services/nearby_meetup_service.dart` | live | 3-stage location acquisition (`getCurrentLocation`) + nearby search. |
| `lib/services/geocoding_service.dart` | live | Nominatim free-text place search. |
| `lib/services/portal_api_service.dart` | live | Portal REST client (auth + read + write). |
| `lib/services/portal_leader_service.dart` | live | Public `/api/meetup-leaders` list, per-meetup leader check, 24h cache. |
| `lib/screens/portal_meetups_screen.dart` | live | "Meine Meetups" (login, my meetups, leader admin, manage events, event editor). |
| `lib/services/portal_meetups_screen.dart` | **dead** | Older duplicate of the screen above (no tour, no NpubChip). Not imported anywhere. Do not port. |
| `lib/screens/community_portal_screen.dart` | live | Static link hub to einundzwanzig.space web pages ("Portal-Webseite"). |
| `lib/screens/nearby_meetups_screen.dart` | live | Map + radius + date filters + geocoded "planned" search. |
| `lib/screens/meetup_details.dart` | live | Meetup detail page. |
| `lib/screens/meetup_selection.dart` | live | Full-screen multi-select favorites picker (Home CTA). |
| `lib/widgets/meetup_search_sheet.dart` | live | Bottom-sheet multi-select favorites picker (Profile, Identity setup). |
| `lib/screens/location_picker_screen.dart` | live | Tap-on-map venue picker (used by Nostr calendar event creation). |
| `lib/screens/meetup_list_screen.dart` | **dead** | Not imported anywhere. Documented in §2.9 for completeness; recommend not porting. |
| `lib/screens/create_meetup.dart` | **dead** | Fake form ("request sent" without any request). Not reachable. Do not port. |
| `lib/screens/radar.dart` | **dead** | Legacy "RADAR" screen. Not reachable. Do not port. |

Helpers read to understand behavior: `lib/services/meetup_key.dart` (slug rule), `lib/services/safe_url_launcher.dart`,
`lib/widgets/npub_chip.dart`, `lib/services/meetup_calendar_service.dart`, `lib/models/calendar_event.dart`,
`lib/services/meetup_event_matcher.dart`, `lib/models/user.dart`, `lib/services/signing_service.dart` (SignedEvent JSON),
`lib/tours/more_tours.dart`, `lib/services/guide_service.dart`, `lib/mixins/guide_service_host.dart`, `lib/theme.dart`,
and the call sites in `home_screen.dart`, `community_hub_screen.dart`, `calendar_screen.dart`, `admin_panel.dart`,
`meetup_verification.dart`, `event_badge_session_service.dart`, `event_badge_auth_service.dart`,
`event_calendar_screen.dart`, `profile_edit.dart`, `identity_setup_screen.dart`, `nfc_writer.dart`, `app_shell.dart`.

---

## 1. Overview and user-visible features

The app pulls its meetup directory from the Einundzwanzig portal (`https://portal.einundzwanzig.space`).
Users pick one or more **favorite meetups** (stored as portal IDs). Those drive the Home dashboard cards,
the home-screen widget, chat-room lookup and the NFC writer. A **nearby search** shows meetups around the
current GPS position or around a geocoded place on an OpenStreetMap map, filtered by radius and date.
**Organizers** sign in to the portal with their Nostr key: the app signs a kind-22242 challenge event locally
and exchanges it for a Sanctum bearer token. Signed in, they can list the meetups they manage, create and edit
events, and add or remove co-leaders. The location service also supplies the radius rules (5 km / 10 km /
50 km) that the badge issue and claim flows in other domains depend on, and the public leader list is the
per-meetup authority check when scanning badges.

### Feature list (every user-visible item)

Portal ("Meine Meetups"):
- Sign in to portal with Nostr (local key, Amber, NIP-46 bunker, NIP-07): one button, spinner + "Anmeldung läuft …".
- Sign-in failure snackbar: "Anmeldung fehlgeschlagen: <server/network error>" (red, floating).
- Sign out icon in the app bar (only when connected), no confirmation; server token revoke is best effort.
- List of my portal meetups (pull-to-refresh), with an "Organisator" badge when `is_leader`.
- Empty state: icon + "Du verwaltest noch keine Meetups im Portal."
- Footer "Verbunden mit portal.einundzwanzig.space".
- Per meetup "Termine verwalten" button → event list screen.
- Leader-only: "Organisatoren" section listing co-leaders (creator marked with star + "Ersteller" badge, others removable).
- Leader-only: "Admin hinzufügen" → dialog to enter npub → validation snackbar "Bitte einen gültigen npub eingeben." → success "Organisator hinzugefügt" / failure "Aktion fehlgeschlagen: <err>".
- Remove co-leader: confirmation dialog "Diesen Organisator entfernen?" → "Organisator entfernt" / failure.
- Co-leader row: name + NpubChip (tap opens njump.me profile with light haptic; long-press copies npub with medium haptic + "npub kopiert" snackbar; chip asynchronously replaces the npub with the Nostr display name).
- Event list per meetup (pull-to-refresh): "BESTEHENDE TERMINE", rows with local date/time and location (or "Zum Bearbeiten antippen"), empty state "Noch keine Termine für dieses Meetup.".
- Extended FAB "Termin anlegen" → event editor (create). Tap a row → event editor (edit).
- Event editor: date+time picker (time defaults to 19:00 if time dialog is dismissed), location, description (4 lines), link (URL keyboard); validation snackbar "Bitte Datum & Uhrzeit wählen."; save button with spinner "Wird gespeichert …"; success → back + green snackbar "Termin angelegt ✓" / "Termin aktualisiert ✓"; failure snackbar with portal error.
- First-visit guided tour (1 step) on "Meine Meetups".

Nearby ("Meetups in der Nähe"):
- Map (OSM tiles) with search-center dot, radius circle, meetup pins (orange if a matching date exists, grey otherwise); tap pin → meetup details.
- Mode toggle "Hier & jetzt" (GPS) / "Geplant" (geocoded place search).
- Place search with 600 ms debounce, inline spinner, up to 5 Nominatim results; selecting one sets the center and shows its name.
- Location-problem hint (in "Hier & jetzt" when location unavailable) with "Standort erneut versuchen".
- Radius slider 5–100 km in 5 km steps (default 25 km), live label, search on release.
- Date chips: "Jederzeit" / "Tag wählen" (single day) / "Zeitraum wählen" (range); active chips show the chosen date(s).
- Result header "N Meetups im Umkreis"; result cards with city, "X km entfernt", next date ("Heute, 19:00" / "Morgen, 19:00" / "EEE, dd.MM.yyyy · HH:mm"), event location, "+N weitere Termine" or "Kein Termin angekündigt", "Details" button, directions button (opens openstreetmap.org).
- Empty states: "Keine Meetups im Umkreis" + "Vergrößere den Umkreis oder ändere Ort/Datum."; planned mode without place: search icon + "Ort suchen (z.B. Hamburg)".

Meetup details:
- Country chip, city title, "TERMINE" card (loading spinner, empty "Aktuell keine Termine im Kalender.", tappable rows → bottom sheet with title/date/location/description), "LINKS" card (Telegram, Twitter/X, Nostr), "STANDORT" card with coordinates + "Route" (Google Maps), app-bar open-in-new for the Telegram/community link. Link failure snackbar "Konnte Link nicht öffnen".

Favorites:
- Full-screen "MEETUP AUSWÄHLEN" (multi-select with stars, search, selected count in app bar, save button "N Favoriten speichern" / "Ohne Favorit speichern").
- Bottom-sheet variant (Profile, Identity setup): search "Stadt suchen...", stars, done button "N ★".
- Silent migration of legacy city-name favorites to portal IDs (unambiguous) or removal (ambiguous).

Location picker ("Ort des Events"): tap to set/move pin, coordinates (5 decimals), "Zu meinem Standort" (jumps map only, does not set the pin), "Ort übernehmen" (disabled until a point is set).

Community portal web hub ("Portal-Webseite"): quick-access tiles, discover grid, association links, podcast/media links, social links, footer; all open externally; failure snackbar "Konnte Link nicht öffnen: <url>".

Haptics in this domain: opening Nearby from the bottom nav triggers a light haptic (if pref `haptic_enabled`, default true, owned by app-shell domain); NpubChip tap = light, long-press copy = medium. No other haptics. No share actions in this domain.

---

## 2. Screens & UX flows

Color tokens referenced (from `lib/theme.dart`): `cDark #0C0C0E`, `cCard #131315`, `cSurface #0F0F11`, `cOrange #F7931A`,
`cCyan #D4A017` (gold, despite the name), `cGreen #22C55E`, `cRed #E53935`, `cText #FFFFFF`, `cTextSecondary #9CA0A8`,
`cTextTertiary #868A94`, `cBorder #1C1C22`, `cTileBorder #202028`, `kTileRadius = 10`, `kTileGap = 10`. Map these to the M3
color scheme of the theme domain; pixel parity is not required, but the information hierarchy and states are.

### 2.1 PortalMeetupsScreen — "Meine Meetups"

Entry points (no arguments):
- Home dashboard tile "Meine Meetups" (`tileActLookup` "Nachschlagen" / `tilePortal` / `tilePortalSub`) — home domain.
- Community hub → "Portal" area screen → row `paMine` "Meine Meetups" / `paMineSub` "Termine verwalten (Organisator)" — community domain.

State: `checking=true`, `connected=false`, `loggingIn=false`, `loadingMeetups=false`, `meetups=[]`.

Init:
1. `connected = PortalApiService.hasToken()` (only checks token presence, NOT `tokenMatchesCurrentKey`); `checking=false`.
2. If connected → `loadMeetups()`.
3. Tour: after 700 ms, if `GuideTour.myMeetups` not completed (pref `guide_completed_myMeetups`), start `MyMeetupsTour.steps()`.
4. On dispose: finish the tour if it is the active one.

Layout:
- App bar: title `portalTitle` (target of the tour spotlight). Action (only if connected): logout icon, tooltip `portalLogout`.
- Body while `checking`: centered spinner.
- **Not connected view** (scrollable, padding 24): 40 dp gap, 64×64 rounded tile with hub icon (orange), title `portalNotConnected`, body `portalConnectInfo`, full-width filled button: icon login (or 16 dp spinner while logging in), label `portalConnect` / `portalConnecting`; disabled while logging in.
- **Connected view**:
  - while loading: spinner + `portalLoadingMeetups`.
  - else pull-to-refresh list (padding 16): empty → event_busy icon (44) + `portalNoMeetups`; else one `MeetupAdminCard` per meetup; then footer `portalSource` (11 sp, tertiary).

Actions:
- Login: `PortalApiService.loginWithNostr()`. ok → `connected=true`, load meetups. Fail → snackbar `"${portalLoginFailed}: ${error ?: ''}"` red floating.
- Logout: `PortalApiService.logout()` → `connected=false`, `meetups=[]`.
- Load: `getMyMeetups()` (returns [] on ANY failure incl. 401 — see §14).

**MeetupAdminCard** (per `PortalMeetup`):
- Row: meetup `name` (16 sp bold) + if `isLeader` a small orange badge `portalLeader` ("Organisator").
- Full-width outlined orange button `portalManageEvents` (event_note icon) → push ManageEventsScreen(meetup).
- If `isLeader`: divider; header row shield icon + `ldTitle` ("Organisatoren"); while loading admins a 20 dp spinner; else:
  - one row per admin map (`id`, `name`, `nostr`, `is_creator`):
    - leading icon: star (orange) if creator, person (secondary) otherwise;
    - primary text: `name` if non-empty, else `NpubChip.shorten(nostr)` if non-empty, else `"?"`;
    - secondary: if both name and npub present → NpubChip(nostr, showIcon=false) (shows display name from Nostr kind 0 once fetched; tap → njump.me, long-press → copy);
    - trailing: creator → badge `ldCreator`; else red "X" button (disabled while busy) → remove flow.
  - full-width outlined green button `ldAddButton` ("Admin hinzufügen"), disabled while busy → add flow.
- Admin list load: `PortalApiService.getMeetupLeaders(meetup.id)` on card init (only if leader).

Add flow: AlertDialog(title `ldAdd`, autofocus TextField hint `ldAddHint`, actions `resetCancel` / `ldAddDo`).
Result trimmed; empty/cancel → nothing. Validation: `startsWith("npub1") && length >= 60` else snackbar `ldNpubInvalid` red.
Then `busy=true`, `addMeetupLeader(id, npub)`; ok → snackbar `ldAdded` (green #388E3C) + reload admins; fail → `"${ldFailed}: ${error}"` red.

Remove flow: requires `admin["id"]` to be an Int (else silently return). AlertDialog(title `ldRemove`, content `ldRemoveConfirm`,
actions `resetCancel` / `ldRemove` red bold). Confirm → `removeMeetupLeader(meetupId, userId)`; ok → `ldRemoved` + reload; fail → `"${ldFailed}: ${error}"`.

**ManageEventsScreen(meetup: PortalMeetup)**:
- App bar title = `meetup.name`. Extended FAB (orange) add icon + `portalNewEvent`.
- Load: `getMyMeetupEvents()`, keep `meetupId == meetup.id`, sort ascending by the raw `start` **string** (lexicographic). No past filter (all events shown).
- Loading: spinner + `portalLoadingEvents`. Content: pull-to-refresh list (bottom padding 100 for the FAB):
  header `portalExistingEvents.uppercase()`; empty → event_busy (40) + `portalNoEvents`; rows:
  - 42×42 orange-tinted tile with event icon;
  - line 1: `fmtStart(ev.start)` = parse with **portalStartUtc** (§8.12) → `"dd.MM.yyyy  HH:mm"` (TWO spaces between date and time); if unparsable show the raw string;
  - line 2: `location` (1 line, ellipsis) if non-empty, else `portalTapToEdit`;
  - trailing edit icon; tap → EventEditor(meetup, existing=ev).
- FAB → EventEditor(meetup). When the editor returns `true`: snackbar `portalCreatedOk` (create) / `portalUpdatedOk` (edit), green (cGreen), reload.

**EventEditor(meetup, existing?)**:
- Title: `portalEditEvent` if editing else `portalEventTitle`.
- Context card: groups icon + meetup name.
- Field "DATUM & UHRZEIT" (`portalFieldStart` uppercased label): tappable box; placeholder `portalPickDate`; when set shows `"dd.MM.yyyy  HH:mm"` (two spaces) with orange border.
  - Date picker: initial = start ?: now; first = now − 1 day; last = now + 1095 days (365×3).
  - Then time picker: initial = start ?: now. If the time dialog is dismissed the time is **19:00**. If the date dialog is dismissed nothing changes.
- `portalFieldLocation` + hint `portalFieldLocationHint` (1 line).
- `portalFieldDescription` + hint `portalFieldDescriptionHint` (4 lines).
- `portalFieldLink` + hint `portalFieldLinkHint` (keyboard type URL).
- Save button (height 52): icon check or spinner; label `portalSaving` while saving, else `portalUpdate` (edit) / `portalSave` (create).
- Prefill (edit): location/description/link from `existing`; start = **see §8.12 / §14 (timezone bug; recommended fix)**.
- Save:
  - `start == null` → snackbar `portalNeedStart` red.
  - `startIso = start.toUtc().toIso8601String()` → exact format `YYYY-MM-DDTHH:mm:ss.SSSZ` (e.g. `2026-07-21T17:00:00.000Z`).
  - edit: `existing.id == null` → snackbar `"Fehler: Termin ohne ID"` (hard-coded German) and abort; else `updateMeetupEvent(eventId, start, location?, description?, link?)` where each text is `trim()` and empty → null (null fields are OMITTED, so clearing a field does NOT clear it on the portal).
  - create: `createMeetupEvent(meetupId, start, location?, description?, link?)`.
  - ok → pop(true). Fail → snackbar `res.error ?: "Fehler"` red.
- No recurrence UI (API supports it; see §5).

Tour hook: `GuideTour.myMeetups`, single step: target = app bar title, `titleKey guideMyMeetupsListTitle`, `bodyKey guideMyMeetupsListBody`,
`scrollIntoView=false`, `waitTimeout=800ms`. Completion stored by the guide domain as `guide_completed_myMeetups` (bool).

l10n keys: portalTitle, portalLogout, portalNotConnected, portalConnectInfo, portalConnecting, portalConnect, portalLoginFailed,
portalLoadingMeetups, portalNoMeetups, portalSource, portalLeader, portalManageEvents, ldTitle, ldAdd, ldAddHint, ldAddDo,
ldAddButton, ldNpubInvalid, ldAdded, ldFailed, ldRemove, ldRemoveConfirm, ldRemoved, ldCreator, resetCancel, portalNewEvent,
portalLoadingEvents, portalExistingEvents, portalNoEvents, portalTapToEdit, portalCreatedOk, portalUpdatedOk, portalEditEvent,
portalEventTitle, portalFieldStart, portalPickDate, portalFieldLocation, portalFieldLocationHint, portalFieldDescription,
portalFieldDescriptionHint, portalFieldLink, portalFieldLinkHint, portalSaving, portalUpdate, portalSave, portalNeedStart,
guideMyMeetupsListTitle, guideMyMeetupsListBody, npubCopied (NpubChip).

### 2.2 NearbyMeetupsScreen — "MEETUPS IN DER NÄHE"

Entry points (no args): bottom-nav action "In der Nähe" (`navNearby`, app shell, light haptic); Community hub Portal-area row
`paMap` "Karte" / `paMapSub` "Meetups in der Nähe".

State: `mode=HERE`, `radiusKm=25.0`, `centerLat/Lng=null`, `centerLabel=""`, `locStatus=OK`, `dateMode=ANY`, `dayFrom/dayTo=null`,
`placeResults=[]`, `searchingPlace=false`, `loading=true`, `results=[]`.

Init → `initHere()`:
1. `loading=true`; `loc = NearbyMeetupService.getCurrentLocation()` (prompts for permission if needed).
2. `locStatus = loc.status`. If OK → center = position, `centerLabel = nbCenterHere`. Else center = null.
3. `runSearch()`.

`runSearch()`: center null → `loading=false, results=[]`. Else `loading=true`, `results = NearbyMeetupService.search(center, radiusKm, dateMode, dayFrom, dayTo)`, `loading=false`, then `fitMap()`.

`fitMap()` (post-frame): points = center (if any) + all results with coords. Empty → nothing. One → move(point, zoom 10). Else fit bounds with padding 50 dp, maxZoom 12. Exceptions swallowed.

Layout (column):
1. **Map**, height 230 dp. Initial center = center ?: (50.5, 9.5); initial zoom 9 if center else 5. All gestures except rotation.
   Tiles `https://tile.openstreetmap.org/{z}/{x}/{y}.png`, user-agent package `space.einundzwanzig.meetup`.
   Layers: radius circle (if center) radius = `radiusKm*1000` m, fill orange α0.08, stroke orange α0.4, 1.5 dp;
   markers: center = 24 dp gold (cCyan) circle with 2 dp white border; each result with coords = 40 dp box, location_on icon size 38,
   orange if `nextEvent != null` else secondary grey; tap → MeetupDetailsScreen(meetup).
2. Scrollable list (padding 12,12,12,24):
   a. **Mode toggle** (segmented, 2 segments): `nbModeHere` (my_location icon), `nbModePlanned` (event icon). Active = orange bg, black text.
   b. **Controls card**:
      - if mode PLANNED → place search (§ below); else if `locStatus != OK` → location hint row: location_off icon, `nbLocationDeniedSub` (11 sp), TextButton `nbRetryLocation` → `initHere()`.
      - Radius row: radar icon, `nbRadius`, right-aligned `nbRadiusValue(round(radiusKm))` orange. Slider min 5, max 100, divisions 19; `onChange` updates label only; `onChangeFinished` → `runSearch()`.
      - Date header: calendar icon + `nbDateSingle` ("Datum").
      - Chips (wrap, spacing 8): `nbDateAny` (active when ANY) → setDateAny; single-day chip label = `dd.MM.yyyy` of dayFrom when mode SINGLE_DAY else `nbPickDay` (event icon) → pickSingleDay; range chip label = `nbDateFromTo(dd.MM., dd.MM.)` when RANGE else `nbPickRange` (date_range icon) → pickRange. Active chip = orange bg, black text.
   c. **Results header** (hidden while loading or center null): `nbResultsHeader(count)`.
   d. **Results**: loading → spinner; PLANNED with no center → info box (search icon, `nbSearchPlace`); empty → info box (event_busy icon, `nbNoneInRadius`, sub `nbNoneInRadiusSub`); else result cards.

Place search (PLANNED mode): TextField (hint `nbSearchPlace`, search prefix icon, 16 dp spinner suffix while searching).
`onChanged(q)`: cancel debounce; if `q.trim().length < 2` → clear results; else after 600 ms → `searchingPlace=true`, `GeocodingService.search(q)` (limit 5) → results.
Results drop-down rows: place icon + `displayName` (1 line). Select → hide keyboard, center = place, `centerLabel = shortName`, clear results, text field = shortName, `runSearch()`.
Below: if center and label non-empty → location icon + `centerLabel` (gold).

Mode switch: set mode. HERE: if center null → `initHere()`; else `centerLabel = nbCenterHere` and `runSearch()` (BUG: it keeps the current center, which may be a geocoded place; see §14). PLANNED: no action (waits for a place).

Date actions:
- `pickSingleDay`: date picker (initial dayFrom ?: now, first now−1d, last now+365d, dark orange theme). Picked → mode SINGLE_DAY, dayFrom=d, dayTo=null, search.
- `pickRange`: date range picker (first now−1d, last now+365d, initial range if both set). Picked → mode RANGE, dayFrom/dayTo, search.
- `setDateAny`: mode ANY, clear days, search.

Result card (per `NearbyMeetup n`):
- Header row: 42 dp orange tile with location icon; `n.meetup.city` (15 sp bold) (group name NOT shown); (only if `distanceKm != null`) `nbKmAway(distanceKm.toStringAsFixed(distanceKm < 10 ? 1 : 0))` (11 sp, cTextSecondary; exact algorithm and vectors in §8.13);
  trailing directions button (gold icon) → open `https://www.openstreetmap.org/?mlat=<lat>&mlon=<lng>#map=15/<lat>/<lng>` externally (only if resolvable; silent otherwise).
- If `nextEvent`: box with schedule icon + `formatEventDate(start)`:
  today → `"${nbToday}, HH:mm"`; tomorrow → `"${nbTomorrow}, HH:mm"`; else `DateFormat('EEE, dd.MM.yyyy · HH:mm')` (Flutter uses the intl default locale which is never set → **English weekday abbreviations**; recommend app locale in native).
  Then location row if `location` non-empty and `location.lowercase() != "ort unbekannt"`. Then `nbMoreDates(allEvents.size − 1)` if more than one.
- Else `nbNoDate`.
- Full-width tonal "Details" button (`nbDetails`) → MeetupDetailsScreen(meetup).

l10n keys: nbTitle, nbCenterHere, nbModeHere, nbModePlanned, nbRadius, nbRadiusValue, nbDateSingle, nbDateAny, nbPickDay,
nbDateFromTo, nbPickRange, nbSearchPlace, nbLocationDeniedSub, nbRetryLocation, nbResultsHeader, nbNoneInRadius,
nbNoneInRadiusSub, nbKmAway, nbMoreDates, nbNoDate, nbDetails, nbToday, nbTomorrow, navNearby, paMap, paMapSub.

### 2.3 MeetupDetailsScreen(meetup: Meetup)

Entry points: Nearby (pin tap, Details button), Home favorite card "Info" action (home domain; disabled when the favorite can't be resolved to a `Meetup`).
(`events.dart` and `meetup_list_screen.dart` also push it but are unreachable.)

Load: `MeetupCalendarService().fetchMeetupsPortalFirst()` (calendar domain), keep events where
`title.lowercase().contains(city.lowercase()) || location.lowercase().contains(city.lowercase())`, sort by start ascending.
NOTE: no date filter here. The portal path already drops events older than now−6 h, but the ICS fallback returns past events as well.

Layout (scroll, padding 20):
- App bar title `city.uppercase()`; action open_in_new (only if `telegramLink` non-empty) → `launchHttpUrl(telegramLink)`.
- Country chip (orange outline, `country.uppercase()`), city (28 sp bold).
- Card "TERMINE" (`sectionDates`, calendar_month icon gold): spinner while loading; empty → `meetupNoDatesCal`; else rows:
  date chip (width 100) `formatDate(start)` = `"<Mo|Di|Mi|Do|Fr|Sa|So>, dd.MM. HH:mm"` (German weekday abbreviations HARD-CODED, Monday=Mo); title (2 lines); location (1 line) if non-empty; chevron.
  Tap → modal bottom sheet (max 70% height, drag handle): title (orange 22 bold), calendar row `"dd.MM.yyyy, HH:mm Uhr"` (hard-coded "Uhr"), location row (if non-empty), divider, `sectionDescription` label, description text.
- Card "LINKS" (`sectionLinks`, always shown even if empty):
  - Telegram (if `telegramLink`): label "Telegram", value = link → `launchHttpUrl(link)`.
  - Twitter / X (if `twitterUsername`): divider only if Telegram shown; label "Twitter / X"; value `"@<username>"`; tap → `launchHttpUrl("https://twitter.com/" + username.replace("@",""))`.
  - Nostr (if `nostrNpub`): divider (always); label "Nostr"; value `NpubChip.shorten(npub)` (head 10, tail 8, monospace); tap → open `https://njump.me/<npub.trim()>` externally (errors only logged).
- Card "STANDORT" (`sectionLocation`): `"${lat.toFixed(4)}, ${lng.toFixed(4)}"` monospace + TextButton `meetupRoute` → `launchHttpUrl("https://www.google.com/maps/search/?api=1&query=<lat>,<lng>")` (Dart `double.toString()` formatting, e.g. `48.1351`, `9.0`). Shown even for 0,0.
- `launchHttpUrl` failure → snackbar `errorOpenLink`.

l10n keys: sectionDates, meetupNoDatesCal, sectionDescription, sectionLinks, sectionLocation, meetupRoute, errorOpenLink.

### 2.4 MeetupSelectionScreen — favorites (full screen)

Entry: Home "home meetup" tile call-to-action when the user has no favorite (`homeMeetupId` and `favoriteMeetupIds` both empty) → push; on return Home reloads the user, then recomputes the favorite cards (order matters: reload user FIRST). Returns `true` on save.

Load: `UserProfile.load()` + `MeetupService.fetchMeetups()` (no offline fallback). Migration of stored favorites (§8.4) → preselect.
Layout: app bar `msSelectMeetup`, action = selected count (orange, hidden while loading). Loading → spinner.
Search field (hint `msSearchMeetup`, clear button when non-empty): filters by `city` or `country` contains (case-insensitive; NOT name).
Hint row: star icon + `msFavoritesHint`.
List: cards (radius 16, border orange 1.4 when active else cBorder 1): location tile, `city.uppercase()` (1 line), group name if `name` non-empty and differs from city (case-insensitive), trailing star filled/outline. Tap toggles membership by **portal ID**.
Bottom button (orange, black text): `msSaveNone` if none else `msSaveFavorites(n)`; spinner while saving.
Save: `favoriteMeetupIds = selected` (insertion order of the set: migrated ones first in stored order, then taps; removing + re-adding moves to end), `homeMeetupId = first ?: ""`, `user.save()`, pop(true).

l10n: msSelectMeetup, msSearchMeetup, msFavoritesHint, msSaveNone, msSaveFavorites.

### 2.5 MeetupSearchSheet — favorites (bottom sheet)

Callers: Profile edit (`_showMeetupPicker`, meetups pre-sorted by city; on done: favorites=list, homeMeetup=first, "home meetup missing" flag = empty) and Identity setup (meetup step; on done: selection; "save" writes favorites and `homeMeetupId = first`). Both own their persistence; the sheet only returns a list.

Props: `meetups: List<Meetup>`, `initialSelected: List<String>`, `onDone(List<String>)`.
Init: migrate initialSelected (id kept if present; else unique city match → that id; else dropped, silently).
Layout: draggable sheet initial 0.9, min 0.5, max 0.95. Autofocus search (hint `profileSearchCity`) filters `city` or `name` contains (case-insensitive).
Rows (ListTile): title = `name` if (count of meetups with same city, case-insensitive) > 1 and name non-empty, else `city`; active → orange bold. Subtitle = `name` if non-empty and != city (case-insensitive) (duplicates the title in the multi-group case — §14). Trailing star. Tap toggles by ID.
Bottom button `"${selected.size} ★"` → `onDone(selected)` then dismiss. Dismissing the sheet by drag/back DISCARDS changes.

### 2.6 LocationPickerScreen(initialLat?, initialLng?) → returns LatLng?

Entry: Nostr calendar event creation (`event_calendar_screen`, other domain) passes the current venue if non-zero; result `(latitude, longitude)` written back.
Init: if both initial values non-null and not both 0 → preset pick.
Layout: app bar `locPickTitle`; action my_location (spinner while locating, tooltip `locPickJumpToMe`).
Hint bar (card bg): `locPickHint` if no pick else `locPickHintDone`.
Map: center = pick ?: (51.1657, 10.4515); zoom 15 if pick else 5; no rotation; tap → set/move pick. Marker: 44 dp `location_on_rounded` orange, anchored so the pin TIP is at the point (bottom-center anchor).
Bottom: coordinates `"${lat.toFixed(5)}, ${lng.toFixed(5)}"` (monospace) when picked; full-width button `locPickConfirm` (check icon), disabled (surface color, tertiary text) until picked → pop(pick).
Jump to me: `MeetupLocationService.resolveLocation()` (Flutter also downloads the meetup list here, which is unnecessary — native should call the location provider directly). If the result is (0,0) (failure) do nothing (silent; consider a snackbar). Else move camera to it at zoom 13. Does NOT set the pick (deliberate: avoids storing the sofa location).

### 2.7 CommunityPortalScreen — "Portal-Webseite" (static link hub)

Entry: Community hub Portal-area row `paWeb` "Portal-Webseite" / `paWebSub`.
All taps → `launchUrl(url, external)`; failure → snackbar `"${errorOpenLink}: $url"` red.
App bar (pinned, 0.5 dp bottom border): back arrow; title = SVG `assets/images/einundzwanzig_logo.svg` (height 16); action open_in_new (tooltip `portalOpen`) → `https://portal.einundzwanzig.space`.
Sections (uppercase 10 sp tertiary titles):
1. `portalQuickAccess` — horizontal row of 68×82 tiles (labels hard-coded German):
   Profil → `https://portal.einundzwanzig.space/`; Meetups → `https://einundzwanzig.space/meetups/`; Podcast → `https://einundzwanzig.space/podcast/`;
   Shoutout → `https://shoutout.einundzwanzig.space`; Spenden → `https://einundzwanzig.space/spenden/`.
2. `portalDiscover` — 2-col grid (aspect 1.65):
   `portalMeetupMap`/`portalMeetupMapSub` (orange, map icon) → `https://einundzwanzig.space/meetups/`;
   `portalShop`/`portalMerch` (gold, store) → `https://einundzwanzig.space/shops/`;
   `portalProfile2`/`portalProfile` (purple, hub) → `https://portal.einundzwanzig.space/`;
   `portalSoundboard`/`portalClipsSounds` (orange, voice) → `https://einundzwanzig.space/soundboard/`.
3. `portalAssociation` — list: `portalAboutAssoc` → `https://einundzwanzig.space/verein/`; `portalMembership` → `https://verein.einundzwanzig.space/`;
   `portalStatutes` → `https://einundzwanzig.space/files/Statuten_v1.3.pdf`; `portalContact` → `https://einundzwanzig.space/kontakt/`; `portalPrivacy` → `https://einundzwanzig.space/datenschutz/`.
4. `portalPodcastMedia` — `portalPodcast` → `https://einundzwanzig.space/podcast/`; `portalBeginnerPath` → `https://einundzwanzig.space/podcast/der-weg/`;
   `portalInterviews` → `https://einundzwanzig.space/podcast/interviews/`; "YouTube" → `https://www.youtube.com/c/EinundzwanzigPodcast`;
   `portalMediaArticles` → `https://einundzwanzig.space/media/`; "Soundcloud" → `https://soundcloud.com/einundzwanzig_beats`.
5. `portalSocialNetworks` — "Nostr" → `https://njump.me/npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc`;
   "X / Twitter" → `https://x.com/_einundzwanzig_`; "Instagram" → `https://www.instagram.com/einundzwanzig_podcast`; `portalShoutoutSend` → `https://shoutout.einundzwanzig.space`.
Footer: 40×2 orange bar, `portalInfotainment`, `portalTagline` (italic 11 sp grey), underlined "einundzwanzig.space" → `https://einundzwanzig.space`.

### 2.8 Related screens owned by other domains (context only)

- Community hub "Portal" area (`paTitle`): rows → CalendarScreen (paMeetups, paEvents), CoursesScreen (paCourses), NearbyMeetupsScreen (paMap), PortalMeetupsScreen (paMine), CommunityPortalScreen (paWeb). Tour `GuideTour.portalArea`.
- `PortalEventsScreen` (community hub, RSVP list) and CalendarScreen (RSVP, attendees) consume `PortalApiService` (§5).

### 2.9 Dead screens (do not port; documented for completeness)

- `MeetupListScreen` ("MEETUPS"): search (`msSearchMeetup`) over city/country/description; FutureBuilder states (spinner, error "Fehler beim Laden" + error text + "Erneut versuchen", empty "Keine Meetups gefunden.", no match `mlNoMeetupFor(query)`); grouped by country code (empty → "Sonstige"), groups sorted by code, items by city; country names via countryDE/AT/CH/ES/NL/IT/FR else raw code; "N Meetup(s)" count; card with city, description (2 lines), telegram link; tap → details. Not referenced anywhere.
- `CreateMeetupScreen`: four text fields; button pops and shows "ANFRAGE GESENDET 🚀" without sending anything.
- `RadarScreen`: legacy profile + badge grid + "BADGE ABHOLEN"; unreachable.

---

## 3. Data models

### 3.1 `Meetup`

| Field | Type | Default | Source key(s) in `/api/mobile/meetups` (see §8.1) |
|---|---|---|---|
| `id` | String | required | `id` → `toString()` (int 107 → `"107"`); missing → `name`; missing → `"unknown"` |
| `name` | String | `""` | `name?.toString()` ?: `""` — the GROUP name, e.g. "Einundzwanzig Berlin Mitte" |
| `city` | String | required | `city` ?: `name` ?: `"Unbekannt"` |
| `country` | String | required | `country` ?: `"DE"` (ISO-3166 alpha-2 expected) |
| `telegramLink` | String | required | **`url`** ?: `""` (community/telegram link; may lack scheme, e.g. `t.me/x`) |
| `lat` | Double | required | first numeric of [`latitude`, `lat`] (num or numeric string), else `0.0` |
| `lng` | Double | required | first numeric of [`longitude`, `lon`, `lng`], else `0.0` |
| `logoUrl` | String | `""` | `logo` ?: `""` (may be relative `/storage/...`) |
| `description` | String | `""` | **`intro`** ?: `""` |
| `website` | String | `""` | `website` ?: `""` |
| `portalLink` | String | `""` | `portalLink` trimmed; if empty and `slug` non-empty → `https://portal.einundzwanzig.space/de/meetup/<encodeComponent(slug.trim())>` |
| `twitterUsername` | String | `""` | `twitter_username` ?: `""` |
| `nostrNpub` | String | `""` | `nostr` ?: `""` (npub of group/organizer) |
| `coverImagePath` | String | `""` | first non-empty of `cover`, `image`, `logo` (string check: `!= null && toString().isNotEmpty`) |

Derived: `groupName: String? = if (name.isNotEmpty() && name.lowercase() != city.lowercase()) name else null`.
`hasCoords` (NearbyMeetup) = `lat != 0.0 || lng != 0.0`. "No coordinates" everywhere means `lat == 0 && lng == 0`.
No equality override (identity). Treat `id` as the identity key.

Note: the field `adminSecret` was deliberately removed (Security Audit Fix 5). Do not reintroduce.

Offline fallback list `allMeetups` (= `fallbackMeetups`), used when the portal list is empty/unreachable:

| id | city | country | telegramLink | lat | lng |
|---|---|---|---|---|---|
| `m_muc` | `München` | DE | `t.me/einundzwanzig_muc` | 48.1351 | 11.5820 |
| `m_hh` | `Hambmurg` (sic, typo) | DE | `t.me/einundzwanzig_hh` | 53.5511 | 9.9937 |
| `m_b` | `Berlin` | DE | `t.me/einundzwanzig_berlin` | 52.5200 | 13.4050 |

(See §14 regarding the typo: its slug ends up in badges signed offline by organizers.)

Dead `Meetup.fromJson` (do not port, listed so nobody "fixes" the live parser to match it): `telegram`→telegramLink, `twitter`|`twitter_username`, `lat`/`lon`, country heuristic (wien/innsbruck/graz→AT, zürich/bern/luzern→CH, mallorca→ES, else DE), id fallback = epoch ms.

### 3.2 Location types

```
enum GpsStatus { OK, DENIED, SERVICE_DISABLED, ERROR }               // MeetupLocationService (coarse)
enum LocationStatus { OK, DENIED, DENIED_FOREVER, SERVICE_DISABLED, NO_FIX, ERROR }  // NearbyMeetupService (fine)
data class LocationResult(status: LocationStatus, position: Position? = null)
// Position needs: latitude, longitude, accuracy (m), timestamp (for last-known age)

data class MeetupCandidate(meetup: Meetup, distanceKm: Double)
data class LocationMeetupResult(
  status: GpsStatus, lat: Double = 0.0, lng: Double = 0.0,
  candidates: List<MeetupCandidate> = emptyList()   // sorted ascending by distanceKm
) {
  val nearest get() = candidates.firstOrNull()
  val withinCreationRadius get() = candidates.filter { it.distanceKm <= 10.0 }
  val isAmbiguous get() = candidates.size >= 2 && abs(candidates[1].distanceKm - candidates[0].distanceKm) < 2.0
  companion object { const val AMBIGUITY_THRESHOLD_KM = 2.0 }
}
```
Status mapping `LocationStatus → GpsStatus`: DENIED→DENIED, DENIED_FOREVER→DENIED, SERVICE_DISABLED→SERVICE_DISABLED, NO_FIX→ERROR, ERROR→ERROR, OK→OK.

### 3.3 Nearby types

```
enum DateMode { ANY, SINGLE_DAY, RANGE }
enum SearchMode { HERE, PLANNED }
data class NearbyMeetup(meetup: Meetup, nextEvent: CalendarEvent? = null,
                        allEvents: List<CalendarEvent> = emptyList(), distanceKm: Double? = null) {
  val hasCoords get() = meetup.lat != 0.0 || meetup.lng != 0.0 }
data class GeoPlace(displayName: String, shortName: String, lat: Double, lng: Double)
```
`CalendarEvent` (calendar domain) fields used here: `title, description, location, startTime (local), url, meetupId, meetupPortalLink, portalEventId`.

### 3.4 Portal types

`PortalMeetup` (from `/api/my-meetups` `data[]`):

| Field | Type | JSON key | Rule |
|---|---|---|---|
| id | Int | `id` | MUST be an integer, else the entry is dropped |
| name | String | `name` | `(name ?? '').toString()` |
| slug | String | `slug` | `(slug ?? '').toString()` |
| isLeader | Boolean | `is_leader` | `== true` (strict boolean) |

`PortalMeetupEvent` (from `/api/my-meetup-events` `data[]`):

| Field | Type | JSON key | Rule |
|---|---|---|---|
| id | Int? | `id` | `as int?` (non-int non-null would throw in Dart → whole list lost; native: tolerate → null) |
| meetupId | Int | `meetup_id` | int else 0 |
| start | String | `start` | required (null → entry dropped); kept RAW (see §8.12) |
| location | String? | `location` | `as String?` |
| description | String? | `description` | `as String?` |
| link | String? | `link` | `as String?` |

`PortalResult(ok: Boolean, statusCode: Int, error: String? = null, data: Any? = null)` — `statusCode 0` = no HTTP response.

Leader admin row (from `/api/meetup/{id}/leaders`, untyped map): `id` (Int user id, required for removal), `name` (String), `nostr` (npub String), `is_creator` (Boolean).

RSVP map (untyped, tolerant): `status` ('attending'|'maybe'|'none'), `going`/`is_going`/`rsvped` (Boolean), `attendees`/`count`/`total`/`rsvps` (Int count). Attendee entry: `{npub: String, name: String}`.

Public event map (from `/api/meetup-events/{date}`): `id` (Int event id), `start` (String, UTC without zone), `location`, `description`, `link`,
meetup fields either nested `meetup: {id, name, logo, portalLink, city}` or FLAT dotted keys `"meetup.id"`, `"meetup.name"`, `"meetup.logo"`, `"meetup.portalLink"`, `"meetup.city"`. (Conversion to `CalendarEvent` is calendar domain.)

Login response: `{ "token": String, "user": {...} }`. `/api/user` response: map with at least `nostr` (npub or null).

---

## 4. Persistence

Flutter storage backends (for migration): legacy `SharedPreferences` → Android file `FlutterSharedPreferences.xml`, keys prefixed `flutter.`;
ints stored as Long; string lists stored as `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!" + JSON array` (older versions:
`"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu" + base64(ObjectOutputStream)`). `flutter_secure_storage 10.3.4` → file `FlutterSecureStorage.xml`, key
prefix `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg_`, values encrypted with keystore-wrapped AES (migration owned by the
identity/secure-storage domain spec).

| Key (exact) | Store | Type / format | Writer | Reader | Notes |
|---|---|---|---|---|---|
| `portal_api_token` | secure | String (Sanctum bearer token) | `storeToken`, `setManualToken` | `getToken` (every request) | Deleted by `deleteToken` (401 paths, logout, mismatch). Read failure ⇒ treated as logged out. |
| `portal_api_token_npub` | secure | String npub of the identity the token was bound to | `storeToken` (if `SigningService.npub()` non-empty), healed by `tokenMatchesCurrentKey` | `tokenMatchesCurrentKey` | Deleted with the token. |
| `portal_leaders_cache` | prefs | String: raw JSON body of `/api/meetup-leaders` | `PortalLeaderService._saveToCache` | `_loadFromCache` | Only written after a non-empty parse. |
| `portal_leaders_cache_ts` | prefs | Int (epoch ms) | same | same | TTL 24 h. |
| `rsvp_cache_<eventId>` | prefs | String: JSON of RSVP map | `getRsvpCached` | `getRsvpCached` | TTL 1 h; removed by `clearRsvpCache` (unused). One key per event, never garbage collected. |
| `rsvp_cache_ts_<eventId>` | prefs | Int (epoch ms) | same | same | |
| `favorite_meetups` | prefs | StringList of favorite keys (portal IDs; legacy: city names) | `UserProfile.save()` (profile domain) via favorites UIs | `UserProfile.load()` | Order matters: first = home/widget anchor. |
| `home_meetup` | prefs | String = first favorite or `""` | `UserProfile.save()` | `UserProfile.load()` | Migration: if `favorite_meetups` absent and `home_meetup` non-empty → favorites = `[home_meetup]`. |
| `guide_completed_myMeetups` | prefs | Bool | guide domain | guide domain | Tour completion for "Meine Meetups". |
| `haptic_enabled` | prefs | Bool (default true) | settings domain | app shell | Gates the light haptic when opening Nearby. |

Favorites value semantics: since the ID migration, stored values are portal meetup IDs as strings (`"107"`). Values from older versions may be
city names (`"Würzburg"`); every reader must resolve via `MeetupService.resolveFavorite` (ID first, then case-insensitive city). Writers in
this domain only write IDs. Backups carry `homeMeetupId`/`favoriteMeetupIds` (backup domain; merge = union, device home wins).

In-memory caches (not persisted): `MeetupService.cache` (last successful deduped portal list), `NearbyMeetupService` meetup+event caches
(app-process lifetime, never invalidated by the UI), `PortalLeaderService` map + `loadedAt`, `MeetupCalendarService.portalLogos` (calendar domain).

Recommended native stores: keep reading legacy `FlutterSharedPreferences` once at first launch (migration module), then DataStore
Preferences with the SAME logical key names; portal token in an Android Keystore-backed encrypted store (Tink AEAD, or the identity
domain's `SecureStore`). If the legacy token cannot be decrypted, treat the user as logged out: auto-connect (home domain) logs in again
silently for local-key users, so losing the token is harmless.

---

## 5. Network

Common: base `https://portal.einundzwanzig.space`, API base `https://portal.einundzwanzig.space/api`. No retries anywhere. No custom
User-Agent in Flutter (dart:io default `Dart/x.y (dart:io)`); the native app may send its own UA. **Always send `Accept: application/json`
where Flutter does**: Laravel otherwise answers auth failures with HTML/redirects.

Header sets:
- `AUTH_JSON` (`_authHeaders`): `Accept: application/json`, `Content-Type: application/json`, `Authorization: Bearer <token>` (only if a token exists).
- `GET_OPT_AUTH` (`_get`, `rawGetStatus`): `Accept: application/json` + `Authorization: Bearer <token>` if a token exists.
- none: plain GET without extra headers.

Timeouts: portal client 15 s (whole request), `/api/mobile/meetups` 15 s, `/api/meetup-leaders` 8 s, Nominatim 10 s.

### 5.1 Endpoint table

| # | Method + path | Auth / headers | Request | Success handling | Error handling | Used by |
|---|---|---|---|---|---|---|
| 1 | `GET /api/mobile/meetups` | none | — | 200 → JSON array → §8.1 | non-200 → `[]` (log HTTP code); exception → `[]`; cache untouched | MeetupService.fetchMeetups |
| 2 | `GET /api/meetup-leaders` | none | — | 200 → `[{meetup_id:int, npubs:[String]}]` → §8.10 | non-200/exception → keep old state | PortalLeaderService |
| 3 | `POST /api/mobile/token` | `Accept`, `Content-Type` JSON (no auth) | `{"k1":<hex64>,"event":<signed kind 22242>,"device_name":"Einundzwanzig Meetup App"}` | 200/201 → `token` must be non-empty String → store → `PortalResult(ok, data=body.user)` | non-200/201 → `error=_errorFrom(r)`; empty token → `"Kein Token vom Portal erhalten."`; exception → `(0, "Anmeldung fehlgeschlagen: $e")` | loginWithNostr |
| 4 | `DELETE /api/mobile/token` | `Authorization`, `Accept` | — | ignored | ignored (best effort) | logout |
| 5 | `GET /api/user` | AUTH_JSON (getProfile) / `Accept`+`Authorization` (tokenMatches) | — | 200 → Map | getProfile: 401 → deleteToken, null; other → null | getProfile (unused), tokenMatchesCurrentKey |
| 6 | `GET /api/my-meetups` | AUTH_JSON | — | `{data:[PortalMeetup]}` | any failure → `[]` (no token deletion) | getMyMeetups; `rawGetStatus('/my-meetups')` (home organizer check) |
| 7 | `GET /api/my-meetup-events` | AUTH_JSON | — | `{data:[PortalMeetupEvent]}` | any failure → `[]` | getMyMeetupEvents |
| 8 | `GET /api/my-meetup-events/{id}` | GET_OPT_AUTH | — | attendee fallback (§8.9) | null | getRsvpAttendees |
| 9 | `POST /api/meetup-events` | AUTH_JSON | create payload (§5.2) | `_write` | `_write` | createMeetupEvent |
| 10 | `PATCH /api/meetup-events/{eventId}` | AUTH_JSON | update payload (§5.2) | `_write` | `_write` | updateMeetupEvent |
| 11 | `GET /api/meetup-events/{yyyy-MM-dd}` | GET_OPT_AUTH | — | `{data:[...]}` or bare list | null → skipped | getAllMeetupEvents |
| 12 | `GET /api/meetup-events/{id}/rsvp` | GET_OPT_AUTH | — | Map; result = `body.data` if Map else body | null | getRsvp, getRsvpCached, getRsvpAttendees |
| 13 | `POST /api/meetup-events/{id}/rsvp` | AUTH_JSON | `{"status":"attending"\|"maybe"\|"none"}` (default attending) | `_write` | `_write` | rsvp |
| 14 | `GET /api/meetup-events/{id}/attendees`, `/rsvps`, `/participants` | GET_OPT_AUTH | — | speculative attendee lists | ignored | getRsvpAttendees |
| 15 | `GET /api/courses?withDetails=1` | GET_OPT_AUTH | — | bare list or `data`/`items` | `[]` | getCourses |
| 16 | `GET /api/courses/{id}` | GET_OPT_AUTH | — | `data ?? body` Map | null | getCourse |
| 17 | `GET /api/lecturers?withDetails=1` | GET_OPT_AUTH | — | list (tolerant) | `[]` | getLecturers |
| 18 | `GET /api/lecturers/{id}` | GET_OPT_AUTH | — | `data ?? body` Map | null | getLecturer |
| 19 | `GET /api/meetup/{meetupId}/leaders` | GET_OPT_AUTH | — | `data` list or bare list of Maps | `[]` | getMeetupLeaders |
| 20 | `POST /api/meetup/{meetupId}/leaders` | AUTH_JSON | `{"npub":"npub1…"}` | `_write` | `_write` | addMeetupLeader |
| 21 | `DELETE /api/meetup/{meetupId}/leaders/{userId}` | AUTH_JSON (Content-Type sent, no body) | — | `_write` | `_write` | removeMeetupLeader |
| 22 | `GET https://nominatim.openstreetmap.org/search` | `User-Agent: EinundzwanzigMeetupApp/1.0 (nostr meetup app)` | query §5.4 | §5.4 | `[]` | GeocodingService |
| 23 | `GET https://tile.openstreetmap.org/{z}/{x}/{y}.png` | UA identifying `space.einundzwanzig.meetup` | — | map tiles | — | Nearby map, location picker |

(`GET https://portal.einundzwanzig.space/stream-calendar` ICS fallback is owned by the calendar domain.)

Generic readers:
- `_get(path)`: GET `$api$path` with GET_OPT_AUTH; non-200 → null; JSON decode; exception → null (logged).
- `rawGet(path)` = `_get` (unused publicly but keep).
- `rawGetStatus(path)` → `(status, body)`: 200 → `(200, json)`; non-200 → `(code, null)`; exception → `(0, null)`. MUST distinguish 401/403 from offline (home organizer logic relies on it).
- `_asList(body)`: `data = body is Map ? (body.data ?? body.items ?? body) : body`; must be a List; keep Map elements only.

### 5.2 Write calls (`_write`)

```
if token == null -> PortalResult(ok=false, 401, "Nicht angemeldet. Bitte zuerst mit dem Portal verbinden.")
headers = AUTH_JSON; body = payload == null ? null : jsonEncode(payload)
POST | PATCH | DELETE (others -> (false, 0, "Unbekannte Methode."))
status in {200,201,204} -> data = tryJsonDecode(body) (null if not JSON; warn unless 204) ; PortalResult(ok=true, status, data)
status == 401 -> deleteToken()
else -> PortalResult(false, status, _errorFrom(r))
exception -> PortalResult(false, 0, "Netzwerkfehler. Bist du online?")
```

Create payload (insertion order as in Dart; keys omitted when the value is absent):
```
meetup_id: Int            (always)
start: String             (always; RFC 3339 UTC, Flutter sends "YYYY-MM-DDTHH:mm:ss.SSSZ")
location / description / link: String   only if non-null AND non-empty
recurrence_type: String   only if non-null  (daily|weekly|monthly|yearly|custom)
recurrence_day_of_week: String           only if non-null
recurrence_day_position: String          only if non-null
recurrence_interval: Int                 only if non-null
recurrence_end_date: String (RFC 3339)   only if non-null
```
Portal semantics: if `recurrence_type` AND `recurrence_end_date` are present, the portal creates a series (max 100), else a single event.
Explicit `null` for recurrence enums is rejected by the portal (422/500) — NEVER serialize nulls (configure kotlinx `explicitNulls=false` or build `JsonObject` manually).

Update payload: same keys (`meetup_id` optional too); every key only if non-null (empty strings would be sent, but the UI maps blank → null).

### 5.3 Error message extraction `_errorFrom(response)` (exact)

```
try body = json(r.body)
  if body is Map:
     if body.errors is Map:
        msgs = for each value v in errors.values: if v is List -> addAll(v.map(toString)) else add(v.toString())
        if msgs nonEmpty -> return msgs.join(" · ") + " (HTTP ${code})"
     if body.message is non-empty String -> return "${message} (HTTP ${code})"
catch: ignore
switch code:
  401 -> "Nicht authentifiziert. Bitte erneut anmelden."
  403 -> "Keine Berechtigung (nur Ersteller oder Admin)."
  404 -> "Nicht gefunden."
  422 -> "Validierungsfehler. Bitte Eingaben prüfen."
  else -> "Fehler (HTTP ${code})."
```
Separator is `" · "` (U+00B7 with spaces). These strings are user-visible (snackbars); put them in string resources (de = the text above;
en translations to be added) but keep the structure.

### 5.4 Nominatim (GeocodingService.search)

- `q = query.trim()`; if `q.length < 2` → `[]` (no request).
- URL `https://nominatim.openstreetmap.org/search` with query params in this order: `q`, `format=jsonv2`, `limit=<limit, default 5>`, `addressdetails=1`, `accept-language=de,en`.
- Header `User-Agent: EinundzwanzigMeetupApp/1.0 (nostr meetup app)` (required by the Nominatim usage policy).
- Timeout 10 s. Non-200 → `[]`. Body decoded as UTF-8 from bytes. JSON array → for each `e`: `display = (e.display_name ?? '').toString()`, `name = (e.name ?? '').toString()`,
  `shortName = name.isNotEmpty ? name : display.split(',').first.trim()`, `lat = tryParse(e.lat.toString()) ?: 0`, `lng = tryParse(e.lon.toString()) ?: 0`; drop entries where `lat == 0 && lng == 0`. Exception → `[]`.
- Rate limit: policy max 1 req/s. Flutter relies only on the 600 ms UI debounce. Native: keep the debounce, cancel in-flight requests on new input, and enforce ≥1000 ms between requests (improvement, no compatibility impact).

### 5.5 Map tiles

OSM standard tiles `https://tile.openstreetmap.org/{z}/{x}/{y}.png`. flutter_map sends `User-Agent: flutter_map (space.einundzwanzig.meetup)`. Native must
set an identifying UA (osmdroid: `Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID`) and show OSM attribution ("© OpenStreetMap contributors") — Flutter omits attribution; add it in native (tile policy).

### 5.6 External links opened (all `ACTION_VIEW`, external app)

- Via **safe launcher** (`launchHttpUrl`: only `http`/`https` with non-empty host): meetup `telegramLink`, Twitter `https://twitter.com/<handle>`, Google Maps route.
- Direct (constant/constructed hosts): njump.me profile (`https://njump.me/<npub>`), OSM directions, all CommunityPortalScreen URLs, portal base.

---

## 6. Nostr

Only one event kind is produced in this domain:

**Kind 22242 — portal login (NIP-42-style client authentication, stateless).**
- Built and signed via the identity domain's `SigningService.signEvent(kind = 22242, tags = [["challenge", k1]], content = "")`, transparently with the local key, Amber (NIP-55), NIP-46 bunker or NIP-07.
- `created_at` = current unix seconds (set by the signer). Portal requires freshness < 300 s.
- Tags exactly: `[["challenge", "<k1 64-hex>"]]`. No `relay` tag. Content `""`.
- NOT published to any relay. Sent only as the `event` field of `POST /api/mobile/token`, serialized as
  `{"id","pubkey","created_at","kind","tags","content","sig"}` (SignedEvent.toJson key order; order irrelevant to the server).
- Server checks: valid BIP-340 signature over NIP-01 id, kind 22242, `challenge` tag == posted `k1`, freshness. The server keeps no state.

Other Nostr touchpoints:
- NIP-19: `MeetupService.organizerMeetupFor` converts hex → `npub` (`Nip19.encodePubkey`) before comparing with the portal `nostr` field (exact string compare after `trim()`). Leader checks compare npub strings exactly (trimmed, case-sensitive). `tokenMatchesCurrentKey` compares npubs case-insensitively.
- NpubChip (shared widget) decodes npub → hex and asks `NostrProfileService.fetchDisplayName(hex)` (kind 0 metadata lookup, profile domain).
- Meetup `nostrNpub` is shown and linked to `https://njump.me/<npub>`.

No REQ subscriptions, no relays, no replaceable events in this domain.

---

## 7. Crypto & encoding

- **k1**: 32 bytes from a CSPRNG (`Random.secure()`), each byte → 2-digit lowercase hex, concatenated → 64 chars. Kotlin:
  `SecureRandom().let { r -> ByteArray(32).also(r::nextBytes) }.joinToString("") { "%02x".format(it) }`. Verbatim Dart:
  ```dart
  final random = Random.secure();
  final k1 = List.generate(32, (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
  ```
- Event id / signature: NIP-01 / BIP-340 by the signing domain.
- npub: NIP-19 bech32, HRP `npub`.
- `portalLink` slug encoding: Dart `Uri.encodeComponent` = percent-encode UTF-8 bytes (uppercase hex) except `A-Z a-z 0-9 - _ . ! ~ * ' ( )`. Do NOT use `java.net.URLEncoder` (it turns spaces into `+` and escapes `~`, `*`, `'`, `(`, `)`, `!`). Since `portalLink` feeds `MeetupEventMatcher.portalIdentity` (last path segment comparison), encoding mismatches would break event→meetup matching.
- No hashing, KDFs or encryption in this domain.

---

## 8. Business rules & algorithms

### 8.1 `MeetupService.fetchMeetups()` (exact)

```
GET https://portal.einundzwanzig.space/api/mobile/meetups  (timeout 15 s)
if status != 200: warn "Portal antwortete mit HTTP <code>." ; return []
data = jsonDecode(body) as List
for json in data:
  image = "" ; if cover non-null && cover.toString() non-empty -> cover
               elif image non-null && non-empty -> image
               elif logo non-null && non-empty -> logo
  portalLink = json.portalLink?.toString().trim() ?? ""
  slug = json.slug?.toString().trim() ?? ""
  if portalLink empty && slug non-empty: portalLink = "https://portal.einundzwanzig.space/de/meetup/" + encodeComponent(slug)
  Meetup(id = json.id?.toString() ?? json.name ?? "unknown", name = json.name?.toString() ?? "",
         city = json.city ?? json.name ?? "Unbekannt", country = json.country ?? "DE", telegramLink = json.url ?? "",
         logoUrl = json.logo ?? "", description = json.intro ?? "", website = json.website ?? "", portalLink,
         twitterUsername = json.twitter_username ?? "", nostrNpub = json.nostr ?? "",
         lat = firstNum([json.latitude, json.lat]), lng = firstNum([json.longitude, json.lon, json.lng]),
         coverImagePath = image)
firstNum(c): for each: num -> toDouble ; String -> double.tryParse (first success) ; else continue ; default 0.0
// diagnostics: group by city.trim().lowercase(); cities with >1 entries -> one diag line naming the first 8 as "<city> <n>x",
//   plus " … und <rest> weitere"; per-city IDs at debug level
// dedupe: keep FIRST occurrence per exact id; warn "<n> Eintrag/Eintraege mit identischer ID entfernt." if any removed
cache = unique ; return unique
any exception -> warn "Fehler beim Laden der Meetups: $e" ; return []   (cache NOT cleared)
```
`??` is null-coalescing only: an empty string `city: ""` stays `""` (does NOT fall back to `name`); same for every `??` in this spec.
Two different groups in the same city are deliberately kept. Dart throws on unexpected types (e.g. non-string `url`), which drops the WHOLE list; native should coerce per field (`toString()` of primitives) — more robust, not a compatibility issue.

### 8.2 Favorite / identity resolution (MeetupService; all read the cache only, except organizerMeetupFor)

- `resolveFavorite(stored)`: `stored` empty or cache empty → null. First meetup with `id == stored`. Else meetups whose `city.lowercase() == stored.lowercase()` (no trim): none → null; else first (debug-log if ambiguous).
- `labelFor(stored)`: m = resolveFavorite; null → `stored`. If (count of cache entries with `city.lowercase() == m.city.lowercase()`) > 1 and `m.name` non-empty → `m.name`; else `m.city`.
- `cityFor(stored)`: `resolveFavorite(stored)?.city ?: stored`.
- `bySlug(slug, country)`: empty slug or cache → null. `c = country.trim().uppercase()`. First m with `MeetupKey.slug(m.city) == slug` and (`c` empty or `m.country.trim().uppercase() == c`). `MeetupKey.slug(x) = x.trim().lowercase().replace(" ", "-")` (badge domain). Display only.
- `byName(name)`: `n = name.trim().lowercase()`; empty/cache empty → null; first with `m.name.trim().lowercase() == n`, else first with `m.city.trim().lowercase() == n`.
- `organizerMeetupFor(pubkeyHexOrNpub)` (suspend): empty → null; if cache empty → `fetchMeetups()` (exception → null); still empty → null; if value doesn't start with `npub` → encode hex to npub (failure → null); return first m with `nostrNpub` non-empty and `nostrNpub.trim() == npub`.
- `cached`: last successful list (possibly empty).

### 8.3 Favorite persistence rules

- Keys written are always portal IDs. `homeMeetupId = favorites.first ?: ""` (widget anchor).
- Readers fall back to legacy city values via `resolveFavorite`.
- Legacy readers in other domains still compare `meetup.city == user.homeMeetupId` (home `_loadUser`, NFC writer, admin `_openPortalForEvent`). With ID favorites these comparisons fail (silently yield null). Native: use `resolveFavorite(homeMeetupId)` everywhere (fix; no compatibility impact).

### 8.4 Favorite migration on picker open (MeetupSelectionScreen & MeetupSearchSheet)

```
migrated = ordered set
for stored in user.favoriteMeetupIds (sheet: initialSelected):
   if list.any { it.id == stored } -> add(stored)
   else sameCity = list.filter { it.city.lowercase() == stored.lowercase() }
        size == 1 -> add(sameCity[0].id)       (debug log)
        size > 1  -> drop, warn "mehrdeutig — bitte neu waehlen"  (sheet: silent)
        size == 0 -> drop
```
Persisted only when the user saves.

### 8.5 Location acquisition `NearbyMeetupService.getCurrentLocation()` (exact semantics)

```
if !locationServicesEnabled -> SERVICE_DISABLED
perm = check(); if perm == DENIED -> perm = request()
if perm == DENIED_FOREVER -> DENIED_FOREVER ; if perm == DENIED -> DENIED
try #1: current position, accuracy MEDIUM (~balanced power / ~100 m), timeLimit 25 s, default provider
        (Fused on GMS devices) -> OK(pos)    [diag: accuracy rounded to m]
catch:
  try #2: current position via android.location.LocationManager (forced), accuracy HIGH, timeLimit 20 s -> OK(pos)
  catch: warn
  last = lastKnownPosition(); if last != null && floor(age.minutes) <= 10 -> OK(last)   // i.e. age < 11 min
  else warn (too old / none) -> NO_FIX
outer exception -> ERROR
```
Rationale to preserve: de-googled devices (GrapheneOS/LineageOS/microG) often have no fused provider; indoor fixes take long; a last-known
fix ≤10 min is acceptable presence evidence (5 km radius), older ones are rejected. Native must NOT hard-depend on Google Play services.

`MeetupLocationService.resolveLocation()`: `loc = getCurrentLocation()`; not OK → `LocationMeetupResult(mapStatus)`; else `fetchMeetups()`
(exception → []), for each meetup with coords compute `distanceKm` to the position, sort ascending, return `(OK, lat, lng, candidates)`.
Note: it does NOT fall back to `allMeetups` (callers do that themselves when needed).

`openLocationSettings()` → `Settings.ACTION_LOCATION_SOURCE_SETTINGS`; `openAppSettings()` → `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the package.

### 8.6 Distance (MUST match exactly)

geolocator's pure-Dart haversine with **R = 6378137.0 m** (NOT Android `Location.distanceBetween`, which uses Vincenty on WGS84 and gives different values):
```kotlin
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6378137.0
    fun rad(d: Double) = d * PI / 180
    val dLat = rad(lat2 - lat1); val dLon = rad(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + sin(dLon / 2).pow(2) * cos(rad(lat1)) * cos(rad(lat2))
    return r * 2 * asin(sqrt(a))
}
fun distanceKm(...) = distanceMeters(...) / 1000.0
```
Radius checks with `<=`.

### 8.7 Radius constants (used by badge domains)

| Constant | Value | Meaning |
|---|---|---|
| `participantRadiusKm` | 5.0 | Participant ↔ organizer-MEASURED reference (QR `la`/`lo`). Badge refused if measurable and farther. |
| `creationRadiusKm` | 10.0 | Organizer: meetups within this radius are "here" (`withinCreationRadius`). 1 → auto-pick, >1 → picker sheet (top 5). |
| `derivedRadiusKm` | 50.0 | Participant ↔ DERIVED reference (portal coordinates of the meetup). |
| `ambiguityThresholdKm` | 2.0 | `isAmbiguous` when the two nearest differ by < 2 km. |
| `isWithinParticipantRadius(lat1,lng1,lat2,lng2)` | — | `true` if `(lat2,lng2) == (0,0)` (no org coords → no check), else `distanceKm <= 5.0`. |

### 8.8 Nearby search (`NearbyMeetupService`)

`preload(forceReload=false)`: if not forced and both caches set → return. Fetch meetups (`MeetupService.fetchMeetups`) and events
(`MeetupCalendarService().fetchMeetupsPortalFirst()`) concurrently. Meetups empty → `allMeetups`. **Dedupe by city**: key =
`city.lowercase().trim()`; entries with empty key are always kept; otherwise first per key wins (this hides the second group of a
two-group city — see §14). Store both caches (process lifetime; the screen never forces a reload).

`search(centerLat, centerLng, radiusKm, dateMode, dayFrom?, dayTo?)`:
```
preload(); now = now()
for m in meetups:
  skip if lat == 0 && lng == 0
  d = distanceKm(center, m); skip if d > radiusKm
  matching = eventsFor(m, events).filter {
     ANY:        start > now - 12h
     SINGLE_DAY: dayFrom != null && sameLocalDay(start, dayFrom)
     RANGE:      dayFrom, dayTo != null && start > (dayFrom@00:00:00 - 1s) && start < (dayTo@23:59:59 + 1s)
  }.sortedBy(start)
  dedupe by key "${title}|${start.toIso8601String()}|${location}" (first wins)
  if dateMode != ANY && matching empty: skip     // ANY also shows meetups without a date
  out += NearbyMeetup(m, matching.firstOrNull, matching, d)
return out.sortedBy(distanceKm ?: 0)
```
`eventsFor(m, events)` (legacy substring matcher; NOT `MeetupEventMatcher`):
```
city = m.city.lowercase().trim(); if empty -> []
event matches if loc.contains(city) || title.contains(city) ||
                 (loc.isNotEmpty() && city.contains(loc.split(',').first().trim()))
   where loc = e.location.lowercase(), title = e.title.lowercase()
```
Caveat preserved by parity: if `location` starts with a comma, the first part is `""` and `city.contains("")` is true → the event matches every meetup.

### 8.9 Portal events, RSVP, attendees

`getAllMeetupEvents()`:
```
fmt(x) = "${x.year}-${MM}-${dd}" (zero-padded month/day, local date)
dates = [fmt(today), fmt(firstDayOf(month+1)), fmt(firstDayOf(month+2))]   // Dart DateTime normalizes month 13 -> January next year
byId = LinkedHashMap<String, Map>()
for date in dates (sequential): body = _get("/meetup-events/$date"); data = body is Map ? body.data : body
   if data !is List continue; for e in data (Maps only): byId["${e.id ?? e.hashCode}"] = e   // later date overwrites value, keeps first position
return byId.values (insertion order)
```
`getRsvp(id)`: body Map → `data` if Map else body; else null.
`getRsvpCached(id, forceRefresh=false)`: unless forced, if `rsvp_cache_ts_<id>` and `rsvp_cache_<id>` exist and age < 1 h and the JSON decodes to a Map → return it (no network). Else `fresh = getRsvp(id)`; if non-null store JSON + now(ms). Return fresh (may be null, cache kept).
`clearRsvpCache(id)`: remove both keys.
`isGoing(r)`: null → false; `status` in {`attending`, `maybe`} → true; else `(going ?? is_going ?? rsvped) == true`.
`rsvp(id, status="attending")`: POST `{status}`.

`getRsvpAttendees(eventId)` → `List<{npub,name}>`, first non-empty result wins:
```
cleanName(raw) = raw with leading regex ^id_\d+\| removed       // portal stores "id_<userId>|<Name>"
parse(list): for item:
   Map -> inner = item.user ?? item.member ?? item.profile ?? item ; src = inner is Map ? inner : item
          npub = (src.npub ?? src.pubkey ?? src.public_key ?? item.npub ?? item.pubkey ?? '').toString()
          name = cleanName((src.name ?? src.nickname ?? src.display_name ?? src.username ?? src.nick ?? item.name ?? item.nickname ?? '').toString())
          add if npub or name non-empty
   String non-empty -> add {npub:'', name: cleanName(item)}
1. GET /meetup-events/{id}/rsvp: if Map, try keys attendee_names, attendees_list, names
2. GET /my-meetup-events/{id}: root = body is Map ? (body.data ?? body) : body;
      try root[key] for attendees, rsvps, participants, going, members, guests; then parse(root) itself
3. for path in /meetup-events/{id}/attendees, /rsvps, /participants:
      data = body is Map ? (body.data ?? body.attendees ?? body.rsvps ?? body) : body ; parse(data)
4. r = getRsvp(id): try keys attendees, rsvps, going_users, participants, members
else []
```
(`??` = null-coalescing only; empty strings count as present.) Today the portal returns only counts (`attendees` is a number), so 1–4 usually yield [].

### 8.10 PortalLeaderService

```
ensureLoaded(force=false):
  if !force && leaders.nonEmpty && loadedAt != null && now - loadedAt < 24h: return
  if leaders.isEmpty: loadFromCache()          // prefs portal_leaders_cache(+_ts); sets leaders + loadedAt=ts if non-empty
  GET /api/meetup-leaders (8 s)               // ALWAYS attempted on this path (even if cache was fresh)
  non-200 -> warn, return
  parse: for e in list: e is Map, e.meetup_id is Int, e.npubs is List ->
           set = npubs.filterIsInstance<String>().map(trim).filter(nonEmpty).toSet(); if set nonEmpty: map[id] = set
  if map empty -> warn "Leere Leader-Liste — alter Stand bleibt." return   // never replace with empty
  leaders = map; loadedAt = now; save raw body + now ms
  exception -> warn
loadFromCache parse: same but WITHOUT trim/empty filtering; Int id; skip non-matching; empty -> ignore; any error -> ignore
isLeaderOf(meetupId: Int, npub): npub empty -> null; ensureLoaded(); leaders empty -> null (UNKNOWN);
   set = leaders[meetupId]; null -> false (meetups without leaders are omitted by the portal); else set.contains(npub.trim())
meetupCountFor(npub): display only: count of sets containing npub.trim()
```
SECURITY: there is intentionally no API returning all leader npubs as a flat set (§9).

### 8.11 Portal token lifecycle

`storeToken(t)`: write `portal_api_token`; `own = SigningService.npub()` (mode-aware: Amber/NIP-07/NIP-46/local); if non-empty write `portal_api_token_npub`.
`setManualToken(t)` = `storeToken(t.trim())` (no UI today).
`deleteToken()`: delete both secure keys (log the measure; must never be silently swallowed).
`logout()`: if token → `DELETE /api/mobile/token` (errors ignored) → `deleteToken()`.
`hasToken()`: `getToken() != null` (a storage read error counts as no token).

`tokenMatchesCurrentKey()` decision table (returns true = keep token, false = token belongs to someone else / is dead; callers delete):

| Condition (in order) | Result |
|---|---|
| no token | false |
| `SigningService.npub()` null/empty | false |
| stored binding == current npub (exact) | true (offline-safe fast path) |
| `GET /api/user` (Accept + Bearer) → 401 | false |
| → other non-200 | true (no judgement possible) |
| → 200, `nostr` (trimmed) null/empty | true (manual token of an account without npub) |
| → 200, `nostr.equals(current, ignoreCase=true)` | write binding = current; true ("healed") |
| → 200, different npub | false (log first 12 chars of each) |
| exception (offline/timeout) | `stored == null` (no binding → true; contradicting binding → false) |

Home-domain organizer check (consumer; context for the API contract): no token → if external signer show hint snackbar `portalConnectForOrganizer` (once per session, not if already admin) and stop; else silent `loginWithNostr()`. Then `tokenMatchesCurrentKey()`; false → delete; local key → re-login, external → snackbar `portalTokenMismatch`. Then `rawGetStatus('/my-meetups')`: 401/403 → delete token, re-login (local) or hint (external), retry once; non-200 → no change; `data` non-empty → `adminViaPortal = true` (+ publish organizer claim); empty while `adminViaPortal` → revoke the portal flag only and clear own admin-registry cache entry. Network errors → no change.

### 8.12 Portal time handling

- `/api/meetup-events/{date}` (public): times are UTC WITHOUT zone (e.g. `"2026-07-21 16:00:00"`) → calendar domain `portalStart`.
- `/api/my-meetup-events` (organizer): raw DB time, UTC without zone → `portalStartUtc(raw)`:
  ```
  s = raw.trim(); empty -> null
  dt = DartDateTime.tryParse(s) ; null -> null
  if dt has zone (Z or ±hh[:]mm) -> dt.toLocal()
  else -> DateTime.utc(y,M,d,h,m,s).toLocal()      // milliseconds dropped
  ```
  Dart `DateTime.parse` grammar to replicate (regex):
  `^([+-]?\d{4,6})-?(\d\d)-?(\d\d)(?:[ T](\d\d)(?::?(\d\d)(?::?(\d\d)(?:[.,](\d+))?)?)?( ?[zZ]| ?([-+])(\d\d)(?::?(\d\d))?)?)?$`
- Create/update `start` must be UTC RFC 3339; Flutter sends `toUtc().toIso8601String()` → `"YYYY-MM-DDTHH:mm:ss.SSSZ"`. Native: `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)`.
- **Editor prefill bug** (Flutter): `_start = DateTime.tryParse(ex.start)?.toLocal()`; for a zone-less value this treats UTC wall time as LOCAL, so the editor shows 16:00 for an 18:00 CEST event, and saving without re-picking sends `14:00Z` (−2 h shift). The list row shows the correct local time (portalStartUtc). Known open issue (UEBERGABE.md "Termin-Editor"). **Native: prefill with `portalStartUtc(ex.start)` and send `toUtc` — a correct round trip.** Log raw and converted values once (diagnostics), as Flutter does.

### 8.13 Misc rules

- Leader npub input validation: `startsWith("npub1") && length >= 60` (a real npub is 63 chars). Recommend additionally validating bech32 (improvement).
- Manage-events sort: lexicographic on raw `start`.
- Portal meetup cards: order as returned by the API.
- Nearby radius: default 25, range 5..100, step 5 (divisions 19). Distance label (source `nearby_meetups_screen.dart` result card): `t.nbKmAway(n.distanceKm!.toStringAsFixed(n.distanceKm! < 10 ? 1 : 0))`, rendered only when `n.distanceKm != null`.
  - Native: `getString(R.string.nbKmAway, distanceKm.toStringAsFixed(if (distanceKm < 10) 1 else 0))` using the shared `Double.toStringAsFixed` from `:core:common` (`core.common.dart`; spec 14 §3.1.4: `BigDecimal(abs(x)).setScale(digits, RoundingMode.HALF_UP).toPlainString()`, `-` prefix for negatives and `-0.0`). Never `String.format`/`NumberFormat`/`"%.1f"`: the separator is always `.` (also in DE, so `"2.3 km entfernt"`, never `"2,3"`) and rounding is HALF_UP on the exact binary value of the double.
  - The `< 10` test uses the raw (unrounded) double, so `9.96 → "10.0"` (1 decimal kept) while `10.0 → "10"`.
  - Test vectors: `1.45 → "1.4"` (binary 1.4499…), `2.25 → "2.3"`, `0.05 → "0.1"`, `9.94 → "9.9"`, `9.96 → "10.0"`, `10.5 → "11"`, `12.5 → "13"`.
  - l10n key `nbKmAway` (placeholder `km`, type Object, inserted verbatim): DE `"{km} km entfernt"`, EN `"{km} km away"`.
- "Hier & jetzt" any-date window: events starting after now − 12 h.
- Results sorted by distance ascending.
- Geocoding: debounce 600 ms, min 2 chars, limit 5.
- Event editor: time default 19:00 if time picker dismissed; date range [now−1d, now+1095d].
- Nearby date pickers: [now−1d, now+365d].

---

## 9. Security measures to preserve

1. **Per-meetup leader authority** (PortalLeaderService): a badge signer is accepted as organizer only if listed among the leaders of THAT meetup. Anyone can create a portal meetup and become its leader; a flat "all leaders" check would let anyone issue trusted badges for foreign meetups. Do not expose an "all leader npubs" API.
2. **Unknown ≠ No**: `isLeaderOf` returns `null` when no data could be loaded (offline/empty) and `false` only when data exists and the npub is absent. The scanner shows different messages (`mvAdminCheckFailed` vs `mvUnknownSigner`). Never collapse.
3. **Empty leader list is never adopted** (could be an outage).
4. **Portal token in secure storage** (Keystore-backed), never in plain prefs, never in backups/logs.
5. **Token bound to identity** (`portal_api_token_npub`) using the mode-aware `SigningService.npub()` (Amber bug v1.3.1: `SecureKeyStore.getNpub()` returned null for Amber, which broke the binding). A token belonging to another npub must not grant organizer status after an account switch; heal the binding from `GET /user` rather than destroying a valid login when offline.
6. **401 → delete token** in `_write` and `getProfile`; organizer check deletes on 401/403. Logout and app reset (`PortalApiService.logout()` in the reset flow) revoke server-side (best effort) and always delete locally; deletion failures must be logged, never swallowed.
7. **Stateless login**: k1 from CSPRNG, kind 22242 with exactly one `challenge` tag, fresh `created_at`; the event is never published to relays.
8. **No silent auto-login with external signers** (Amber/NIP-46/NIP-07) at app start: the user would get an unexpected signing prompt. Only local-key users are logged in silently (home domain).
9. **Safe URL launcher (Audit M2)**: links from portal/Nostr data (telegram/community link, twitter, maps route) open only if scheme is http/https and host non-empty; reject `javascript:`, `intent:`, `file:`, `tel:`, custom schemes. Hard-coded URLs may bypass it.
10. **Location presence rules** (consumed by badge domains): last-known positions older than 10 min are rejected; measured references use 5 km, derived 50 km; failure to measure ≠ evidence of absence (badge domains mark as unverified instead of refusing).
11. **No `adminSecret`** in the Meetup model (Audit Fix 5).
12. Location is used only locally (radius checks/map); it is never published as part of reputation proofs (README privacy statement).

---

## 10. Tests & vectors to port

Existing Flutter tests:

1. `test/meetup_group_name_test.dart`
   - `Meetup(name="Einundzwanzig Augsburg Mitte", city="Augsburg").groupName == "Einundzwanzig Augsburg Mitte"`.
   - `name=""` → null; `name="Augsburg"` → null; `name="augsburg"` → null.
2. `test/meetup_portal_identity_test.dart` (MockClient):
   - `/api/mobile/meetups` returns `[{id:107, name:"Einundzwanzig Südniedersachsen", city:"Göttingen", slug:"einundzwanzig-suedniedersachsen"}, {id:32, name:"Einundzwanzig Sachsen", city:"Sachsen", portalLink:"https://portal.einundzwanzig.space/de/meetup/einundzwanzig-sachsen"}]`;
     any `/api/meetup-events/*` returns `{data:[{id:3869, start:<now+10d UTC ISO>, "meetup.name":"Einundzwanzig Südniedersachsen", "meetup.portalLink":"https://portal.einundzwanzig.space/de/meetup/einundzwanzig-suedniedersachsen", "meetup.city":"Göttingen", location:"Dinx Diner", link:"https://example.com/event"}]}`.
   - Expect: exactly 1 event (dedupe by id across the 3 date requests); `meetupId == ""`; `meetupPortalLink` == that link; `url == "https://example.com/event"`; `portalEventId == 3869`; `MeetupEventMatcher.resolve(e, groups).id == "107"` (relies on the slug-derived `portalLink` of meetup 107).
   - Live opt-in test (`LIVE_PORTAL_TEST`): all live "Einundzwanzig Südniedersachsen" events resolve to 107. Port as an instrumented/manual test gated by a Gradle property.
3. Related, owned elsewhere but exercising this domain's data: `test/meetup_key_test.dart` (slug/compose/parse — badge domain; `bySlug` uses `MeetupKey.slug`), `test/meetup_event_matcher_test.dart` (calendar/home domain; uses `Meetup.portalLink`), `test/backup_restore_decision_test.dart` "Profil ergänzt nur leere Felder und vereinigt Favoriten" (backup domain: device home `bonn` wins; favorites union contains `bonn`,`koeln`), `test/guide_service_host_test.dart` (guide domain).

New unit tests to add in native (derived from the Dart behavior; vectors computed with the exact formulas):

- Haversine (R = 6378137): München (48.1351, 11.5820) → Berlin (52.5200, 13.4050) = **504.980394 km**; München → Hamburg (53.5511, 9.9937) = **613.114143 km**; Berlin → Hamburg = **255.536106 km**; Berlin (52.5200, 13.4050) → (52.5650, 13.4050) = **5.009377 km** (just outside 5 km); identical points = 0; Aschaffenburg (49.9769, 9.1525) → München = **270.982665 km** (> 50 km derived radius). Tolerance 1e-6 km.
- `firstNum`: `{latitude:"48.1"}` → 48.1; `{latitude:null, lat:48}` → 48.0; `{lat:"abc", ...}` → 0.0; `{longitude:null, lon:null, lng:"9.5"}` → 9.5.
- Image priority: cover "" + image "x" → "x"; all empty → "".
- portalLink: explicit `portalLink` wins (trimmed); else slug → `https://portal.einundzwanzig.space/de/meetup/<slug>`; slug `"a b~*"` → `a%20b~*`.
- Dedupe: two entries with id 5 → first kept; two entries same city different ids → both kept.
- `id` fallbacks: no id → name; neither → "unknown". `city` fallback: none → name → "Unbekannt". country missing → "DE".
- `resolveFavorite`: id match; legacy "würzburg" with two Würzburg meetups → first; empty cache → null. `labelFor`: two same-city → group name; unique city → city; unresolvable → stored string. `cityFor` unresolvable → stored.
- `bySlug("neu-ulm","de")` resolves city "Neu-Ulm" only if country matches (case-insensitive); empty country ignores country.
- `byName`: name match beats city match.
- `organizerMeetupFor(hex)` converts to npub and matches trimmed `nostr` field; invalid hex → null.
- Favorites migration (§8.4): `["107","Würzburg"(2 meetups),"Göttingen"(1 meetup id 107)]` → `["107"]` (duplicate id collapses in the set).
- `LocationMeetupResult`: candidates [1.0, 2.5] → ambiguous (diff 1.5 < 2); [1.0, 3.0] → not ambiguous; `withinCreationRadius` includes 10.0, excludes 10.0001. `isWithinParticipantRadius` with (0,0) reference → true.
- Location acquisition state machine with a fake provider: service off → SERVICE_DISABLED; denied → request → denied → DENIED; deniedForever → DENIED_FOREVER (no request); both fixes fail + last known 10 min 59 s old → OK; 11 min → NO_FIX; none → NO_FIX.
- Nearby search: radius filter; ANY includes meetups without events and events after now−12 h; SINGLE_DAY excludes meetups without matching events; RANGE inclusive of 00:00:00 first day and 23:59:59 last day; event dedupe by (title,start,location); location ", X" matches every meetup (parity quirk); results sorted by distance; preload dedupes by city.
- `getAllMeetupEvents` date list for now = 2026-12-15 → `["2026-12-15","2027-01-01","2027-02-01"]`; for 2026-11-30 → `["2026-11-30","2026-12-01","2027-01-01"]`.
- `_errorFrom`: `{errors:{start:["a","b"], link:"c"}}` 422 → `"a · b · c (HTTP 422)"`; `{message:"Nope"}` 403 → `"Nope (HTTP 403)"`; non-JSON 404 → `"Nicht gefunden."`; non-JSON 500 → `"Fehler (HTTP 500)."`.
- Write payload omission: create with location "" → no `location` key; recurrence null → no key; update with only start → `{"start":…}`.
- `_write`: no token → (false, 401, "Nicht angemeldet. …") without request; 204 empty body → ok, data null; 401 → token deleted.
- Login: request body has keys `k1` (64 lowercase hex), `event` (kind 22242, tags `[["challenge",k1]]`, content ""), `device_name` "Einundzwanzig Meetup App"; 201 with `{token:"t", user:{…}}` → token + binding stored, `data == user`; 200 with `{}` → "Kein Token vom Portal erhalten.".
- `tokenMatchesCurrentKey`: every row of the §8.11 table.
- PortalLeaderService: parse `[{"meetup_id":2,"npubs":[" npub1a ",""]},{"meetup_id":"3","npubs":["x"]}]` → `{2:{"npub1a"}}`; empty array → keeps previous; `isLeaderOf(9, n)` with loaded data but no entry → false; no data → null; `isLeaderOf(2," npub1a")` → true.
- RSVP: `isGoing({status:"maybe"})` true; `{going:true}` true; `{status:"none", going:false}` false; cache TTL 59 min hit / 61 min miss.
- Attendees `cleanName("id_12|Alice") == "Alice"`; `["id_3|Bob"]` → `[{npub:"",name:"Bob"}]`; `[{user:{npub:"npub1x", nickname:"N"}}]` → `[{npub:"npub1x",name:"N"}]`.
- `portalStartUtc("2026-07-21 16:00")` on a device in Europe/Berlin → 18:00 local; `"2026-07-21T16:00:00Z"` → 18:00; `""` → null. Editor round trip: prefill → save without changes → payload `start == "2026-07-21T16:00:00.000Z"`.
- Nominatim parsing: `name` empty → shortName = first comma part of display_name; lat "0"/lon "0" dropped; query of 1 char → no request.

No fixtures exist in `test/fixtures` for this domain (only `nip44.vectors.json`).

---

## 11. Android capabilities, permissions, libraries

| Need | Permission / API | Recommendation |
|---|---|---|
| HTTP (portal, Nominatim, tiles) | `INTERNET` | OkHttp 4 + kotlinx.serialization (`JsonElement` for tolerant parsing; `explicitNulls = false`). Optionally Retrofit for typed endpoints; untyped maps for RSVP/attendees/courses. |
| Location | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` (request both; Android 12+ users may grant approximate only — that is sufficient) | `LocationManagerCompat.isLocationEnabled`; Play Services `FusedLocationProviderClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token)` with 25 s timeout when GMS is available (`GoogleApiAvailability`); fallback `LocationManagerCompat.getCurrentLocation(GPS_PROVIDER or best)` with 20 s timeout; `getLastKnownLocation` (fused then LocationManager providers) with ≤10 min age. Must work without GMS (F-Droid/GrapheneOS). |
| Permission UX | `ActivityResultContracts.RequestMultiplePermissions` | Detect "denied forever" (`shouldShowRequestPermissionRationale == false` after a denial); intents `Settings.ACTION_LOCATION_SOURCE_SETTINGS`, `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`. |
| Map | none | osmdroid (in `AndroidView`) or MapLibre Native Android with an OSM raster style; set UA = applicationId; show OSM attribution; disable rotation; circle overlay in meters; marker anchors (pin tip bottom-center). Avoid Google Maps (GMS dependency, different tiles). |
| Secure token storage | Android Keystore | Tink AEAD / identity domain `SecureStore`. |
| Preferences | — | DataStore Preferences + one-time migration from `FlutterSharedPreferences`. |
| External links | `<queries>` for `ACTION_VIEW` `https` (already in Flutter manifest) | `Intent(ACTION_VIEW)` / Custom Tabs; safe-launcher check for data-derived URLs. |
| Pickers | — | M3 `DatePickerDialog`, `TimePicker`, `DateRangePicker` (selectable-dates bounds per §8.13). |
| Lists/refresh | — | M3 `PullToRefreshBox`, `ModalBottomSheet` (favorites sheet, event detail sheet), `ExtendedFloatingActionButton`, `SegmentedButton` (mode toggle), `Slider(steps = 18)` (19 intervals in Flutter = 18 intermediate steps in Compose). |
| Images | — | SVG logo (Coil SVG decoder or vector drawable conversion). |
| Haptics | — | `HapticFeedbackConstants`/`View.performHapticFeedback` via the shared haptic service. |
| Background work | — | None in this domain (no WorkManager needed). |

---

## 12. Cross-domain dependencies

This domain calls:
- **Identity/Signing**: `SigningService.signEvent(kind, tags, content)` (kind 22242), `SigningService.npub()` (mode-aware), `isExternalSigner` (consumers).
- **Nostr core**: NIP-19 encode/decode (hex↔npub).
- **Calendar**: `MeetupCalendarService.fetchMeetupsPortalFirst()` (events for Nearby/Details; it calls back into `PortalApiService.getAllMeetupEvents()`), `portalStart`, `portalStartUtc` (or move `portalStartUtc` into this domain), `CalendarEvent` model, `MeetupEventMatcher` (uses `Meetup.portalLink`/`id`/`name`/`city`).
- **Profile/User**: `UserProfile.load()/save()` for `favoriteMeetupIds` / `homeMeetupId`.
- **Badges**: `MeetupKey.slug()` (for `bySlug`).
- **Guide/Tours**: `GuideService` (`GuideTour.myMeetups`, completion pref).
- **Shared UI**: `NpubChip` (+ `NostrProfileService.fetchDisplayName`, haptics, clipboard, `npubCopied`), safe URL launcher, theme, AppLogger (diag/warn/debug lines with tags `Meetups`, `PortalAPI`, `PortalLeader`, `Standort`, `Nearby`, `Geocode`, `Portal`).

Other domains call this domain:
- **Home dashboard**: `MeetupService.fetchMeetups/cached/resolveFavorite/labelFor/cityFor`, `allMeetups`; `MeetupDetailsScreen`; `MeetupSelectionScreen`; `PortalMeetupsScreen` tile; portal connection toggle (`tokenMatchesCurrentKey`, `logout`, `loginWithNostr`); organizer check (§8.11: `hasToken`, `loginWithNostr`, `tokenMatchesCurrentKey`, `deleteToken`, `rawGetStatus("/my-meetups")`); "my dates" (`getRsvpCached`, `isGoing`); chat room lookup by favorite; home-screen widget (front favorite); app reset → `PortalApiService.logout()`.
- **Calendar screen**: `getAllMeetupEvents`, `getRsvpCached`, `rsvp`, `getRsvpAttendees`, `tokenMatchesCurrentKey`, `loginWithNostr`, `MeetupService.fetchMeetups/cached/resolveFavorite/labelFor` (favorite toggling stores `meetup.id`).
- **Community hub**: Portal-area rows → Nearby, PortalMeetups, CommunityPortal; `PortalEventsScreen` (`getAllMeetupEvents`, `getRsvp`, `rsvp`, `hasToken`); Courses/Lecturers (`getCourses`, `getCourse`, `getLecturers`, `getLecturer`).
- **Nostr calendar events (NIP-52)**: `LocationPickerScreen`; `getCourses/getCourse`.
- **Badge scanning/verification**: `MeetupService.bySlug/resolveFavorite/byName/organizerMeetupFor/fetchMeetups`, `allMeetups`, `PortalLeaderService.isLeaderOf`, `MeetupLocationService.resolveLocation/distanceKm/participantRadiusKm/derivedRadiusKm`, `GpsStatus`.
- **Organizer badge session (admin panel)**: `MeetupLocationService.resolveLocation/withinCreationRadius/openLocationSettings/openAppSettings`, `MeetupCandidate`, `Meetup.groupName`, `MeetupService.fetchMeetups`, `allMeetups`, `PortalApiService.getMyMeetups` (own meetups first in the picker), `Meetup.portalLink`.
- **Event badges**: `NearbyMeetupService.getCurrentLocation` (fine-grained `LocationStatus`), `MeetupLocationService.distanceKm`, `PortalApiService.getMyMeetups` (any membership = right to issue event badges).
- **Profile edit / Identity setup**: `MeetupSearchSheet`, `MeetupService.fetchMeetups`.
- **NFC writer**: `MeetupService.fetchMeetups`, `allMeetups` (home meetup → compact id `"<city-slug>-<country lower>"`).
- **Backup**: favorites/home carried in the backup payload.

---

## 13. Proposed Kotlin structure & public API

Module `:feature:meetups` (UI) + `:data:meetups` (directory, portal network/auth, location — shared by the badge domains; ARCHITECTURE.md module map). Package root `space.einundzwanzig.meetup`.

```
data/meetups/location/
  LocationStatus.kt, GpsStatus.kt, Position.kt
  LocationProvider.kt            // interface + GmsOrPlatformLocationProvider impl
  Geo.kt                         // distanceMeters/Km (haversine R=6378137)
  MeetupLocationService.kt
data/meetups/portal/
  PortalConfig.kt                // BASE, API, timeouts
  PortalHttp.kt                  // OkHttp client, header sets, getJson/rawGetStatus/write
  PortalTokenStore.kt            // secure token + npub binding
  PortalAuthRepository.kt        // loginWithNostr, logout, tokenMatchesCurrentKey
  PortalApi.kt                   // typed endpoints (my-meetups, events CRUD, leaders, rsvp, courses, lecturers)
  PortalErrors.kt                // errorFrom()
  PortalLeaderRepository.kt
  RsvpCache.kt
  PortalTime.kt                  // portalStartUtc, toPortalIso
  model/ PortalMeetup.kt, PortalMeetupEvent.kt, PortalResult.kt
data/meetups/
  MeetupRepository.kt (fetch, cache, resolve*), FallbackMeetups.kt, GeocodingRepository.kt, NearbyMeetupSearch.kt
  model/ Meetup.kt, NearbyMeetup.kt, GeoPlace.kt, DateMode.kt
feature/meetups/
  favorites/ FavoritesMigration.kt, MeetupSelectionScreen.kt, MeetupSearchSheet.kt
  nearby/ NearbyMeetupsScreen.kt, NearbyViewModel.kt
  details/ MeetupDetailsScreen.kt, MeetupDetailsViewModel.kt
  portal/ PortalMeetupsScreen.kt, PortalMeetupsViewModel.kt, ManageEventsScreen.kt, EventEditorScreen.kt, LeaderAdminCard.kt
  picker/ LocationPickerScreen.kt
  web/ CommunityPortalScreen.kt, CommunityLinks.kt
```

Key signatures:
```kotlin
data class Meetup(
  val id: String, val name: String = "", val city: String, val country: String, val telegramLink: String,
  val lat: Double, val lng: Double, val logoUrl: String = "", val description: String = "", val website: String = "",
  val portalLink: String = "", val twitterUsername: String = "", val nostrNpub: String = "", val coverImagePath: String = "",
) { val groupName: String? get() = name.takeIf { it.isNotEmpty() && it.lowercase() != city.lowercase() }
    val hasCoords get() = lat != 0.0 || lng != 0.0 }

interface MeetupRepository {
  val cached: List<Meetup>
  suspend fun fetchMeetups(): List<Meetup>            // never throws; [] on failure; updates cache on success
  fun resolveFavorite(stored: String): Meetup?
  fun labelFor(stored: String): String
  fun cityFor(stored: String): String
  fun bySlug(slug: String, country: String): Meetup?
  fun byName(name: String): Meetup?
  suspend fun organizerMeetupFor(pubkeyHexOrNpub: String): Meetup?
  val fallbackMeetups: List<Meetup>
}

object Geo { fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double
             fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double }

interface LocationProvider { suspend fun getCurrentLocation(): LocationResult }   // §8.5
class MeetupLocationService(private val loc: LocationProvider, private val meetups: MeetupRepository) {
  companion object { const val PARTICIPANT_RADIUS_KM = 5.0; const val CREATION_RADIUS_KM = 10.0
                     const val DERIVED_RADIUS_KM = 50.0; const val AMBIGUITY_THRESHOLD_KM = 2.0 }
  suspend fun resolveLocation(): LocationMeetupResult
  fun isWithinParticipantRadius(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Boolean
  fun openLocationSettings(ctx: Context); fun openAppSettings(ctx: Context)
}

class NearbyMeetupSearch(meetups: MeetupRepository, events: MeetupEventsSource /* calendar domain */) {
  suspend fun preload(forceReload: Boolean = false)
  suspend fun search(centerLat: Double, centerLng: Double, radiusKm: Double, dateMode: DateMode,
                     dayFrom: LocalDate?, dayTo: LocalDate?, now: ZonedDateTime = ZonedDateTime.now()): List<NearbyMeetup>
}

interface GeocodingRepository { suspend fun search(query: String, limit: Int = 5): List<GeoPlace> }

class PortalTokenStore { suspend fun getToken(): String?; suspend fun hasToken(): Boolean
  suspend fun storeToken(token: String); suspend fun setManualToken(token: String); suspend fun deleteToken()
  suspend fun boundNpub(): String?; suspend fun writeBinding(npub: String) }

interface PortalAuthRepository {
  suspend fun loginWithNostr(deviceName: String = "Einundzwanzig Meetup App"): PortalResult
  suspend fun logout()
  suspend fun tokenMatchesCurrentKey(): Boolean
  suspend fun getProfile(): JsonObject?
}

data class StatusBody(val status: Int, val body: JsonElement?)   // status 0 = no response
interface PortalApi {
  suspend fun rawGet(path: String): JsonElement?
  suspend fun rawGetStatus(path: String): StatusBody
  suspend fun getMyMeetups(): List<PortalMeetup>
  suspend fun getMyMeetupEvents(): List<PortalMeetupEvent>
  suspend fun createMeetupEvent(meetupId: Int, start: String, location: String? = null, description: String? = null,
      link: String? = null, recurrenceType: String? = null, recurrenceDayOfWeek: String? = null,
      recurrenceDayPosition: String? = null, recurrenceInterval: Int? = null, recurrenceEndDate: String? = null): PortalResult
  suspend fun updateMeetupEvent(eventId: Int, meetupId: Int? = null, start: String? = null, location: String? = null,
      description: String? = null, link: String? = null, recurrenceType: String? = null, recurrenceDayOfWeek: String? = null,
      recurrenceDayPosition: String? = null, recurrenceInterval: Int? = null, recurrenceEndDate: String? = null): PortalResult
  suspend fun getAllMeetupEvents(today: LocalDate = LocalDate.now()): List<JsonObject>
  suspend fun getRsvp(eventId: Int): JsonObject?
  suspend fun getRsvpCached(eventId: Int, forceRefresh: Boolean = false): JsonObject?
  suspend fun clearRsvpCache(eventId: Int)
  suspend fun rsvp(eventId: Int, status: String = "attending"): PortalResult
  suspend fun getRsvpAttendees(eventId: Int): List<Attendee>     // Attendee(npub: String, name: String)
  fun isGoing(r: JsonObject?): Boolean
  suspend fun getCourses(): List<JsonObject>; suspend fun getCourse(id: Int): JsonObject?
  suspend fun getLecturers(): List<JsonObject>; suspend fun getLecturer(id: Int): JsonObject?
  suspend fun getMeetupLeaders(meetupId: Int): List<JsonObject>
  suspend fun addMeetupLeader(meetupId: Int, npub: String): PortalResult
  suspend fun removeMeetupLeader(meetupId: Int, userId: Int): PortalResult
}

interface PortalLeaderRepository {
  suspend fun ensureLoaded(force: Boolean = false)
  suspend fun isLeaderOf(meetupId: Int, npub: String): Boolean?   // null = unknown
  fun meetupCountFor(npub: String): Int                           // display only
}

object PortalTime { fun portalStartUtc(raw: String): ZonedDateTime?; fun toPortalIso(local: ZonedDateTime): String }
```
Navigation routes: `meetups/portal`, `meetups/portal/{meetupId}/events` (pass `PortalMeetup` via saved state or re-fetch),
`meetups/portal/{meetupId}/events/edit?eventId=`, `meetups/nearby`, `meetups/details/{meetupId}` (look up in repository cache; fall back to
passing a parcelable `Meetup`, since Nearby can show fallback/offline meetups), `meetups/favorites`, `meetups/location-picker?lat=&lng=`
(result via `savedStateHandle`), `community/portal-web`. Favorites sheet = composable `MeetupSearchSheet(meetups, initialSelected, onDone)`.

Concurrency: repositories are singletons (Hilt) with `Mutex`-guarded in-memory caches; all network on `Dispatchers.IO`; ViewModels
expose `StateFlow` UI state mirroring the Flutter state booleans listed in §2.

---

## 14. Risks / compatibility pitfalls

1. **Distance formula**: using `android.location.Location.distanceBetween` (Vincenty) instead of the R = 6378137 haversine changes radius decisions near 5/10/50 km boundaries. Use §8.6.
2. **Portal time zones**: public and organizer endpoints return UTC without a zone marker. Parsing them as local time shifts events by the UTC offset (−1/−2 h in DE). Implement `portalStartUtc` exactly (§8.12) and keep the calendar domain's `portalStart` consistent.
3. **Event editor round trip** (Flutter bug): fix it in native by prefilling via `portalStartUtc` (§8.12). If you copy the Flutter code, every edit shifts the event by −1/−2 h.
4. **Clearing fields in edit**: Flutter omits empty fields from PATCH, so an organizer cannot clear location/description/link. Parity keeps the limitation. If you fix it, verify how the portal treats `""` vs `null` for these keys first, and never send `null` for recurrence enums.
5. **Null serialization**: sending `"recurrence_type": null` makes the portal answer 422/500. Configure the serializer to omit nulls.
6. **`Accept: application/json`** is required on authenticated calls; without it Laravel may redirect (302 → HTML) instead of returning 401 JSON, and the 401 → deleteToken logic breaks.
7. **`getMyMeetups` swallows 401**: "Meine Meetups" then shows "Du verwaltest noch keine Meetups" for an expired token. Parity is acceptable. Better: call `rawGetStatus` and, on 401, delete the token and switch to the login view.
8. **PortalMeetupsScreen trusts `hasToken`** and does not check that the token belongs to the current key. The home domain usually fixes this at startup. Consider calling `tokenMatchesCurrentKey()` on entry.
9. **Leader semantics**: never merge leader sets across meetups, never adopt an empty list, and keep `null` (unknown) separate from `false` (§9). `isLeaderOf` needs the portal meetup ID as an Int (`int.tryParse(meetup.id)`). Fallback meetups (`m_muc` and the others) have no numeric ID, so there is no leader check for them.
10. **Favorites keys**: values may be IDs or legacy city names. Always resolve via `resolveFavorite`. Several Flutter call sites still compare `city == homeMeetupId` (§8.3). Fix them in native, but keep the stored format (string IDs) identical so backups and other devices stay compatible.
11. **MeetupSelectionScreen offline wipe**: if the meetup list fails to load, nothing migrates, the selection is empty and "Ohne Favorit speichern" erases all favorites. Native: show an error/retry state and disable save while the list is empty, or keep unresolved stored values.
12. **Nearby dedupe by city** hides the second group of multi-group cities (Würzburg Walk vs Meetup). Result cards also show only `city`. Recommended: dedupe by `id` and show `groupName`. This is UI-only, so it is safe to improve.
13. **Nearby mode switch bug**: switching back to "Hier & jetzt" after a geocoded search keeps the geocoded center but labels it "Mein Standort". Keep the GPS center separate.
14. **Nearby event matching** uses substring matching, not `MeetupEventMatcher`, so events of one group appear under another group of the same city. Also, a location starting with "," matches everything. Parity or improvement is a product decision. If you improve it, use `MeetupEventMatcher.resolve(event, cached)?.id == meetup.id` with a city fallback only for identity-less ICS events.
15. **Meetup details event filter** is a plain `contains(city)` over title/location (e.g. "Au" problems) and shows past events in ICS-fallback mode. Same recommendation as 14, plus a now−6h cutoff.
16. **`telegramLink` without scheme** (`t.me/...`, also in the fallback list) fails the safe launcher and shows "Konnte Link nicht öffnen". Optional fix: prefix `https://` when the value starts with `t.me/`, then still enforce the http(s) check.
17. **Fallback typo "Hambmurg"**: organizers who pick it offline sign the badge slug `hambmurg(-de)`, so co-attendance keys for those badges differ from online `hamburg` ones. Fixing the typo makes native offline badges match online ones. Flutter users who issued offline "Hambmurg" badges are unaffected (their badges keep verifying; only grouping differs). Decide explicitly and document it. Recommended: fix to "Hamburg".
18. **Dart parser fragility**: a single malformed field in `/api/mobile/meetups` drops the entire list in Flutter. Native should parse per field. That is strictly more robust and harmless.
19. **Slug percent-encoding**: must match Dart `Uri.encodeComponent` (§7), or `portalIdentity` matching breaks for slugs with special characters.
20. **Location without Google Play services**: the 3-stage fallback (fused 25 s → LocationManager 20 s → last-known ≤10 min) is a field-tested requirement (GrapheneOS). A GMS-only implementation reintroduces the "no badge indoors" failures.
21. **Approximate location (Android 12+)**: Flutter's geolocator treats any granted permission as OK. With only coarse permission, fixes may be ~2–3 km off, which still fits within 5 km. Do not demand FINE.
22. **OSM tile/Nominatim policies**: missing UA or attribution and request bursts can get the app blocked for all users. Set the UA, add attribution, throttle Nominatim to ≤1 req/s.
23. **RSVP cache growth**: `rsvp_cache_<id>` keys are never cleaned up. In DataStore, consider pruning entries older than 1 h at startup (no compatibility impact).
24. **Leader cache first call always hits the network** (8 s timeout) even with a fresh cache. On a scan without network this delays the result by up to 8 s. Keep the semantics, but consider using the fresh cache immediately and refreshing in the background (the result stays the same).
25. **Hard-coded German strings** outside ARB (portal error mapping, "Fehler: Termin ohne ID", "Fehler", German weekday abbreviations and "Uhr" in details, CommunityPortal quick-access labels, "Sonstige"): move them to string resources in native and keep the German values.
26. **English weekday names in Nearby** (`DateFormat('EEE…')` with an unset intl default locale): native should use the app locale. This is cosmetic.
27. **Dead code**: don't port `MeetupListScreen`, `CreateMeetupScreen` (it pretends to send a request), `RadarScreen`, `services/portal_meetups_screen.dart` or `Meetup.fromJson`. If you port them anyway, the fake "request sent" in CreateMeetupScreen would mislead users.

---

## Appendix A — l10n keys used by this domain (de / en values from `lib/l10n/app_de.arb`, `app_en.arb`)

Placeholders in braces must be preserved (`{count}`, `{km}`, `{query}`, `{from}`, `{to}`, `{msg}`, `{meetup}`, `{max}`). `app_es.arb` also exists and must be ported by the l10n domain.

| Key | de | en | Placeholders |
|---|---|---|---|
| `portalTitle` | Meine Meetups | My meetups |  |
| `portalLogout` | Abmelden | Sign out |  |
| `portalNotConnected` | Mit dem Portal verbinden | Connect to the portal |  |
| `portalConnectInfo` | Melde dich mit deinem Nostr-Schlüssel am Einundzwanzig-Portal an, um deine Meetup-Termine direkt aus der App zu verwalten. | Sign in to the Einundzwanzig portal with your Nostr key to manage your meetup events right from the app. |  |
| `portalConnecting` | Anmeldung läuft … | Signing in … |  |
| `portalConnect` | Anmelden | Sign in |  |
| `portalConnected` | Portal verbunden | Portal connected |  |
| `portalLoginFailed` | Anmeldung fehlgeschlagen | Sign-in failed |  |
| `portalLoadingMeetups` | Lade deine Meetups … | Loading your meetups … |  |
| `portalNoMeetups` | Du verwaltest noch keine Meetups im Portal. | You don't manage any meetups on the portal yet. |  |
| `portalSource` | Verbunden mit portal.einundzwanzig.space | Connected to portal.einundzwanzig.space |  |
| `portalLeader` | Organisator | Leader |  |
| `portalManageEvents` | Termine verwalten | Manage events |  |
| `ldAdd` | Organisator hinzufügen | Add organizer |  |
| `ldAddHint` | npub des neuen Organisators | npub of the new organizer |  |
| `resetCancel` | Abbruch | Cancel |  |
| `ldAddDo` | Hinzufügen | Add |  |
| `ldNpubInvalid` | Bitte einen gültigen npub eingeben. | Please enter a valid npub. |  |
| `ldAdded` | Organisator hinzugefügt | Organizer added |  |
| `ldFailed` | Aktion fehlgeschlagen | Action failed |  |
| `ldRemove` | Entfernen | Remove |  |
| `ldRemoveConfirm` | Diesen Organisator entfernen? | Remove this organizer? |  |
| `ldRemoved` | Organisator entfernt | Organizer removed |  |
| `ldTitle` | Organisatoren | Organizers |  |
| `ldAddButton` | Admin hinzufügen | Add admin |  |
| `ldCreator` | Ersteller | Creator |  |
| `portalCreatedOk` | Termin angelegt ✓ | Event created ✓ |  |
| `portalUpdatedOk` | Termin aktualisiert ✓ | Event updated ✓ |  |
| `portalNewEvent` | Termin anlegen | Create event |  |
| `portalLoadingEvents` | Lade Termine … | Loading events … |  |
| `portalExistingEvents` | Bestehende Termine | Existing events |  |
| `portalNoEvents` | Noch keine Termine für dieses Meetup. | No events for this meetup yet. |  |
| `portalTapToEdit` | Zum Bearbeiten antippen | Tap to edit |  |
| `portalNeedStart` | Bitte Datum & Uhrzeit wählen. | Please pick date & time. |  |
| `portalEditEvent` | Termin bearbeiten | Edit event |  |
| `portalEventTitle` | Neuer Termin | New event |  |
| `portalFieldStart` | Datum & Uhrzeit | Date & time |  |
| `portalPickDate` | Datum wählen | Pick date |  |
| `portalFieldLocation` | Ort | Location |  |
| `portalFieldLocationHint` | z.B. Bitcoin-Treff Café (optional) | e.g. Bitcoin meetup café (optional) |  |
| `portalFieldDescription` | Beschreibung | Description |  |
| `portalFieldDescriptionHint` | Worum geht es? (optional) | What's it about? (optional) |  |
| `portalFieldLink` | Link | Link |  |
| `portalFieldLinkHint` | https://… (optional) | https://… (optional) |  |
| `portalSaving` | Wird gespeichert … | Saving … |  |
| `portalUpdate` | Änderungen speichern | Save changes |  |
| `portalSave` | Termin speichern | Save event |  |
| `guideMyMeetupsListTitle` | Deine Meetups | Your meetups |  |
| `guideMyMeetupsListBody` | Die Meetups, für die du im Portal eingetragen bist. Tippe eines an, um seine Termine zu sehen und zu pflegen — dort legst du mit dem Knopf unten auch neue an. | The meetups you are registered for in the portal. Tap one to see and maintain its events — the button down there also creates new ones. |  |
| `errorOpenLink` | Konnte Link nicht öffnen | Couldn't open link |  |
| `portalOpen` | Portal öffnen | Open portal |  |
| `portalQuickAccess` | SCHNELLZUGRIFF | QUICK ACCESS |  |
| `portalDiscover` | ENTDECKEN | DISCOVER |  |
| `portalAssociation` | VEREIN | ASSOCIATION |  |
| `portalPodcastMedia` | PODCAST & MEDIA | PODCAST & MEDIA |  |
| `portalSocialNetworks` | SOZIALE NETZWERKE | SOCIAL NETWORKS |  |
| `portalMeetupMap` | Meetup-Karte | Meetup map |  |
| `portalMeetupMapSub` | Treffen in deiner Nähe | Meetups near you |  |
| `portalShop` | Shop | Shop |  |
| `portalMerch` | Merch & Bitcoin-Produkte | Merch & Bitcoin products |  |
| `portalProfile2` | Portal | Portal |  |
| `portalProfile` | Dein Profil & Badges | Your profile & badges |  |
| `portalSoundboard` | Soundboard | Soundboard |  |
| `portalClipsSounds` | Clips & Sounds | Clips & sounds |  |
| `portalAboutAssoc` | Über den Verein | About the association |  |
| `portalMembership` | Mitglied werden | Become a member |  |
| `portalStatutes` | Satzung (PDF) | Statutes (PDF) |  |
| `portalContact` | Kontakt | Contact |  |
| `portalPrivacy` | Datenschutz | Privacy |  |
| `portalPodcast` | Podcast | Podcast |  |
| `portalBeginnerPath` | Der Weg (Einsteiger) | The Path (beginners) |  |
| `portalInterviews` | Interviews | Interviews |  |
| `portalMediaArticles` | Media & Artikel | Media & articles |  |
| `portalShoutoutSend` | Shoutout senden | Send shoutout |  |
| `portalInfotainment` | Toximalistisches Infotainment | Toxic-maximalist infotainment |  |
| `portalTagline` | für bullishe Bitcoiner. | for bullish Bitcoiners. |  |
| `mlTitle` | MEETUPS | MEETUPS |  |
| `msSearchMeetup` | Meetup suchen... | Search meetup... |  |
| `mlLoadError` | Fehler beim Laden | Error loading |  |
| `mlRetry` | Erneut versuchen | Retry |  |
| `mlNoMeetupsFound` | Keine Meetups gefunden. | No meetups found. |  |
| `mlNoMeetupFor` | Kein Meetup für "{query}" | No meetup for "{query}" | query |
| `countryDE` | Deutschland | Germany |  |
| `countryAT` | Österreich | Austria |  |
| `countryCH` | Schweiz | Switzerland |  |
| `countryES` | Spanien | Spain |  |
| `countryNL` | Niederlande | Netherlands |  |
| `countryIT` | Italien | Italy |  |
| `countryFR` | Frankreich | France |  |
| `msSelectMeetup` | MEETUP AUSWÄHLEN | SELECT MEETUP |  |
| `msFavoritesHint` | Wähle deine Meetups — du kannst mehrere auswählen. | Pick your meetups — you can choose several. |  |
| `msSaveNone` | Ohne Favorit speichern | Save without favorite |  |
| `msSaveFavorites` | {count} Favoriten speichern | Save {count} favorites | count |
| `sectionDescription` | BESCHREIBUNG | DESCRIPTION |  |
| `sectionDates` | TERMINE | DATES |  |
| `meetupNoDatesCal` | Aktuell keine Termine im Kalender. | No dates in the calendar right now. |  |
| `sectionLinks` | LINKS | LINKS |  |
| `sectionLocation` | STANDORT | LOCATION |  |
| `meetupRoute` | Route | Route |  |
| `cmNewMeetup` | NEUES MEETUP | NEW MEETUP |  |
| `cmFoundBase` | GRÜNDE EINE BASIS. | FOUND A BASE. |  |
| `cmCityName` | NAME DER STADT | CITY NAME |  |
| `cmCityExample` | z.B. Frankfurt | e.g. Frankfurt |  |
| `cmLocation` | LOCATION / ORT | LOCATION |  |
| `cmLocationExample` | z.B. Room 77 | e.g. Room 77 |  |
| `cmDateTime` | DATUM & UHRZEIT | DATE & TIME |  |
| `cmDateExample` | z.B. 21. Mai, 19:00 | e.g. May 21, 7:00 PM |  |
| `cmTelegramGroup` | TELEGRAM GRUPPE (OPTIONAL) | TELEGRAM GROUP (OPTIONAL) |  |
| `cmRequestSent` | ANFRAGE GESENDET 🚀 | REQUEST SENT 🚀 |  |
| `apStartMeetup` | MEETUP STARTEN | START MEETUP |  |
| `nbCenterHere` | Mein Standort | My location |  |
| `nbTitle` | MEETUPS IN DER NÄHE | MEETUPS NEARBY |  |
| `nbModeHere` | Hier & jetzt | Here & now |  |
| `nbModePlanned` | Geplant | Planned |  |
| `nbRadius` | Umkreis | Radius |  |
| `nbRadiusValue` | {km} km | {km} km | km |
| `nbDateSingle` | Datum | Date |  |
| `nbDateAny` | Jederzeit | Anytime |  |
| `nbPickDay` | Tag wählen | Pick day |  |
| `nbDateFromTo` | {from} – {to} | {from} – {to} | from, to |
| `nbPickRange` | Zeitraum wählen | Pick range |  |
| `nbSearchPlace` | Ort suchen (z.B. Hamburg) | Search place (e.g. Hamburg) |  |
| `nbLocationDeniedSub` | Ohne Standort zeigen wir alle Meetups nach Datum sortiert. Aktiviere den Standort in den Einstellungen für Entfernungen. | Without location we show all meetups sorted by date. Enable location in settings to see distances. |  |
| `nbRetryLocation` | Standort erneut versuchen | Try location again |  |
| `nbResultsHeader` | {count} Meetups im Umkreis | {count} meetups in range | count |
| `nbNoneInRadius` | Keine Meetups im Umkreis | No meetups in range |  |
| `nbNoneInRadiusSub` | Vergrößere den Umkreis oder ändere Ort/Datum. | Increase the radius or change place/date. |  |
| `nbKmAway` | {km} km entfernt | {km} km away | km |
| `nbMoreDates` | +{count} weitere Termine | +{count} more dates | count |
| `nbNoDate` | Kein Termin angekündigt | No date announced |  |
| `nbDetails` | Details | Details |  |
| `nbToday` | Heute | Today |  |
| `nbTomorrow` | Morgen | Tomorrow |  |
| `locPickTitle` | Ort des Events | Event location |  |
| `locPickJumpToMe` | Zu meinem Standort | Jump to my location |  |
| `locPickHint` | Tippe auf die Karte, um den Veranstaltungsort zu setzen. | Tap the map to set the venue. |  |
| `locPickHintDone` | Tippe erneut, um den Punkt zu verschieben. | Tap again to move the marker. |  |
| `locPickConfirm` | Ort übernehmen | Use this location |  |
| `profileSearchCity` | Stadt suchen... | Search city... |  |
| `rdEditIdentity` | Identität bearbeiten | Edit identity |  |
| `rdAnon` | ANON | ANON |  |
| `rdCollectBadge` | BADGE ABHOLEN | COLLECT BADGE |  |
| `rdYourReputation` | DEINE REPUTATION | YOUR REPUTATION |  |
| `rdNoBadges` | Noch keine Badges gesammelt.\nGeh zu einem Meetup! | No badges collected yet.\nGo to a meetup! |  |
| `rdSelfSovereign` | Self-Sovereign: Diese App läuft ohne Server. Deine Badges gehören nur dir und sind auf diesem Gerät gespeichert. | Self-sovereign: This app runs without a server. Your badges belong only to you and are stored on this device. |  |
| `rdNostrVerified` | NOSTR VERIFIED | NOSTR VERIFIED |  |
| `rdScanAdminTag` | ADMIN TAG SCANNEN | SCAN ADMIN TAG |  |
| `rdVerifiedByAdmin` | VERIFIZIERT DURCH ADMIN | VERIFIED BY ADMIN |  |
| `rdLinkingIdentity` | Identität verknüpfen... | Linking identity... |  |
| `portalTokenMismatch` | Dein Portal-Login gehört zu einem anderen Nostr-Schlüssel und wurde getrennt. Bitte verbinde das Portal neu — mit dem Schlüssel, mit dem du dort Leiter bist. | Your portal login belongs to a different Nostr key and has been disconnected. Please reconnect the portal — with the key you are a leader with. |  |
| `portalConnectForOrganizer` | Nicht mit dem Portal verbunden — dein Organisator-Status kann dadurch nicht erkannt werden. | Not connected to the portal — your organizer status cannot be detected. |  |
| `rsvpNeedLogin` | Zum Zusagen bitte zuerst im Portal anmelden (Meine Meetups). | Please sign in to the portal first (My meetups). |  |
| `rsvpFailed` | Antwort nicht gespeichert: {msg} | Response not saved: {msg} | msg |
| `tilePortal` | Meine Meetups | My meetups |  |
| `tilePortalSub` | Termine im Portal verwalten | Manage events on the portal |  |
| `tileActLookup` | Nachschlagen | Look up |  |
| `paTitle` | Portal | Portal |  |
| `paMap` | Karte | Map |  |
| `paMapSub` | Meetups in der Nähe | Meetups nearby |  |
| `paMine` | Meine Meetups | My meetups |  |
| `paMineSub` | Termine verwalten (Organisator) | Manage events (organizer) |  |
| `paWeb` | Portal-Webseite | Portal website |  |
| `paWebSub` | portal.einundzwanzig.space im Browser | Open portal.einundzwanzig.space |  |
| `mvPortalOrganizer` | ✓ Organisator von {meetup}\nIm Einundzwanzig-Portal als Leader dieses Meetups eingetragen. | ✓ Organiser of {meetup}\nListed in the Einundzwanzig portal as a leader of this meetup. | meetup |
| `mvAdminCheckFailed` | ! Nicht prüfbar — die Organisatoren-Liste war gerade nicht erreichbar. Das Badge selbst ist gültig; die Signatur stimmt. | ! Cannot verify — the organiser registry was unreachable. The badge itself is valid; the signature checks out. |  |
| `mvUnknownSigner` | Kein Eintrag gefunden\nWeder in der Organisatoren-Liste noch bei den Leadern dieses Meetups ist dieser Schlüssel hinterlegt. Das Badge selbst ist gültig — die Signatur stimmt und ist an dieses Badge gebunden. | No entry found\nThis key is listed neither in the organiser registry nor among this meetup's leaders. The badge itself is valid — the signature checks out and is bound to this badge. |  |
| `gpsTooFar` | Zu weit entfernt | Too far away |  |
| `gpsTooFarSub` | Du bist {km} km vom Meetup-Ort entfernt. Badges können nur vor Ort gesammelt werden (max. {max} km). | You're {km} km from the meetup location. Badges can only be collected on site (max {max} km). | km, max |
| `gpsPickMeetup` | Welches Meetup? | Which meetup? |  |
| `gpsPickMeetupSub` | Mehrere Meetups sind in deiner Nähe. Bitte wähle das richtige. | Several meetups are near you. Please pick the right one. |  |
| `gpsDistanceKm` | {km} km entfernt | {km} km away | km |
| `gpsDenied` | Standortzugriff verweigert. Bitte in den Einstellungen erlauben. | Location access denied. Please allow it in settings. |  |
| `gpsDisabled` | Standortdienste sind deaktiviert. Bitte aktivieren. | Location services are disabled. Please enable them. |  |
| `gpsError` | Kein GPS-Signal erhalten. In Gebäuden dauert die Ortung oft länger – geh kurz ans Fenster oder vor die Tür und versuche es erneut. | No GPS signal. Indoors this often takes longer – step near a window or outside and try again. |  |
| `gpsOpenLocationSettings` | Standort-Einstellungen öffnen | Open location settings |  |
| `gpsOpenAppSettings` | App-Einstellungen öffnen | Open app settings |  |
| `gpsRetry` | Erneut versuchen | Try again |  |
| `npubCopied` | npub kopiert | npub copied |  |
| `btnInfo` | Info | Info |  |
| `paMeetups` | Meetups | Meetups |  |
| `paMeetupsSub` | Alle Meetups durchsuchen | Browse all meetups |  |
| `paEvents` | Events & Zusagen | Events & RSVP |  |
| `paEventsSub` | Termine ansehen und direkt zusagen | View events and RSVP |  |
| `paCourses` | Kurse & Dozenten | Courses & lecturers |  |
| `paCoursesSub` | Das Einundzwanzig-Bildungsangebot | The Einundzwanzig education program |  |
| `navNearby` | In der Nähe | Nearby |  |
