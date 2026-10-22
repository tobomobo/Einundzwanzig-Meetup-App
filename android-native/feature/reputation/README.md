# :feature:reputation

Trust & reputation screens (spec 04 §2). Entry function: `EntryProviderScope<NavKey>.reputationEntries()`
in `ReputationEntries.kt`.

| NavKey | Route / screen | Spec |
|---|---|---|
| `ReputationQr` | `qr/ReputationQrRoute` → `ReputationQrScreen` (+ `ReputationCardVisual`) | 04 §2.1, §2.6 |
| `ReputationCard` | `card/ReputationCardRoute` → `ReputationCardScreen` | 04 §2.2 |
| `ReputationScanner` | `scan/ReputationScannerRoute` (camera, gallery, demo dialog; `replaceTop` to result) | 04 §2.3 |
| `ReputationScanResult(code)` | `scan/ReputationScanResultRoute` (re-parses `code`, loads live layers) | 04 §2.3 |
| `ReputationVerify(initialInput)` | `verify/ReputationVerifyRoute` (+ `ReputationLayers`) | 04 §2.4, §2.5 |
| `VerifyPerson(initialTargetNpub)` | `person/VerifyPersonRoute` | 04 §2.7 |
| `VerifyPersonQrCamera` | `person/VerifyPersonQrCameraRoute` → `NavResults.NpubScanned` | 04 §2.7.1 |
| `TrustNetworkHub` | `hub/TrustNetworkHubRoute` | 04 §2.8 |

`verify/ReputationLayers` is the four-layer evidence widget (scoring in `:data:trust` `TrustLayersEvaluator`).

## Structure

- `data/ReputationDataSource`: one seam over the data layer (pure delegation) so ViewModels test with a fake.
- `common/`: shared rows, level mapping, live-layer loader (social / zaps / NIP-05 in parallel), verify-message
  localization, PNG capture + share (`core:ui` `ComposableCapturer`/`ShareHelper`, 3x, `cacheDir/share`).
- Native-only strings (`native_*`, de/en/es) in `src/main/res`: the §2.10 keys (`repPublish*`, `qrStatsLine`,
  `qrProofLine`, `qrSignedBy`, `qrHopsAway`, `qrVerify*`, `rcNoDataHint`, `rvInputHint`, `rvMsg*`) plus retry/errors,
  and `native_zapActivity*` / `native_nip05*` for the German `activityLabel` / `domainLabel` literals of `:data:proofs`.
- Colours: Flutter `Colors.amber` → `extendedColors.amber`, `cCyan` gold → `extendedColors.community` (`common/ReputationUi.kt`).
- `ReputationScannerRoute(scanner)` and `VerifyPersonQrCameraRoute(scanner)` take a camera slot (default `QrScanner`)
  so tests can feed codes; the scanner's hit/demo flags are `rememberSaveable` because `QrScannerState.isStopped` survives
  recreation.

## Dependencies

`:data:reputation`, `:data:trust`, `:data:proofs`, `:data:socialgraph`, `:data:badges`, `:data:identity`,
`:core:camera` (QR scanner + gallery decode). Tests: `secp256k1-kmp-jni-jvm` to verify the 21v3 fixture on the JVM.

## Recommended fixes applied

Signer failure falls back to the unsigned code with a snackbar (§2.1 step 4); the card's signed label reflects the
real signature (R-8); scan result loads layers for the signer and warns on a claimed-npub mismatch (S-8); the npub
camera accepts `21v3:` reputation QRs and uses the verified signer (R-21); publish messages are localized.

## Tests

`./tools/gradlew-locked :feature:reputation:testDebugUnitTest`: ViewModel tests with `FakeReputationDataSource`,
pure logic tests (npub extraction, layer mapping), Robolectric Compose UI tests (`ReputationScreensUiTest`) and route
tests with a recording `Navigator` and a fake camera slot (`ReputationRoutesUiTest`: `replaceTop(ReputationScanResult)`,
`NavResults.NpubScanned` + back, demo dialog across recreation, S-8 mismatch banner). 61 tests.
