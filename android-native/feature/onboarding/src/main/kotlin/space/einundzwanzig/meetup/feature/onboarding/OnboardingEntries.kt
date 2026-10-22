package space.einundzwanzig.meetup.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.IdentitySetup
import space.einundzwanzig.meetup.core.navigation.Intro
import space.einundzwanzig.meetup.core.navigation.Splash
import space.einundzwanzig.meetup.feature.onboarding.intro.IntroRoute
import space.einundzwanzig.meetup.feature.onboarding.setup.IdentitySetupRoute
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashRoute
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashViewModel

/**
 * Registers Splash, Intro and IdentitySetup (spec 01 §2.1–§2.3, spec 12 §2.3) in the app's entry provider.
 *
 * [splashViewModel] supplies the one [SplashViewModel] of the start. By default it is scoped to
 * the `Splash` entry. If `:app` also keeps the system splash on screen with
 * `installSplashScreen().setKeepOnScreenCondition { vm.isChecking }`, it must pass the same
 * instance, e.g. `{ hiltViewModel(LocalActivity.current as ComponentActivity) }`; otherwise two
 * ViewModels would run the session check (and `SecureKeyStore.ensureMigrated()`) in parallel,
 * which spec 01 §2.1 forbids.
 */
fun EntryProviderScope<NavKey>.onboardingEntries(
    splashViewModel: @Composable () -> SplashViewModel = { hiltViewModel() },
) {
    entry<Splash> { SplashRoute(splashViewModel()) }
    entry<Intro> { IntroRoute() }
    entry<IdentitySetup> { IdentitySetupRoute() }
}
