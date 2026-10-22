# Einundzwanzig · Apple native

SwiftUI app for **iOS 26+ / macOS 26+**, Swift 6 and Xcode 26. The checked-in project builds the native app, Bitcoin widget and isolated simulator smoke-test target. Flutter `ios/` and `macos/` remain intact. [All 57 source routes](Docs/PARITY.md) describe connected implementation and remaining platform limits.

Open `apple-native/Einundzwanzig.xcodeproj`, choose **Einundzwanzig**, then an iOS 26 simulator or **My Mac**. XcodeGen and Flutter are not build prerequisites. No production development team is configured. Simulator/unsigned builds need no credentials; installing or upgrading a physical app requires ordinary Apple signing and the owner's original team/access groups.

Create a public profile to browse. **Signing identity** creates/imports an actual key or pairs a NIP-46 bunker, protected by a password-encrypted device Keychain vault. Export a NIP-49 recovery key or complete encrypted `.21bkp` backup before changing devices. The vault locks on background. Preview uses isolated public samples and has no signing identity. Existing accessible Flutter installation data opens reviewed migration/recovery before key replacement.

The native flows include:

- Local/remote signing, official NIP-44/49 encryption, encrypted backups, full profile/favorites/wallet recovery, durable same-app migration and protected interrupted-recovery transactions.
- Source kind-21000 validation, rolling organizer issuance, collector kind-21002 claims, encrypted wallet/sync, signed registry/vouch/promotion authority, trust/reputation/proofs and bounded three-degree observed social paths.
- Real WSS relay/filter/signature/auth handling, NIP-52 event editing/RSVP, NIP-29 rooms and NIP-22 comments; actual portal challenge/token, managed dates and leader operations.
- Public meetup maps/location/favorites, complete portal-or-ICS calendar fallback, courses/lecturers, full Nostr news/reactions, all glossary entries and native tour.
- Bitcoin metrics/seven fiat currencies and conversion, configurable HTTPS/custom HTTP/onion hosts, WidgetKit/public background refresh, all 41 PlebRap tracks with playback/cache and explicit external-wallet LNURL/zap invoice review.
- German/English/Spanish resources, native Settings, diagnostics export and semantic accessibility controls. Device accessibility acceptance remains pending.

## Build and test

From the repository root:

```sh
python3 apple-native/Scripts/validate_project.py --require-route-matrix
python3 apple-native/Scripts/parse_swift.py
swift test --package-path apple-native/Packages/MeetupCore --force-resolved-versions -Xswiftc -warnings-as-errors
python3 apple-native/Scripts/test_local_models.py
python3 apple-native/Packages/MeetupCore/Tests/MeetupNostrTests/Fixtures/IdentityLifecycleHarness.py
python3 apple-native/Packages/MeetupCore/Tests/MeetupNostrTests/Fixtures/BadgeLifecycleHarness.py
python3 apple-native/Packages/MeetupCore/Tests/MeetupNostrTests/Fixtures/IdentityAppStoreHarness.py
```

The harnesses compile actual app stores and production callbacks with fixture platform/storage adapters. They use public synthetic identities and offline transports, and cannot establish Apple Keychain/SwiftUI behavior. Official crypto vectors, Dart protocol goldens and real unchanged Flutter backup-service fixtures are separate tests.

```sh
xcodebuild -project apple-native/Einundzwanzig.xcodeproj -scheme Einundzwanzig \
  -configuration Debug -onlyUsePackageVersionsFromResolvedFile -skipPackagePluginValidation \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/e21-simulator CODE_SIGNING_ALLOWED=NO build
```

Use `-sdk iphoneos -destination 'generic/platform=iOS'` for the device-only VisionKit path and `-sdk macosx -destination 'platform=macOS'` for Mac. Use **Upgrade** instead of Debug to compile with the original Flutter app ID (`dev.einundzwanzig.einundzwanzigMeetupApp`). Debug/Release retain `space.einundzwanzig.meetup.apple` for installation alongside Flutter.

```sh
simulator_id=$(python3 apple-native/Scripts/select_simulator.py)
xcodebuild test -project apple-native/Einundzwanzig.xcodeproj -scheme Einundzwanzig \
  -configuration Debug -onlyUsePackageVersionsFromResolvedFile -skipPackagePluginValidation \
  -sdk iphonesimulator -destination "platform=iOS Simulator,id=$simulator_id" \
  -derivedDataPath /tmp/e21-ui-tests -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

The DEBUG-only `--ui-testing` mode isolates files and Keychain service, prohibits legacy Flutter access and replaces relay/HTTP with offline fixtures. `--reset-ui-test-data` clears only this isolated service on the first launch; relaunch preserves the fixture vault. The smoke test uses production identity and wallet controls, proves encrypted persistence/unlock and offline failure, and never publishes publicly.

[Apple CI](../.github/workflows/apple-native.yml) runs package/store checks, project/resource validation, simulator/device/Mac unsigned SDK builds, an unsigned Upgrade build and simulator UI tests with an `.xcresult` artifact. The run at `9e21196` passed 125 XCTest cases (one optional skip, including three Network.framework loopback tests), 69 protocol tests and actual IdentityStore/BadgeStore/AppStore suites on Mac. The iOS simulator app build then exposed a Safari delegate actor boundary, corrected in `150efdf`; **app SDK and simulator UI gates remain pending validation of that fix**. Subsequent security regressions passed focused checks, without a full latest-head rerun. Linux syntax parsing is not an SDK build. [VALIDATION.md](Docs/VALIDATION.md) records exact runs and acceptance limits.

## Storage and security boundaries

Native signing secrets/session keys remain in NIP-49 encrypted Keychain records; full badges and social cache are signer-encrypted blobs. Public profile/preferences and sanitized public caches use their existing atomic local storage. Public recovery staging recursively removes secret field names, including legacy Flutter aliases, while protected full recovery retains the keys needed to resume. Imported authority flags are cleared and evidence must be reverified. Storage errors preserve unreadable data and pending transactions. No raw key, bearer token or proof payload is included in diagnostics.

Same-app Flutter migration reads the actual `flutter_secure_storage_service`, Flutter preferences and legacy AES-GCM badge copies only when accessible. Default migration preserves originals and a password-encrypted source archive; deletion is explicit and follows durable native commit. Unknown wrapped platform keys remain archived and fail closed. Actual signed same-team upgrade, permissions and keychain access are device acceptance work.

Passkeys require verified RP/domain/provisioning and remain unavailable. NFC follows the disabled Flutter baseline. Browser NIP-07/Android NIP-55 are not emulated. HTTPS is the default. Explicit custom HTTP and the source onion host are supported for public Bitcoin data; onion requires a running system VPN such as Orbot. The app starts no Tor proxy and never silently falls back to clearnet. Live public writes, production credentials and real payment authorization were not used for validation.

## Pinned dependencies and acknowledgments

**21-DOT-DEV/swift-secp256k1 0.23.2** is locked to `e70a10e036a55fffea31568f0af92d69b6d449cd`, using P256K/libsecp256k1. Both SwiftPM and Xcode workspace locks are checked. Its inspected `SharedSourcesPlugin` prepares source files using shell/find/cp in build outputs. Building consents to that exact pinned plugin; CI's invocation-scoped `-skipPackagePluginValidation` skips Xcode's trust prompt after pin validation. See [the provider/plugin review](Packages/MeetupCore/Docs/CRYPTO_PROVIDER.md). No wrapper audit is claimed.

**CryptoSwift 1.8.4** is locked to `729e01bc9b9dab466ac85f21fb9ee2bc1c61b258`. Copyright (C) 2014–3099 Marcin Krzyżanowski. Distributed under its permissive [license](https://github.com/krzyzanowskim/CryptoSwift/blob/1.8.4/LICENSE). Required acknowledgment: This product includes software developed by the "Marcin Krzyzanowski" (http://krzyzanowskim.com/). It supplies reviewed scrypt, ChaCha20/XChaCha20-Poly1305 and AES-GCM building blocks; protocol compatibility is covered by official NIP vectors and Flutter fixtures. Source/vector review is not an independent cryptographic audit or perfect memory-erasure guarantee.

## Reviewed follow-up

The Upgrade configuration uses **2.0.0 (32)**, higher than the checked-in Flutter **1.7.2 (31)** baseline; the owner must confirm the latest distributed App Store values before distribution. The app/widget versions stay aligned.

Explicit HTTP Bitcoin GETs use a dedicated bounded Network.framework adapter. Portal, signer, proofs and ordinary HTTPS paths keep their existing URLSession/TLS policies and global ATS remains enabled. The adapter accepts no credentials/cookies, follows no redirects, caps headers/body/chunk framing and supports cancellation and 20-second/45-second onion deadlines. This follows Apple's [ATS framework scope](https://developer.apple.com/documentation/security/preventing-insecure-network-connections); it does not create a proxy. Parser/normalization/price regressions run portably; Apple-only loopback tests exercise the actual Network adapter in Mac CI.
