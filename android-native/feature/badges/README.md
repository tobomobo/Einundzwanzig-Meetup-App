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
  unavailable/disabled dialogs (opens `Settings.ACTION_NFC_SETTINGS`), blocking locate dialog,
  presence check and save via `BadgeSaveUseCase` ("too far" dialog shows the applied radius),
  save snackbars on the app snackbar host, background reputation publish, co-attendance opt-in,
  `replaceTop(BadgeWallet)`, success/error haptics.
- **Rolling QR:** session bar, 10-s QR aligned to step boundaries, expressive countdown (red ≤3 s),
  block/npub footer, end-session confirmation, empty state, pause/resume with the lifecycle,
  keeps the screen on at full brightness while a code shows.
- **Localized failures:** scan rejections and failed bindings are localized from the typed
  `VerifyFailure` / `ClaimFailure` / rolling-QR format reasons (`native_verify_*`, `native_claim_*`,
  `native_nonce_malformed`); the German data-module message is only the fallback.
- **NFC:** `nfc/Nfc.kt` wraps `NfcAdapter.enableReaderMode` (NFC-A/B/V) with real NDEF read,
  write and format of blank tags; payload/size logic comes from `NfcBadgePayload` in `:data:issuance`.
  `src/main/AndroidManifest.xml` declares `android.permission.NFC` and the optional
  `android.hardware.nfc` feature (as the Flutter manifest), merged into the app.

The reputation QR check (02 §2.6) lives in `:feature:reputation`.

## Dependencies

`:data:issuance`, `:data:badges`, `:data:meetups`, `:data:identity`, `:data:reputation`,
`:core:camera` (plus the feature convention's core modules). Native-only strings
(`native_*`) are in `src/main/res/values{,-en,-es}/strings.xml`.

## Tests

`tools/gradlew-locked :feature:badges:testDebugUnitTest` — ViewModel tests with fakes
(scanner, rolling QR, NFC writer), localized text tests, Robolectric Compose UI tests and
route tests (`BadgeRoutesTest`: camera result + single pop, scanner result/wallet/snackbar wiring,
wizard and writer `navigateFromRoot(RollingQr)`) with a recording `Navigator` and a real `ResultEventBus`.
