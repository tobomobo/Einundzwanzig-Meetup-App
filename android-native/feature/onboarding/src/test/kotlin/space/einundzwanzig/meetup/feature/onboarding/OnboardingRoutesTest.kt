package space.einundzwanzig.meetup.feature.onboarding

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.navigation.IdentitySetup
import space.einundzwanzig.meetup.core.navigation.Intro
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.navigation.TopLevelDestination
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.feature.onboarding.intro.IntroRoute
import space.einundzwanzig.meetup.feature.onboarding.intro.IntroTags
import space.einundzwanzig.meetup.feature.onboarding.intro.IntroViewModel
import space.einundzwanzig.meetup.feature.onboarding.setup.IdentitySetupRoute
import space.einundzwanzig.meetup.feature.onboarding.setup.IdentitySetupViewModel
import space.einundzwanzig.meetup.feature.onboarding.setup.SetupAction
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashRoute
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashViewModel

/** Robolectric tests of the stateful routes: navigation effects and the app-level snackbar. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OnboardingRoutesTest {

    @get:Rule
    val rule = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private val navigator = RecordingNavigator()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun splashVm(profile: UserProfile) =
        SplashViewModel(FakeMigrationGate(), FakeSecureKeyStore(), FakeUserProfileRepository(profile))

    private fun showSplash(vm: SplashViewModel) {
        rule.setContent {
            EinundzwanzigTheme { CompositionLocalProvider(LocalNavigator provides navigator) { SplashRoute(vm) } }
        }
        scheduler.advanceUntilIdle()
        rule.waitForIdle()
    }

    @Test
    fun splashReadyIntroResetsToIntro() {
        showSplash(splashVm(UserProfile()))
        assertEquals(listOf<NavKey>(Intro), navigator.resets)
    }

    @Test
    fun splashReadyHomeResetsToTheShell() {
        showSplash(splashVm(UserProfile(nickname = "Hal")))
        assertEquals(listOf(TopLevelDestination.start), navigator.resets)
    }

    @Test
    fun setupGoHomeResetsToTheShell() {
        val backup = FakeBackupLauncher().apply { restoreResult = true }
        val profiles = FakeUserProfileRepository()
        val vm = IdentitySetupViewModel(
            FakeSigningService(), FakeSecureKeyStore(), FakeLocalKeyVault(), FakeNostrKeyService(),
            FakeLocalEasyAuth(FakeLocalKeyVault(), profiles), FakePasskeyPrfService(), profiles,
            FakeAppReviewSignIn(), FakeMeetupService(), backup, FakeKeyFileWriter(), FixedTime(0),
        )
        rule.setContent {
            EinundzwanzigTheme { CompositionLocalProvider(LocalNavigator provides navigator) { IdentitySetupRoute(vm) } }
        }
        rule.waitForIdle()
        vm.onAction(SetupAction.RestoreBackup)
        scheduler.advanceUntilIdle()
        rule.waitForIdle()
        assertEquals(listOf(TopLevelDestination.start), navigator.resets)
    }

    @Test
    fun introSnackbarSurvivesLeavingTheIntro() {
        val appHost = SnackbarHostState()
        var showIntro by mutableStateOf(true)
        navigator.onNavigate = { showIntro = false }
        val vm = IntroViewModel(FakeUserProfileRepository(), FakeBackupLauncher(), FakeLocaleController())
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator, LocalAppSnackbarHostState provides appHost) {
                    if (showIntro) IntroRoute(vm)
                }
            }
        }
        rule.onNodeWithTag(IntroTags.JOIN).performScrollTo().performClick()
        scheduler.advanceUntilIdle()
        rule.waitForIdle()
        // The intro entry is gone (setup pushed), but the "set your identity" hint stays.
        assertEquals(listOf<NavKey>(IdentitySetup), navigator.pushed)
        assertEquals(false, showIntro)
        assertNotNull(appHost.currentSnackbarData)
    }
}

private class RecordingNavigator : Navigator {
    val pushed = mutableListOf<NavKey>()
    val resets = mutableListOf<NavKey>()
    var onNavigate: (NavKey) -> Unit = {}
    override fun navigate(key: NavKey) {
        pushed += key
        onNavigate(key)
    }
    override fun replaceTop(key: NavKey) = Unit
    override fun back() = Unit
    override fun backTo(key: NavKey, inclusive: Boolean) = Unit
    override fun resetTo(key: NavKey) {
        resets += key
    }
    override fun navigateFromRoot(key: NavKey) = Unit
    override fun openUrl(url: String) = Unit
}
