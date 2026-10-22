# Native Android parity and validation

The Kotlin/Compose implementation covers the shipped Flutter Android feature areas in
`spec/01`–`spec/14`. Native Material 3 Expressive presentation is intentional. The audit baseline
contains 40 findings and 626 source-inspected assertions; those assertions are **not** 626 device
tests. Each finding has a disposition in [parity/resolutions.json](parity/resolutions.json).

Local validation on 2026-10-22: **3,914 JVM tests passed**, one opt-in live portal test skipped;
**nine optimized app device tests and five legacy-storage device tests passed on API 36**;
the separate real Flutter upgrade check passed. Preview lint and the strict APK verifier passed.
There are no error-level lint baseline entries (98 existing warnings and one hint remain).
Machine-readable results live in `parity/{jvm,device,apk}-validation.json`.

Follow-up checks after the comparison audit passed **258 tests** in Home and backup modules,
including eight new regressions/interoperability cases. Reset now reports incomplete secret
erasure; restore cannot report successful proof writes after a persistence failure. A real
Flutter-exported backup restores on Kotlin, and Kotlin's export restores through Flutter's
unchanged importer. The fresh optimized suites also passed **nine app tests plus five legacy
storage tests on both API 26 and API 36**, at CI's 320×640 viewport. The updated ordinary
preview passed lint and all APK checks again. These focused runs supplement the full-suite
result above; they do not represent another full run of every unchanged module.

| Feature area | Implementation and automated coverage | Device evidence |
|---|---|---|
| Identity, keys and onboarding | Local keys, NIP-49, vault/recovery, typed localized errors, external signers, pending-secret protection | Real Flutter identity upgraded; same public key and a successful local signature |
| Badge creation and claims | QR/NFC parsing, signed claims, organizer issuance, duplicate/error handling | Camera permission denial/gallery fallback; bundled QR decoding regression |
| Badge wallet | Collection, details, QR/image sharing, map and filtering | Real Flutter badge retained and opened; osmdroid projection/draw test |
| Trust and reputation | Trust graph, reputation payloads, publishing guards, honest verification messages | Navigation and empty reputation card; no live publishing during tests |
| Social graph and organizer tools | Session lifecycle, attendance, graph/admin state machines | JVM coverage; real organizer/peer/network interaction remains a field check |
| Platform and humanity proofs | Generation plus intended-identity fencing inside transactions; ownership checked on reads/exports/publication | Deterministic JVM interleavings; actual Hilt/Keystore/proof integration device regression |
| Nostr infrastructure and profile | Relay/signing modes, profile editing, Nostr avatar, authenticated recovery | Migrated profile and public key displayed; external signer hardware/app interaction remains a field check |
| Backup and sync | Additive/replace restore, encrypted keys, ownership-aware proof checks, truthful persistence results, share/reset outcomes | Android document picker cancellation; actual Flutter ↔ Kotlin export/import of a synthetic identity/profile/wallet; JVM storage/error tests |
| Meetups and portal | Directory, details, favorites, crests, location bridge and portal flows | Real system location prompt, denial, retry and grant |
| Calendar and events | Month/year/list views, filtering, localized fallbacks, widget retention | Month and list navigation, localized empty states |
| Chat, news and community | Chat/news/community routes, media services, Lightning intents and web fallbacks | Community/news navigation and offline state; no public messages sent |
| Shell, Home and settings | All 57 route keys, tab stacks, snackbar queue, responsive sheet, locale/theme/settings | Route serialization, real Activity recreation, settings/language and Home navigation |
| Bitcoin tools | Dashboard, converter, cache/offline handling, custom hosts and polling limits | Loopback server: block 1,000,000; EUR 100 → 100,000 sats; server stopped → error plus retained result |
| Android platform and migration | Store-level gate, legacy Keystore readers, safe recovery/cleanup, widgets, release verification | Five real Android Keystore format tests; in-place Flutter APK upgrade; optimized preview suite |

## Reproducible checks

Use JDK 17 and the Android SDK configured in `local.properties`. The wrapper serializes Gradle
invocations across agents; do not bypass its lock.

```bash
tools/gradlew-locked testDebugUnitTest --max-workers=3
python3 -m unittest discover -s tools/tests -v
tools/gradlew-locked :app:assemblePreview :app:lintPreview --max-workers=3
bash tools/run_device_tests.sh preview emulator-5556 /tmp/e21-device-tests
```

The device harness refuses AVD names that do not start with `e21test`. `preview` is genuinely
optimized/obfuscated and non-debuggable. Its instrumentation build pins only APIs called across
the app/test APK boundary, plus two reflective test seams. Ordinary preview and production
release builds contain neither the test entry point nor those additional rules.

The pending-identity regression also passed three fresh installs at CI's 320×640 display size.
Its fixture waits for migration completion and uses a distinct temporary file plus atomic rename,
avoiding a collision with production `AtomicFile` readers. The ten-second UI deadline remains;
failures save the visible screen, accessibility tree and migration report in device artifacts.
Fresh AVDs can show Android's first immersive-mode explanation over the app. The UI fixture
confirms only that specific system window before asserting the app screen. API 26's emulator
image denies Wi-Fi control to the shell UID; the runner uses privileged ADB setup on that
dedicated rootable AVD and restores its prior daemon mode and connectivity on exit. App and
instrumentation UIDs remain their normal application UIDs.

`migrationTest` is deliberately unminified and debuggable, with the production application ID,
so an existing Flutter APK can be updated in place with the matching signing key. This covers
upgrade data preservation separately from genuine R8 runtime behavior.

The local Flutter 1.7.2+31 upgrade retained the same synthetic identity, nickname, home meetup,
favorites, one badge and the widget headline. The migration recorded 45 preference entries,
seven imported secrets, two opened badge copies and zero failed secret names. The upgraded
identity successfully signed and verified locally. No private material is included in reports.

[parity/jvm-validation.json](parity/jvm-validation.json) records the JVM result by module. CI
additionally runs optimized preview and legacy-storage tests on APIs 26, 29, 34 and 36 before
publishing a prerelease. A configured matrix is not evidence that every job has passed; consult
the particular workflow run and its uploaded device results.

## Deliberate differences and external prerequisites

- Native Material navigation, cards and motion implement the requested Android UI. Flutter
  pixel parity, including the event-card chip shape and line wrapping, is not a release criterion.
- Spec 05 risk 18 permits an explicit organizer-session error. A failed creation stays on the
  panel and does not record successful attendance or open an unusable session.
- Localized typed key errors replace Dart exception prefixes.
- Both loopback security-policy exceptions are required for supported custom mempool/tunnel
  configurations. Validation accepts `127.0.0.1` and trailing-dot `localhost.` URLs.
- Passkey creation requires a verified relying-party association with the actual app signing
  certificate. The configured `assetlinks.json` endpoint returned HTTP 404 during validation on
  2026-10-22; creation stays gated. Existing migrated unwrap remains supported on compatible APIs.
- Preview signing uses a test key. Actual production signing and its certificate pin require the
  deployment owner's secrets; the manual release-validation workflow verifies that artifact.
- External signer apps, real NFC hardware, peer devices, Bluetooth/biometrics/passkey providers,
  organizer relay publication and live payment flows require field validation. Automated checks
  do not claim successful public writes or real financial transactions.
