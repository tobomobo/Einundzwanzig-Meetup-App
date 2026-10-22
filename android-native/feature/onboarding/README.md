# :feature:onboarding

Splash, Intro and the identity setup wizard. Specs: `spec/01-identity-keys.md` §2.1–§2.6 and §2.11,
`spec/12-shell-home-settings.md` §2.3 (splash routing), `core/navigation/ROUTES.md` (Onboarding).

## Entries

```kotlin
fun EntryProviderScope<NavKey>.onboardingEntries(
    splashViewModel: @Composable () -> SplashViewModel = { hiltViewModel() },
)   // Splash, Intro, IdentitySetup
```

| NavKey | Route / Screen | Spec |
|---|---|---|
| `Splash` | `SplashRoute` / `SplashScreen(state, onRetry)` + `SplashViewModel` | 01 §2.1, 12 §2.3 |
| `Intro` | `IntroRoute` / `IntroScreen(state, onAction)` + `IntroViewModel` | 01 §2.2 |
| `IdentitySetup` | `IdentitySetupRoute` / `IdentitySetupScreen(state, onAction, bunkerState)` + `IdentitySetupViewModel` | 01 §2.3–§2.6 |

Completing the setup, a successful backup restore or "join" when already onboarded calls
`navigator.resetTo(TopLevelDestination.start)` (Flutter `pushAndRemoveUntil(AppShell)`). The splash
uses `resetTo(Intro)` or `resetTo(HomeTab)`.

### Splash (`SplashViewModel`)

The session check waits for `MigrationGate.await()` (at most 20 s), then runs
`SecureKeyStore.ensureMigrated()`, loads the profile and holds 800 ms. `SplashRouting.destinationFor(profile)`
applies `isOnboarded = hasCustomNickname || isNostrVerified || isAdminVerified`. An unreadable
secure store gives `SplashUiState.StorageUnavailable` with a retry button and never routes to
onboarding. Only one `SplashViewModel` may exist per start (spec 01 §2.1 forbids parallel runs of the
secure-storage migration). If `:app` uses `isChecking` with `installSplashScreen().setKeepOnScreenCondition`,
it creates the ViewModel with the Activity as owner and passes the same instance to the entry:
`onboardingEntries(splashViewModel = { hiltViewModel(LocalActivity.current as ComponentActivity) })`.
Without a splash condition, keep the default (entry-scoped).

### Identity setup

Steps `choose, neu, resume, passkey, existing, existingMore, nameOnly, meetup`. Covers creating a
local key (`LocalEasyAuth.register`), resuming (local key / password / passkey), adding a passkey,
the "secure your identity" sheet (backup via `BackupLauncher.export`, ncryptsec key file via SAF
`CreateDocument` or plain clipboard), Amber NIP-55, the NIP-46 `BunkerConnectSheet` (signer-app QR
with `nostrconnect://`, pasting `bunker://`, inline `auth_url`), importing nsec/ncryptsec, restoring a
backup, the name step, the meetup step (own `MeetupPickerSheet`, stores portal meetup IDs) and the
App Review sign-in (only when `AppReviewSignIn.isOffered`, i.e. `BuildConfig.DEBUG || APP_REVIEW_SIGNIN`).

Passkey ceremonies run in the route with the Activity (`PasskeyPrfService.register/unlock`). The
scrypt parts run in the ViewModel (`LocalEasyAuth.passkeyRegistration/savePasskeyWrap/passkeyCredentialIds/unlockWithPrfSecret`).

Native deviations (documented in code):
- "Neu hier" while an identity survives on the device asks for confirmation first (01 §2.3.3 MAY).
- Back on the `passkey` step means "Später" (01 §2.3.6 SHOULD), so a second key cannot overwrite the first.
- Service errors drop the Dart `Exception: ` / `FormatException: ` prefixes. A cancelled passkey shows nothing.
- The bunker sheet also has a "scan QR" button (`:core:camera`) that fills the `bunker://` field.
- An unreadable key store counts as "key present" during bootstrap, so the user is never invited to overwrite it.

## Dependencies

`:data:identity`, `:data:meetups` (meetup step), `:data:settings` (language chip), `:data:migration`
(`MigrationGate`), `:feature:backup` (`BackupLauncher`), `:core:camera` (bunker QR scan),
`androidx.activity.compose`.

Native strings: `res/values{,-en,-es}/strings.xml`, `native_onboarding_*`.

## Tests

`tools/gradlew-locked :feature:onboarding:testDebugUnitTest`: 93 tests. ViewModel tests with fakes
cover splash routing, intro, all setup paths and the bunker controller. Robolectric Compose tests cover
the screens, the sheets and the dialogs; route tests check the navigation effects (fake `Navigator`) and that app-host snackbars outlive the entry.
