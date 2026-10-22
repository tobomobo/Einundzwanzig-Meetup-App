# :feature:admin

Organizer panel (spec 05 §2.2, Dart `lib/screens/admin_panel.dart`).

## Entries

```kotlin
fun EntryProviderScope<NavKey>.adminEntries() // registers AdminPanel -> AdminPanelRoute()
```

## Screens

| NavKey | Composables | Spec |
|---|---|---|
| `AdminPanel` | `AdminPanelRoute` / `AdminPanelScreen(state, onAction)` + `AdminPanelViewModel` | 05 §2.2.1 |

In-place dialogs and sheets (`AdminDialogs.kt`, 05 §2.2.2): start/end confirmations, blocking
GPS/creation indicator, "continue without location" (settings / app settings / retry / continue),
"custom name needs location", "no reference", searchable portal meetup picker with nested
"Bist du hier?" confirmation, manual name dialog with validation, multiple-nearby bottom sheet,
error snackbar `wotErrorShort`.

The start flow (05 §2.2.3) lives in `AdminPanelViewModel.runStartFlow`; each dialog suspends the
flow until the user answers. Picker ordering/filtering is in `PortalMeetupPicker`.

The session check runs on every resume (the first resume right after composition is the initial
check; a running check is replaced, never duplicated). Failures of the location lookup, the
session wipe or the admin info never crash the app: they are logged and, where the user acted,
shown as the `wotErrorShort` snackbar while the session state is still cleared. Wiping an
auto-expired session during the check is background work: a failure is only logged (Dart
`_checkSession` shows nothing either).

Organizer co-attendance failures during session creation are swallowed intentionally by
`OrganizerSessionService` (Dart `CoAttendanceService.recordOrganizerAttendance` catches internally).

## Deliberate deviations from Dart

- The snackbar argument is `e.message` (falling back to `e.toString()`), not Dart's
  `e.toString()`: Kotlin's `toString()` prefixes the fully qualified exception class name.
- The active session card also shows the session's meetup name under the status row (native
  addition, not in spec 05 §2.2.1).

## Tests

`AdminPanelViewModelTest` (fake `AdminPanelRepository`), `DefaultAdminPanelRepositoryTest`
(dynamic-proxy stubs of the data services), `AdminPanelScreenTest` (Robolectric Compose),
`PortalMeetupPickerTest`.

## Dependencies

- `:data:issuance`: `RollingQrService` (session load/end, countdown), `OrganizerSessionService`
  (end stale session, create session with `MeetupKey.compose`, organizer co-attendance), `IssuanceFeatures.NFC_ENABLED`.
- `:data:meetups`: `MeetupLocationService` (GPS, candidates, settings intents), `MeetupService`
  (portal directory + bundled fallback), `PortalApiService.getMyMeetups`.
- `:data:identity`: `SigningService.npub`, `UserProfileRepository` (promotion source).
- `:data:admin`: `AdminConstants.SUPER_ADMIN_NPUB`.
- `:data:badges`: `DartString` (Dart-compatible case mapping).

`AdminPanelRepository` is a thin delegating seam so the ViewModel can be tested with a fake.

## Native-only strings

`native_admin_own_meetup`, `native_admin_has_location`, `native_admin_no_location`,
`native_admin_clear_search` (content descriptions), in `res/values{,-en,-es}`.
