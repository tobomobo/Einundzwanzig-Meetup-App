# :feature:badges

Badge scanning, organizer rolling QR and NFC session tools (spec 02 §2.1–§2.5, Dart
`lib/screens/meetup_verification.dart`, `rolling_qr_screen.dart`, `nfc_writer.dart`,
`meetup_session_wizard.dart`).

## Entries

```kotlin
fun EntryProviderScope<NavKey>.badgesEntries()
// BadgeScanner, BadgeQrCamera, RollingQr, NfcTagWriter, MeetupSessionWizard
```

## Screens

| NavKey | Composables | Spec |
|---|---|---|
| `BadgeScanner` | `BadgeScannerRoute` / `BadgeScannerScreen` + `BadgeScannerViewModel` | 02 §2.1 |
| `BadgeQrCamera` | `BadgeQrCameraRoute` / `BadgeQrCameraScreen` (returns `NavResults.BadgeQrScanned`) | 02 §2.2 |
| `RollingQr` | `RollingQrRoute` / `RollingQrScreen` + `RollingQrViewModel` | 02 §2.3 |
| `NfcTagWriter` | `NfcTagWriterRoute` / `NfcTagWriterScreen` + `NfcTagWriterViewModel` (NFC flag) | 02 §2.4 |
| `MeetupSessionWizard` | `MeetupSessionWizardRoute` / `MeetupSessionWizardScreen` (NFC flag) | 02 §2.5 |

- **Scanner:** QR/NFC input, verification + claim via `BadgeScanProcessor`, confirmation view
  (found / unknown signer / already collected / kept aside), app-review demo dialog, NFC
  unavailable/disabled dialogs (the route opens `Settings.ACTION_NFC_SETTINGS` from the activity,
  same task), blocking locate dialog, presence check and save via `BadgeSaveUseCase` ("too far"
  dialog shows the applied radius), background reputation publish, co-attendance opt-in,
  `replaceTop(BadgeWallet)`, success/error haptics.
- **Scanner snackbars:** posted to the app-lifetime `LocalAppSnackbarController` (`:core:ui`), so
  they survive the switch to the wallet and activity recreation. The co-attendance "yes" opens the
  wallet at once; the publish runs in `@ApplicationScope` and reports `caPublished`/`caPublishFailed`
  through a `ScannerResultSink` (pre-resolved strings + the controller; no host or activity kept).
  Without a provided controller (tests, previews) a composition-scoped one feeds the app/local host,
  and messages still queued when the scanner leaves are dropped.
- **Process death:** the scanned `PendingBadge`/`FoundInfo` lives only in the ViewModel (no
  `SavedStateHandle`, like Flutter): if the process dies between "BADGE GEFUNDEN" and "ZUR WALLET
  HINZUFÜGEN", the user scans again. The unknown-meetup label travels with `QrScanned`/`NfcTagDiscovered`.
- **Rolling QR:** session bar, 10-s QR aligned to step boundaries, expressive countdown (red ≤3 s),
  block/npub footer, end-session confirmation, empty state, pause/resume with the lifecycle,
  keeps the screen on at full brightness while a code shows. Code-to-code changes are a hard cut
  (no cross-fade that a camera could catch half-blended); the stop icon has the `rqEndSession` tooltip.
- **Camera:** the instruction box is hidden while `:core:camera` shows its permission or
  unavailable panel (it would cover their buttons).
- **Top bars:** scanner, writer and wizard use `AppMediumTopBar` (`MediumFlexibleTopAppBar`,
  exit-until-collapsed); rolling QR and the camera keep the small bar.
- **Localized failures:** scan rejections and failed bindings are localized from the typed
  `VerifyFailure` / `ClaimFailure` / rolling-QR format reasons (`native_verify_*`, `native_claim_*`,
  `native_nonce_malformed`); the German data-module message is only the fallback.
- **NFC:** `nfc/Nfc.kt` wraps `NfcAdapter.enableReaderMode` (NFC-A/B/F/V, NDEF check on) with real
  NDEF read, write and format of blank tags; payload/size logic comes from `NfcBadgePayload` in
  `:data:issuance`. All of it is behind `IssuanceFeatures.NFC_ENABLED = false`, so the manifest
  declares **no** NFC permission or feature (spec 14 rows 54/56/292, Audit N4). Re-add
  `android.permission.NFC` and `<uses-feature android:name="android.hardware.nfc" android:required="false"/>`
  in `src/main/AndroidManifest.xml` together with the flag.

The reputation QR check (02 §2.6) lives in `:feature:reputation`.

## Dependencies

`:data:issuance`, `:data:badges`, `:data:meetups`, `:data:identity`, `:data:reputation`,
`:core:camera` (plus the feature convention's core modules). Native-only strings
(`native_*`) are in `src/main/res/values{,-en,-es}/strings.xml`.

App shell: provide one app-lifetime `AppSnackbarController` as `LocalAppSnackbarController` and run
`AppSnackbarControllerEffect(controller, appHost)` (see `:core:ui` README, "App shell wiring").

## Tests

`tools/gradlew-locked :feature:badges:testDebugUnitTest` — ViewModel tests with fakes
(scanner, rolling QR, NFC writer), localized text tests, Robolectric Compose UI tests and
route tests (`BadgeRoutesTest`: camera result + single pop, scanner result/wallet/snackbar wiring,
co-attendance result on the app queue after the scanner left,
wizard and writer `navigateFromRoot(RollingQr)`) with a recording `Navigator` and a real `ResultEventBus`.
