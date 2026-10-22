package space.einundzwanzig.meetup.feature.onboarding

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.IdentitySetup
import space.einundzwanzig.meetup.core.navigation.Intro
import space.einundzwanzig.meetup.core.navigation.Splash
import space.einundzwanzig.meetup.feature.onboarding.intro.IntroRoute
import space.einundzwanzig.meetup.feature.onboarding.setup.IdentitySetupRoute
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashRoute

/** Registers Splash, Intro and IdentitySetup (spec 01 §2.1–§2.3, spec 12 §2.3) in the app's entry provider. */
fun EntryProviderScope<NavKey>.onboardingEntries() {
    entry<Splash> { SplashRoute() }
    entry<Intro> { IntroRoute() }
    entry<IdentitySetup> { IdentitySetupRoute() }
}
