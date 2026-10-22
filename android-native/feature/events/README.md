# :feature:events

Calendar and event screens (spec 10 §2.1–§2.5, §2.7): the Events tab hub, the portal meetup dates
list, the event calendar with its details sheet, the event editor including event badges, and
"Meine Termine".

```kotlin
// :app
entryProvider { eventsEntries() }
```

`EntryProviderScope<NavKey>.eventsEntries()` registers:

| NavKey | Screen | Spec |
|---|---|---|
| `EventsTab` | `EventsHubRoute` (two cards → `MeetupCalendar()` / `EventCalendar()`) | 10 §2.1 |
| `MeetupCalendar(initialSearch, initialMeetupId, preselectNextFavorite)` | `MeetupCalendarRoute`: search, crest, favorite star + snackbars, portal RSVP row with attendee list, auto portal login, details sheet (RSVP toggles, open link, share with clipboard fallback, add to calendar) | 10 §2.2 |
| `EventCalendar(initialDay)` | `EventCalendarRoute`: month/year/list views, legend, type + location filter, community/worldwide switch, refresh + pull-to-refresh, FAB → editor. Details sheet: badge notice + session start (→ `RollingQr`, snackbar actions to location/app settings), issuer list with NpubChips, helper edit sheet, cancel dialog, Nostr RSVP, "Chat zum Termin" (→ `EventChat`), links, add to calendar | 10 §2.3, §2.7 |
| `EventEditor` | `EventEditorRoute`: fields, all-day, date/time pickers (19:00 default), badge section (permission check, gallery pick + Blossom upload, `LocationPicker` result, helper rows), validation, publish → `NavResults.EventPublished`; `GuideTour.Events` (event badge tour) | 10 §2.4 |
| `MyEvents` | `MyEventsRoute`: RSVP'd meetup dates with meetup-chat lookup (→ `MeetupChat`), accepted Nostr events with unread badge (→ `EventChat`), long-press withdraw dialog, pull-to-refresh of unread counts | 10 §2.5, 12 §8.6 |

Every screen is a stateful `XRoute` + stateless `XScreen(state, onAction)` with a `@Preview`;
ViewModels expose `StateFlow<UiState>`, snackbars as `Flow<UiMessage>` and navigation as effects.

## Dependencies

`:data:calendar` (dates, NIP-52 events, RSVPs, merge/format/issuer helpers, My events),
`:data:meetups` (`MeetupService`, `PortalApiService`, logo registry), `:data:identity` (profile
favorites, own pubkey), `:data:profile` (Blossom upload, display names), `:data:issuance`
(`EventBadgeSessionService`, `EventBadgeAuthService`, `RollingQrService`), `:data:chat` (room lookup,
event chat unread counts), `:core:crypto` (npub encoding). No feature module dependencies.

Small seams in `common/Gateways.kt` (`EventsIdentity`, `EventsPortal`, `EventsChat`,
`EventsSessions`, `EventCrests`, `BadgeImagePreparer`, `ZoneProvider`) only delegate to the data
layer so ViewModels can be tested with fakes. `BadgeImagePreparer` re-encodes the gallery pick to
≤ 1200 px JPEG q85 before the upload (Flutter `image_picker` settings).

Native-only strings (`native_events_*`) are in `res/values{,-en,-es}/strings.xml`.

## Tests

`tools/gradlew-locked :feature:events:testDebugUnitTest` — 89 tests: ViewModel tests with fakes
(event calendar, editor, meetup dates, my events) and Robolectric Compose UI tests for every screen,
the details sheet content, the helper editor and the bounded details sheet
(port of `event_details_sheet_test.dart`: compact for one line, ≤ 90 % height, close button with
`actionClose`).

## Deviations / notes

- The event details sheet uses the M3 partial-expand state instead of Flutter's draggable
  0.35/0.55/0.9 snap points; the 90 % cap of §2.7 applies.
- The rolling QR screen reports its return through a resume effect: the calendar reloads the running
  session whenever it becomes visible again.
- `MyEvents` loads its lists itself (Flutter got them from Home); Home reloads its counters when it is
  visible again.
