# 10 — Calendar & Events (Meetup dates, NIP-52 calendar, RSVPs, home-screen widget)

Porting spec for the native Android (Kotlin + Jetpack Compose, Material 3 Expressive) rewrite of the
Einundzwanzig Meetup App. Source of truth: the Flutter code at the commit this file was written against.

Dart files covered (read in full): `lib/models/calendar_event.dart`, `lib/services/calendar_event_service.dart`,
`lib/services/meetup_calendar_service.dart`, `lib/services/recurrence_expander.dart`,
`lib/services/meetup_event_matcher.dart`, `lib/services/event_rsvp_service.dart`,
`lib/screens/event_calendar_screen.dart` (incl. `EventEditorScreen`), `lib/screens/calendar_screen.dart`,
`lib/screens/events.dart`, `lib/screens/events_hub_screen.dart` (incl. dead `ExternalEventsScreen`),
`lib/screens/my_events_screen.dart`, `lib/widgets/event_details_sheet.dart`, `lib/services/widget_service.dart`,
`android/app/src/main/kotlin/.../MeetupWidgetProvider.kt`, `WidgetRouterActivity.kt`, `MainActivity.kt`
(widget channel part), `res/layout/meetup_widget.xml`, `res/xml/meetup_widget_info.xml`, widget drawables,
`AndroidManifest.xml`. Helpers followed: `relay_socket.dart`, `relay_config.dart`, `portal_api_service.dart`
(events/RSVP/courses/token parts), `event_badge_session_service.dart`, `event_badge_auth_service.dart`,
`tours/event_badge_tour.dart`, `meetup_service.dart`, `models/meetup.dart`, `safe_url_launcher.dart`,
`news_reactions_service.dart` (`readTargets`), `home_screen.dart` (calendar/widget call sites),
the `icalendar_parser 2.1.0`, `add_2_calendar 3.1.1`, `home_widget 0.6.0` and `shared_preferences_android 2.4.20`
plugin sources.

Conventions in this file:
- "Dart local DateTime" = wall-clock time in the device time zone.
- Strings in `code` are exact wire values. Keys in **bold** are l10n keys from `lib/l10n/app_de.arb`
  (DE / EN values listed in §2.9).
- Colors: `cOrange #F7931A`, `cNostr #8B5CF6`, `cCourse #9AA0AA`, `cCyan #D4A017` (gold!), `cGreen #22C55E`,
  `cRed #E53935`, `cDark #0C0C0E`, `cCard #131315`, `cSurface #0F0F11`, `cText #FFFFFF`,
  `cTextSecondary #9CA0A8`, `cTextTertiary #868A94`, `cTileBorder #202028`, `cBorder #1C1C22`, `kTileRadius 10dp`.

---

## 1. Overview and user-visible features

The domain shows **three kinds of dated items** and lets users act on them:

| Item | Source | Identity | Color |
|---|---|---|---|
| Meetup date ("Meetup") | Einundzwanzig Portal API `/api/meetup-events/{date}`; fallback iCal feed `/stream-calendar` (with RRULE expansion) | portal event id (`portalEventId`), meetup id / portal link | `cOrange` |
| Nostr calendar event ("Veranstaltung"/"Event"/"Externe") | NIP-52 kinds 31923 (time) / 31922 (date) on relays | address `<kind>:<pubkey>:<d>` | `cNostr` |
| Course date ("Kurs") | Portal `/api/courses?withDetails=1` + `/api/courses/{id}` → `events[]` | course event id | `cCourse` |

Two independent RSVP systems exist and must both be ported:
- **Portal RSVP** for meetup dates (`POST /api/meetup-events/{id}/rsvp`, statuses `attending|maybe|none`), Bearer token.
- **Nostr RSVP** for NIP-52 events (kind 31925, statuses `accepted|declined|tentative`), signed by the user's key.

Feature list (every user-visible feature):

Events hub (bottom-nav tab "Events")
1. Hub screen with two cards: "Meetups" → meetup dates list; "Veranstaltungskalender" → event calendar.

Meetup dates list (`CalendarScreen`, title "MEETUP TERMINE")
2. Search field filtering by title/location substring (case-insensitive).
3. Pre-filled search + exact-meetup filter when opened from Home (favorite card / widget); exact filter only while search text equals the initial text.
4. Card per date: date box (DD / MM), meetup crest (portal logo or city match) with fallback icon, title, "HH:MM Uhr", location, favorite star (only if the date resolves unambiguously to one meetup), chevron.
5. Toggle favorite meetup (star) → snackbar "{city} zu Favoriten hinzugefügt ★" / "… entfernt"; first favorite becomes home meetup.
6. Portal RSVP row under each card (portal mode only): "N Zusagen" (tappable when N>0 to expand attendee names), "Zusagen" button or "Du hast zugesagt ✓" + "Absagen".
7. Attendee list expansion: loading spinner, names with initial avatar + shortened npub, or "no names" hint, "Anonym" for nameless.
8. Auto portal login (Nostr-signed) when RSVPing without valid token: "Anmeldung läuft …" → "Portal verbunden" / "Anmeldung fehlgeschlagen: …".
9. RSVP status prefetch with 1-hour cache (first 12 immediately, rest in waves of 20).
10. Details bottom sheet (bounded height, close button): crest+title, "DD.MM.YYYY · HH:MM", location, description, RSVP count line "N Zusagen · M Vielleicht", toggle buttons "Ich komme" / "Vielleicht" (tap active again = withdraw → `none`), spinner on pressed button, double-tap guard.
11. "Link öffnen" (http/https only), "Teilen" (share text; fallback copy to clipboard + snackbar), "Zum Kalender" (system calendar insert, end = start + 2h).
12. Loading spinner; empty state "Keine Termine gefunden."; iCal fallback when portal unavailable.

Event calendar (`EventCalendarScreen`, title "Veranstaltungskalender")
13. Three views via segmented control: Month grid, Year (12-month grid with counts), List (upcoming).
14. Color legend (Meetups / Veranstaltungen / Kurse).
15. Type filter (Alle / Meetups / Externe / Kurse), location search (items with empty location always shown), clear-filter X.
16. Community / Weltweit switch (community relay + app-created events only vs. all active relays, all events) + hint text when worldwide.
17. Refresh button (disabled while loading) and pull-to-refresh; loading state with text "Lade Veranstaltungen …".
18. Month view: prev/next month, weekday header (Mon-first), day cells with today highlight / selection, up to 3 colored dots; selected-day list with "Heute" chip; empty "Keine Veranstaltungen an diesem Tag."; footer "Events via Nostr (NIP-52)".
19. Year view: prev/next year, month tiles with event counts (orange pill) or "–"; tap → month view.
20. List view: all filtered items from (now − 1 day) on; empty state icon + "Keine Veranstaltungen in diesem Zeitraum.".
21. Event card: type dot, meetup crest (logo registry), title, badge icon chip (if event has badge), type chip (Meetup/Event), badge image band, time/date, location, description (3 lines).
22. Cross-source de-duplication (course > meetup > nostr).
23. Details sheet (draggable 35–90 %): title + type chip, when, location, full description, badge notice, issuer list, creator-only "Termin absagen", Nostr RSVP ("Ich komme" / "Ich komme nicht", optimistic), "Chat zum Termin", clickable http(s) links extracted from description/location, "Zum Kalender".
24. Badge notice: "Hier gibt es ein Badge" / "Du darfst hier Badges ausgeben", missing-location warning in red, "Badge-Session starten" / "QR anzeigen" button only for issuers on the event day with coordinates; busy state "Standort wird ermittelt …" with progress bar and hint; failure snackbars with "Einstellungen" action for location-off / denied-forever.
25. Issuer list ("Badges verteilen"): creator (star, "Veranstalter") + helpers as npub chips (tap → njump.me, long-press copy); creator-only "Helfer bearbeiten" sheet (add npub/hex, remove, validation errors, save → republish).
26. Cancel event (creator only): confirmation dialog → replaceable "cancelled" version + NIP-09 deletion request → snackbar.
27. Extended FAB "Event eintragen" → event editor; on publish success the calendar reloads.

Event editor (`EventEditorScreen`, title "Veranstaltung eintragen")
28. Title, location, all-day switch (resets dates), start (required), end (optional, "Ende entfernen"), description.
29. Badge section visible to everyone; switch enabled only with permission (checking → "Berechtigung wird geprüft …"); when on: badge image URL field + gallery pick & Blossom upload with preview, map location picker (shows "lat, lng" 5 decimals, clear X), helper npub rows (+ on last row, delete on others, red border for invalid rows).
30. Public-on-Nostr info box; publish button with spinner; validation snackbars; publish failure snackbar.
31. First-run guided tour (`GuideTour.events`) with badge steps only if permitted.

My events (`MyEventsScreen`, title "Meine Termine")
32. Section "Meine Meetups": portal meetup dates the user RSVP'd (from favorites, next 90 days) with attendee count and chat button.
33. Section "Zugesagte Veranstaltungen": Nostr events with accepted RSVP, date color by proximity, chat icon with unread badge (99+); tap opens event chat (reload unread on return); long-press → withdraw RSVP dialog → sends `declined` and removes the row.
34. Pull-to-refresh (unread counts), empty state text.

Home-screen widget ("21Meetup")
35. Bitcoin metrics (block, price in chosen currency, Moscow time, fees, hashrate, supply, difficulty, "Stand HH:MM"), next meetup (city + countdown "Heute/Morgen/in N Tagen" or "Kein Home-Meetup / In der App wählen"), latest news title with "NEU" badge, refresh ⟳ (background), four tap targets (logo→app, bitcoin, meetup, news).

Haptics: none in this domain. Share actions: only the meetup-date details sheet ("Teilen").

Dead code (do NOT port unless product wants it): `EventsScreen` (`lib/screens/events.dart`, unreachable) and
`ExternalEventsScreen` (in `events_hub_screen.dart`, removed from the hub in Aug 2026). They are described briefly in §2.8.

---

## 2. Screens & UX flows

### 2.1 Events hub (`EventsHubScreen`)
- Purpose: neutral chooser, the content of bottom-nav tab index 3 ("navEvents", `Icons.event_rounded`) in `AppShell`
  (kept alive in an `IndexedStack`).
- Layout (scrollable list, padding 16): AppBar title **hubTitle**, no actions.
  1. Card: icon `groups_rounded` in `cOrange`, title **hubMeetups**, subtitle **hubMeetupsSub** → push `CalendarScreen()` (no args).
  2. 14dp gap.
  3. Card: icon `calendar_month_rounded` in `cCyan` (gold), title **hubCalendar**, subtitle **hubCalendarSub** → push `EventCalendarScreen()` (no args).
- Card style: gradient (color 10% → card 98%), radius 18, 0.8 border color 28 %, elevation-2 shadow tinted, 54dp icon box radius 14, chevron in accent color.
- No loading/empty/error states.

### 2.2 Meetup dates list (`CalendarScreen`)
Entry points and arguments:
| From | `initialSearch` | `initialMeetupId` |
|---|---|---|
| Events hub card "Meetups" | – | – |
| Home favorite-card action "EVENTS" (**btnEvents**) | `card.city` (= `MeetupService.cityFor(favKey)`) | `meetup?.id` (resolved favorite) |
| Home-screen widget tap on meetup area (target `meetup`) | front favorite card's city (or `homeMeetup.city`) | `MeetupService.resolveFavorite(key)?.id` where key = front card key or `user.homeMeetupId`; if no city: no args |
| Badge wallet empty-state action ("emptyFindMeetup") | – | – |
| Community portal area rows "paMeetups" and "paEvents" | – | – |

Init sequence (all concurrent, fire-and-forget):
1. `_loadFavs()`: `UserProfile.load()`, `MeetupService.fetchMeetups()` (fills the meetup cache used by the matcher), then `_favCities = user.favoriteMeetupIds.toSet()` and re-filter.
2. If `initialSearch != null` the search field text is set to it.
3. `_loadEvents()` (see below).
4. `_loadMeetupCrests()`: `MeetupService.fetchMeetups()` again → `_meetups` for crest city matching (clears crest cache).

`_loadEvents()`:
1. Portal first: `PortalApiService.getAllMeetupEvents()` (§5.1). If non-empty: cutoff = now − 6 h; for each raw event `e`:
   - `start = portalStart(e['start'])`; skip if null or before cutoff.
   - `mv(key)`: if `e['meetup']` is a Map and `e['meetup'][key] != null` → its `toString()`; else flat key `e['meetup.'+key]` → `toString()`; else `''`.
   - `title = mv('name').trim()` or `'Meetup'` if empty; `description = e['description'] ?? ''`; `location = e['location'] ?? ''`;
     `url = e['link']` if non-empty else `mv('portalLink')`; `meetupId = mv('id')`; `meetupPortalLink = mv('portalLink')`;
     `portalEventId = e['id']` if it is an int else null.
   - Crest: `mv('logo')` stored per item (raw, possibly relative — see §14).
   - If `e['id']` is int: initial RSVP info `{count: e['attendees'] if int else 0, might: e['might_attendees'] if int else 0}`.
   - If ≥1 item survived: sort by start ascending, show, then `_loadRsvpStatuses()`; return.
   - Any exception → fall through.
2. Fallback: `MeetupCalendarService.fetchMeetups()` (ICS, §8.2/8.3); show whatever it returns (possibly `[]`). In ICS mode
   there are no portal ids → no RSVP rows, no crests from the portal (city match only).

`_loadRsvpStatuses()`: ids = all portal event ids in list order; first 12 loaded in parallel via `getRsvpCached(id)`
and merged (`{...old, ...response}`); then the rest in sequential waves of 20 (parallel within a wave). Each response is
shallow-merged into the per-id map.

Layout:
- AppBar: title **calendarTitle** (uppercase string), `cDark`, no actions.
- Search `TextField` (padding 16, filled `cCard`, radius 12, no border, prefix `search` icon orange, hint **calendarSearch**); `onChanged` → filter.
- Body: loading → centered orange spinner; filtered empty → centered **calendarNoEvents** (grey); else list (horizontal padding 16), one card per item:
  - Row: date box (orange 10 % bg, 30 % border, radius 8): day 2-digit (20sp bold orange) over month 2-digit (14sp);
    crest 42×42 radius 9 (fallback: orange 10 % box with `groups_rounded`); column: title (16 bold, 1 line),
    clock icon + `"HH:MM Uhr"` (literal German " Uhr", not localized), location (12sp, 1 line);
    trailing: if `MeetupEventMatcher.resolve(event, MeetupService.cached)` is non-null → star toggle
    (`star_rounded` orange if favorite else `star_outline_rounded` grey) + chevron; else only chevron.
  - Under the row (portal mode, item has portal id): RSVP row (see below).
  - Tap card → details sheet.

Filtering (`_filterEvents`): `query = searchText.lowercase()`. If `initialMeetupId != null` AND `query == initialSearch.lowercase()`:
keep events where `MeetupEventMatcher.resolve(event, MeetupService.cached)?.id == initialMeetupId`. Otherwise keep
events where `title.lowercase().contains(query) || location.lowercase().contains(query)` (empty query keeps all).

Crest resolution: portal logo of the item; else `_matchMeetup`: among cached meetups with `city.trim().length >= 3`
whose lowercase city is a substring of `"${title} ${location}".lowercase()`, pick the one with the LONGEST city; url =
`logoUrl` if non-empty else `coverImagePath`. Cached per `"$title|$location"`. Image load error → fallback icon.

Favorite toggle (`_toggleFav(favKey = resolvedMeetup.id)`):
```
favs = user.favoriteMeetupIds (copy)
sameMeetup(key) = key == favKey || MeetupService.resolveFavorite(key)?.id == favKey
added = !favs.any(sameMeetup)
if added favs.add(favKey) else favs.removeWhere(sameMeetup)
user.favoriteMeetupIds = favs; user.homeMeetupId = favs.firstOrNull ?: ""; user.save()
```
Star shows filled if any stored key resolves (`resolveFavorite(key)?.id`) to `favKey`.
Snackbar 2 s: added → green bg, **calFavAdded**(`MeetupService.labelFor(favKey)`); removed → `cSurface` bg, **calFavRemoved**(label).

RSVP row (portal id present):
- `count = rsvpCount(r)`: first of `r['attendees'] ?? r['count'] ?? r['total'] ?? r['rsvps']`; int → value, else −1.
- `going = isGoing(r)`: `r['status'] in {attending, maybe}` → true; else `(r['going'] ?? r['is_going'] ?? r['rsvped']) == true`.
- Left: if count ≥ 0 → text `"$count ${rsvpCount}"` (grey 12sp); if count > 0 it is tappable and shows expand_more/expand_less → toggles attendee list.
- Right: if going → **rsvpYouGo** (green bold) + outlined red "**rsvpCancel**" chip → `_doRsvp(id, 'none')` (spinner while busy);
  else 32dp-high green outlined button **rsvpGoing** → `_doRsvp(id, 'attending')` (spinner while busy). Disabled while busy.
- Expanded attendee list (container white 3 %): loading spinner (first time only, then cached in memory); empty →
  **rsvpNoNames**; else rows: 26dp circle with first letter of name uppercased (or "?"), name or **rsvpAnon**, shortened npub
  `npub.substring(0,10) + "…" + last 6` (if length > 16) in mono 10sp.

`_doRsvp(id, status)`:
1. If `!PortalApiService.tokenMatchesCurrentKey()` → `_ensurePortalLogin()`; abort if false.
   `_ensurePortalLogin`: re-checks token (returns true if OK); else floating snackbar **portalConnecting** (8 s, `cCard` bg) →
   `PortalApiService.loginWithNostr()` → hide → success: green **portalConnected**; failure: red `"${portalLoginFailed}: ${error}"`.
2. Mark busy; `PortalApiService.rsvp(id, status)`; unmark.
3. Success: set `r['status'] = status` locally immediately; then `getRsvpCached(id, forceRefresh: true)` and merge.
4. Failure: red floating snackbar. (Flutter bug: it interpolates the *function* `t.rsvpFailed` — shows garbage. Port as
   **rsvpFailed**(`error ?: ""`).)

Details sheet (`showEventDetailsSheet`, §2.7 container) content:
- Row: crest + title (18 bold).
- `when = "DD.MM.YYYY · HH:MM"` (all two-digit padded) with `event_rounded` icon.
- Location (if non-empty), description (if non-empty, plain text).
- If portal id: line `"${count>=0?count:0} ${rsvpCount}"` + (if `might >= 0`: `" · $might ${rsvpMaybe}"`) where
  `might = (r['might'] ?? r['might_attendees'])` if int else −1. Then two toggle buttons:
  - "**rsvpImComing**" (`check_rounded`) active when `status == 'attending'`; tap → `status == attending ? 'none' : 'attending'`.
  - "**rsvpMaybe**" (`help_outline_rounded`) active when `status == 'maybe'`; tap → `status == maybe ? 'none' : 'maybe'`.
  - Sheet-local busy state: while a request runs both are disabled; the pressed button (or the active one when sending `none`)
    shows a spinner instead of its icon. Double-tap guard. Active style: green 16 % bg, green 1.2 border, green text.
- Action row: if `url` non-empty: "**evOpenLink**" (`link_rounded`) → `launchHttpUrl(url)`; "**evShare**" (`share_rounded`) →
  share text:
  ```
  "${title} · ${when}" + (location.isNotEmpty ? "\n${location}" : "") + (url.isNotEmpty ? "\n${url}" : "")
  ```
  On share exception: copy that text to clipboard + snackbar **evShareCopied** (orange bg).
- Row: "**evToCalendar**" (`event_available_rounded`) → calendar insert: title, description, location, begin = start,
  end = start + 2 h, allDay = false.

### 2.3 Event calendar (`EventCalendarScreen(initialDay: LocalDate?)`)
Entry points: Events hub card (no args); Home "Events" tile (`initialDay = now`) — on return Home re-checks running
session and reloads "My events"; dead `EventsScreen` button.

State: view (month default), `focused` = first of month of `initialDay ?? today`, `selected` = `initialDay ?? today` (date),
loading, all items, filtered items, byDay map, `typeFilter` (0 all, 1 meetups, 2 external, 3 courses), `worldwide=false`,
location query, `myPubkey`, `startingSession` (observable bool), `runningSessionId`, `rsvps: Map<address, RsvpStatus>`,
`rsvpBusy: address?`. **Nothing is persisted** — filters/view/worldwide reset on every open.

Init: `_loadMyPubkey()` (SigningService.pubkeyHex), `_loadRunningSession()` (RollingQRService.loadSession → meetupId if not
expired), `_loadRsvps()` (EventRsvpService.loadMine, once per screen open), `_load()`.

`_load()` (§8.6 merge algorithm):
1. loading = true. Start concurrently: `CalendarEventService.fetchEvents(worldwide)`, `MeetupCalendarService.fetchMeetupsPortalFirst()`,
   `PortalApiService.getCourses()`; await all three.
2. Items from Nostr events (isMeetup=false, nostr ref) and meetup dates (isMeetup=true, end=null, allDay=false, url dropped).
3. Course details: ids = `courses.map(c['id']).whereType<int>().toSet()`; fetch `getCourse(id)` in sequential batches of 8
   (parallel within batch); then course items (§8.6).
4. Sort, cross-source dedup, sort; loading=false; apply filter.

Layout (top to bottom):
- AppBar: title **calTitle**; action refresh icon (`refresh_rounded`, disabled while loading) → `_load()`.
- Extended FAB (orange): `add_rounded` + **calAddEvent** → push `EventEditorScreen`; if it returns `true` → `_load()`.
- View switcher (segmented, 3): **calViewMonth**, **calViewYear**, **calViewList** (selected = orange fill, white text).
- Legend: dots `cOrange` **calLegendMeetup**, `cNostr` **calLegendEvent**, `cCourse` **calLegendCourse**.
- Filter bar:
  - Type segmented (4): **calFilterAll** (orange), **calFilterMeetups** (orange), **calFilterExternal** (cNostr), **calFilterCourses** (cCourse).
  - Location text field (hint **calFilterLocation**, `place_rounded` prefix) — every change re-filters; plus a clear "X"
    button shown when filter active (`typeFilter != 0 || query.trim().isNotEmpty`) → resets type 0, clears text.
  - Community/Worldwide row (whole row tappable + Switch): icon `groups_rounded` orange / `public_rounded` cNostr,
    label **calCommunityOnly** / **calWorldwide**; toggling flips `worldwide` and calls `_load()`. If worldwide: hint
    **calWorldwideHint** below.
- Body: loading → spinner + **calLoading**; else pull-to-refresh wrapping the active view.

Month view (list with bottom padding 100):
- Header: chevron_left / "MonthName YYYY" (17sp w800) / chevron_right → focused month ± 1 (selection unchanged).
- Weekday row: **calWd0..calWd6** (Mo…So, Monday first).
- Grid: leading blanks `(firstOfMonth.dayOfWeek(1..7) + 6) % 7`; one square cell per day; rows of 7 padded with blanks.
  Cell: selected → orange bg, white bold text; today (not selected) → orange 12 % bg + 1dp orange border, bold;
  dots: up to 3 (4dp circles) colored by item color (white when selected); tap → `selected = day`.
- Selected-day header: `"${day}. ${monthName}"` (15 bold) + "**calToday**" chip if today.
- List of cards for the selected day, or **calNoEvents**.
- Footer: **calSource** (centered, 11sp tertiary).

Year view: header chevrons ± 1 year around `"YYYY"`; 3-column grid (aspect 1.1) of 12 tiles: month name + count pill
(orange 15 % bg) of filtered items in that year/month, or "–"; border orange 40 % when count > 0. Tap tile →
`focused = (year, month)` and view = month.

List view: `upcoming = filtered.where(start > now − 1 day)`. Empty → 100dp spacer, `event_busy_rounded` icon 44dp,
**calNoEventsRange**. Else cards with date (`showDate=true`) and footer **calSource**.

Event card (tap → details sheet):
- Row: 8dp dot (item color); if meetup: crest from `MeetupCalendarService.logoFor(title)` 26×26 radius 6 (hidden on error);
  title (15 bold); if `hasBadge`: orange chip with `military_tech_rounded`; type chip: `isMeetup ? calTypeMeetup : calTypeEvent`
  (courses also show "Event" but in course color).
- If `badgeImageUrl` non-empty: image band 84dp high, full width, cover, radius 8, hidden on error.
- `schedule_rounded` + `_fmtWhen` (see §8.7); location row if non-empty; description (max 3 lines, ellipsis) if non-empty.

Details sheet (modal, scroll-controlled, draggable: initial 0.55, min 0.35, max 0.9 of height; drag handle 44×4):
Order:
1. Row: 10dp dot, title (18 w800), chip: `isCourse ? calLegendCourse : isMeetup ? calLegendMeetup : calLegendEvent`.
2. `schedule_rounded` + `_fmtWhen(showDate=true)`.
3. Location (if non-empty).
4. Description (if non-empty, plain text 14sp).
5. If `hasBadge`: badge notice (below), then if Nostr item: issuer list (below).
6. If Nostr item AND `myPubkey == nostr.pubkey` (exact string compare): outlined red button `event_busy_rounded` **evCancelAction** → cancel flow.
7. If Nostr item: RSVP row (two buttons).
8. If Nostr item: outlined `cNostr` button `forum_rounded` **chatEventOpen** → pop sheet, push `ChatScreen.event(eventAddress=address, eventAuthor=pubkey, title)`.
9. Links: every distinct match of `https?://[^\s\)\]>,]+` in `"${description} ${location}"` (first-seen order) as a tappable
   orange row (link icon, URL 1 line ellipsis, open_in_new) → `launchHttpUrl(url)`.
10. "**evToCalendar**" row → calendar insert: title, description, location, begin = start, end = `end ?? start + 2h`, allDay = item.allDay.

The sheet must re-render when calendar state changes (RSVP / busy) — in Compose hoist state into the ViewModel.

Badge notice (only when `nostr.badgeEnabled`):
- `iAmIssuer = myPubkey != null && nostr.isIssuer(myPubkey)` (no epoch). `noLocation = lat == 0 && lng == 0`.
- Box: orange bg 12 % (issuer) / 6 %, border 50 % / 25 %; icon `military_tech_rounded`.
- Title: iAmIssuer ? **evBadgeYouIssue** : **evBadgeAvailable**.
- Subtitle: noLocation ? **evBadgeNoLocationSet** (red) : iAmIssuer ? **evBadgeYouIssueSub** : **evBadgeAvailableSub**.
- Button only if `iAmIssuer && !noLocation && nostr.isBadgeWindowOpen`: orange (stays orange 75 % when disabled), black
  text/icon. Label: busy ? **evBadgeLocating** (16dp spinner) : (`runningSessionId != null && runningSessionId.contains(nostr.dTag)`
  ? **evBadgeShowSession** : **evBadgeStartSession**). While busy: 2dp linear progress + **evBadgeLocatingHint**.
- Press → `_startEventSession`:
  1. Guard: return if already starting. Set starting=true; call `EventBadgeSessionService.start(event)` (badges domain); finally starting=false.
  2. Failure → red snackbar with text per error (below), duration 10 s for `locationNoFix` else 6 s; action **evSessionOpenSettings**:
     `locationServiceOff` → system location settings; `locationDeniedForever` → app settings; other errors no action.
  3. Success → pop sheet, push `RollingQRScreen`; after return reload running session.
  Error → text: noIdentity **evSessionNoIdentity**, notIssuer **evSessionNotIssuer**, outsideWindow **evSessionOutsideWindow**,
  noEventLocation **evSessionNoEventLocation**, locationUnavailable **evSessionNoLocation**, locationServiceOff **evSessionLocationOff**,
  locationDenied **evSessionLocationDenied**, locationDeniedForever **evSessionLocationDeniedForever**, locationNoFix
  **evSessionLocationNoFix**, tooFarAway **evSessionTooFar**(`distanceKm` formatted with 1 decimal, `?: 0`), else **evSessionFailed**.

Issuer list (only for Nostr items with badge): surface box; header **evIssuersTitle** uppercased (10.5sp, letter-spacing 1.2);
rows: creator first (`star_rounded` orange, NpubChip(npub(creator)), label **evIssuerCreator**), then each issuer whose
lowercase ≠ creator lowercase (`person_rounded`). NpubChip without icon (shortened head 10/tail 8, optional profile name;
tap → njump.me profile, long-press → copy full npub). If `myPubkey == creator`: text button `edit_rounded` **evIssuersEdit** → edit sheet.

Edit-helpers sheet (modal, `cCard`, radius 20 top, keyboard-aware):
- Title **evIssuersEdit**; hint **evIssuersEditHint**; list initialised with `event.issuers` lowercased, excluding creator.
- Empty → **evIssuersNone**. Each entry: NpubChip + red close icon (disabled while saving) removing it.
- Input (mono font, hint `npub1…`, error text) + orange filled add button (also on keyboard submit). Add rule:
  `raw = text.trim()`; `hex = raw.startsWith("npub") ? NIP-19 decode(raw) : raw.lowercase()`; decode failure → **evIssuersInvalid**;
  `hex.length != 64` → **evIssuersInvalid**; `hex == creator.lowercase() || in list` → **evIssuersDuplicate**; else add, clear input.
  (Dart does not check that a 64-char non-npub input is hex — port SHOULD require `^[0-9a-f]{64}$`.)
- Save button (orange, spinner while saving) → `CalendarEventService.updateIssuers(event, list)` → close sheet → snackbar
  success green **evIssuersSaved** / failure red **evIssuersFailed**; on success also close the details sheet and `_load()`.

Cancel flow: AlertDialog (`cCard`, radius 16) title **evCancelTitle**, body **evCancelBody**(title), buttons **actionCancel**
(text) / **evCancelConfirm** (red filled). Confirm → `CalendarEventService.cancelEvent(event)` → pop details sheet →
snackbar **evCancelDone** (green) / **evCancelFailed** (red); on success `_load()`.

Nostr RSVP row: two equal outlined buttons:
- `check_rounded` **rsvpYes** → `accepted` (active color `cGreen`), `close_rounded` **rsvpNo** → `declined` (active color `cTextSecondary`).
- Inactive: transparent bg, `cTileBorder` 0.5 border, `cTextSecondary` text; active: filled with color, black text/icon, w800.
  No "tentative" button by design. Tapping the active button re-sends the same status (no toggle-off).
- Both disabled while `rsvpBusy == address`.
- `_setRsvp`: remember previous; optimistic `rsvps[address] = status`, busy; `EventRsvpService.setStatus(address, pubkey, status)`;
  clear busy; on error restore previous (or remove) and show red snackbar **rsvpFailed**(error).

### 2.4 Event editor (`EventEditorScreen`) — returns `true` via `Navigator.pop` on successful publish
Entry: Event calendar FAB (and dead `ExternalEventsScreen`). There is NO edit mode for existing events (only
issuers can be edited, §2.3).

Init: `EventBadgeAuthService.myRight()` (badges domain: admin registry → portal `/my-meetups` non-empty →
stored `user.isAdmin`; else `none`). After it resolves: if `guide_completed_events` not true → wait 400 ms →
start tour `GuideTour.events` with `EventBadgeTour.steps(mayCreateBadge: right != none)`. On dispose: if active tour is
`events` → finish it.

Layout (scroll, padding 16), labels are uppercased 10sp tertiary:
1. [tour target `basicsKey`] **calFieldTitle** + input (hint **calFieldTitleHint**); **calFieldLocation** + input (hint **calFieldLocationHint**).
2. All-day row: `event_available_rounded`, **calFieldAllDay**, Switch (orange). Toggle → `allDay = v; start = null; end = null`.
3. [tour target `whenWhereKey`] **calFieldStart** + date button; **calFieldEnd** + date button.
   Button text: null → (allDay ? **calPickDate** : **calPickDateTime**); set → `"DD.MM.YYYY"` or `"DD.MM.YYYY  HH:MM"` (two spaces).
   Active (set) style: orange border/icon.
4. If end set: right-aligned text button **calClearEnd** → end = null.
5. **calFieldDescription** + 4-line input (hint **calFieldDescriptionHint**).
6. Badge section (below).
7. Info box (cNostr 8 %): `public_rounded` + **calPublishInfo**.
8. Publish button 52dp: `send_rounded` + **calPublish**; while publishing: spinner + **calPublishing**, disabled.

Date/time picking: date picker `initialDate = initial ?? now`, `firstDate = now − 1 day`, `lastDate = now + 5×365 days`.
Start uses initial `start`; end uses initial `end ?? start`. All-day → returns local midnight of picked date. Else time picker
(initial = time of `initial ?? now`); if the time dialog is dismissed the time defaults to **19:00**. Cancelling the date
dialog leaves the value unchanged. No validation that end ≥ start.

Badge section (always visible; card with border orange 50 % when switch on):
- Header: `military_tech_rounded` (orange if may else tertiary), **evBadgeCreate**, subtitle: right unresolved →
  **evBadgeChecking**; may → **evBadgeCreateSub**; else **evBadgeNotAllowed**. Switch [tour `switchKey`] enabled only if may.
- When switch on:
  - [tour `imageKey`] **evBadgeImage**: input (hint **evBadgeImageHint**) + 48dp orange button `photo_library_rounded` →
    gallery pick (max 1200×1200, JPEG quality 85) → `BlossomUploadService.uploadImage(path)` → on success sets field to URL;
    failure → red snackbar **evBadgeUploadFailed**(error). While uploading: button shows spinner; info text
    **evBadgeUploading** else **evBadgeImageInfo**. Preview (120dp, cover) when field text starts with `http` (hidden on error).
  - [tour `locationKey`] **evBadgeLocation**: picker row → `LocationPickerScreen(initialLat/Lng if non-zero)`; returns
    lat/lng. Row text: set → `"${lat.toFixed(5)}, ${lng.toFixed(5)}"` + X (clear to 0/0); unset → **evBadgeLocationPick** + chevron.
    Info **evBadgeLocationInfo**.
  - [tour `issuersKey`] **evBadgeIssuers**: one text row per helper (initially one empty row), hint **evBadgeIssuerHint**,
    red border while the row is non-empty but invalid; last row has orange "+" (adds row), others a delete button (removing the
    last remaining row leaves one empty row). Info **evBadgeIssuerInfo**.
  - Row → hex: `npub1…` → NIP-19 decode, must be 64 chars → lowercase; or `^[0-9a-fA-F]{64}$` → lowercase; else invalid.
    Issuers sent = valid rows, deduplicated, in row order; invalid rows are silently ignored at publish.

Publish validation (floating red snackbars, in order):
1. Title trimmed empty → **calNeedTitle**.
2. Start null → **calNeedStart**.
3. `badgeEnabled && mayCreateBadge && lat == 0 && lng == 0` → **evBadgeNeedLocation**.
Then `withBadge = badgeEnabled && mayCreateBadge` (double gate — keep it) and call
`CalendarEventService.publishEvent(title.trim, desc.trim, location.trim, start, end, allDay, badgeEnabled=withBadge,
badgeImageUrl = withBadge ? image.trim : "", lat/lng = withBadge ? value : 0, issuers = withBadge ? hexes : [])`.
Result > 0 → pop(true); else snackbar **calPublishFail**.

Tour steps (`GuideTour.events`, keys are l10n keys):
1. target basics: **guideEvBasicsTitle** / **guideEvBasicsBody**
2. target whenWhere: **guideEvWhenWhereTitle** / **guideEvWhenWhereBody**
3. (if may) target switch: **guideEvBadgeSwitchTitle** / **guideEvBadgeSwitchBody**, hint **guideEvBadgeSwitchHint** (does NOT wait for the switch)
4. (if may) target image, waitForTarget, timeout 900 ms (skipped if switch off): **guideEvBadgeImageTitle/Body**
5. (if may) target location, same: **guideEvBadgeLocationTitle/Body**
6. (if may) target issuers, same: **guideEvBadgeIssuersTitle/Body**

### 2.5 My events (`MyEventsScreen`)
Entry: Home tile "Meine Termine" (tile id `event_chats`, not removable). Arguments:
- `events: List<NostrCalendarEvent>` — Home `_loadMyEvents()`: `EventRsvpService.loadMine()` → addresses with status
  `accepted` → for each (sequentially) `CalendarEventService.fetchByAddress(address)`; drop nulls and events with
  `start < now − 1 day`; sort by start.
- `meetupDates: List<MyMeetupDate(favKey, label, event, attendees)>` — Home `_loadMeetupRsvps()`: fav keys =
  `{homeMeetupId (if set), ...favoriteMeetupIds}`; candidates = portal meetup dates with `portalEventId != null`,
  `start >= now − 1 day`, `start <= now + 90 days`, matching any favorite (`_eventMatchesFavorite`, §8.4); for each
  (sequentially) `getRsvpCached(portalEventId)`; keep if `PortalApiService.isGoing(r)`; favKey = first fav key matching,
  else `event.meetupId`; label = `MeetupService.labelFor(favKey)`; attendees = `r['attendees'] ?? r['count']` if int else −1;
  sort by start.
- `onOpenMeetupChat(favKey, label)` (chat domain), `onChanged` (= Home reload of my events).
Home reloads my events after returning.

Behaviour/layout:
- AppBar **eventChatsTitle**. Body wrapped in pull-to-refresh → reload unread counts only (`EventChatService.unreadCounts(addresses)`).
- Local mutable copy of `events` (re-copied when a new list instance is passed in).
- Both lists empty → scrollable empty state (60 % height) **eventChatsEmpty** centered.
- Else: if meetupDates: section label **eventChatsMeetups** (uppercase 11sp) + meetup cards; if events: label
  **eventChatsEvents** + event cards.
- Meetup card: label (1 line, 14.5 bold); `event_rounded` + `"D.M.YYYY · HH:MM"` (day/month NOT padded, hour/min padded)
  + (attendees ≥ 0 ? `" · " + rsvpAttendees(n)` : ""); right: `forum_rounded` button (cNostr 14 % bg) → `onOpenMeetupChat(favKey, label)`.
- Event card: title (2 lines), `event_rounded` icon colored by `days = (start − now).inWholeDays` (truncated real duration):
  `days <= 0` → orange; `<= 3` → orange 80 %; else tertiary; text `"D.M.YYYY"` + (location ? `" · $location"`).
  Right: `forum_rounded` (cNostr if unread else tertiary) with orange badge `unread > 99 ? "99+" : unread`. Border cNostr 50 % if unread.
  - Tap → push `ChatScreen.event(address, pubkey, title)`, then reload unread.
  - Long-press → dialog **rsvpWithdrawTitle** / **rsvpWithdrawBody**(title) / **actionCancel** / **rsvpWithdrawConfirm** (red) →
    `EventRsvpService.setStatus(address, pubkey, declined)` → error: red snackbar **rsvpFailed**(err); success: remove from
    local list (stay on screen) and call `onChanged()`. No swipe-to-delete (deliberate).

### 2.6 Home screen touchpoints (owned by the Home domain, listed for completeness)
- "Events" tile: value = count of today's items (portal meetup dates + community-mode Nostr events whose start is today;
  courses excluded), or **tileEventsCalendar** if 0; label **tileEvents**, sub **tileEventsToday** when > 0. Tap → `EventCalendarScreen(initialDay = now)`.
- "Meine Termine" tile: value `myEvents.size + favMeetupDates.size`; value orange if unread > 0; sub: none → **tileEventChatsNone**,
  unread → **tileEventChatsUnread**(n), else **tileEventChatsSub**.
- Favorite cards (next date per favorite) use `fetchMeetupsPortalFirst` + matcher (§8.4) and feed the widget (§2.10).

### 2.7 Shared bounded details sheet (`showEventDetailsSheet`)
Modal bottom sheet, `cCard`, radius 20 top, scroll-controlled, uses safe area. Max height = 90 % of the available height
(recomputed on rotation). Header: centered drag handle (44×4, `cTileBorder`) with a close `IconButton` (`close_rounded`,
tooltip **actionClose**) aligned right in the same row; content scrolls below with padding (20, 0, 20, 24). Close button
pops only the sheet. Short content keeps the sheet compact (test: < 200 dp for one text line).

### 2.8 Dead screens (reference only)
- `EventsScreen` (title **evDatesEvents**): info header (**evUpcomingEvents**, **evIntro**), button to EventCalendarScreen
  (**evCalendarButton**/**evCalendarButtonSub**), meetup search by city/country (**evSearchCityCountry**), meetup cards → MeetupDetailsScreen,
  empty **evNoMeetupsFound**. Not referenced anywhere.
- `ExternalEventsScreen` (title **extTitle**): community-mode Nostr events from now − 1 day, intro **extIntro**, empty **extNone**,
  loading **extLoading**, FAB **extAdd** → editor. Not reachable.

### 2.9 Localized strings (DE / EN) used by this domain
| key | DE | EN |
|---|---|---|
| actionCancel | Abbrechen | Cancel |
| actionClose | Schließen | Close |
| calAddEvent | Event eintragen | Add event |
| calAllDay | Ganztägig | All day |
| calClearEnd | Ende entfernen | Remove end |
| calCommunityOnly | Nur Community | Community only |
| calFieldAllDay | Ganztägige Veranstaltung | All-day event |
| calFieldDescription | Beschreibung | Description |
| calFieldDescriptionHint | Worum geht es? (optional) | What's it about? (optional) |
| calFieldEnd | Ende (optional) | End (optional) |
| calFieldLocation | Ort | Location |
| calFieldLocationHint | z.B. Prag, Tschechien | e.g. Prague, Czechia |
| calFieldStart | Beginn | Start |
| calFieldTitle | Titel | Title |
| calFieldTitleHint | z.B. BTC Prag, Zitadelle … | e.g. BTC Prague, Zitadelle … |
| calFilterAll / Meetups / External / Courses | Alle / Meetups / Externe / Kurse | All / Meetups / External / Courses |
| calFilterLocation | Ort/Land suchen … | Search place/country … |
| calLegendMeetup / Event / Course | Meetups / Veranstaltungen / Kurse | Meetups / Events / Courses |
| calLoading | Lade Veranstaltungen … | Loading events … |
| calMonth1..12 | Januar … Dezember | January … December |
| calNeedStart | Bitte Beginn wählen. | Please pick a start. |
| calNeedTitle | Bitte gib einen Titel ein. | Please enter a title. |
| calNewEventTitle | Veranstaltung eintragen | Add event |
| calNoEvents | Keine Veranstaltungen an diesem Tag. | No events on this day. |
| calNoEventsRange | Keine Veranstaltungen in diesem Zeitraum. | No events in this period. |
| calPickDate / calPickDateTime | Datum wählen / Datum & Uhrzeit wählen | Pick date / Pick date & time |
| calPublish / calPublishing | Bei Nostr veröffentlichen / Wird veröffentlicht … | Publish to Nostr / Publishing … |
| calPublishFail | Veröffentlichung fehlgeschlagen. Online & angemeldet? | Publishing failed. Online & signed in? |
| calPublishInfo | Diese Veranstaltung wird öffentlich bei Nostr eingetragen – jeder mit dieser App sieht sie in seinem Kalender. | This event is published publicly on Nostr – everyone with this app sees it in their calendar. |
| calSource | Events via Nostr (NIP-52) | same |
| calTitle | Veranstaltungskalender | Event calendar |
| calToday | Heute | Today |
| calTypeEvent / calTypeMeetup | Event / Meetup | Event / Meetup |
| calViewMonth / Year / List | Monat / Jahr / Liste | Month / Year / List |
| calWd0..6 | Mo Di Mi Do Fr Sa So | Mon … Sun |
| calWorldwide | Weltweit | Worldwide |
| calWorldwideHint | Weltweit zeigt alle Nostr-Events – auch fremde. | Worldwide shows all Nostr events – including external ones. |
| chatEventOpen | Chat zum Termin | Chat about this event |
| evBadgeAvailable / Sub | Hier gibt es ein Badge / Vor Ort kannst du dir ein Badge abholen — am Tag des Events, in der Nähe des Veranstaltungsorts. | A badge is available here / You can collect a badge on site — on the day of the event, near the venue. |
| evBadgeChecking | Berechtigung wird geprüft … | Checking permission … |
| evBadgeCreate / Sub | Event-Badge erstellen / Teilnehmer können sich vor Ort ein Badge abholen. | Create event badge / Attendees can collect a badge on site. |
| evBadgeImage / Hint / Info | Bild fürs Badge / https://…/bild.png / Bild aus der Galerie wählen — es wird hochgeladen, damit alle es sehen können. Eine fertige URL geht auch. | Badge image / https://…/image.png / Pick an image from your gallery — … |
| evBadgeIssuerHint / Info / Issuers | npub1… einfügen / Du selbst darfst immer. Trage weitere Helfer ein, … / Wer darf Badges ausgeben? | Paste npub1… / You are always allowed. … / Who may issue badges? |
| evBadgeLocating / Hint | Standort wird ermittelt … / Die App prüft, ob du am Veranstaltungsort bist. … bitte nicht erneut tippen. | Checking your location … / … |
| evBadgeLocation / Info / Pick | Ort des Events / Badges lassen sich nur in der Nähe dieser Koordinaten und nur am Tag des Events ausgeben. / Auf der Karte wählen | Event location / … / Pick on the map |
| evBadgeNeedLocation | Für ein Badge braucht der Termin einen Ort auf der Karte — daran wird geprüft, wer vor Ort ist. | … |
| evBadgeNoLocationSet | Für diesen Termin ist kein Ort auf der Karte hinterlegt — dadurch lässt sich hier kein Badge ausgeben oder abholen. | … |
| evBadgeNotAllowed | Nur Meetup-Organisatoren und Leader können Badges vergeben. Das Event kannst du trotzdem eintragen. | … |
| evBadgeShowSession / StartSession | QR anzeigen / Badge-Session starten | Show QR code / Start badge session |
| evBadgeUploadFailed {msg} | Upload fehlgeschlagen: {msg} | Upload failed: {msg} |
| evBadgeUploading | Bild wird hochgeladen … | Uploading image … |
| evBadgeYouIssue / Sub | Du darfst hier Badges ausgeben / Am Tag des Events kannst du vor Ort eine Session starten und Badges verteilen. | … |
| evCancelAction / Title / Confirm | Termin absagen / Termin absagen? / Absagen | Cancel this event / Cancel this event? / Cancel it |
| evCancelBody {title} | „{title}“ verschwindet aus allen Kalendern. Nostr kennt kein echtes Löschen — … | “{title}” disappears from every calendar. … |
| evCancelDone / Failed | Termin abgesagt. / Absage nicht angekommen — kein Relay hat sie angenommen. | Event cancelled. / Cancellation not delivered — no relay accepted it. |
| evIssuerCreator | Veranstalter | organiser |
| evIssuersTitle / Edit / EditHint | Badges verteilen / Helfer bearbeiten / Wer hier steht, kann vor Ort Badges ausgeben. … | Handing out badges / Edit helpers / … |
| evIssuersInvalid / Duplicate / None | Das ist kein gültiger npub. / Steht schon auf der Liste. / Noch keine Helfer — nur du gibst Badges aus. | … |
| evIssuersSave / Saved / Failed | Speichern / Helfer gespeichert. / Nicht gespeichert — kein Relay hat die Änderung angenommen. | Save / Helpers saved. / Not saved — … |
| evSession* | (see arb; 11 messages, `evSessionTooFar {km}`) | |
| evSessionOpenSettings | Einstellungen | Settings |
| evToCalendar | Zum Kalender | Add to calendar |
| evOpenLink / evShare / evShareCopied | Link öffnen / Teilen / Termin in die Zwischenablage kopiert | Open link / Share / Event copied to clipboard |
| rsvpYes / rsvpNo | Ich komme / Ich komme nicht | I'm coming / Not coming |
| rsvpFailed {msg} | Antwort nicht gespeichert: {msg} | Response not saved: {msg} |
| calendarTitle / Search / NoEvents | MEETUP TERMINE / Suche (z.B. München, Bitcoin...) / Keine Termine gefunden. | MEETUP EVENTS / … / No events found. |
| calFavAdded {city} / calFavRemoved {city} | {city} zu Favoriten hinzugefügt ★ / {city} aus Favoriten entfernt | … |
| portalConnecting / Connected / LoginFailed | Anmeldung läuft … / Portal verbunden / Anmeldung fehlgeschlagen | Signing in … / Portal connected / Sign-in failed |
| rsvpAnon / Cancel / Count / Going / ImComing / Maybe / NoNames / YouGo | Anonym / Absagen / Zusagen / Zusagen / Ich komme / Vielleicht / Das Portal stellt für dieses Event keine Namensliste bereit. / Du hast zugesagt ✓ | Anonymous / Cancel RSVP / going / RSVP / I'm coming / Maybe / … / You're going ✓ |
| hubTitle / hubMeetups / hubMeetupsSub / hubCalendar / hubCalendarSub | Events / Meetups / Meetups suchen & entdecken / Veranstaltungskalender / Alle Termine im Überblick, farblich sortiert | … |
| eventChatsTitle / Meetups / Events / Empty | Meine Termine / Meine Meetups / Zugesagte Veranstaltungen / Hier stehen die nächsten Termine deiner Meetups … | My events / My meetups / Events I'm attending / … |
| rsvpAttendees {count} | {count} dabei | {count} attending |
| rsvpWithdrawTitle / Body {title} / Confirm | Zusage zurücknehmen? / „{title}“ verschwindet dann aus deinen Terminen. … / Absagen | Withdraw your RSVP? / … / Decline |
| guideEv* (13 keys) | see §2.4 | |
| tileEvents / tileEventsCalendar / tileEventsToday / tileEventChats / tileEventChatsNone / tileEventChatsUnread {count} / tileEventChatsSub / btnEvents | Events / Kalender / heute im Veranstaltungskalender / Meine Termine / Nichts geplant / {count} neue Nachrichten / Zusagen & Chats / EVENTS | … |
Full values: `lib/l10n/app_de.arb`, `app_en.arb`, `app_es.arb` (also Spanish exists — port all three).

Hard-coded (non-localized) strings in Dart that the port should localize unless marked WIRE:
`"Meetup"` (title fallback), `"Ort unbekannt"` (ICS location fallback), `"Kurs"` (course name fallback), `" Uhr"`,
`"npub1…"` hint, `"–"`, widget strings (§2.10), `"Termin abgesagt"` (**WIRE** — kind-5 content, keep exactly),
`"Kein Relay hat die Antwort angenommen"` (RSVP error message, shown inside rsvpFailed — localize).

### 2.10 Home-screen widget ("21Meetup")
Provider `space.einundzwanzig.meetup.MeetupWidgetProvider` (RemoteViews). Info: `minWidth 250dp`, `minHeight 110dp`,
`targetCellWidth 4`, `targetCellHeight 2`, `updatePeriodMillis 1800000`, `resizeMode horizontal|vertical`,
`widgetCategory home_screen`, preview = launcher icon, description `"Bitcoin-Netzwerk & nächstes Meetup"`.

Layout (vertical, padding 16, bg `#141416` radius 20 stroke 1dp `#33F7931A`):
1. Header row (margin-bottom 20): logo image (`widget_logo.png`, height 18, fitStart, weight 1) — tap → target `home`;
   "updated" text (`#8A8F98` 10sp) shows `"Stand $updated"` or empty; refresh "⟳" (`#F7931A` 22sp bold) → background refresh.
2. Bitcoin area (tap → `bitcoin`): row "BITCOIN" (orange 13 bold, weight 1) + "BLOCK" (grey 9sp) + block value (orange 30sp bold);
   divider 1dp `#22F7931A`; metrics row 1: PREIS (`"$price $priceSymbol"`), MOSCOW, FEES L·M·H (15sp white bold);
   metrics row 2: HASHRATE, SUPPLY, DIFFICULTY (13sp white bold). Labels grey 9sp.
3. Meetup area (tap → `meetup`; bg `#1AF7931A` radius 12, padding 12): "NÄCHSTES MEETUP" (orange 9sp bold) over city
   (white 16 bold, 1 line ellipsis); countdown right (orange 15 bold).
4. News row (tap → `news`; margin-top 10): "NEWS" (orange 10 bold), "NEU" badge (`#141416` on `#F7931A` radius 4, visible
   only if `newsIsNew`), title (`#CCCCCC` 12sp, 1 line), "›".

Rendering (`onUpdate`, for each widget id, never throws): reads `HomeWidgetPreferences` with defaults:
`block "––"`, price = `priceFiat` ?: `priceEur` ?: `"––"`, `priceSymbol "€"`, `moscow "––:––"`, `fees "–·–·–"`,
`hashrate/supply/difficulty "––"`, `updated ""`, `meetupCity ""`, `meetupCountdown ""`, `newsTitle "Aktuelle Artikel"`,
`newsIsNew false`. If `meetupCity` empty → city "Kein Home-Meetup", countdown "In der App wählen".
(Localize these labels in the port; keep layout.)

Tap routing: each area's `PendingIntent.getActivity(requestCode, Intent(WidgetRouterActivity))` with action
`space.einundzwanzig.meetup.WIDGET_<TARGET_UPPER>`, data `homewidget://<target>`, extra `open=<target>`,
`FLAG_ACTIVITY_NEW_TASK`; PI flags `UPDATE_CURRENT | IMMUTABLE`. Request codes: home 0, news 1, bitcoin 2, meetup 3.
`WidgetRouterActivity` (not exported, `noHistory`, `excludeFromRecents`, `taskAffinity=""`, translucent): target =
`intent.data.host ?: extra "open"`; if non-empty and ≠ `home` → `getSharedPreferences("e21_widget").putString("pending_target", target).commit()`;
then start MainActivity (NEW_TASK) and finish. App side: on start and every resume, read-and-remove `pending_target`
(once), wait 250 ms, route: `news` → mark news seen + open News; `bitcoin` → Bitcoin dashboard; `meetup` → CalendarScreen
as in §2.2. Reason: MIUI replays stale launcher intents — keep the "consume-once pending target" design.

Refresh ⟳: `HomeWidgetBackgroundIntent.getBroadcast(uri "homewidget://refresh")` → background Dart callback: if
`uri.host == "refresh"` → `refreshBitcoin()` then `refreshNews()`. Port: own non-exported BroadcastReceiver → enqueue an
expedited one-time WorkManager job doing the same, then update all widget ids.

Data writers (all write to `HomeWidgetPreferences`, then request widget update):
- `updateBitcoin(d)`: `block` = groupInt(blockHeight) or `"––"` if ≤ 0; `priceFiat` = groupInt(round(price in selected currency))
  or `"––"`; `priceSymbol` = currency symbol (EUR `€`, USD `$`, GBP `£`, JPY `¥`, CAD `CA$`, AUD `A$`, else code);
  `priceEur` (legacy) = groupInt(round(priceEur)) or `"––"`; `moscow` = `d.moscowTime` (`"HH:MM"`-style from sats/USD,
  or `"--:--"`); `fees` = `"$low·$medium·$high"` (U+00B7); `hashrate` = `"${hashrateEhs.toFixed(0)} EH/s"` or `"––"`;
  `supply` = groupInt or `"––"`; `difficulty` = `"${pct>=0?"+":""}${pct.toFixed(1)}% / $remainingBlocks"` if remaining > 0 else `"––"`;
  `updated` = local `"HH:MM"`. groupInt uses `.` thousands separator (`95000 → "95.000"`). `"––"` is two U+2013.
- `refreshBitcoin()`: fetch dashboard; if `isDead` (no source answered) → do NOT overwrite (keep last good values).
- `updateMeetup(city, countdown)`: `meetupCity`, `meetupCountdown`. Called by Home after computing favorite cards:
  city = front card's `cityFor(favKey)`, countdown = `""` if front card has no date, else by calendar-day difference
  `days = round((date(start) − date(today)).hours / 24)`: `days <= 0 → "Heute"`, `1 → "Morgen"`, else `"in $days Tagen"`
  (German, hard-coded — localize in port). Called on Home load, resume, midnight timer (+2 s), favorites change.
  Quirk: with zero favorites Home returns early and never clears the widget — port should write empty city.
- `refreshNews()`: `NewsService.fetchArticles(limit: 1)`; if any: `isNew = id.isNotEmpty && id != widget_last_seen_news_id`;
  store `widget_latest_news_id`, `widget_latest_news_title` (app prefs); widget `newsTitle` = title or `"News"`, `newsIsNew` = isNew.
- `markNewsSeen()`: `widget_last_seen_news_id = widget_latest_news_id` (if non-empty); widget `newsIsNew = false`.
- Callers (exhaustive; `lib/main.dart`, `home_screen.dart`, `currency_service.dart`):
  - Widget refresh button ⟳ only → `refreshBitcoin()` then `refreshNews()` (sequential, inside `widgetBackgroundCallback(uri)`
    when `uri.host == "refresh"`; runs in a background isolate, app not opened). `main`/`_start` does NOT call any widget
    writer; it only registers the callback (`HomeWidget.registerInteractivityCallback`, Android only).
  - Home `initState` → `refreshNews()` (fire-and-forget) — the only news fetch on a cold start.
  - Bitcoin dashboard data load (`_load()`: initial + every 60 s while the dashboard is shown) → `updateBitcoin(d)` with
    the result of `MempoolService.getDashboardData()`; currency change (`CurrencyService.set`) → `updateBitcoin(lastDashboard)`
    if `lastDashboard != null` (no new fetch).
  - Opening News (`_openNews`, also widget target `news`) → `markNewsSeen()`.
  - Home favorites computation → `updateMeetup(city, countdown)` (see above).
  Port: do NOT run `refreshBitcoin`/`refreshNews` on app start — that would add a full dashboard fetch (6 mempool requests)
  plus an extra news fetch on every cold start.
- Android-only (no-op elsewhere).

---

## 3. Data models

### 3.1 `CalendarEvent` (meetup date from portal or ICS) — port name suggestion `MeetupDate`
| field | type | default | source |
|---|---|---|---|
| title | String | required | portal `meetup.name` (trimmed, fallback `"Meetup"`); ICS `SUMMARY` (fallback `"Meetup"`) |
| description | String | required | portal `description` ?: ""; ICS `DESCRIPTION` cleaned (§8.2) |
| location | String | required | portal `location` ?: ""; ICS `LOCATION` (fallback `"Ort unbekannt"`) |
| startTime | local DateTime → use `Instant` | required | §8.1 / §8.2 |
| url | String | required | portal `link` if non-empty else `meetup.portalLink`; ICS `""` |
| meetupId | String | `""` | portal `meetup.id` (string); empty for ICS/Nostr |
| meetupPortalLink | String | `""` | portal `meetup.portalLink` |
| portalEventId | Int? | null | portal event `id` if JSON int |
No equality override (identity). Flutter uses instances as map keys; in Kotlin key maps by `portalEventId` instead of using
data-class equality. Not persisted, no JSON serialization.

`CalendarEvent.fromMap(map)` keys from the ICS parser: `dtstart` (IcsDateTime or String), `summary`, `description`, `location`.

### 3.2 `NostrCalendarEvent`
| field | type | default | parse (from verified event JSON) |
|---|---|---|---|
| id | String | – | `e['id']` toString |
| pubkey | String (hex) | – | `e['pubkey']` toString (case as received) |
| dTag | String | – | first `d` tag value or `""` |
| title | String | – | first `title` tag; if empty → first `name` tag; empty → reject |
| description | String | – | `content` |
| location | String | – | first `location` tag or `""` |
| start | DateTime | – | 31923: `int(start) * 1000` ms → local; 31922: `DateTime.tryParse(start)` (YYYY-MM-DD → local midnight); null → reject |
| end | DateTime? | null | same rule on `end` tag; unparsable → null |
| allDay | bool | – | `kind == 31922` |
| kind | int | – | 31923 or 31922 else reject |
| fromApp | bool | false | first `client` tag value == `"einundzwanzig-meetup-app"` |
| status | String | `""` | first `status` tag (`planned` / `cancelled` / other) |
| badgeEnabled | bool | false | first `badge` tag, `lowercase() == "true"` |
| badgeImageUrl | String | `""` | first `badge_image` tag |
| lat, lng | double | 0 | first tag with name `g` and ≥3 elements: `double.tryParse(t[1]) ?: 0`, `t[2]` |
| issuers | List<String> | [] | all `p` tags with ≥4 elements, `t[3] == "issuer"`, `t[1].length == 64` → `t[1]` (case as received) |
| formerIssuers | Map<String, Int> | {} | all `p` tags with ≥5 elements, `t[3] == "former_issuer"`, `t[1].length == 64`, `t[4]` int → `t[1] → int(t[4])` (later duplicates overwrite) |
Derived:
- `isCancelled = status == "cancelled"`.
- `address = "$kind:$pubkey:$dTag"`.
- `day` = local date of start.
- `isIssuer(pubkeyHex, atEpoch?)` and `isBadgeWindowOpen` — §8.8.
- Tag values are converted with `toString()`; any exception during parse → null (event skipped).
- `tagVal(key)` = value at index 1 of the FIRST tag whose index 0 equals key, `""` if that tag has < 2 elements.

### 3.3 `RsvpStatus` (Nostr) — enum `accepted`, `declined`, `tentative`; wire value = enum name (lowercase as written).

### 3.4 Portal RSVP info map (per portal event id; tolerant, schema not documented)
Initial: `{count: Int, might: Int}` from the event list (`attendees`, `might_attendees`). Merged with the
`GET /meetup-events/{id}/rsvp` response (`data` unwrapped if it is a Map). Read keys:
status: `status` ∈ `attending|maybe|none`; going flags `going|is_going|rsvped`; count `attendees|count|total|rsvps` (int);
maybe count `might|might_attendees` (int). Attendee entries: `{npub, name}` (see §5.1.6).

### 3.5 `_CalItem` (unified calendar row) — port as `CalendarItem`
`title, description, location, start, end?, allDay, isMeetup, isCourse=false, url="", nostr: NostrCalendarEvent?`.
Derived: `day`, `color = isCourse ? cCourse : isMeetup ? cOrange : cNostr`, `hasBadge = nostr?.badgeEnabled ?: false`,
`badgeImage = nostr?.badgeImageUrl ?: ""`. From Nostr: copies fields, isMeetup=false. From meetup: end=null, allDay=false,
isMeetup=true, url **not** copied.

### 3.6 `MyMeetupDate` — `favKey: String`, `label: String`, `event: CalendarEvent`, `attendees: Int = -1`.

### 3.7 `Meetup` (meetups domain, used here): `id, name, city, country, telegramLink, lat, lng, logoUrl, description,
website, portalLink, twitterUsername, nostrNpub, coverImagePath`. `MeetupService.fetchMeetups` builds `portalLink` from
`portalLink` or `https://portal.einundzwanzig.space/de/meetup/<urlencoded slug>`.

### 3.8 Session/badge types referenced: `EventSessionError` (12 values listed in §2.3), `EventSessionResult{session, error, distanceKm}`,
`EventBadgeRight {none, adminRegistry, portalLeader}` — owned by the badges domain.

---

## 4. Persistence

The calendar domain itself caches **no events**. Keys touched (Flutter legacy `SharedPreferences` live in
`FlutterSharedPreferences.xml` with prefix `flutter.`; string lists are stored with prefix
`VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!` + JSON array (older: same prefix without `!` + base64 Java-serialized list)
— migration of that file is a cross-cutting concern):

| key | store | format | writer | reader |
|---|---|---|---|---|
| `rsvp_cache_<eventId>` | app prefs | JSON object string (raw portal RSVP map) | `PortalApiService.getRsvpCached` | same |
| `rsvp_cache_ts_<eventId>` | app prefs | int ms epoch | same | same (TTL 1 h) |
| `favorite_meetups` | app prefs | String list (portal ids; legacy: city names) | CalendarScreen star, Home | UserProfile |
| `home_meetup` | app prefs | String (= first favorite or "") | CalendarScreen star, Home | UserProfile (migration: if `favorite_meetups` missing, `[home_meetup]`) |
| `guide_completed_events` | app prefs | bool | GuideService | editor tour |
| `custom_relays`, `disabled_default_relays` | app prefs | String lists | relay settings | `RelayConfig.getActiveRelays` |
| `portal_api_token`, `portal_api_token_npub` | secure storage | String | portal domain | RSVP flow |
| `widget_last_seen_news_id`, `widget_latest_news_id`, `widget_latest_news_title` | app prefs | String | WidgetService | WidgetService |
| `pending_target` | `e21_widget` prefs file (native) | String (`bitcoin|meetup|news`) | WidgetRouterActivity (commit) | app on start/resume (read + remove) |
| `block, priceFiat, priceSymbol, priceEur, moscow, fees, hashrate, supply, difficulty, updated, meetupCity, meetupCountdown, newsTitle` | `HomeWidgetPreferences` (native prefs file) | String | WidgetService | MeetupWidgetProvider |
| `newsIsNew` | `HomeWidgetPreferences` | Boolean | WidgetService | provider |
| Running badge session (`RollingQRService` keys) | badges domain | – | – | EventCalendar "QR anzeigen" label |

Migration notes:
- Keep `HomeWidgetPreferences` file name and keys (or migrate once on first native start) so already-placed widgets keep
  showing data immediately after the upgrade; keep the provider FQN `space.einundzwanzig.meetup.MeetupWidgetProvider`
  (AppWidget instances are bound to the receiver component name — renaming it silently removes users' widgets).
- RSVP cache keys can be dropped (pure cache), but keeping them avoids a burst of requests on first start.
- Nostr RSVPs and calendar events are NOT stored locally; everything is re-fetched.

---

## 5. Network

### 5.1 Portal (`https://portal.einundzwanzig.space`)
Common GET helper `_get(path)`: `GET https://portal.einundzwanzig.space/api{path}`, headers `Accept: application/json` +
`Authorization: Bearer <token>` if a token exists; timeout 15 s; non-200 or exception → null (no retries). JSON body.

1. **Meetup dates** `getAllMeetupEvents()`: three sequential GETs `/meetup-events/{yyyy-MM-dd}` for: today,
   first day of next month, first day of the month after (computed with calendar arithmetic). Each returns monthly
   data from that date. Body: `{data: [...]}` or a bare list. Merge into a map keyed by `"${e['id'] ?? e.hashCode}"`
   (later responses overwrite). Item fields used: `id` (int), `start` (string, UTC without zone in practice),
   `description`, `location`, `link`, `attendees` (int), `might_attendees` (int), meetup info either nested
   `meetup: {name, logo, portalLink, id}` or FLAT dotted keys `"meetup.name"`, `"meetup.logo"`, `"meetup.portalLink"`,
   `"meetup.id"`, (`"meetup.city"` exists but unused). Live API uses the flat form.
2. **ICS fallback** `GET https://portal.einundzwanzig.space/stream-calendar` (no headers), timeout 15 s; 200 → UTF-8 decode → parse;
   non-200/exception → `[]`.
3. **Courses** `GET /courses?withDetails=1` → bare array (or `data`/`items`); then per course id `GET /courses/{id}`
   (body `data` or root map) in sequential batches of 8 (parallel within batch). Detail fields: `name|title`,
   `description`, `portalLink`, `events: [{id, from|start, to, venue: {name, city: {name}}, location, link}]`.
4. **RSVP status** `GET /meetup-events/{id}/rsvp` → map (`data` unwrapped if Map). Cached 1 h (§4) via
   `getRsvpCached(id, forceRefresh)`; only non-null responses are cached.
5. **RSVP write** `POST /api/meetup-events/{id}/rsvp`, headers `Accept: application/json`, `Content-Type: application/json`,
   `Authorization: Bearer <token>`, body `{"status":"attending"|"maybe"|"none"}`, timeout 15 s. No token → fail
   "Nicht angemeldet…" (401). Success = 200/201/204. 401 → delete token. Errors → message from `errors` (all joined with
   " · ") or `message` + " (HTTP n)", else defaults per status (401/403/404/422/other).
6. **Attendee names** `getRsvpAttendees(id)` — tries, in order, until a non-empty list:
   a. `GET /meetup-events/{id}/rsvp` keys `attendee_names`, `attendees_list`, `names`;
   b. `GET /my-meetup-events/{id}` root (`data` or body) keys `attendees, rsvps, participants, going, members, guests`, then root itself as list;
   c. `GET /meetup-events/{id}/attendees`, `/rsvps`, `/participants` (`data|attendees|rsvps|body`);
   d. `getRsvp(id)` keys `attendees, rsvps, going_users, participants, members`.
   Parse: list items that are Maps: inner = `item.user ?? item.member ?? item.profile ?? item`; npub = `npub|pubkey|public_key`
   (inner, then item); name = `name|nickname|display_name|username|nick` (inner, then `item.name|item.nickname`), name
   cleaned by stripping prefix `^id_\d+\|`; kept if npub or name non-empty. String items → `{npub:"", name: cleaned}`.
7. **Token/login** (portal domain): `tokenMatchesCurrentKey()` (may `GET /api/user`), `loginWithNostr()` (`POST /api/mobile/token`,
   kind 22242 challenge event).
8. **Meetups list** (meetups domain): `GET /api/mobile/meetups` (15 s) — needed for matcher/crests.
9. Images: portal logos/covers (relative paths made absolute with `https://portal.einundzwanzig.space` + "/" if missing),
   badge images from arbitrary URLs, Blossom upload (media domain).

### 5.2 Nostr relays (WebSocket)
- Community relay constant: `wss://nostr.einundzwanzig.space`.
- Active relays (`RelayConfig.getActiveRelays`): defaults `wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.nostr.band`,
  `wss://nostr.einundzwanzig.space` minus `disabled_default_relays`, plus `custom_relays` (set union, insertion order).
- RSVP relays (`NewsReactionsService.readTargets`): `{wss://nostr.einundzwanzig.space, wss://relay.damus.io, wss://nos.lol,
  wss://relay.nostr.band} ∪ active relays` (used for BOTH reading and publishing RSVPs, incl. the read-only aggregator).
- Frames > 512×1024 chars are dropped before parsing (Security M4).
- No CLOSE messages are sent; sockets are closed after completion.

| operation | relays | concurrency | timeouts | completion |
|---|---|---|---|---|
| `fetchEvents` | community only (default) or all active (worldwide) | sequential per relay | connect 8 s; wait 8 s | EOSE, error, close, or timeout (returns partial) |
| `fetchByAddress` | `{community} ∪ active` | parallel | connect 8 s; wait 8 s | EOSE/close/timeout (best so far) |
| `publish` (calendar/cancel/delete) | `active ∪ {community}` | sequential | connect 5 s (`RelayConfig.publishTimeout`), then fixed 2 s sleep, close | counted OK if connect+send succeeded (no `OK` wait!) |
| RSVP `setStatus` | readTargets | parallel | connect 4 s; wait for `["OK", id, true]` up to 7 s | success only on `OK true` |
| RSVP `loadMine` | readTargets | parallel | connect 4 s; wait 7 s | EOSE or CLOSED |

---

## 6. Nostr

NIPs: NIP-01 (events/REQ/EOSE/OK/CLOSED), NIP-52 (calendar 31922/31923, RSVP 31925), NIP-09 (deletion kind 5 with `e`/`a`/`k`),
NIP-19 (npub), NIP-33/NIP-01 addressable semantics, NIP-22 (event chat — chat domain), NIP-42-style kind 22242 (portal login — portal domain).

### 6.1 Calendar event fetch (list)
```json
["REQ", "cal-<16 hex chars from 8 secure-random bytes>", {"kinds":[31923,31922], "limit":200}]
```
`limit` param = 200. Per EVENT message: `verifiedEvent` (id + Schnorr sig) → `answersFilter(event, filter)` →
`NostrCalendarEvent.fromEvent` → drop if null or `isCancelled`. Community mode additionally drops events with
`fromApp == false`. Dedup across relays: key `"${pubkey}:${dTag}:${kind}"`, **last seen wins** (relay order, then arrival order;
NOT by created_at — see §14). Result sorted by start ascending.

### 6.2 Fetch by address (`<kind>:<pubkey>:<d>`)
Parse: `split(':')`; need ≥3 parts; kind = int(parts[0]); author = parts[1].lowercase() must be 64 chars; dTag =
`parts[2..].join(':')` non-empty; else null.
```json
["REQ", "cal1-<12 hex chars from 6 random bytes>", {"kinds":[<kind>], "authors":["<author>"], "#d":["<dTag>"], "limit":1}]
```
Accept an EVENT only if verified, answers the filter, `replaceableCreatedAt(raw)` non-null (created_at int ≥ 0 and
≤ now + 600 s), parses, `pubkey.lowercase() == author`, `dTag ==`, `kind ==`, and `created_at >= bestAt` (ties: later wins).
Across relays: max created_at wins (ties: later relay in iteration order wins). Final: null if none or winner `isCancelled`.
Cancelled versions are considered for "newest" (so a newer cancellation hides older active versions).

### 6.3 Publish calendar event (kinds 31923 / 31922) — EXACT tag layout and order
```dart
final dTag = existingDTag ??
    List.generate(16, (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0')).join();   // 32 lowercase hex chars, Random.secure

final badgeTags = <List<String>>[
  if (badgeEnabled) ['badge', 'true'],
  if (badgeEnabled && badgeImageUrl.trim().isNotEmpty)
    ['badge_image', badgeImageUrl.trim()],
  if (badgeEnabled && (lat != 0 || lng != 0))
    ['g', lat.toStringAsFixed(6), lng.toStringAsFixed(6)],
  if (badgeEnabled)
    for (final hex in issuers)
      if (hex.length == 64) ['p', hex, '', 'issuer'],
  if (badgeEnabled)
    for (final e in formerIssuers.entries)
      if (e.key.length == 64)
        ['p', e.key, '', 'former_issuer', e.value.toString()],
];

if (allDay) {                       // kind 31922
  String ymd(DateTime d) => '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  tags = [
    ['d', dTag],
    ['title', title],
    ['start', ymd(start)],
    if (end != null) ['end', ymd(end)],
    if (location.trim().isNotEmpty) ['location', location.trim()],
    ['client', 'einundzwanzig-meetup-app'],
    ...badgeTags,
  ];
} else {                            // kind 31923
  final startUnix = (start.toUtc().millisecondsSinceEpoch ~/ 1000).toString();
  tags = [
    ['d', dTag],
    ['title', title],
    ['start', startUnix],
    if (end != null) ['end', (end.toUtc().millisecondsSinceEpoch ~/ 1000).toString()],
    if (location.trim().isNotEmpty) ['location', location.trim()],
    ['client', 'einundzwanzig-meetup-app'],
    ...badgeTags,
  ];
}
final signed = await SigningService.signEvent(kind: kind, tags: tags, content: description.trim());
```
Notes: `title` is used as passed (editor passes it trimmed). `g` is NON-standard (NIP-52 expects a geohash) — keep
`['g', "<lat 6 decimals>", "<lng 6 decimals>"]` exactly; other clients will misread it as a geohash, harmless.
`client` tag has only 2 elements. `p` tags carry an empty relay hint `''` at index 2. No `start_tzid`, `summary`, `image`, `t` tags.
created_at = signer's now (signing domain). Returns number of relays "accepted" (§5.2); exceptions → 0.

### 6.4 Update issuers (creator only)
```
me = SigningService.pubkeyHex(); if (me == null || me != event.pubkey) → false   // exact, case-sensitive
now = unix seconds
wanted = newIssuersHex.map(lowercase).filter(len == 64 && != event.pubkey.lowercase()).toOrderedSet()
former = copy(event.formerIssuers)
for old in event.issuers: k = old.lowercase(); if k !in wanted: former[k] = now
for k in wanted: former.remove(k)
publishEvent(title, description, location, start, end, allDay, badgeEnabled, badgeImageUrl, lat, lng,
             issuers = wanted.toList(), formerIssuers = former, existingDTag = event.dTag)
return n > 0
```
Consequence: if `event.badgeEnabled` is false, no issuer tags are written at all. Republishing drops any foreign
tags the original event had (only the creator can do this anyway).

### 6.5 Cancel event (creator only) — two events
1. Replacement (same kind, same d):
```dart
tags = [
  ['d', event.dTag],
  ['title', event.title],
  ['start', event.allDay ? _ymd(event.start) : (event.start.millisecondsSinceEpoch ~/ 1000).toString()],
  ['status', 'cancelled'],
  ['client', 'einundzwanzig-meetup-app'],
];
content = event.description;   // NOT trimmed
// _ymd here does NOT pad the year: '${d.year}-${MM}-${DD}'
```
(end, location, badge tags are dropped.) Published via `_publish`; `n` = relay count.
2. Deletion request (best effort, failure ignored):
```dart
kind: 5, tags: [['e', event.id], ['a', event.address], ['k', event.kind.toString()]], content: 'Termin abgesagt'
```
Result = `n > 0` from step 1. Note NIP-09 `a` deletion also removes the just-published cancelled version on relays that honour it (intended).

### 6.6 Publishing frame
```json
["EVENT", {"id":..., "pubkey":..., "created_at":..., "kind":..., "tags":[...], "content":..., "sig":...}]
```

### 6.7 RSVP (kind 31925)
```dart
static String _dTag(String eventAddress) {
  final clean = eventAddress
      .toLowerCase()
      .replaceAll(RegExp(r'[^a-z0-9]+'), '-')
      .replaceAll(RegExp(r'^-+|-+$'), '');
  return 'rsvp-$clean';
}
signed = SigningService.signEvent(kind: 31925, content: '', tags: [
  ['a', eventAddress],
  ['d', _dTag(eventAddress)],
  ['status', status.name],                 // accepted | declined | tentative
  if (eventAuthorPubkey.isNotEmpty) ['p', eventAuthorPubkey],
]);
```
Deterministic d-tag ⇒ a second answer replaces the first (decline = replace with `declined`, never delete).
Lowercasing must be locale-independent (`Locale.ROOT`). Publish to all readTargets in parallel; success when ≥1 relay
answers `["OK", <id>, true]`. Error strings: signing exception → `e.toString()`; none accepted → `"Kein Relay hat die Antwort angenommen"`.

`loadMine()`:
```json
["REQ", "rsvp", {"kinds":[31925], "authors":["<my pubkey hex>"], "limit":300}]
```
(subscription id is the literal `rsvp`). For each verified + filter-matching event: `created_at` must be an int;
take the LAST `a` tag value and the LAST `status` tag value; skip if address empty or status not one of the three;
keep per address the entry with strictly greater created_at. Returns `Map<address, RsvpStatus>`. No pubkey → `{}`.
(No future-timestamp guard here — see §14.)

### 6.8 Event chat
`ChatScreen.event(eventAddress, eventAuthor, title)` — NIP-22 comment thread on the event address, NOT the group relay (chat domain).

### 6.9 Signature verification
Every incoming event goes through `RelaySocket.verifiedEvent` (recompute NIP-01 id from `[0,pubkey,created_at,kind,tags,content]`,
compare, verify BIP-340 sig; tag elements stringified; any malformed input → reject, never throw) and `answersFilter`
(kinds, authors, since/until, every `#x` tag filter must have a matching tag `[x, v, ...]` with `v` in the list; `limit` ignored).

---

## 7. Crypto & encoding

- Random d-tag: 16 bytes from `SecureRandom`, lowercase hex, 2 chars per byte → 32 chars.
- Sub ids: `cal-` + 8 random bytes hex (16 chars); `cal1-` + 6 random bytes hex (12 chars); `rsvp` literal. Not wire-critical.
- Event id / Schnorr signing: delegated to the signing domain (NIP-01 serialization, BIP-340).
- npub ↔ hex: NIP-19 bech32 (`npub` HRP). Editor accepts `npub1…` (decoded; must give 64 hex) or 64 hex chars; stored lowercase.
- Unix time: `start.toUtc().millisecondsSinceEpoch ~/ 1000` (floor division of ms; times are minute-precise anyway).
- Date-only: `YYYY-MM-DD` zero-padded (year padded to 4 on publish, unpadded on cancel — identical for years ≥ 1000).
- `g` tag decimals: Dart `toStringAsFixed(6)` rounds the EXACT binary value of the double, half away from zero, and keeps
  the sign of negative values that round to zero (incl. `-0.0`). These strings are inside a signed event — use exactly
  the shared `Double.toStringAsFixed(fractionDigits)` from `:core:common` (`space.einundzwanzig.meetup.core.common.dart`, DartCompat.kt) as `v.toStringAsFixed(6)` (no alternative, no local copy). Reference semantics:
  ```kotlin
  fun dartFixed6(v: Double): String {   // == v.toStringAsFixed(6) from :core:common; reference only
      require(v.isFinite())                                   // lat/lng from the map picker are always finite
      val neg = java.lang.Double.doubleToRawLongBits(v) < 0   // true for -0.0 and tiny negatives
      val mag = BigDecimal(Math.abs(v))                        // BigDecimal(Double) = exact binary value, NOT BigDecimal.valueOf
          .setScale(6, RoundingMode.HALF_UP).toPlainString()
      return if (neg) "-$mag" else mag
  }
  ```
  Do NOT use `String.format(..., "%.6f", v)` / `"%.6f".format(v)` (Java rounds the shortest decimal repr, not the exact
  binary value → last digit differs on near-ties) and do NOT use `BigDecimal.valueOf(v)` (same problem) or plain
  `BigDecimal(v).setScale(...)` without the sign step (loses `-` for `-0.0`/`-1e-7`). Vectors (verified with Dart VM):
  `0.1234565 → "0.123456"` (String.format gives `0.123457`), `47.0000005 → "47.000000"` (`47.000001`),
  `16.3738185 → "16.373818"` (`16.373819`), `5e-7 → "0.000000"` (`0.000001`), `0.0078125 → "0.007813"`,
  `-0.0078125 → "-0.007813"`, `-1e-7 → "-0.000000"`, `-0.0 → "-0.000000"`, `48.2082 → "48.208200"`,
  `-122.4194 → "-122.419400"`. (Note: `lat != 0 || lng != 0` treats `-0.0` as zero, but one coordinate can still be `-0.0`.)
- Editor display of coordinates: 5 decimals (UI only).
- No hashing/KDF in this domain (the event-badge `m` id `evt:<address>:<slug>` is built in the badges domain; the calendar
  only checks `runningSessionId.contains(dTag)`).

---

## 8. Business rules & algorithms

### 8.1 Portal time parsing
```dart
static DateTime? portalStart(String rawIn) {
  final s = rawIn.trim();
  if (s.isEmpty) return null;
  final hasZone = s.endsWith('Z') || RegExp(r'[+-]\d{2}:?\d{2}$').hasMatch(s);
  final dt = DateTime.tryParse(s);
  if (dt == null) return null;
  return hasZone
      ? dt.toLocal()
      : DateTime.utc(dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second).toLocal();
}
```
i.e. a string WITHOUT zone is interpreted as **UTC** (proven by field logs: the live portal emits UTC without marker).
`portalStartUtc` (used by the organizer screens, other domain) behaves the same, using `dt.isUtc` instead of the regex.
Accepted input grammar = Dart `DateTime.parse`: `YYYY-MM-DD`, optional `T` or space + `HH[:MM[:SS[.fraction]]]` (also compact
`YYYYMMDD[THHMMSS]`), optional `Z`/`z` or `±HH[:MM]`/`±HHMM`. Milliseconds are dropped in the no-zone branch.
Kotlin: implement a small tolerant parser returning `LocalDateTime + ZoneOffset?`; no offset → `atOffset(UTC)`; then to Instant.
Logged once per session (diag): raw format and resulting HH:MM.

### 8.2 ICS parsing (fallback feed)
Replicate the subset of `icalendar_parser` behaviour that matters:
- Split lines on `\r\n|\n|\r`; drop trailing empty lines; first line must be `BEGIN:VCALENDAR`, last `END:VCALENDAR`
  (else parse error → `[]`); VERSION and PRODID required (else error → `[]`).
- Unfolding: following lines that start with a SPACE are appended after `trim()` of both (TAB folding not supported).
- Property line: name = text before the first `:` split on `;` (first element = name, rest `K=V` params); value =
  everything after the first `:`, with `\,` → `,`. Lines whose name part is not all-uppercase and contains no `;` are
  appended to the previous string property (continuation quirk).
- `BEGIN:X` (X ≠ VCALENDAR) starts a component `{type: X}`; `END` closes it (nested VALARM etc. become separate components).
- Used properties inside `VEVENT`: `DTSTART` (raw value string; TZID param ignored), `SUMMARY`, `DESCRIPTION`, `LOCATION`, `RRULE`
  (raw value string). EXDATE / RECURRENCE-ID / DTEND are ignored by the app.
- `CalendarEvent.fromMap`:
```dart
DateTime start = DateTime.now().add(const Duration(days: 365));   // fallback
final isUtc = raw.trim().endsWith('Z') || raw.trim().endsWith('z');
final s = raw.replaceAll(RegExp(r'[^0-9]'), '');
if (s.length >= 8) {
  y = s[0..4], m = s[4..6], d = s[6..8]; h = 19; min = 0;            // 19:00 fallback for DATE values
  if (s.length >= 12) { h = s[8..10]; min = s[10..12]; }
  start = isUtc ? DateTime.utc(y, m, d, h, min).toLocal() : DateTime(y, m, d, h, min);   // floating/TZID = device-local
}
description = rawDesc.replaceAll('\\n', '\n').replaceAll('\\', '');   // literal "\n" → newline, then strip ALL backslashes
title = summary ?? 'Meetup'; location = location ?? 'Ort unbekannt'; url = ''
```
(SUMMARY/LOCATION are not backslash-cleaned; only `\,` was already unescaped.) Then each VEVENT → `RecurrenceExpander.expand(base, rrule)`.
All results sorted by start; NO past filtering in this path.

### 8.3 Recurrence expansion (`RecurrenceExpander`)
Constants: `horizonDays = 366`, `maxOccurrences = 200`.
```
expand(base, rrule):
  if rrule null/blank → [base]
  rule = parse: strip leading "RRULE:" (case-insensitive); split ';'; each part split '=' and kept only if exactly 2 pieces;
         key = upper(trim(k)); value = trim(v)   (values are NOT uppercased)
  freq = rule.FREQ; if freq not in {"WEEKLY","MONTHLY"} → [base]
  interval = int(rule.INTERVAL ?: "1") ?: 1; if interval < 1 → 1
  count = int(rule.COUNT) or null
  until = parseUntil(rule.UNTIL): digits only, ≥8 → DateTime.utc(Y,M,D,23,59,59).toLocal(), else null
  horizon = now + 366 days; limit = (until != null && until < horizon) ? until : horizon
  results = [base] + (weekly|monthly occurrences); sort by start; take first 200
  any exception → [base]
```
Weekly:
```
weekdays = ordered set of BYDAY items (split ',', trim, upper) found in {MO..SU}; if empty → {start.weekday}
produced = 1
weekStart = date(start) − (start.weekday − 1) days        // Monday 00:00 of start's week
weekIndex = 0
loop:
  if weekIndex % interval == 0:
    for wd in weekdays (in BYDAY order):
      occ = weekStart + (wd − 1) days + start.hour h + start.minute min
      if occ > start && occ <= limit: add clone(occ); produced++; if count != null && produced >= count → return
  weekStart += 7 days; weekIndex++
  if weekStart > limit → return
  if produced >= 200 → return
```
Monthly:
```
produced = 1; ordinal = null; weekday = null
if BYDAY non-empty: match ^(-?\d+)?(MO|TU|WE|TH|FR|SA|SU)$ on trim(upper(BYDAY)) (a comma list never matches)
   → ordinal = int(group1) (null if absent), weekday = group2
monthIndex = interval; safety = 0
loop:
  if ++safety > 120 → return
  base0 = first day of (start.year, start.month + monthIndex)       // calendar arithmetic
  if base0 > limit → return
  if weekday != null && ordinal != null: occ = nthWeekdayOfMonth(...)
  else: occ = same day-of-month as start if it exists in that month (no clamping: 31st skips short months), else none
  if occ != null && occ > start && occ <= limit: add; produced++; if count reached → return
  monthIndex += interval
  if produced >= 200 → return
nthWeekdayOfMonth(y, m, wd, ord, h, min):
  ord > 0: offset = floorMod(wd − weekdayOf(y-m-01), 7); day = 1 + offset + (ord−1)*7; day > lastDay → null
  ord == −1: last = lastDay; offset = floorMod(weekdayOf(y-m-last) − wd, 7); day = last − offset
  other ord (e.g. −2, 0) → null
```
Kotlin pitfalls: use `Math.floorMod` (Dart `%` is non-negative); weekday numbering Monday=1..Sunday=7 (`DayOfWeek.value`).
Clone copies every field (incl. meetupId, meetupPortalLink, portalEventId) with the new start.
Known quirks to preserve or consciously fix: COUNT=1 (and 0) yields base + 1 occurrence; weekly arithmetic in Dart adds
absolute 24 h durations (wall-clock time drifts ±1 h after a DST change — recommended fix: compute with `LocalDate`/`LocalTime`);
a series that started long ago with no UNTIL can exhaust the 200 cap before reaching today.

### 8.4 Meetup ↔ date matching (`MeetupEventMatcher`)
```dart
static String portalIdentity(String value) {
  final uri = Uri.tryParse(value.trim());
  if (uri == null || !{'http','https'}.contains(uri.scheme) ||
      uri.host.toLowerCase() != 'portal.einundzwanzig.space') return '';
  final parts = uri.pathSegments.where((p) => p.isNotEmpty).toList();
  final index = parts.indexOf('meetup');               // FIRST occurrence
  if (index < 0 || index != parts.length - 2) return '';
  return parts.last;                                    // slug
}
static Meetup? resolve(CalendarEvent event, List<Meetup> meetups) {
  unique(list) = list.length == 1 ? single : null
  if (event.meetupId.isNotEmpty) return unique(meetups.where(m.id == event.meetupId));      // never falls through
  if (event.meetupPortalLink.isNotEmpty) {
    id = portalIdentity(event.meetupPortalLink); if (id.isEmpty) return null;
    return unique(meetups.where(portalIdentity(m.portalLink) == id));
  }
  named = meetups.where(m.name.trim().isNotEmpty && m.name.trim().lower == event.title.trim().lower)
  if (named.isNotEmpty) return unique(named);
  return unique(meetups.where(matchesCity(event, m.city)));
}
static bool matchesCity(CalendarEvent event, String city) {
  if (event.meetupId.isNotEmpty || event.meetupPortalLink.isNotEmpty) return false;
  term = city.trim().lower; if (term.length < 3 || term in {bitcoin, meetup, einundzwanzig, stammtisch}) return false;
  hay = "${event.title} ${event.location}".lower
  return RegExp('(^|[^\\p{L}\\p{N}])' + escape(term) + '($|[^\\p{L}\\p{N}])', unicode: true).hasMatch(hay);
}
```
Query string/fragment are ignored (path only); scheme/locale segment (`/de/`, `/en/`) irrelevant; trailing slash tolerated.
Kotlin regex: `Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(term) + "($|[^\\p{L}\\p{N}])")` (Java supports `\p{L}`/`\p{N}`);
lowercase with `Locale.ROOT`. Use `java.net.URI` carefully: Dart `pathSegments` are percent-decoded.
Favorite matching used by Home (`_eventMatchesFavorite(event, key)`): `m = MeetupService.resolveFavorite(key)`;
`m != null ? resolve(event, cached)?.id == m.id : matchesCity(event, key)`.

### 8.5 Portal-first meetup feed (`fetchMeetupsPortalFirst`)
As in §2.2 step 1 (identical field mapping), cutoff now − 6 h, side effect: `portalLogos[name] = logo` (raw). If ≥1 event →
sorted list. If 0 events or exception → ICS `fetchMeetups()`. All screens should use this single entry point.
`logoFor(titleOrCity)`: q = trim.lower; empty → ""; iterate registry in insertion order; first entry whose lowercase name
`== q || name.contains(q) || q.contains(name)` → `absoluteImageUrl(logo)`; none → "".
`absoluteImageUrl(u)`: trim; empty or starts with `http://`/`https://` → as is; else `https://portal.einundzwanzig.space` + (`/` if missing) + u.

### 8.6 Event calendar merge
1. Items: Nostr (from `fetchEvents(worldwide)`), meetup dates (portal-first), courses:
   for each course detail `c`: `cname = c.name ?? c.title ?? "Kurs"`, `cdesc = c.description ?? ""`, `cportal = c.portalLink ?? ""`;
   for each map `ev` in `c.events`: `start = portalStart(ev.from ?? ev.start ?? "")`; skip if null or `< now − 6h`;
   key = `ev.id != null ? "e${ev.id}" : "$cname@${start.toIso8601String()}"`; skip duplicates (set across ALL courses);
   location = `venue.name` (+ `" · " + venue.city.name` if city name non-empty; just the city if name empty), else `ev.location ?? ""`;
   end = `DateTime.tryParse(ev.to)?.toLocal()` (NOTE: not via portalStart — zone-less "to" is read as LOCAL; fix: use portalStart);
   allDay=false; isCourse=true; url = `ev.link ?? cportal` (not displayed anywhere).
2. Sort by start. Dedup:
```dart
String norm(String s) => s.toLowerCase().replaceAll(RegExp(r'[^a-z0-9äöü]'), '');
int rank(_CalItem e) => e.isCourse ? 0 : (e.isMeetup ? 1 : 2);
tkey = norm(it.title); tShort = tkey.length > 12 ? tkey.substring(0, 12) : tkey;
minuteKey = '${y}${m}${d}${h}${min}';            // NOT zero-padded (local time fields)
key = '$minuteKey|$tShort';
keep first item per key unless a later one has strictly lower rank (then it replaces)
```
3. Result = map values (first-insertion order) sorted by start.
Filter: type (0 all; 1 `isMeetup`; 2 `!isMeetup && !isCourse`; 3 `isCourse`) AND location (`q = query.trim.lower`;
if q non-empty: keep items whose trimmed location is empty OR whose lowercase location contains q). Group by local date of start.

### 8.7 Formatting
`_fmtWhen(item, showDate)`: datePart = `"${d.day}. ${monthName(d.month)} ${d.year}"` (day not padded); allDay →
`showDate ? "$datePart · ${calAllDay}" : calAllDay`; else time `"HH:MM"` → `showDate ? "$datePart · $time" : time`.
Month view selected header: `"${day}. ${monthName}"`. Month header: `"${monthName} ${year}"`.
All-day multi-day events are only listed on their start day.

### 8.8 Badge permission & window
```dart
bool isIssuer(String pubkeyHex, {int? atEpoch}) {
  if (pubkeyHex.isEmpty) return false;
  final k = pubkeyHex.toLowerCase();
  if (k == pubkey.toLowerCase()) return true;                       // creator always
  if (issuers.any((i) => i.toLowerCase() == k)) return true;
  if (atEpoch == null) return false;
  for (final e in formerIssuers.entries) {
    if (e.key.toLowerCase() == k && atEpoch <= e.value) return true; // valid up to removal time inclusive
  }
  return false;
}
bool get isBadgeWindowOpen {
  if (!badgeEnabled) return false;
  final now = DateTime.now();
  final from = DateTime(start.year, start.month, start.day);           // local midnight of start date
  final until = (end ?? start).add(const Duration(days: 1));          // +24h absolute
  final to = DateTime(until.year, until.month, until.day);
  return !now.isBefore(from) && now.isBefore(to);                      // [from, to)
}
```
Port with `LocalDate`: `from = startDate.atStartOfDay`, `to = (endDate ?: startDate).plusDays(1).atStartOfDay` (differs from Dart
only in DST edge cases). Session start (badges domain) additionally requires coordinates and distance ≤ 5.0 km
(`EventBadgeSessionService.issuerRadiusKm`), session valid until midnight after the last event day.
UI conditions (§2.3) use `isIssuer` WITHOUT epoch; badge verification (badges domain) uses it WITH the signed issue time.

### 8.9 Misc rules
- Community mode = community relay only + `client == "einundzwanzig-meetup-app"`; worldwide = all active relays, all events.
- Cancelled events never shown.
- Portal meetup dates shown from now − 6 h; list view of calendar from now − 1 day; My events from now − 1 day; Home RSVP scan up to +90 days.
- RSVP prefetch: first 12, then waves of 20; cache TTL 1 h; force refresh after own RSVP.
- Session button label: "QR anzeigen" if running (non-expired) session's meetupId contains the event's dTag.
- My events date color: days ≤ 0 orange, ≤ 3 orange 80 %, else tertiary.
- Unread badge cap "99+".

---

## 9. Security measures to preserve

1. **Signature verification of every relay event** (Security Audit H2/M1): recompute id + BIP-340 verify before parsing;
   plus `answersFilter` against the exact REQ filter (a valid but unrelated event must not be accepted as an answer).
2. **Future timestamp guard** for addressable "newest wins" (`created_at > now + 600 s` ignored) in `fetchByAddress`
   (prevents an attacker-relay from pinning an issuer list forever).
3. **Message size cap** 512 KiB chars per frame (M4).
4. **Link opening whitelist** (M2): only `http`/`https` URIs with a non-empty host via external app; applies to description
   links, event URLs, portal links. Never render event description as HTML/Markdown — plain text only.
5. **Issuer semantics**: only `p` tags with role `issuer` (index 3) grant badge rights; plain NIP-52 `p` participants do not;
   64-char check; removed issuers become `former_issuer` with removal time so previously issued badges stay valid but no new
   ones; creator implicitly authorised. Badge verification (other domain) also checks the creator against the admin registry.
6. **Creator-only mutations**: cancel and issuer editing check `SigningService.pubkeyHex() == event.pubkey` in the service
   (not only UI). (Relays enforce it anyway since replaceable events are keyed by pubkey.)
7. **Badge creation double gate**: `badgeEnabled && mayCreateBadge`; badge without coordinates is rejected at creation;
   session needs event day + ≤ 5 km + own key.
8. **Cancellation = replace + delete request** (never rely on NIP-09 alone).
9. **Portal token bound to the current identity** before RSVP (`tokenMatchesCurrentKey`, auto re-login otherwise); 401 deletes the token.
10. **Widget hardening** (N4): `MeetupWidgetProvider`, background receiver/service and `WidgetRouterActivity` are NOT exported;
    PendingIntents are `FLAG_IMMUTABLE`; distinct action + data + requestCode per target; router writes target with `commit()`;
    targets are a closed set (`bitcoin|meetup|news`) — the app must ignore unknown values.
11. Secure randomness for d-tags (`SecureRandom`).
12. Privacy note: badge images and logos are loaded from URLs inside untrusted events (IP leak to arbitrary hosts) — same as
    Flutter; consider a size limit / loading only on demand.

---

## 10. Tests & vectors to port

Existing Flutter tests:
1. `test/meetup_event_matcher_test.dart` (fixtures: south = id 107 "Einundzwanzig Südniedersachsen", city Göttingen,
   slug `einundzwanzig-suedniedersachsen`; saxony = id 32 "Einundzwanzig Sachsen", city Sachsen; portalLink =
   `https://portal.einundzwanzig.space/de/meetup/<slug>`; default event title "Einundzwanzig Südniedersachsen",
   location "Dinx Diner", start 2026-10-09, url `https://example.com/event`):
   - link = south.portalLink → resolve id `107`; `matchesCity(e, "Sachsen") == false`.
   - link with `/en/` instead of `/de/` and suffix `/?source=calendar` → `107`.
   - id `32` + south link → `32`; id `unknown` + south link → null.
   - link `<south>-unknown` → null; `https://example.com/de/meetup/einundzwanzig-suedniedersachsen` → null.
   - no id/link: exact name → `107`; `matchesCity(default, "Sachsen") == false`; title "Treffen in Sachsen" → true;
     "Frankfurter Straße" vs "Frankfurt" → false.
   - two Würzburg groups (`walk-wuerzburg`, `wuerzburg-meetup`): link of b → `2`; title "Treffen Würzburg" → null (ambiguous).
   - "Bitcoin München"/München id 3: title "Treffen in München" → `3`; "ÜSachsen" vs "Sachsen" → false.
2. `test/meetup_portal_identity_test.dart`: mock HTTP — `/api/mobile/meetups` returns `[{id:107,name:"Einundzwanzig Südniedersachsen",city:"Göttingen",slug:"einundzwanzig-suedniedersachsen"},{id:32,name:"Einundzwanzig Sachsen",city:"Sachsen",portalLink:"https://portal.einundzwanzig.space/de/meetup/einundzwanzig-sachsen"}]`;
   every `/api/meetup-events/*` returns `{data:[{id:3869,start:<now+10d ISO UTC>,"meetup.name":"Einundzwanzig Südniedersachsen","meetup.portalLink":"https://portal.einundzwanzig.space/de/meetup/einundzwanzig-suedniedersachsen","meetup.city":"Göttingen",location:"Dinx Diner",link:"https://example.com/event"}]}`.
   Expect exactly 1 event (dedup by id across the 3 date requests), `meetupId` empty, `meetupPortalLink` = link,
   `url` = `https://example.com/event`, `portalEventId` 3869, resolves to `107`. Live variant gated by env `LIVE_PORTAL_TEST`.
3. `test/relay_event_signature_test.dart` (calendar parts): signed kind 31923 with tags `d=test-meetup, title=Echtes Meetup, start=now+86400`
   verifies and parses (title "Echtes Meetup"); empty sig / tampered title / foreign pubkey / changed id / non-hex sig or pubkey
   → rejected; `answersFilter` true for `{kinds:[31923], authors:[pk], #d:[test-meetup]}`, false for kinds `[0]`, wrong author,
   wrong `#d`; `replaceableCreatedAt` returns created_at, null for now+3600; garbage input → null without throwing.
4. `test/event_details_sheet_test.dart` (Compose UI tests): one-line content → sheet < 200 dp, content hit-testable, close
   (content description **actionClose**) dismisses only the sheet; long content (150× sentence, text scale 2.0) at 402×874
   and 320×568 with insets top 62 / bottom 34: close button within insets, sheet shorter than height − 62, scrollable to
   bottom with close button fixed, still correct after rotation; share-anchor test is iOS-specific (skip).
5. `test/meetup_key_test.dart` "Event-Badges sind nicht betroffen": `m = "evt:31923:<64×a>:zitadelle:zitadelle-2026"` → `is_event` (badges domain).

New vectors (derived from the Dart code; tests should inject a clock and a fixed `TimeZone`, e.g. `UTC`, unless stated):
- RSVP d-tag: `"31923:" + "ab"*32 + ":Zitadelle 2026!"` → `"rsvp-31923-" + "ab"*32 + "-zitadelle-2026"`;
  `"31922:<64 hex>:ä-test"` → `"rsvp-31922-<hex>-test"`.
- RSVP tags: `[["a",addr],["d",dtag],["status","accepted"],["p",author]]`, content `""`, kind 31925; empty author → no `p`.
- Publish (time) — start = 2026-06-19T08:00Z, no end, location " Prag ", badge on, image " https://x/y.png ", lat 50.087451,
  lng 14.420671, issuers [A(64 hex)], former {B: 1700000000}, dTag D:
  `[["d",D],["title","BTC Prag"],["start","1781856000"],["location","Prag"],["client","einundzwanzig-meetup-app"],["badge","true"],["badge_image","https://x/y.png"],["g","50.087451","14.420671"],["p",A,"","issuer"],["p",B,"","former_issuer","1700000000"]]`.
- Publish with badge off but issuers given → no badge/g/p tags at all.
- `g` value formatting: run the `dartFixed6` vectors from §7 (exact-binary HALF_UP + sign; String.format must fail them).
- Publish (all-day) start 2026-06-19, end 2026-06-21, empty location → `[["d",D],["title",T],["start","2026-06-19"],["end","2026-06-21"],["client","einundzwanzig-meetup-app"]]`, kind 31922.
- Cancel time-based event start 1781884800 → `[["d",D],["title",T],["start","1781884800"],["status","cancelled"],["client","einundzwanzig-meetup-app"]]`;
  deletion `kind 5`, `[["e",id],["a","31923:<pk>:<D>"],["k","31923"]]`, content `"Termin abgesagt"`.
- updateIssuers: event issuers [A, C], former {}; new list [C, D, creator] at now=T → issuers [C, D] (creator excluded),
  former {A: T}; then new list [A] → issuers [A], former {C: T2, D: T2} (A removed from former).
- `isIssuer`: creator P → true (also uppercase P); issuer A → true; former B(1700000000) → false without epoch,
  true at 1700000000, false at 1700000001; `""` → false.
- `fromEvent`: `["badge","TRUE"]` → badgeEnabled; `["p",A,"","issuer"]` with A length 63 → ignored; `["p",A]` → not issuer;
  `["p",B,"","former_issuer","x"]` → ignored; title missing but `["name","Old"]` → title "Old"; kind 1 → null; start
  `"abc"` on 31923 → null; `["status","cancelled"]` → isCancelled.
- `fetchByAddress` parse: `"31923:ABC…(64):my:d:tag"` → kind 31923, author lowercased, dTag `"my:d:tag"`; `"31923:short:x"` → null.
- `portalStart` (device zone Europe/Berlin): `"2026-07-21 16:00"` → 18:00 local; `"2026-07-21T16:00:00Z"` → 18:00;
  `"2026-07-21T16:00:00+02:00"` → 16:00; `"2026-01-15 18:00:00"` → 19:00; `""`/`"garbage"` → null.
- ICS DTSTART (Europe/Berlin): `20260721T163000Z` → 18:30; `20260721T183000` (TZID) → 18:30; `20260721` → 19:00; missing → now+365d.
- Recurrence (zone UTC, now = 2026-01-01T00:00):
  - base Tue 2026-01-06 19:00, `FREQ=WEEKLY;COUNT=4` → 01-06, 01-13, 01-20, 01-27.
  - base Mon 2026-01-05 18:30, `RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH;COUNT=5` → 01-05, 01-08, 01-19, 01-22, 02-02.
  - base Thu 2026-01-15 19:00, `FREQ=MONTHLY;BYDAY=3TH;COUNT=3` → 01-15, 02-19, 03-19.
  - base Fri 2026-01-30 19:00, `FREQ=MONTHLY;BYDAY=-1FR;COUNT=3` → 01-30, 02-27, 03-27.
  - base Sat 2026-01-31 19:00, `FREQ=MONTHLY;COUNT=3` → 01-31, 03-31, 05-31 (short months skipped).
  - `FREQ=WEEKLY;COUNT=1` → base + 1 (01-06, 01-13).
  - `FREQ=WEEKLY;UNTIL=20260120T000000Z` → 01-06, 01-13, 01-20.
  - `FREQ=DAILY` → [base]; `FREQ=weekly` → [base]; `freq=WEEKLY;interval=0;count=2` → base + next week; `""`/null → [base].
- Dedup key: start 2026-03-05 09:07, title "Einundzwanzig Würzburg Stammtisch" → `"20263597|einundzwanzi"`; a course and a
  Nostr item with the same key → course kept.
- Widget `groupInt`: 0 → "0", 999 → "999", 1000 → "1.000", 1234567 → "1.234.567".
- Widget countdown: same day → "Heute", tomorrow → "Morgen", +5 calendar days → "in 5 Tagen".

---

## 11. Android capabilities & recommended APIs

| Need | Flutter | Native recommendation |
|---|---|---|
| Relay WebSockets | web_socket_channel | OkHttp WebSocket (shared Nostr client from nostr-core domain), coroutines with `withTimeout` |
| HTTP (portal, ICS) | http | OkHttp/Retrofit + kotlinx.serialization `JsonElement` (tolerant parsing) |
| ICS parsing | icalendar_parser | small custom parser (§8.2) — avoid ical4j (heavy, different TZ semantics) |
| Signing | SigningService (local / Amber NIP-55 / NIP-46) | signing domain API |
| npub encode/decode | nostr package | bech32 util (shared) |
| Add to calendar | add_2_calendar | `Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)` with `TITLE`, `DESCRIPTION`, `EVENT_LOCATION`, `EXTRA_EVENT_BEGIN_TIME`, `EXTRA_EVENT_END_TIME`, `EXTRA_EVENT_ALL_DAY`; start with try/catch `ActivityNotFoundException` (no permission needed; `resolveActivity` would need a `<queries>` entry) |
| Share | share_plus | `Intent.ACTION_SEND` text/plain + chooser; fallback `ClipboardManager` |
| Open links | url_launcher | `ACTION_VIEW` (or Custom Tabs) after http/https+host check; `<queries>` for https already declared |
| Image pick | image_picker | Photo Picker `ActivityResultContracts.PickVisualMedia(ImageOnly)` (no permission), downscale to ≤1200 px, JPEG q85 |
| Upload | Blossom service | media domain |
| Location for badge session | geolocator | badges/location domain: Fused/`LocationManager` fallback for de-Googled devices; `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`; settings intents `Settings.ACTION_LOCATION_SOURCE_SETTINGS`, `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` |
| Map picker | flutter_map (OSM) | location domain (osmdroid or MapLibre with OSM tiles) |
| Date/time pickers | Material pickers | M3 `DatePickerDialog` (selectableDates now−1d … now+5y), `TimePickerDialog` (dismiss → 19:00) |
| Home-screen widget | home_widget + RemoteViews | keep `AppWidgetProvider` named `MeetupWidgetProvider` (RemoteViews XML port is simplest; Glance possible if the receiver class keeps the FQN) |
| Widget data | HomeWidgetPreferences | same SharedPreferences file (or DataStore with one-time migration) |
| Widget refresh | home_widget background isolate | non-exported `BroadcastReceiver` → WorkManager `OneTimeWorkRequest` (expedited); optional `PeriodicWorkRequest` (≥15 min) — Flutter has none (updatePeriodMillis only re-renders) |
| Widget tap routing | WidgetRouterActivity + MethodChannel | keep router activity + `e21_widget/pending_target`; MainActivity reads/clears it in `onCreate`/`onResume` |
| Persistence | shared_preferences / flutter_secure_storage | DataStore/SharedPreferences + migration of `FlutterSharedPreferences`; token in Keystore-backed storage (portal domain) |
| Permissions | INTERNET, location (session) | INTERNET; location only when starting a badge session; no calendar permission |

Compose / M3 Expressive mapping: `SingleChoiceSegmentedButtonRow` (view switch, type filter), `ModalBottomSheet` with
`skipPartiallyExpanded=false` (details), `ExtendedFloatingActionButton`, `PullToRefreshBox`, `LoadingIndicator`
(expressive), `AlertDialog`, `SnackbarHost` with actions, `LazyVerticalGrid` (year view), custom month grid.

---

## 12. Cross-domain dependencies

Calls OUT of this domain:
- **Nostr core / relays**: `RelaySocket.connect/listen/send/close`, `verifiedEvent`, `answersFilter`, `replaceableCreatedAt`,
  frame cap; `RelayConfig.getActiveRelays`, `publishTimeout (5 s)`; `NewsReactionsService.readTargets` (RSVP relay set).
- **Signing/identity**: `SigningService.signEvent(kind, tags, content)`, `pubkeyHex()`, `npub()`; NIP-19 encode/decode
  (`Nip19`, `NostrService.npubToHex`).
- **Portal**: `getAllMeetupEvents`, `getCourses`, `getCourse`, `getRsvp`, `getRsvpCached`, `rsvp`, `getRsvpAttendees`,
  `isGoing`, `tokenMatchesCurrentKey`, `loginWithNostr`, `getMyMeetups` (via badge right).
- **Meetups/user**: `MeetupService.fetchMeetups/cached/resolveFavorite/labelFor/cityFor`, `Meetup` model,
  `UserProfile.load/save` (`favoriteMeetupIds`, `homeMeetupId`, `isAdmin`).
- **Badges**: `EventBadgeSessionService.start`, `EventSessionError`, `EventBadgeAuthService.myRight`, `RollingQRService.loadSession`
  (+`isExpired`, `meetupId`), `RollingQRScreen`, `BadgeSecurity.eventMeetupId` (format `evt:<address>:<slug>` that the
  `contains(dTag)` check relies on), `CoAttendanceService` (via session).
- **Chat**: `ChatScreen.event(eventAddress, eventAuthor, title)`, `EventChatService.unreadCounts(addresses)`,
  Home's `_openMeetupChat(favKey, label)`.
- **Media**: `BlossomUploadService.uploadImage(path) → {url|error}`; image picker.
- **Location**: `LocationPickerScreen(initialLat, initialLng) → LatLng?`; settings intents.
- **Guide/tours**: `GuideService.wasTourCompleted/startTour/finishTour/activeTour`, `GuideTour.events`, `EventBadgeTour` keys/steps.
- **UI kit**: `NpubChip` (njump.me, copy), theme colors, `launchHttpUrl`.
- **Widget data**: `MempoolService.getDashboardData`/`BitcoinDashboardData`, `CurrencyService.read/symbol`, `NewsService.fetchArticles`.
- **Logging**: `AppLogger.diag/debug/warn`, `RelayParseTally`.

Calls INTO this domain:
- Home dashboard: `fetchMeetupsPortalFirst`, `CalendarEventService.fetchEvents` (today counter, community mode),
  `fetchByAddress` (my events), `EventRsvpService.loadMine`, `MeetupEventMatcher.resolve/matchesCity`,
  `MeetupCalendarService.logoFor/absoluteImageUrl`, `WidgetService.*`, navigation to `CalendarScreen`, `EventCalendarScreen`, `MyEventsScreen`.
- Meetup details screen: `fetchMeetupsPortalFirst` (filters by city substring itself).
- Nearby meetups service: `fetchMeetupsPortalFirst` (cache).
- Crest watermark widget: `portalLogos`, `logoFor`, `absoluteImageUrl`, `fetchMeetupsPortalFirst` (to fill registry).
- Community hub `PortalEventsScreen`: `portalStart`; organizer screens: `portalStartUtc`.
- Badge verification (`EventBadgeChainService`): `fetchByAddress`, `badgeEnabled`, `isIssuer(signer, atEpoch)`, `badgeImageUrl`, `pubkey`.
- Badge session: `NostrCalendarEvent` fields (`address`, `title`, `lat/lng`, `start/end`, `isBadgeWindowOpen`, `isIssuer`).
- App shell: `EventsHubScreen` tab; badge wallet / community hub: `CalendarScreen()`.
- `main`/background: widget refresh callback.

---

## 13. Proposed Kotlin package structure & public API

```
space.einundzwanzig.meetup.calendar
├── model/        MeetupDate, NostrCalendarEvent, RsvpStatus, CalendarItem, MyMeetupDate, PortalRsvpInfo
├── time/         PortalTime (portalStart/portalStartUtc), DartDateParser
├── ics/          IcsParser, IcsEvent, RecurrenceExpander
├── portal/       MeetupDateRepository (portal-first + ICS), PortalLogoRegistry, CourseDateSource, PortalRsvpRepository (wraps PortalApi + cache)
├── nostr/        NostrCalendarCodec (parse/build tags), NostrCalendarRepository, NostrRsvpRepository
├── domain/       MeetupEventMatcher, CalendarMerger (dedup/rank/filter), BadgeWindow
├── ui/hub/       EventsHubScreen
├── ui/meetups/   MeetupDatesScreen + MeetupDatesViewModel, MeetupDateDetailsSheet
├── ui/calendar/  EventCalendarScreen + EventCalendarViewModel, EventDetailsSheet, EditIssuersSheet, MonthGrid, YearGrid
├── ui/editor/    EventEditorScreen + EventEditorViewModel
├── ui/myevents/  MyEventsScreen + MyEventsViewModel
├── ui/common/    BoundedDetailsSheet, CalendarIntents (insert/share/open)
└── widget/       MeetupWidgetProvider (FQN space.einundzwanzig.meetup.MeetupWidgetProvider), WidgetRouterActivity,
                  WidgetDataStore, WidgetRefreshReceiver, WidgetRefreshWorker, WidgetTargetStore
```

Key signatures:
```kotlin
data class MeetupDate(
    val title: String, val description: String, val location: String,
    val start: Instant, val url: String,
    val meetupId: String = "", val meetupPortalLink: String = "", val portalEventId: Int? = null,
)

data class NostrCalendarEvent(
    val id: String, val pubkey: String, val dTag: String, val title: String, val description: String,
    val location: String, val start: Instant, val end: Instant?, val startDate: LocalDate?, val endDate: LocalDate?,
    val allDay: Boolean, val kind: Int, val fromApp: Boolean = false, val status: String = "",
    val badgeEnabled: Boolean = false, val badgeImageUrl: String = "", val lat: Double = 0.0, val lng: Double = 0.0,
    val issuers: List<String> = emptyList(), val formerIssuers: Map<String, Long> = emptyMap(),
    val createdAt: Long,
) {
    val isCancelled: Boolean get() = status == "cancelled"
    val address: String get() = "$kind:$pubkey:$dTag"
    fun isIssuer(pubkeyHex: String, atEpoch: Long? = null): Boolean
    fun isBadgeWindowOpen(now: ZonedDateTime): Boolean
    companion object { const val KIND_TIME = 31923; const val KIND_DATE = 31922; const val CLIENT = "einundzwanzig-meetup-app" }
}

enum class RsvpStatus(val wire: String) { ACCEPTED("accepted"), DECLINED("declined"), TENTATIVE("tentative") }

object PortalTime {
    fun portalStart(raw: String): Instant?
    fun portalStartUtc(raw: String): Instant?
}

object IcsParser { fun parse(ics: String): List<IcsComponent> /* throws on invalid envelope */ }
object RecurrenceExpander {
    const val HORIZON_DAYS = 366; const val MAX_OCCURRENCES = 200
    fun expand(base: MeetupDate, rrule: String?, now: ZonedDateTime, zone: ZoneId): List<MeetupDate>
}
object MeetupEventMatcher {
    fun portalIdentity(value: String): String
    fun resolve(event: MeetupDate, meetups: List<Meetup>): Meetup?
    fun matchesCity(event: MeetupDate, city: String): Boolean
}

interface MeetupDateRepository {
    suspend fun fetchPortalFirst(): List<MeetupDate>          // §8.5, also fills PortalLogoRegistry
    suspend fun fetchIcs(): List<MeetupDate>                  // §8.2/8.3
}
object PortalLogoRegistry { fun put(name: String, logo: String); fun logoFor(titleOrCity: String): String; fun absoluteImageUrl(url: String): String }

interface CourseDateSource { suspend fun fetchCourseItems(now: Instant): List<CalendarItem> }

interface NostrCalendarRepository {
    suspend fun fetchEvents(limit: Int = 200, worldwide: Boolean = false): List<NostrCalendarEvent>
    suspend fun fetchByAddress(address: String): NostrCalendarEvent?
    suspend fun publishEvent(draft: CalendarEventDraft, existingDTag: String? = null): Int   // relays "accepted"
    suspend fun updateIssuers(event: NostrCalendarEvent, newIssuersHex: List<String>): Boolean
    suspend fun cancelEvent(event: NostrCalendarEvent): Boolean
}
data class CalendarEventDraft(
    val title: String, val description: String, val location: String, val start: ZonedDateTime, val end: ZonedDateTime?,
    val allDay: Boolean, val badgeEnabled: Boolean = false, val badgeImageUrl: String = "",
    val lat: Double = 0.0, val lng: Double = 0.0, val issuers: List<String> = emptyList(),
    val formerIssuers: Map<String, Long> = emptyMap(),
)
object NostrCalendarCodec {
    fun parse(verifiedEvent: JsonObject): NostrCalendarEvent?
    fun buildTags(draft: CalendarEventDraft, dTag: String): Pair<Int, List<List<String>>>
    fun cancelTags(event: NostrCalendarEvent): List<List<String>>
    fun deletionTags(event: NostrCalendarEvent): List<List<String>>
    fun parseAddress(address: String): Triple<Int, String, String>?
}

interface NostrRsvpRepository {
    fun dTagFor(eventAddress: String): String
    suspend fun setStatus(eventAddress: String, eventAuthorPubkey: String, status: RsvpStatus): String?  // null = ok
    suspend fun loadMine(): Map<String, RsvpStatus>
}

interface PortalRsvpRepository {
    suspend fun get(eventId: Int, forceRefresh: Boolean = false): JsonObject?      // 1 h cache
    suspend fun set(eventId: Int, status: String /* attending|maybe|none */): PortalResult
    suspend fun attendees(eventId: Int): List<Attendee>
    fun isGoing(r: JsonObject?): Boolean
    fun count(r: JsonObject?): Int
}

object CalendarMerger {
    fun dedupKey(item: CalendarItem, zone: ZoneId): String
    fun merge(items: List<CalendarItem>, zone: ZoneId): List<CalendarItem>
    fun filter(items: List<CalendarItem>, type: TypeFilter, locationQuery: String): List<CalendarItem>
}

object CalendarIntents {
    fun insert(context: Context, title: String, description: String, location: String, begin: Instant, end: Instant, allDay: Boolean): Boolean
    fun share(context: Context, text: String)
}

class WidgetDataStore(context: Context) {   // file "HomeWidgetPreferences"
    fun writeBitcoin(d: BitcoinDashboardData, currency: String)
    fun writeMeetup(city: String, countdown: String)
    fun writeNews(title: String, isNew: Boolean)
    fun requestUpdate()
}
object WidgetTargetStore { fun consume(context: Context): String? }   // e21_widget / pending_target
```
Screens: `EventsHubScreen(onMeetups, onCalendar)`, `MeetupDatesScreen(initialSearch: String?, initialMeetupId: String?)`,
`EventCalendarScreen(initialDay: LocalDate?)`, `EventEditorScreen(onPublished: () -> Unit)`,
`MyEventsScreen(events, meetupDates, onOpenMeetupChat, onChanged)`.

---

## 14. Risks / compatibility pitfalls

1. **Tag layout must be byte-identical** (order, empty relay hint `''` in `p` tags, 2-element `client` tag, non-standard
   `g` lat/lng tag, `badge`=`"true"`, `former_issuer` unix string). Other app instances verify event badges against these
   tags; changing roles/positions silently invalidates badges.
2. **Address & d-tag**: addresses are `<kind>:<pubkey>:<d>` with the pubkey as published (lowercase hex). RSVP d-tag
   derivation must match exactly or users get duplicate RSVPs alongside old ones (newest-wins still works, but clutter).
3. **Time zones**: portal strings without zone are UTC; ICS floating/TZID times are device-local; `g`/unix are UTC.
   Course `to` is parsed as local in Flutter (bug) — use `portalStart`. All-day events must keep `LocalDate` so republishing
   (issuers/cancel) emits the same date regardless of device zone.
4. **Dart `%` vs Kotlin `%`**: recurrence offsets need `Math.floorMod`. Weekday numbering Monday=1.
5. **Dart Duration on local time** shifts wall-clock across DST in recurrence/badge window; decide to fix (recommended) and
   document the deviation (display only, not wire).
6. **Dedup of Nostr list is "last seen wins", not newest created_at**, and cancelled versions are dropped before dedup — an
   older active copy on another relay can resurrect a cancelled event in the calendar (fetchByAddress handles it correctly).
   Recommended: dedup by `replaceableCreatedAt` including cancelled versions, then drop cancelled.
7. **`limit: 200` without `since`** on the community relay: once >200 calendar events exist, older-created upcoming events
   disappear. Consider adding `since` or paging (read-side only, safe).
8. **Publish "success" is not acknowledged** (connect + send + 2 s sleep counts as accepted). A native port that waits for
   `OK` is stricter and may report failures where Flutter reported success — acceptable, but keep the community relay always included.
9. **RSVP publishes to the read-only aggregator** `relay.nostr.band` too (always times out) — harmless; keep or drop.
10. `loadMine` has no future-`created_at` guard — a malicious relay could pin an RSVP state; consider adding `replaceableCreatedAt`.
11. **Portal RSVP JSON is undocumented**; keep all tolerant key fallbacks (`status|going|is_going|rsvped`, `attendees|count|total|rsvps`, `might|might_attendees`, `data` unwrap).
12. Portal `start`/`meetup.*` use FLAT dotted keys in the live API; nested form must also be accepted.
13. `CalendarEvent` identity semantics: don't use Kotlin data-class equality as map keys for per-event UI state; key by `portalEventId`.
14. Crest URLs from `meetup.logo` are stored raw in CalendarScreen (may be relative → broken image); apply `absoluteImageUrl`.
15. Flutter bug: CalendarScreen RSVP error shows the closure text — port with `rsvpFailed(msg)`.
16. Editor accepts any 64-char string in the helpers sheet; validate hex in the port. No end ≥ start validation (keep or add a soft warning; other clients accept either).
17. **Widget continuity**: keep provider FQN, `HomeWidgetPreferences` keys and `e21_widget/pending_target`; otherwise users lose placed widgets or see blank data until the app runs. Widget strings are hard-coded German in Flutter — localize, but countdown text is computed app-side.
18. Widget is never cleared when all favorites are removed (Flutter quirk) — write empty city in the port.
19. Community-mode detection depends on the exact `client` value `einundzwanzig-meetup-app`; events published without it are invisible to every Flutter user in default mode.
20. `fetchByAddress` must query ALL active relays plus the community relay; failing to find an event makes badge verification
    fail ("eventNotFound") for other users' scans.
21. ICS quirks (description backslash stripping, 19:00 default, `Ort unbekannt`, COUNT=1 off-by-one, 200-occurrence cap from
    an old series start) — only reachable when the portal API is down; reproduce reasonably, not necessarily bug-for-bug.
22. The `isBadgeWindowOpen`/session window uses DEVICE local dates — helpers in another time zone than the event see a
    shifted window (same as Flutter).
23. Large worldwide fetch is sequential in Flutter (up to 16 s per relay); parallelizing is fine as long as dedup is deterministic.
24. RSVP prefetch in CalendarScreen issues one HTTP request per portal date (≈ all dates of 3 months) — keep the 1 h cache;
    consider loading only visible items.
