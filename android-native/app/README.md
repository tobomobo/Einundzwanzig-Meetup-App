# :app

The application module: process start, `MainActivity`, Navigation 3 wiring, the app shell, the
home-screen widget, the manifest and release config. Specs: 12 §2.1–§2.4, 14 (§1.5, §3.2, §4.7, §5.2, §6).

## Pieces

| Piece | Spec | Code |
|---|---|---|
| Process start: logger attach + crash handler, `AppLogger.init`, `FlutterMigration.ensureMigrated()` (IO, app scope), then every `AppStartupTask` grouped by `order` once the migration gate is complete | 12 §2.1, 14 §4.7 | `EinundzwanzigApp`, `startup/AppStartupRunner` |
| `MainActivity : FragmentActivity` (BiometricPrompt), system splash held until migration gate + theme mode + first route; after 20 s the system splash gives way to the blocking in-app `MigrationWaitScreen` (`native_migration_wait_*`), and the app is **never** composed before `MigrationGate.isComplete` (data/migration README release blocker) (Splash VM is Activity-scoped and handed to `onboardingEntries`), edge-to-edge, nav bar hidden with transient swipe (F2), phone portrait rule, per-app language (LocaleManager 33+, `wrapContext` + recreate below), Amber NIP-55 host via ActivityResult | 12 §2.1/§2.3, 14 §1.5, F8 | `MainActivity` |
| Navigator: `navigate/replaceTop/back/backTo/resetTo/navigateFromRoot/openUrl` over a root stack + one stack per tab (tabs keep saveable state and ViewModels like Flutter `IndexedStack`) | ROUTES.md | `navigation/AppNavigator` |
| NavDisplay with saveable-state + ViewModelStore decorators per stack, expressive spring transitions (slide-up for scanner / reputation QR, cross-fade between tabs), predictive back | ARCHITECTURE "Per-entry ViewModels" | `navigation/AppNavHost` |
| Links: http(s) only (Audit M2), Custom Tabs, `ACTION_VIEW` fallback, host-only logging | 12 §2.10 | `navigation/ExternalUrlOpener` |
| Shell: M3E `ShortNavigationBar` (Home, Badges, cookie-shaped scan button, Events, Nearby), haptics, guide anchors, scan sheet (`:feature:home`), Home refresh on tab tap and after the scanner returns, 420 dp max width with hairlines on wide screens (F3) | 12 §2.4, F3–F7 | `ui/AppShell`, `ui/AppRoot` |
| Root: `NavResults` bus, `LocalNavigator`, haptics, snackbar host + app-lifetime `AppSnackbarController`, `BackupHost`, `GuideHost`, avatar/crest seams, NIP-46 `auth_url` snackbar (60 s, close, "ÖFFNEN"; acknowledged only after the user dealt with it, so a recreated activity shows it again), snackbar host inside the 420 dp column | 12 §2.2, F3 | `ui/AppRoot` |
| Widget: `MeetupWidgetProvider` (Glance receiver, same FQCN), `WidgetRouterActivity` (same FQCN, `e21_widget/pending_target`), `MeetupWidget` (3 responsive sizes, render-time countdown, observes `HomeWidgetStore.snapshots()` inside the composition so `updateAll()` during a running Glance session shows new values), refresh → `WidgetRefreshWorker` (Hilt, unique work, network), widget targets consumed on resume while the shell is shown (not pushed again when already on top) | 14 §3 | `widget/*`, root package |
| Bindings: `HomeWidgetRenderer`, `BitcoinWidgetSink`, `MigrationCompletedHook` (widget re-render), `DiagnosticsInventorySource`, `BadgeLoadStartupTask` (order -50), `AppSnackbarController` | | `di/AppModule` |

Entries: `onboarding, home, wallet, events, badges, reputation, network, admin, proofs, profile,
meetups, chat, news, community, bitcoin, settings` (all 57 keys).

## Release

versionCode 20000 (spec 14 §5.2.2), ABIs armeabi-v7a/arm64-v8a/x86_64, R8 rules in
`proguard-rules.pro`, `data_extraction_rules.xml`, `network_security_config.xml` (Flutter copy),
`preview` and `migrationTest` build types. `preview` is non-debuggable with R8 optimization,
obfuscation and resource shrinking, a `.preview` applicationId suffix and its own test signing key.
`migrationTest` uses the release source without an applicationId suffix (spec 14 §5.2.5), but is
debuggable and explicitly unminified with resource shrinking disabled so `run-as` can seed upgrade
fixtures. It is signed from `migrationtest.properties` / `MIGRATION_TEST_*` env with the key of the
Flutter APK on the test device, else the debug keystore. Genuine R8 runtime coverage uses `preview`.
Release signing v2 + v3 only (§5.2.1).

Deviations: the widget colours live in `res/values/colors.xml` (`widget_*`, fixed dark palette)
instead of a `WidgetColors` object in `:core:ui` (spec 14 §3.2), because the widget is in `:app`
and Glance `ColorProvider`s from resources need no extra module API. The launcher label (`app_name`)
and widget picker label both use "21Meetup" for continuity with Flutter (spec 14 §1.6 / R19).
`lint-baseline.xml` records remaining lint findings across modules (`checkDependencies`); resolved
entries are removed after verification, and new errors fail `lintPreview` / `lintRelease`.
`AndroidPasskeyPrfService` guards availability, registration and unlock below API 28, so migrated
passkey identities on API 26–27 receive the unsupported result before Credential Manager is called.

## Tests

Run Gradle from `android-native/` with JDK 17 through `tools/gradlew-locked`, which serializes builds
across agents sharing this checkout. `tools/gradlew-locked :app:testDebugUnitTest` covers navigator
semantics, startup runner (gate, groups, failures), widget router/intents/countdown/refresh body,
Glance size modes (`runGlanceAppWidgetUnitTest`:
second metrics row from MEDIUM, news from LARGE) and live updates of a running session,
`ExternalUrlOpener` (non-http(s) refused, https → Custom Tab), Robolectric Compose tests of the bar,
the NavDisplay (pre-shell vs shell, tab state kept, pushed screens cover the bar), the NIP-46 snackbar
(text, action, close icon, open, refused `javascript:` logged by host), widget-target routing (only
with the shell, no double push), Home refresh on tab tap and after the scanner, the splash keep
condition and the migration wait screen.

The full unit and release lint checks are
`tools/gradlew-locked --no-configuration-cache testDebugUnitTest :app:lintRelease`.
For module-local edits, compile the affected modules and run their `lintAnalyzeRelease` tasks before
the final app lint pass. Verification tooling has its own suite:
`python3 -m unittest discover -s tools/tests -v`.

`bash tools/run_device_tests.sh preview emulator-5556 /tmp/e21-device-tests` builds and installs the
minified preview app plus its mapping-adapted instrumentation APK, runs the app runtime tests and
the `:data:migration` legacy-storage tests, and saves their output. It requires a dedicated AVD whose
name starts with `e21test`; the first argument also accepts `debug` or `migrationTest`. Preview
instrumentation generates keep rules for APIs called across the app/test APK boundary; the normal
release build does not include those test rules. `migrationTest` supports in-place Flutter upgrade
fixtures with the same applicationId and signing identity; the runner excludes
`SeedFlutterUpgradeFixtureTest` and `UpgradeDeviceTest`, which are run separately with the upgrade
fixture and `expectedNpub` instrumentation argument.

The prerelease workflow runs preview device tests on API 26, 29, 34 and 36 before building the
preview artifact. The manual `native_android_release_validation.yml` workflow runs the unit and lint
checks, builds the release APK with the existing Flutter signing identity, then verifies the
certificate pin, exported-component allow-list and 16 KB alignment and uploads the APK and R8 mapping.
