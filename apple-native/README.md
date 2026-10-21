# Einundzwanzig · Apple native prototype

Shared **SwiftUI** app for **iOS 26+ / macOS 26+**, Swift 6, Xcode 26. Native iPhone/iPad tabs and native Mac sidebar, toolbars, Settings scene and keyboard commands. Flutter `ios/` and `macos/` are preserved. No backend, secrets or paid team are required to browse the prototype. The project now has one pinned source dependency for its isolated secp256k1 foundation; see the build/plugin trust note below.

This is an initial implementation. [All 57 source routes and security workflows are explicitly classified](Docs/PARITY.md); there is no full-parity or production-security claim.

## Open and run on a Mac

```bash
open apple-native/Einundzwanzig.xcodeproj
```

Choose shared scheme **Einundzwanzig**, then **My Mac** or an iOS 26 simulator and Run. Select Xcode 26 as the active developer directory. The project is checked in; XcodeGen and Flutter are not involved. The package uses Swift tools 6.1+ (Xcode 26 supplies Swift 6.2); the app target requires OS 26 for Liquid Glass. The Foundation-only MeetupCore target stays separate from the MeetupNostr cryptographic target.

For Mac local development, choose **Sign to Run Locally** / ad-hoc signing if Xcode asks; no development team is configured. Simulator builds need no team. Installing on a physical iPhone requires ordinary Apple signing/provisioning; unsigned builds below verify compilation only. A Mac build with `CODE_SIGNING_ALLOWED=NO` is not a promise that every macOS privacy-protected operation works unsigned. Public-only profile file persistence allows browsing without the provisioned data-protection Keychain; calendar denial can fall back to `.ics` export.

On first launch choose a nickname. An optional **public** npub, nostr: URI or 64-hex reference is validated and normalized on save. Format/checksum validation does not prove curve membership or key ownership and does not connect a signer. Use **Preview** for an offline walkthrough, or the ordinary local profile to load public data. Settings can switch modes; sample favorites and plans are isolated from live ones. There are no sample trusted credentials or generated public keys.

Mac shortcuts: ⌘1 Home, ⌘2 Badges, ⌘3 Events, ⌘4 Meetups, ⌘5 Community, ⇧⌘S Scan, ⌘R refresh, and the standard Settings menu.

## Working flows

- Onboarding and profile editing; persisted language, currency, appearance and dashboard tile order/visibility.
- Live meetup directory, favorites by portal ID, foreground one-shot location, manual MapKit place search, radius/date filters, MapKit markers, detail, Apple Maps and native share. Denial/no-fix states retain planned-place search; group locations are never invented as event venues.
- Portal-first meetup calendar with complete-aggregate-or-fallback behavior, ICS WEEKLY/MONTHLY recurrence/time-zone/DST/EXDATE handling, all-day dates, source/freshness/limitation labels, conservative group matching, local plans, native calendar review and `.ics` export.
- Badge metadata previews from manual JSON, bounded files, native iOS QR camera or selected photos decoded with Vision; searchable collections, grouping, detail and deletion. Every imported/sample item remains **unverified** and contributes no trust. Production encrypted wallets are not imported.
- Honest trust/security capability display; links to explicit unavailable integration states instead of successful fake protocol actions.
- Live RSS news with excerpts, images, dates, read marks and native article/share flows; original article opens on the publisher website.
- Live Bitcoin block height, EUR/USD prices and recommended fees from mempool-compatible HTTPS hosts; exact decimal sats/BTC conversion with optional timestamped fiat estimate.
- Public courses and lecturers: searchable native tabs, detail/related ID navigation, cached summary/full detail with honest failure/source labels, dates, join links and calendar review.
- Native relay/server preferences, resource/community navigation, selected source glossary entries in German, English and Spanish (516 interface/glossary keys per language).

Standard SwiftUI navigation, tabs, toolbars, pickers, sheets and controls adopt system styling. The custom Home discover/scan controls use real `GlassEffectContainer` and `glassEffect(.regular.interactive(), in: .capsule)` APIs. Content surfaces remain readable and opaque. Reduce Transparency replaces custom glass with an opaque surface; the prototype introduces no custom motion. System fonts, scalable text and semantic accessibility labels are used throughout.

## Data contracts and local storage

Read-only sources follow the checked-in Flutter implementation:

| Source | Contract |
| --- | --- |
| Meetup directory | `https://portal.einundzwanzig.space/api/mobile/meetups` array; optional slim records, numeric/string coordinate aliases, relative logos, slug portal links; duplicate identical IDs removed, different same-city groups retained. |
| Dates | `/api/meetup-events/{yyyy-MM-dd}` for today + first of next two months; flat dotted or nested meetup fields; `start_iso/end_iso` preferred, naive times interpreted as UTC, missing end stays unknown. |
| News | `https://media.einundzwanzig.space/s/einundzwanzig-news/feed.xml`; bounded RSS/XML text parser, safe original links and plain text summaries. |
| Bitcoin | Default `https://mempool.space`: `/api/blocks/tip/height`, `/api/v1/prices`, `/api/v1/fees/recommended`. Bitcoin remains live even in preview mode and is labelled separately. |

The iCal `https://portal.einundzwanzig.space/stream-calendar` source is the real fallback on any failed portal month or no usable portal dates. Unsupported/limited recurrence is explicitly labelled. No failed portal aggregate is presented as complete. Courses use `/api/courses?withDetails=1`, lecturers `/api/lecturers?withDetails=1`, and ID detail endpoints. List summaries never invent full appointments from `next_event`. Refresh failure retains available cached content and displays a source error; directory/RSS timestamps are separate from calendar source/fetch/last-attempt labels. A cached source is not labelled fresh after a failed refresh. Bitcoin's three endpoints currently succeed as one snapshot or report failure; old data retains its original time.

Public/local profile fields use device-only Keychain on iOS. macOS uses an atomic public-only file because provisioned data-protection Keychain entitlements must not block a no-team prototype. Preferences, ID favorites, private local plan notes, preview metadata and public feed cache live in the app's `Application Support/EinundzwanzigApple` container, with atomic writes, restrictive permissions and backup exclusion. Keychain/read/write errors are surfaced. Corrupt profile/preferences are preserved and cannot be silently overwritten; explicit reset is available. This is not an encrypted production badge vault or an encrypted backup mechanism.

The Apple bundle ID is `space.einundzwanzig.meetup.apple`; it does not migrate or overwrite Flutter application data. Raw QR payloads, app private keys and bearer tokens are never persisted or logged. Courses/lecturers and detailed public records have a separate protected public cache; reset also replaces its API client to prevent old cached details reappearing. Accepted preview imports retain only name/import time/optional claimed height, not a credential suitable for later verification. Unknown JSON fields are rejected. Nsec/ncryptsec and backup imports are unsupported.

## Build and verification commands

From the repository root:

```bash
python3 apple-native/Scripts/validate_project.py --require-route-matrix
python3 apple-native/Scripts/parse_swift.py
swift test --package-path apple-native/Packages/MeetupCore --force-resolved-versions -Xswiftc -warnings-as-errors
python3 apple-native/Scripts/test_local_models.py
swiftc -swift-version 6 -D CALENDAR_ICS_TESTS \
  apple-native/App/Platform/CalendarICS.swift \
  apple-native/App/Platform/Tests/CalendarICSTests.swift \
  -o /tmp/einundzwanzig-calendar-tests
/tmp/einundzwanzig-calendar-tests
```

```bash
xcodebuild -project apple-native/Einundzwanzig.xcodeproj -scheme Einundzwanzig \
  -configuration Debug -onlyUsePackageVersionsFromResolvedFile -skipPackagePluginValidation -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/einundzwanzig-ios-derived-data CODE_SIGNING_ALLOWED=NO build

xcodebuild -project apple-native/Einundzwanzig.xcodeproj -scheme Einundzwanzig \
  -configuration Debug -onlyUsePackageVersionsFromResolvedFile -skipPackagePluginValidation -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath /tmp/einundzwanzig-iphoneos-derived-data CODE_SIGNING_ALLOWED=NO build

xcodebuild -project apple-native/Einundzwanzig.xcodeproj -scheme Einundzwanzig \
  -configuration Debug -onlyUsePackageVersionsFromResolvedFile -skipPackagePluginValidation -sdk macosx -destination 'platform=macOS' \
  -derivedDataPath /tmp/einundzwanzig-macos-derived-data CODE_SIGNING_ALLOWED=NO build
```

[The dedicated workflow](../.github/workflows/apple-native.yml) selects an installed Xcode 26, validates the project, tests portable code and builds simulator, device and Mac paths. It links both local products, validates the exact package/workspace pins, and tests MeetupCore plus MeetupNostr. It does not deploy, publish or require secrets. A device build is included because the VisionKit camera implementation is excluded on simulators.

On Linux a portable Swift 6 compiler can run core/model/calendar tests and syntax parsing. Set `--scratch-path` on scripts/package tests if caches should stay in `/tmp`. No Apple SDK, Xcode build, simulator, camera, calendar, VoiceOver, visual or device-runtime validation was available during implementation. Passing a Linux parse is not SwiftUI/Apple SDK typechecking. See [validation evidence and manual acceptance](Docs/VALIDATION.md).

## Intentional gaps

App signing and trusted badge/claim verification remain disabled. The separate MeetupNostr module now implements real secp256k1/BIP-340 and canonical NIP-01 primitives, official-vector tests and exact UTF-8 local read policies, without a private-key UI or relay transport. Organizer authority, Nostr subscriptions/publishing, NIP-52 events, portal auth/RSVP, chats, payment/zap verification, passkeys, encrypted backups and Flutter data migration remain unavailable. Relay settings are persisted configuration for future adapters; they do not imply a live connection. PlebRap playback, widgets, full trust algorithms, background updates and broad glossary/tour coverage remain ahead. Existing profiles from checkpoint 61ed8d4 may contain a shape-only public reference; validation occurs when it is saved again.

Calendar export is a local action. iOS opens `EKEventEditViewController` without full calendar permission. Mac asks only for write access after explicit confirmation. Saving and cancellation are distinguished; unknown event durations are proposed for review, not asserted as API facts.

Sources and native decisions: [Liquid Glass custom views](https://developer.apple.com/documentation/swiftui/applying-liquid-glass-to-custom-views), [adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass), [calendar permission migration](https://developer.apple.com/documentation/technotes/tn3152-migrating-to-the-latest-calendar-access-levels), [macOS Keychains](https://developer.apple.com/documentation/technotes/tn3137-on-mac-keychains). Assets reuse the repository's MIT-licensed logo/icon; no downloaded fonts or third-party binaries are shipped.

## Pinned cryptographic source and build plugin

`MeetupNostr` uses **21-DOT-DEV/swift-secp256k1 0.23.2**, resolved to `e70a10e036a55fffea31568f0af92d69b6d449cd`; its P256K module wraps bundled libsecp256k1 v0.7.1. Both the package lock and the embedded Xcode workspace lock are checked in and validated. No binary XCFramework or additional Swift package is used. Source inspection and passing vectors do **not** establish an independent wrapper audit.

The pinned provider's `SharedSourcesPlugin` invokes shell/find/cp to prepare shared Swift/C source files in build outputs. Building is consent to run that exact inspected plugin. Xcode may ask to trust the package plugin in interactive use. CI's invocation-scoped `-skipPackagePluginValidation` bypasses that prompt after pin validation; it is not a sandbox exemption or audit certification. See [the concrete provider/plugin review](Packages/MeetupCore/Docs/CRYPTO_PROVIDER.md). Keep resolved-only flags and review both locks before upgrading. Linux SwiftPM plugin execution and provider tests have run; Apple's package resolver/plugin prompt and both Apple SDK builds remain untested here.

Primitive signing accepts ephemeral byte buffers inside an actor; it is not connected to app profile storage or product signing controls. No promise of perfect memory erasure is made for caller copies. Official deterministic auxiliary values exist only in tests; production primitive signing uses fresh randomness. A verified NIP-01 signature alone never confers badge, registry, claim or organizer trust.
