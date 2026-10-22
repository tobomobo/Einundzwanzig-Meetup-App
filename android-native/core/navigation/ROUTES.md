# Routes

Every `NavKey` of the app (`Destinations.kt`, package `space.einundzwanzig.meetup.core.navigation`),
the spec section that describes the screen, and the feature module that implements it
(`:feature:<module>`, which registers the key in its `EntryProviderScope<NavKey>.xxxEntries()`).

All keys implement the sealed interface `Destination : NavKey`. The three bottom-bar tabs also
implement `TopLevelDestination` (`TopLevelDestination.entries`, `TopLevelDestination.start == HomeTab`).
`resetTo(TopLevelDestination.start)` replaces Flutter's `pushAndRemoveUntil(AppShell)`;
`navigateFromRoot(key)` replaces `pushAndRemoveUntil(route, (route) => route.isFirst)` (pop
everything above the current root, then push).

Keys never validate ids that come from remote data (meetup ids, chat room ids, article ids): a
screen shows a not-found state for an unknown id instead of crashing. `require` checks only guard
caller programming errors (`LocationPicker` coordinate pair, `MeetupCalendar` exclusivity,
`EventCalendar` ISO day, non-negative positions in `BadgeDetails`/`PortalEventEditor`, non-empty
values the app builds itself: `BadgeDetails.unionKey`, `EventChat.eventAddress` = `"<kind>:<pubkey>:<d>"`,
`ReputationScanResult.code` with its `21…:` prefix).

57 keys: 40 `data object`s, 17 `data class`es.

## Destinations

| NavKey | Arguments | Spec | Module | Opened from | Returns |
|---|---|---|---|---|---|
| **Onboarding** | | | | | |
| `Splash` | — | 01 §2.1, 12 §2.3 | onboarding | cold start (back stack root) | — |
| `Intro` | — | 01 §2.2 | onboarding | Splash (`resetTo`), home reset flow 12 §2.5.12 (`resetTo`) | — |
| `IdentitySetup` | — | 01 §2.3 | onboarding | Intro "COMMUNITY BETRETEN"; Home when nickname is empty/"Anon" (12 §2.5.10) | — |
| **App shell tabs** (`TopLevelDestination`) | | | | | |
| `HomeTab` | — | 12 §2.4, §2.5 | home | bottom bar; `resetTo` after onboarding/profile save | — |
| `WalletTab` | — | 12 §2.4, 03 §2.1 | wallet | bottom bar "Badges" | — |
| `EventsTab` | — | 12 §2.4, 10 §2.1 | events | bottom bar "Events" | — |
| **Badges** | | | | | |
| `BadgeScanner` | — | 02 §2.1 | badges | scan sheet "Badge scannen" (12 §2.4) | `replaceTop(BadgeWallet)` on success |
| `BadgeQrCamera` | — | 02 §2.2 | badges | BadgeScanner "QR-CODE SCANNEN" | `NavResults.BadgeQrScanned: String` |
| `RollingQr` | — | 02 §2.3 | badges | AdminPanel, Home live-session card (12 §2.5.4), event calendar after event badge session start (10 §2.3), NfcTagWriter/MeetupSessionWizard (`navigateFromRoot`) | — |
| `NfcTagWriter` | — | 02 §2.4 (NFC flag off) | badges | MeetupSessionWizard | — |
| `MeetupSessionWizard` | — | 02 §2.5 (NFC flag off) | badges | AdminPanel after session start when NFC is on | — |
| **Badge wallet** | | | | | |
| `BadgeWallet` | — | 03 §2.1 | wallet | BadgeScanner (`replaceTop`) after save / duplicate / "ZUR WALLET" | — |
| `BadgeCollection` | `groupKey: String`, `query: String = ""` | 03 §2.1.1 | wallet | wallet collection card ("Nach Meetup") | — |
| `BadgeDetails` | `unionKey: String`, `collectionIndex: Int` | 03 §2.2 | wallet | wallet cards, collection cards | — |
| `BadgeWorldMap` | — | 03 §2.4 | wallet | wallet app-bar map button | — |
| **Trust & reputation** | | | | | |
| `ReputationQr` | — | 04 §2.1 | reputation | scan sheet "Reputation prüfen", wallet share sheet "QR-Code anzeigen" (03 §2.1.2), Home reputation tile | — |
| `ReputationCard` | — | 04 §2.2 | reputation | Home score pill (12 §2.5.3), ReputationQr share action | — |
| `ReputationScanner` | — | 04 §2.3, 02 §2.6 | reputation | ReputationQr scan action and empty-state button | `replaceTop(ReputationScanResult)` |
| `ReputationScanResult` | `code: String` | 04 §2.3, 02 §2.6 | reputation | ReputationScanner (`replaceTop`) | — |
| `ReputationVerify` | `initialInput: String? = null` | 04 §2.4 (orphan) | reputation | none yet (spec: port for later wiring) | — |
| `VerifyPerson` | `initialTargetNpub: String? = null` | 04 §2.7, 05 §2.3 | reputation | MyTrustNetwork app-bar action, TrustNetworkHub card | — |
| `VerifyPersonQrCamera` | — | 04 §2.7.1 | reputation | VerifyPerson "QR scannen" | `NavResults.NpubScanned: String` |
| `TrustNetworkHub` | — | 04 §2.8 (orphan) | reputation | none yet (spec: port for later wiring) | — |
| **Co-attendance network & organizer** | | | | | |
| `MyTrustNetwork` | — | 05 §2.1 | network | Home tile `trust_network` | — |
| `AdminPanel` | — | 05 §2.2 | admin | Home tile `organisator` (admins / review demo) | — |
| **Proofs** | | | | | |
| `PlatformProofs` | — | 06 §2.1 | proofs | ProfileEdit identity tile "Plattformen verknüpfen" (07 §2.2.5) | — |
| `HumanityProof` | — | 06 §2.2 | proofs | ProfileEdit identity tile "Proof of Humanity" (07 §2.2.5) | — |
| **Profile & Nostr infrastructure** | | | | | |
| `ProfileEdit` | — | 07 §2.2, 01 §2.7 | profile | settings sheet "Profil bearbeiten" (12 §2.6) | `resetTo(HomeTab)` on save |
| `RelaySettings` | — | 07 §2.1 | profile | settings sheet "Nostr-Relays" (12 §2.6) | — |
| **Meetups, location & portal** | | | | | |
| `NearbyMeetups` | — | 09 §2.2 | meetups | bottom-bar action "In der Nähe" (12 §2.4), PortalArea "Karte" | — |
| `MeetupDetails` | `meetupId: String` | 09 §2.3 | meetups | NearbyMeetups pin/"Details", Home favorite card "Info" (12 §2.5.5) | — |
| `MeetupSelection` | — | 09 §2.4 | meetups | Home CTA "Wähle deinen Stammtisch" (12 §2.5.5) | saves the profile; Home reloads |
| `LocationPicker` | `initialLatitude: Double? = null`, `initialLongitude: Double? = null` | 09 §2.6 | meetups | EventEditor badge location row (10 §2.4) | `NavResults.LocationPicked: PickedLocation` |
| `PortalMeetups` | — | 09 §2.1 | meetups | Home tile "Meine Meetups", PortalArea "Meine Meetups" | — |
| `PortalMeetupEvents` | `meetupId: Int`, `meetupName: String` | 09 §2.1 (`ManageEventsScreen`) | meetups | PortalMeetups "Termine verwalten" | — |
| `PortalEventEditor` | `meetupId: Int`, `meetupName: String`, `eventId: Int? = null`, `eventIndex: Int? = null` | 09 §2.1 (`EventEditor`) | meetups | PortalMeetupEvents FAB (create) / row (edit, also dates without portal id) | `NavResults.PortalEventSaved: Boolean` (true = created) |
| `CommunityPortal` | — | 09 §2.7 | meetups | PortalArea "Portal-Webseite" | — |
| **Calendar & events** | | | | | |
| `MeetupCalendar` | `initialSearch: String? = null`, `initialMeetupId: String? = null`, `preselectNextFavorite: Boolean = false` | 10 §2.2 | events | EventsTab "Meetups", wallet empty state, PortalArea "Meetups"/"Events" (no args); Home favorite card "EVENTS" (city + id); widget meetup area (`preselectNextFavorite`) | — |
| `EventCalendar` | `initialDay: String? = null` (ISO `yyyy-MM-dd`) | 10 §2.3 | events | EventsTab "Veranstaltungskalender"; Home "Events" tile (`EventCalendar.forDay(today)`) | — |
| `EventEditor` | — | 10 §2.4 | events | EventCalendar FAB "Event eintragen" | `NavResults.EventPublished: Unit` |
| `MyEvents` | — | 10 §2.5 | events | Home tile "Meine Termine" (`event_chats`) | — |
| **Chat** | | | | | |
| `MeetupChat` | `roomId: String`, `roomName: String = ""` | 11 §2.1 (`ChatScreen.room`) | chat | Home favorite card "Chat" and MyEvents, after the room lookup (12 §8.6) | — |
| `EventChat` | `eventAddress: String`, `title: String` | 11 §2.1 (`ChatScreen.event`) | chat | event calendar details sheet "Chat zum Termin" (10 §2.3), MyEvents row | — |
| **News** | | | | | |
| `News` | `markAsRead: Boolean = false` | 11 §2.3 | news | Home News tile and widget news row (`markAsRead = true`); CommunityHub News card | — |
| `NewsArticleDetails` | `articleId: String`, `listIndex: Int = -1` | 11 §2.4 | news | News article card | — |
| **Community** | | | | | |
| `CommunityHub` | — | 11 §2.5 | community | Home tile `community` | — |
| `PortalArea` | — | 11 §2.6 | community | CommunityHub portal card, Home tile `portal_area` | — |
| `Courses` | — | 11 §2.7 | community | PortalArea "Kurse" | — |
| `CourseDetails` | `courseId: Int` | 11 §2.8 | community | Courses (courses tab), LecturerDetails course rows | — |
| `LecturerDetails` | `lecturerId: Int` | 11 §2.8 | community | Courses (lecturers tab), CourseDetails lecturer card | — |
| `ValueForValue` | — | 11 §2.10 | community | settings sheet "V4V" (12 §2.6) | — |
| `PlebrapLibrary` | — | 11 §2.11 | community | Home PlebRap tile library icon, CommunityHub PlebRap card | — |
| `Glossary` | — | 11 §2.12 | community | Home logo bar "?" icon (12 §2.5.2) | — |
| **Bitcoin tools** | | | | | |
| `BitcoinDashboard` | — | 13 §2.1 | bitcoin | Home tile `btc_dashboard`, widget Bitcoin area | — |
| `Converter` | — | 13 §2.2 | bitcoin | Home tile `converter` | — |
| `MempoolSettings` | — | 13 §2.3 | bitcoin | settings sheet "Mempool-Server" (12 §2.6), BitcoinDashboard "change server" | — |
| **Settings & diagnostics** | | | | | |
| `DiagnosticLog` | — | 12 §2.8 | settings | settings sheet "Diagnose-Log" (12 §2.6) | — |

Modules without destinations:

- **identity** (spec 01): its UI is the BunkerConnectSheet (01 §2.5), the key-management section and
  dialogs embedded in ProfileEdit (01 §2.7), the AppReviewSignIn widget and the NIP-46 `auth_url`
  snackbar. All are sheets, dialogs or embedded sections.
- **backup** (spec 08): owns no screen (08 §2); password dialog, overlay and identity dialogs are
  hosted at the app root.
- **settings** owns the settings bottom sheet (12 §2.6) besides `DiagnosticLog`.

## Arguments instead of models

Flutter passes whole objects in several places. The keys carry the identifier the spec names and the
screen resolves the model from its repository:

| Flutter | Key | Resolution |
|---|---|---|
| `BadgeDetailsScreen(badge)` | `BadgeDetails(unionKey, collectionIndex)` | badge at `collectionIndex` if its union key matches, else the first badge with that union key (ids are not unique, duplicates share a union key; 03 §13) |
| `_CollectionScreen(title, badges, allBadges)` | `BadgeCollection(groupKey, query)` | rebuild the group with 03 §8.1 |
| `_VerificationResultScreen(...parsed fields)` | `ReputationScanResult(code)` | re-run the pure parse/verify of 04 §7.7 |
| `MeetupDetailsScreen(meetup)` | `MeetupDetails(meetupId)` | portal meetup list, then the offline list `allMeetups` |
| `ManageEventsScreen(portalMeetup)` / `EventEditor(meetup, existing)` | `meetupId`, `meetupName`, `eventId`, `eventIndex` | `:data:meetups` keeps the last `getMyMeetupEvents()` result in memory (no network round trip); the editor takes this meetup's dates sorted by start: the date at `eventIndex` if its id equals `eventId`, else the date with id `eventId`, else not found. Dates without a portal id open prefilled; only saving fails ("Fehler: Termin ohne ID"), as in Flutter |
| `ChatScreen.room(room)` | `MeetupChat(roomId = h, roomName)` | room metadata by `h` |
| `ChatScreen.event(address, author, title)` | `EventChat(eventAddress, title)` | `eventAuthor` is the pubkey segment of the address |
| `ArticleDetail(article)` | `NewsArticleDetails(articleId, listIndex)` | article at `listIndex` of the news repository's current list if its id equals `articleId`, else the first article with that id, else not found (ids may be empty or shared: RSS `guid`, else `link`, 11 §2.3) |
| `CourseDetailScreen(id, fallback)` / `LecturerDetailScreen(id, fallback)` | `courseId` / `lecturerId` | `detail ?: cached[id]`: `:data:community` keeps an in-memory map from id to the last seen course/lecturer JSON, filled from the `getCourses`/`getLecturers` lists and from the nested `lecturer` objects and `courses` arrays of detail responses (Flutter's fallback can be such a nested object) |
| `MyEventsScreen(events, meetupDates, callbacks)` | `MyEvents` | the screen loads both lists itself and does the room lookup before `MeetupChat` |
| `MeetupVerificationScreen(meetup: "global" placeholder)` | `BadgeScanner` | the placeholder never carries coordinates (02 §2.1) |

## Results

Destinations that hand a value back use Navigation 3's `ResultEventBus` with the typed keys in
`NavResults` (`NavResults.kt`): the producer calls `LocalResultEventBus.current.sendResult(key, value)`
and then `navigator.back()`; the caller collects with `NavResultEffect(key) { ... }`. Popping without
a result means "cancelled". `:app` provides the bus
(`CompositionLocalProvider(LocalResultEventBus provides rememberResultEventBus())`).

| Key | Producer → consumer | Payload |
|---|---|---|
| `NavResults.LocationPicked` | `LocationPicker` → `EventEditor` | `PickedLocation(latitude, longitude)` |
| `NavResults.BadgeQrScanned` | `BadgeQrCamera` → `BadgeScanner` | raw QR text |
| `NavResults.NpubScanned` | `VerifyPersonQrCamera` → `VerifyPerson` | lower-cased npub |
| `NavResults.EventPublished` | `EventEditor` → `EventCalendar` | `Unit` |
| `NavResults.PortalEventSaved` | `PortalEventEditor` → `PortalMeetupEvents` | `true` created / `false` updated |

`MeetupSelection` and `MempoolSettings` persist their changes; their callers reload when they are
visible again (Flutter reloads after every return), so they need no result key.

## Deep links (home-screen widget)

The only external entry points are the widget tap areas (10 §2.10, 12 §2.5.10). `DeepLinks.parse(uri)`
maps the widget data URI, `DeepLinks.forWidgetTarget(target)` maps the stored pending target or the
`open` extra:

| URI / target | Destination |
|---|---|
| `homewidget://news` / `news` | `News(markAsRead = true)` |
| `homewidget://bitcoin` / `bitcoin` | `BitcoinDashboard` |
| `homewidget://meetup` / `meetup` | `MeetupCalendar(preselectNextFavorite = true)` |
| `homewidget://home` / `home` | `null` (open the app only) |
| `homewidget://refresh` | `null` (background refresh broadcast) |
| anything else | `null` |

Scheme comparison is case-insensitive, the target is matched exactly (Dart `switch`). Keep the MIUI
safe "consume the pending target once" design (12 §2.5.10): the router activity stores the target,
`MainActivity` reads and clears it on start/resume and navigates to `forWidgetTarget(target)`.

## Not destinations

Opened in place, so no `NavKey`:

- Scan sheet with "Badge scannen" / "Reputation prüfen" (12 §2.4), first-run tour dialog, spotlight
  overlay (12 §2.9)
- Settings bottom sheet with language and currency dialogs and the reset dialogs (12 §2.5.12, §2.6),
  customize sheet (12 §2.5.9)
- BunkerConnectSheet, "secure your identity" sheet, key/nsec/ncryptsec dialogs (01 §2.3.7, §2.5, §2.7)
- Backup password dialog, overlay, identity dialogs (08 §2.2–2.5)
- Wallet share sheet, archive sheet, cleanup dialog (03 §2.1.2–2.1.4)
- Co-attendance opt-in, "zu weit entfernt" and demo dialogs (02 §2.1)
- Person detail and explanation sheets of the network screen (05 §2.1), admin panel pickers and
  dialogs (05 §2.2.2–2.2.3)
- Platform proof sheet (06 §2.1), MeetupSearchSheet (09 §2.5), portal leader dialogs (09 §2.1)
- Meetup date details sheet, event details sheet, helper editor sheet (10 §2.2, §2.3, §2.7)
- Chat author sheet, zap amount/no-wallet sheets, V4V no-wallet sheet, course link sheet
  (11 §2.1, §2.4, §2.7, §2.10)
- Converter currency sheet, currency dialog (13 §2.2, §2.4)

Dead Flutter screens that the specs say not to port: `RadarScreen`, `MeetupListScreen`,
`CreateMeetupScreen` (09 §2.9), `SettingsScreen` (12 §2.7), `EventsScreen`, `ExternalEventsScreen`
(10 §2.8), `PortalEventsScreen` (11 §2.9, unreachable), `PosScreen` (13 §2.6), the duplicate
`lib/services/portal_meetups_screen.dart` (09 §0).
