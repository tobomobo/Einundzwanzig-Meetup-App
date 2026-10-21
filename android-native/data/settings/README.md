# :data:settings

App settings of spec 12 (§3.6, §4, §8.13): language, haptics, theme mode, Home "Nostr last seen",
tour progress, feature flags and the start-up diagnostics fingerprint. No UI (the settings sheet
and dialogs belong to `:feature:settings`).

```kotlin
implementation(project(":data:settings"))   // exposes :core:ui as api (GuideController, ThemeMode)
```

Package root: `space.einundzwanzig.meetup.data.settings`. All storage goes through the shared
`:core:data` `KeyValueStore` under the exact Flutter key names; this module opens no file of its own
and has no Flutter importer (`:data:migration` copies the keys).

## Keys (`SettingsKeys`)

| Key | Type | Default | Notes |
|---|---|---|---|
| `app_locale` | String | absent = system | `de`/`en`/`es`; empty = system; removed for "System" |
| `haptic_enabled` | Bool | `true` | |
| `nostr_last_seen` | Int | 0 | unix **seconds** |
| `guide_completed_<tour>` | Bool | absent | `<tour>` = `GuideTour.persistName` (`myMeetups`, `portalArea` camelCase); `false` = key removed |
| `guide_onboarding_asked` | Bool | absent | |
| `native_theme_mode` | String | `dark` | native only: `dark`/`light`/`system` |
| `native_applied_locale` | String | absent | native only, API 33+: last tag handed to `LocaleManager` (`""` = system) |

Not here: `fiat_currency` (→ `:data:mempool`), `tile_order`/`tile_hidden`/`mig_*` (→ `:feature:home`),
`diagnostic_log`/`diagnostic_verbose` (→ `:core:common` `FileLogStore`).

## Public API

| API | Purpose |
|---|---|
| `interface SettingsRepository` (inject) | `appLocale: Flow<String?>`, `getAppLocale()`, `setAppLocale(code?)`; `hapticEnabled: StateFlow<Boolean>`, `isHapticEnabled()`, `setHapticEnabled(on)`; `themeMode: StateFlow<ThemeMode>`, `getThemeMode()`, `setThemeMode(mode)`; `getNostrLastSeen()`, `setNostrLastSeen(sec)`. Writes return `Boolean` (durably stored). Companion: `DEFAULT_HAPTIC_ENABLED`, `DEFAULT_THEME_MODE`, `themeModeToPref/FromPref` |
| `object SettingsKeys` | Key constants above |
| `object FeatureFlags` | `NFC_ENABLED = false`, `WEB_LOCAL_NSEC_LOGIN_ENABLED = false` (`lib/features.dart`) |
| `interface LocaleController` (inject) | Dart `LocaleController`: `appLocale: Flow<String?>`, `current()`, `setLocale(code?)` (stores + applies), `syncWithSystem()`, `isManagedByPlatform`, `currentBlocking()`, `wrapContext(base)` |
| `object AppLocales` | `SUPPORTED = [de, en, es]`, `displayName(code)` (`Deutsch`/`English`/`Español`/`System`), `flagFor(code)` (🇩🇪 🇬🇧 🇪🇸 🌐), `languageOf(tag)`, `wrapContext(base, code)` |
| `TourProgressStore` (bound, from `:core:ui`) | Implemented on `KeyValueStore` with the Flutter keys |
| `GuideController` (provided `@Singleton`) | The app-wide tour state machine from `:core:ui`, wired to the store above and `@ApplicationScope` |
| `fun interface DiagnosticsInventorySource` | Optional binding (`@BindsOptionalOf`): `counts(): InventoryCounts` for the `Bestand:` line. Without it the line is skipped. **TODO `:app`**: `@Binds` an implementation that returns `BadgeRepository` badge count + `UserProfile.favoriteMeetupIds.size` (no data module depends on `:data:settings`, so `:app` is the place) |
| `di.SettingsEntryPoint` | `localeController()` for code that runs before injection (`attachBaseContext`) |

### Startup tasks (contributed with `@IntoSet AppStartupTask`)

**`:app` must run the `AppStartupTask` set only after the Flutter migration has completed**
(behind the splash): both tasks read migrated keys (`app_locale`, `signing_mode`).

- `LocaleSync` (order 0): on API 33+, adopts a language chosen in the Android "App languages" settings into
  `app_locale`, then keeps `LocaleManager` in step with `app_locale` for the whole process (also
  after an app reset clears the store). No-op below API 33.
- `DiagnosticsFingerprint` (order -100, alone in the first group): `DiagnosticsService.logEnvironment`
  (spec 12 §8.13) with the signing mode read from `signing_mode` (Dart `SigningService.getMode()`
  mapping) and the optional inventory source. Started undispatched in the application scope, so
  the `APP-START` header is the first block of each start and the probes do not hold up later
  groups (Dart: unawaited from `main()`). **`:app` must not call `logEnvironment` itself** (the `:core:common` README
  snippet predates this task).

## Wiring in `:app`

```kotlin
// Theme and haptics. `themeMode.value` is DARK until the first store read, so keep the splash
// up until the stored mode is known (otherwise LIGHT/SYSTEM users see a dark first frame):
var themeLoaded = false
installSplashScreen().setKeepOnScreenCondition { !themeLoaded || !migrationDone }
lifecycleScope.launch { val stored = settings.getThemeMode(); settings.themeMode.first { it == stored }; themeLoaded = true }
// in setContent:
val themeMode by settings.themeMode.collectAsStateWithLifecycle()
EinundzwanzigTheme(themeMode = themeMode) {
    val haptics by settings.hapticEnabled.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalHapticsEnabled provides haptics, LocalGuideController provides guide) { … }
}

// Language below API 33 (ComponentActivity, no AppCompat): wrap and recreate
override fun attachBaseContext(base: Context) {
    val locale = EntryPointAccessors.fromApplication<SettingsEntryPoint>(base.applicationContext).localeController()
    appliedLocale = if (locale.isManagedByPlatform) null else locale.currentBlocking()
    super.attachBaseContext(locale.wrapContext(base))
}
// in onCreate, only if !localeController.isManagedByPlatform (no drop(1): the migration may have
// written app_locale after attachBaseContext read the empty store; `!= appliedLocale` prevents loops):
lifecycleScope.launch { localeController.appLocale.collect { if (it != appliedLocale) recreate() } }
```

On API 33+ `LocaleManager` recreates activities itself. `MainActivity.onCreate` should call
`lifecycleScope.launch { localeController.syncWithSystem() }` (no-op below 33): a per-app language
change in the Android settings recreates the activity without a process restart, and this adopts it
into `app_locale` right away instead of at the next cold start.

**App reset** (`ResetAppUseCase`): after clearing the store call `localeController.setLocale(null)`.
It applies to `LocaleManager` synchronously; relying only on the `LocaleSync` collector loses the
reset if the process dies first (the old platform tag would be re-adopted on the next start,
violating spec 12 §9.3/§14.17).

## Known gaps

- **Language below API 33 (26–32)**: only contexts wrapped with `wrapContext` (the activity) use
  the chosen language for resources. `wrapContext` also sets `Locale.getDefault()`, so
  `DateTimeFormatter`/`String.format` follow the choice process-wide, but the Application context
  (notifications, home widget, PlebRap MediaSession, `WorkManager` workers) resolves resources in
  the system language. Flutter applied the locale app-wide. Code that builds user-visible text
  outside the activity should resolve strings from `localeController.wrapContext(appContext)`.
  (Wrapping in `Application.attachBaseContext` is not possible: DataStore needs the attached
  context to read `app_locale`.)
- **Fingerprint `Sprache` line (owner `:core:common`)**: Dart logs `Platform.localeName` (device
  locale). `AndroidEnvironmentProbe.localeName()` uses `Locale.getDefault()`, which follows the
  in-app language (LocaleManager on 33+, `wrapContext` below), so with app language `en` on a
  German device the line reads `en` instead of `de_DE`. Fix belongs in `:core:common`:
  `Resources.getSystem().configuration.locales[0].toString()`. `DiagnosticsSources` has no
  locale override, so this module cannot correct it.
- **APP-START header position**: `AppStartupTask`s run after the Flutter migration, so log lines
  written in `Application.onCreate` and by the migration precede this start's `APP-START` block
  (spec 12 §2.1 wants the fingerprint right after logger setup). Moving it earlier needs `:app` to
  call `logEnvironment` in `Application.onCreate` with a `signingMode` source that awaits migration
  completion, and to drop the `DiagnosticsFingerprint` task binding.
`app/build.gradle.kts` already has `generateLocaleConfig = true` for the system settings entry.

## Tests

`tools/gradlew-locked :data:settings:testDebugUnitTest` — 47 JVM tests:

- `TourProgressStoreTest` (10): the persistence part of `guide_service_host_test.dart`
  (`finishTourNow` clears synchronously and persists `guide_completed_wallet`,
  `finishGuideTourIfActive` ignores other tours) against the real keys, plus spec 12 §10 item 10
  (`startTour` no-op unless forced, `nextStep` on the last step persists, `declineAllTours` writes
  all 10 keys + `guide_onboarding_asked`, `resetAllTours`/`resetTour` remove them), migrated flags.
- `SettingsRepositoryTest` (13): defaults, locale write/remove/empty, flows, haptics without stale
  cache, theme round trip/fallback, `nostr_last_seen` as Int seconds, reset via `clear()`, flags.
- `LocaleControllerTest` (18): store + apply, system, platform already showing the language,
  below API 33, migrated preference vs system settings, external change adoption, unsupported
  system language (`fr-FR`) adopted as system without a platform write, startup task
  incl. reset, `AppLocales` names/flags; last write wins under concurrent `setLocale`, platform
  canonical tag recorded, binder failures swallowed (collector still starts), failed adoption
  does not advance `native_applied_locale`.
- `DiagnosticsStartupTaskTest` (6): signing-mode line, inventory present/absent/failing,
  `getMode` mapping, task order, header written before `run()` returns.

The widget half of `guide_service_host_test.dart` (dispose) is in `:core:ui` `GuideHostTest`;
`snackbar_theme_test.dart` is UI (`:core:ui`).
