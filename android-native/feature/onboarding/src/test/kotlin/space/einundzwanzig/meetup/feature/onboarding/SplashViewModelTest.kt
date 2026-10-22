package space.einundzwanzig.meetup.feature.onboarding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.data.identity.SecureStorageUnreadableException
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashDestination
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashRouting
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashUiState
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val keyStore = FakeSecureKeyStore()
    private val profiles = FakeUserProfileRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `isOnboarded derivation matches the Dart vectors`() {
        assertEquals(SplashDestination.INTRO, SplashRouting.destinationFor(UserProfile()))
        assertEquals(SplashDestination.INTRO, SplashRouting.destinationFor(UserProfile(nickname = "")))
        assertEquals(SplashDestination.INTRO, SplashRouting.destinationFor(UserProfile(nickname = "  Anon ")))
        assertEquals(SplashDestination.HOME, SplashRouting.destinationFor(UserProfile(nickname = "Satoshi")))
        assertEquals(SplashDestination.HOME, SplashRouting.destinationFor(UserProfile(isNostrVerified = true)))
        assertEquals(SplashDestination.HOME, SplashRouting.destinationFor(UserProfile(isAdminVerified = true)))
        // A key alone does not make a user onboarded.
        assertEquals(SplashDestination.INTRO, SplashRouting.destinationFor(UserProfile(hasNostrKey = true)))
    }

    @Test
    fun `new user goes to intro after the minimum splash time`() = runTest(dispatcher) {
        val vm = SplashViewModel(FakeMigrationGate(), keyStore, profiles)
        runCurrent()
        assertTrue(vm.isChecking)
        advanceTimeBy(SplashViewModel.MIN_SPLASH_MILLIS - 1)
        runCurrent()
        assertEquals(SplashUiState.Checking, vm.state.value)
        advanceUntilIdle()
        assertEquals(SplashUiState.Ready(SplashDestination.INTRO), vm.state.value)
        assertFalse(vm.isChecking)
        assertEquals(1, keyStore.ensureMigratedCalls)
    }

    @Test
    fun `onboarded user goes home`() = runTest(dispatcher) {
        profiles.current = UserProfile(nickname = "Hal")
        val vm = SplashViewModel(FakeMigrationGate(), keyStore, profiles)
        advanceUntilIdle()
        assertEquals(SplashUiState.Ready(SplashDestination.HOME), vm.state.value)
    }

    @Test
    fun `waits for the Flutter migration before reading secrets`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = SplashViewModel(FakeMigrationGate(gate), keyStore, profiles)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, keyStore.ensureMigratedCalls)
        assertEquals(0, profiles.loads)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, keyStore.ensureMigratedCalls)
        assertTrue(vm.state.value is SplashUiState.Ready)
    }

    @Test
    fun `a hanging migration does not block the splash forever`() = runTest(dispatcher) {
        val vm = SplashViewModel(FakeMigrationGate(CompletableDeferred()), keyStore, profiles)
        advanceUntilIdle()
        assertEquals(SplashUiState.Ready(SplashDestination.INTRO), vm.state.value)
    }

    @Test
    fun `unreadable secure storage never routes to onboarding and retry recovers`() = runTest(dispatcher) {
        profiles.failure = SecureStorageUnreadableException("nostr_nsec_key", isPermanent = false, cause = null)
        val vm = SplashViewModel(FakeMigrationGate(), keyStore, profiles)
        advanceUntilIdle()
        assertEquals(SplashUiState.StorageUnavailable, vm.state.value)

        profiles.failure = null
        profiles.current = UserProfile(nickname = "Hal")
        vm.retry()
        assertEquals(SplashUiState.Checking, vm.state.value)
        advanceUntilIdle()
        assertEquals(SplashUiState.Ready(SplashDestination.HOME), vm.state.value)
    }
}
