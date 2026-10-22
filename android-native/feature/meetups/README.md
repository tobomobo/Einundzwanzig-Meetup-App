# :feature:meetups

Meetup, location and portal screens of spec 09 §2. Package `space.einundzwanzig.meetup.feature.meetups`.

```kotlin
implementation(project(":feature:meetups"))
// in the app's entryProvider:
meetupsEntries()
```

## Screens

| NavKey | Route / screen | Spec |
|---|---|---|
| `NearbyMeetups` | `NearbyMeetupsRoute` / `NearbyMeetupsScreen` | 09 §2.2: OSM map (radius circle, centre dot, pins → details), "Hier & jetzt" / "Geplant" toggle, Nominatim place search (600 ms debounce), radius slider 5–100 km, date chips (any / day / range pickers), result cards (distance, next date, more dates, directions, details), empty and location-problem states |
| `MeetupDetails(meetupId)` | `MeetupDetailsRoute` / `MeetupDetailsScreen` | 09 §2.3: country chip, city, TERMINE (sheet per date), LINKS (Telegram, Twitter/X, Nostr), STANDORT + route, community-link action, not-found state |
| `MeetupSelection` | `MeetupSelectionRoute` / `MeetupSelectionScreen` | 09 §2.4: multi-select favorites with search, count, legacy migration (§8.4), save |
| `LocationPicker(lat?, lng?)` | `LocationPickerRoute` / `LocationPickerScreen` | 09 §2.6: tap to pick, "Zu meinem Standort", coordinates, "Ort übernehmen" → `NavResults.LocationPicked` |
| `PortalMeetups` | `PortalMeetupsRoute` / `PortalMeetupsScreen` | 09 §2.1: portal login/logout, my meetups (pull to refresh), co-leader list with add/remove dialogs, `myMeetups` tour |
| `PortalMeetupEvents(meetupId, meetupName)` | `PortalMeetupEventsRoute` / `PortalMeetupEventsScreen` | 09 §2.1 `ManageEventsScreen`: dates (raw-start order, local time), FAB, snackbar after `NavResults.PortalEventSaved` |
| `PortalEventEditor(...)` | `PortalEventEditorRoute` / `PortalEventEditorScreen` | 09 §2.1 `EventEditor`: date + time (19:00 when the time dialog is dismissed), location, description, link, save → `NavResults.PortalEventSaved` |
| `CommunityPortal` | `CommunityPortalRoute` / `CommunityPortalScreen` | 09 §2.7: static link hub (no ViewModel) |

`MeetupSearchSheet` (09 §2.5) is not a destination; `:feature:profile` and `:feature:onboarding`
host their own sheets on top of `MeetupSearchRows` from `:data:meetups`.

## Deviations from Flutter (all UI-only)

- Nearby keeps the GPS centre and the geocoded place apart (§14 item 13); switching back to
  "Hier & jetzt" never searches around the place. A place picked earlier is searched again when
  "Geplant" is selected.
- Nearby cards also show the group name; denied-forever / disabled location adds an
  "App-Einstellungen" / "Standort aktivieren" button, and the location is retried on return.
- Details drop dates older than now − 6 h also for the ICS fallback (§14 item 15); scheme-less
  `t.me/…` links get `https://` (§14 item 16).
- Favorites picker shows an error with retry and disables saving when the directory cannot be
  loaded (§14 item 11).
- Editor prefills with `PortalTime.portalStartUtc` (round trip keeps the time, §8.12). When the
  in-memory `lastMyMeetupEvents` cache is empty (process death, direct open) it calls
  `getMyMeetupEvents()` once (loading indicator) before showing "not found"; start, location,
  description and link are kept in `SavedStateHandle`.
- Time dialog is the M3 `TimePickerDialog` with dial/keyboard toggle (keyboard on short windows).
- Nearby fits the camera once per search (applied `fitToken` is saved), so returning from details
  or rotating keeps the user's zoom/pan.
- Favorites picker also blocks saving when the stored profile cannot be read (no favorites wipe).
- "Meine Meetups" checks `tokenMatchesCurrentKey()` before loading anything and drops a foreign
  token (§14 item 8); nothing is requested with it.
- Location picker "jump to me" asks the location provider directly and shows a hint when there
  is no fix.
- Nearby dates use the app locale for weekday names (§14 item 26).

## Dependencies

`:data:meetups`, `:data:calendar` (`MeetupCalendarService`), `:data:identity`
(`UserProfileRepository`), `:core:map`. Npub chips read display names through
`LocalNostrProfileSource` (provided by the app from `:data:profile`).

## Tests

```
tools/gradlew-locked :feature:meetups:testDebugUnitTest
```

ViewModel tests with fakes (portal login/leaders, events, editor round trip, nearby search and
location flow, details resolution, favorites migration and save, location picker) and
Robolectric Compose tests for every screen plus route wiring (NavKeys, results, back).
