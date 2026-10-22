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
`preview` build type, `migrationTest` build type (spec 14 §5.2.5: `initWith(release)`, debuggable, no
applicationId suffix; signed from `migrationtest.properties` / `MIGRATION_TEST_*` env with the key of the
Flutter APK on the test device, else the debug keystore). Release signing v2 + v3 only (§5.2.1).

Deviations: the widget colours live in `res/values/colors.xml` (`widget_*`, fixed dark palette)
instead of a `WidgetColors` object in `:core:ui` (spec 14 §3.2), because the widget is in `:app`
and Glance `ColorProvider`s from resources need no extra module API. The launcher label
(`app_name`) is still "Einundzwanzig": spec 14 §1.6 / R19 leaves it as a product decision
(recommendation: "21Meetup" for continuity; the widget label already is "21Meetup"). `lint-baseline.xml` records lint findings in other modules at integration
time (`checkDependencies`); the app's own findings are fixed (no legacy launcher PNGs, minSdk 26 uses
the adaptive icon). New findings fail `lintPreview`. Open for `:data:identity`: `PublicKeyCredential`
in `AndroidPasskeyPrfService` needs API 28 (minSdk 26), baselined until that module adds an SDK guard.

## Tests

`tools/gradlew-locked :app:testDebugUnitTest` (42 tests): navigator semantics, startup runner (gate, groups,
failures), widget router/intents/countdown/refresh body, Glance size modes (`runGlanceAppWidgetUnitTest`:
second metrics row from MEDIUM, news from LARGE) and live updates of a running session,
`ExternalUrlOpener` (non-http(s) refused, https → Custom Tab), Robolectric Compose tests of the bar,
the NavDisplay (pre-shell vs shell, tab state kept, pushed screens cover the bar), the NIP-46 snackbar
(text, action, close icon, open, refused `javascript:` logged by host), widget-target routing (only
with the shell, no double push), Home refresh on tab tap and after the scanner, the splash keep
condition and the migration wait screen.
