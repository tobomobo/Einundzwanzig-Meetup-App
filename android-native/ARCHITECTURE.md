# Einundzwanzig Meetup — native Android

Native rewrite of the Flutter app (`../lib`) in Kotlin + Jetpack Compose with Material 3 Expressive.
The porting specs in `spec/*.md` describe every feature of the Flutter app; they are the source
of truth for behavior. When a spec and the Dart code disagree, the Dart code wins: read it.

## Hard requirements

- **Wire compatibility.** Users of the Flutter app (Android and iOS) and of the native app
  meet at the same meetups. Nostr event kinds, tags, content JSON, signatures, QR payloads,
  hash inputs, backup files and portal API calls must be byte-for-byte compatible.
  Port the Dart unit tests and their vectors (`../test`) for everything that crosses the wire.
- **Upgrade in place.** The release build keeps `applicationId = space.einundzwanzig.meetup`,
  so it installs as an update over the Flutter app. `:data:migration` imports the Flutter
  SharedPreferences and flutter_secure_storage data on first start. Never lose a user's key
  or badges.
- **Feature parity** with the Flutter app on Android. Web-only code (`nip07`, `*_web.dart`) is
  out of scope.

## Toolchain

| | |
|---|---|
| Gradle | 9.8.2 (wrapper) |
| AGP | 9.4.1, built-in Kotlin (do **not** apply `org.jetbrains.kotlin.android`) |
| Kotlin | 2.4.21, KSP 2.3.12 |
| SDK | compileSdk 37, targetSdk 36, minSdk 26 |
| UI | Compose BOM 2026.09.00, material3 1.5.0-beta01 (Expressive APIs) |
| DI | Hilt 2.60.1 (KSP) |
| Navigation | Navigation 3 (1.2.0) |
| JDK | 17 (`~/development/jdk17`) |

**Always build through `tools/gradlew-locked`** (serializes Gradle runs between agents; the
machine has little free RAM). Build only what you touch, e.g.

```
tools/gradlew-locked :core:crypto:testDebugUnitTest
tools/gradlew-locked :feature:badges:compileDebugKotlin
```

Never run `./gradlew clean` or delete another module's build dir.

## Module layout

```
app/                 MainActivity, Application, NavDisplay wiring, app shell, deep links, manifest
core/common          dispatchers, ApplicationScope, TimeSource, AppLogger, AppStartupTask, DartJson
                     (the ONLY Dart-compatible JSON codec), Dart compat (toDartString/toStringAsFixed,
                     DartDateTime, DartUri)
core/l10n            ALL strings generated from ../lib/l10n/*.arb (tools/arb2xml.py). Do not edit by hand.
core/ui              theme (EinundzwanzigTheme), design-system components, shared composables
core/navigation      Navigator + every NavKey (Destinations.kt)
core/crypto          hex, bech32/NIP-19, sha256/hmac, secp256k1 schnorr/ecdh, NIP-04/44/49, pbkdf2, bcrypt, aes
core/nostr           Event model, signing, relay socket/pool, relay config, NIP-46 client, NIP-05, profiles
core/network         OkHttpClient, Json, HTTP helpers
core/data            DataStore prefs, SecureStore (Android Keystore AES-GCM), JSON file store
data/<domain>        repositories/services per domain (no UI)
feature/<domain>     screens + ViewModels per domain
```

### Module map (spec → module)

| Module | Spec | Contents | May depend on (besides core) |
|---|---|---|---|
| `:core:nostr` | 07 §6–8, 01 §7 | NostrEvent/Filter, id+verify, RelaySocket (OkHttp WS, frame limit), RelayConfig, relay query/publish helpers, NIP-19 helpers, NIP-46 transport pieces | — |
| `:data:identity` | 01 | SigningService (local / Amber NIP-55 / NIP-46), SecureKeyStore, LocalKeyVault, LocalEasyAuth, PasskeyPrf, UserProfileRepository, AppReviewDemo, DeviceIntegrity | — |
| `:data:migration` | 14 | Flutter SharedPreferences + flutter_secure_storage import | identity |
| `:data:settings` | 12 | locale, theme mode, haptics, feature flags, tour progress (implements core:ui TourProgressStore), diagnostics fingerprint | — |
| `:data:mempool` | 13 | mempool API + config, currency/price service | — |
| `:data:meetups` | 09 | meetup directory (bundled + portal), location, nearby, portal API + login, portal leader, geocoding, portal logos | identity |
| `:data:profile` | 07 | NostrProfileService/lookup, display names, local picture, Blossom upload, lud16 lookup | identity |
| `:data:badges` | 02 §3–4,7 / 03 §3 | MeetupBadge model, DartDateTime use, BadgeSecurity, MeetupKey, BadgeClaimService, encrypted BadgeRepository (savedEvents flow), proofs/hashes/stats | identity |
| `:data:calendar` | 10 | NIP-52 calendar events, recurrence, matcher, RSVP, home-widget data | identity, meetups, profile |
| `:data:trust` | 04 §8 | TrustScoreCalculator, layers evaluator, pure scoring | badges |
| `:data:proofs` | 06 | platform proofs, humanity proof, zap receipts/stats, NIP-05, SatoshiDuell | identity, profile, badges |
| `:data:chat` | 11 | chat (NIP-29/42 as specced), event chat | identity, profile |
| `:data:news` | 11 | news, reactions, news zaps | identity, profile |
| `:data:community` | 11 | V4V, PlebRap audio (Media3 MediaSessionService), glossary data | — |
| `:data:admin` | 05 | AdminRegistry, AdminStatusVerifier (+ `reVerifyAdmin`), PromotionClaimService | identity, badges, trust, meetups |
| `:data:socialgraph` | 05 | SocialGraphService, CoAttendance (graph, publish, verifyPerson) | identity, badges, profile, admin |
| `:data:reputation` | 04 | ReputationPublisher/fetcher (kind 30078), QR codec + generator, platform-proof verify-string check | identity, badges, trust, proofs, socialgraph, profile |
| `:data:issuance` | 02 | RollingQR, event-badge session/auth/chain, scan processing + save use-case, presence check, NFC read/write logic | identity, badges, admin, socialgraph, calendar, meetups, mempool |
| `:data:backup` | 08 | `.21bkp` export/import, restore policy | identity, badges, admin, proofs, profile |
| `:data:badgesync` | 08 | NIP-44 relay badge sync | identity, badges, proofs |

Shared UI infrastructure: `:core:camera` (CameraX + ML Kit QR scanner composable, permission
handling) and `:core:map` (osmdroid map composable with markers, snapshot), used by several features.

Component features: `:feature:backup` (export/import dialogs, overlays, SAF launchers) and
`:feature:settings` (settings sheet, diagnostic log) expose composables that `:feature:home` and
`:feature:onboarding` may depend on. These are the only allowed feature → feature dependencies.

Feature modules (`:feature:*`): onboarding, wallet, badges (scan/issue/NFC), reputation, network, admin,
proofs, profile, backup, meetups, events, chat, news, community, bitcoin, home, settings.

### Cross-cutting patterns

- **Startup work** (`BadgeSync.init`, humanity re-verification, reputation background publish, …):
  implement `AppStartupTask` from `:core:common` and contribute it with Hilt `@IntoSet`; the
  Application runs all of them once on start in `@ApplicationScope`.
- **Hooks instead of cycles**: where Dart calls "upwards" (badge save → badge sync, signer ready →
  badge sync), the lower module exposes a `SharedFlow` (e.g. `BadgeRepository.savedEvents`,
  `SigningService.signerReady`) and the higher module collects it from its startup task.
- **Storage keys**: data modules use the exact Flutter key names from their spec (see `:core:data`).

Dependency rules: `feature → data → core`. Features never depend on other features; they
navigate through NavKeys only. `data` modules may depend on other `data` modules when the
spec's cross-domain dependencies require it (no cycles).

Package = `space.einundzwanzig.meetup.<path>` (the library convention plugin derives the
namespace from the Gradle path, e.g. `:data:badges` → `space.einundzwanzig.meetup.data.badges`).

Modules are discovered automatically by `settings.gradle.kts` (any `core|data|feature/<name>/build.gradle.kts`).

### Convention plugins (`build-logic/`)

- `einundzwanzig.android.library` — Android library + kotlinx-serialization, JUnit/Robolectric/Turbine for tests
- `einundzwanzig.android.compose` — Compose compiler + BOM + material3 + icons
- `einundzwanzig.android.hilt` — KSP + Hilt
- `einundzwanzig.android.feature` — library + compose + hilt + core:common/ui/l10n/navigation + lifecycle/nav3

Typical `data` module:

```kotlin
plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:nostr"))
}
```

Typical `feature` module:

```kotlin
plugins { id("einundzwanzig.android.feature") }
dependencies { implementation(project(":data:badges")) }
```

Libraries are in `gradle/libs.versions.toml`. Add a library there (latest stable) instead of
hard-coding coordinates.

## Code conventions

- Kotlin official style, 4 spaces, trailing commas. Explicit API is not required, but keep
  implementation classes `internal` and expose interfaces.
- Coroutines + Flow everywhere. No blocking I/O on the main thread. Inject `@IoDispatcher`.
  Long-lived work (relays, sync) runs in `@ApplicationScope`.
- Hilt: `@HiltViewModel` ViewModels, `@Singleton` services, `@Binds` interface → impl in a
  module-local `di/` package.
- Serialization: kotlinx.serialization with JSON key names copied exactly from the Dart
  `toJson`/`fromJson` (use `@SerialName`). One shared `Json { ignoreUnknownKeys = true; explicitNulls = false }`
  from `:core:network` (`AppJson`).
- Errors: services return `Result<T>` or sealed outcomes; never swallow exceptions silently;
  log through `AppLogger` (`:core:common`) — never log private keys, nsec, seeds or tokens.
- Time: inject `TimeSource`, never call `System.currentTimeMillis()` in business logic.
- Port tests: every Dart test in `../test` that belongs to your domain becomes a JUnit test
  with the same cases and vectors. Pure logic tests run on the JVM; Android-dependent ones
  use Robolectric (`@RunWith(AndroidJUnit4::class)`).

## UI conventions (Material 3 Expressive)

- Wrap nothing yourself: `:app` provides `EinundzwanzigTheme`. Use `MaterialTheme.colorScheme`,
  `MaterialTheme.extendedColors` (success, community/gold, nostr purple, course grey,
  textTertiary, bitcoin) and `MaterialTheme.typography`. **No hard-coded colors** in features.
- Brand: dark by default (near-black surfaces, Bitcoin orange `primary`), Rajdhani type,
  Inconsolata (`MonoStyle`) for npubs/hashes/amounts.
- Prefer Expressive components where they fit: `LoadingIndicator`/`ContainedLoadingIndicator`
  instead of spinners, `ButtonGroup`/connected buttons for segmented choices, `SplitButton`,
  `FloatingToolbar`, `LargeFlexibleTopAppBar`/`MediumFlexibleTopAppBar` with scroll behavior,
  `MaterialShapes` for avatars/badges, wavy `LinearWavyProgressIndicator`, `ShortNavigationBar`,
  `FloatingActionButtonMenu`, shape-morphing `IconButton`/`ToggleButton`.
  Opt in with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- Edge-to-edge: every screen uses `Scaffold` and respects `WindowInsets`; predictive back works
  out of the box with Navigation 3 — do not intercept back unless the spec needs a confirmation.
- Accessibility: content descriptions on icons with meaning, 48dp touch targets, text scales.
- Screens are split into a stateful `XRoute(viewModel = hiltViewModel())` and a stateless
  `XScreen(state, onAction)` with a `@Preview`.
- Strings: `stringResource(space.einundzwanzig.meetup.core.l10n.R.string.<arbKey>)` — the
  resource name is the ARB key from the spec. Import the R class as
  `import space.einundzwanzig.meetup.core.l10n.R as L10nR`. Plurals with a `=0` branch use
  `pluralStringResourceWithZero(L10nR.plurals.x, L10nR.string.x_zero, n, n)`. Args are positional
  in the order listed in the comment above each string in `core/l10n/src/main/res/values/strings.xml`.
  Strings that exist only in the native app go into the feature module's own
  `res/values{,-en,-es}/strings.xml` (German default, all three languages required) with a
  `native_` prefix.

## Navigation

`:core:navigation` owns every destination as a `@Serializable` `NavKey` in `Destinations.kt`.
A feature exposes one function the app wires into its `entryProvider`:

```kotlin
fun EntryProviderScope<NavKey>.badgesEntries() {
    entry<BadgeWallet> { BadgeWalletRoute() }
    entry<BadgeDetails> { key -> BadgeDetailsRoute(key.badgeId) }
}
```

and navigates with `LocalNavigator.current.navigate(BadgeDetails(id))`.

**Per-entry ViewModels (required in `:app`).** Every feature scopes its `hiltViewModel()` to the
nav entry, so `:app`'s `NavDisplay` must install
`entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator())`
(`lifecycle-viewmodel-navigation3`). Navigation 3's default is only the saveable-state decorator;
without the ViewModel-store one, ViewModels live in the Activity store forever: popped screens keep
their work running (e.g. the chat's relay subscription and read marks) and re-opened keys reuse stale state.
