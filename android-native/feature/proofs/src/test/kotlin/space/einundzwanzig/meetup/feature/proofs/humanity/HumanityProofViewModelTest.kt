package space.einundzwanzig.meetup.feature.proofs.humanity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityOutcome
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityStatus
import space.einundzwanzig.meetup.data.proofs.humanity.LastChecked
import space.einundzwanzig.meetup.feature.proofs.testing.FakeHumanityProofRepository
import space.einundzwanzig.meetup.feature.proofs.testing.FakeRepublisher
import space.einundzwanzig.meetup.feature.proofs.testing.FakeTimeSource

class HumanityProofViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeTimeSource(now = 1_700_000_000_000)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loading until the status arrives`() = runTest(dispatcher) {
        val repo = FakeHumanityProofRepository(initial = null)
        val vm = HumanityProofViewModel(repo, FakeRepublisher(), clock)
        advanceUntilIdle()
        assertTrue(vm.state.value.isLoading)
        repo.current.value = HumanityStatus(verified = false)
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.verified)
        assertNull(vm.state.value.lastChecked)
    }

    @Test
    fun `last check is relative to the clock and hidden when never checked`() = runTest(dispatcher) {
        val repo = FakeHumanityProofRepository(HumanityStatus(verified = false, lastCheckedAt = 1_700_000_000 - 5 * 60))
        val vm = HumanityProofViewModel(repo, FakeRepublisher(), clock)
        advanceUntilIdle()
        assertEquals(LastChecked.MinutesAgo(5), vm.state.value.lastChecked)
    }

    @Test
    fun `refreshClock recomputes the relative time after the app was in the background`() = runTest(dispatcher) {
        val repo = FakeHumanityProofRepository(HumanityStatus(verified = false, lastCheckedAt = 1_700_000_000 - 5 * 60))
        val vm = HumanityProofViewModel(repo, FakeRepublisher(), clock)
        advanceUntilIdle()
        assertEquals(LastChecked.MinutesAgo(5), vm.state.value.lastChecked)
        clock.now += 3 * 60 * 60 * 1000L
        vm.refreshClock()
        assertEquals(LastChecked.HoursAgo(3), vm.state.value.lastChecked)
    }

    @Test
    fun `pending re-verification shows not verified`() = runTest(dispatcher) {
        // HumanityStatus forces verified=false while needsReverification is set.
        val repo = FakeHumanityProofRepository(HumanityStatus(verified = false, firstZapAt = 5, needsReverification = true))
        val vm = HumanityProofViewModel(repo, FakeRepublisher(), clock)
        advanceUntilIdle()
        assertFalse(vm.state.value.verified)
    }

    @Test
    fun `check not found - shows the result, keeps unverified, no publish`() = runTest(dispatcher) {
        val repo = FakeHumanityProofRepository().apply { checkGate = CompletableDeferred() }
        val republisher = FakeRepublisher()
        val vm = HumanityProofViewModel(repo, republisher, clock)
        advanceUntilIdle()
        vm.onAction(HumanityProofAction.Check)
        assertTrue(vm.state.value.isChecking)
        assertNull(vm.state.value.result)
        advanceUntilIdle()
        vm.onAction(HumanityProofAction.Check) // ignored while checking
        repo.checkGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repo.checkCalls)
        assertFalse(vm.state.value.isChecking)
        assertEquals(HumanityOutcome.NOT_FOUND, vm.state.value.result)
        assertFalse(vm.state.value.resultSuccess)
        assertFalse(vm.state.value.verified)
        assertEquals(LastChecked.JustNow, vm.state.value.lastChecked)
        assertEquals(0, republisher.calls)
    }

    @Test
    fun `check found - verified, success result and a forced republish`() = runTest(dispatcher) {
        val repo = FakeHumanityProofRepository().apply { nextOutcome = HumanityOutcome.FOUND }
        val republisher = FakeRepublisher()
        val vm = HumanityProofViewModel(repo, republisher, clock)
        advanceUntilIdle()
        vm.onAction(HumanityProofAction.Check)
        advanceUntilIdle()
        assertTrue(vm.state.value.verified)
        assertTrue(vm.state.value.resultSuccess)
        assertTrue(vm.state.value.firstZapDate.isNotEmpty())
        assertEquals(1, republisher.calls)
    }

    @Test
    fun `check error is reported as a retryable outcome`() = runTest(dispatcher) {
        val repo = FakeHumanityProofRepository(HumanityStatus(verified = true, firstZapAt = 1)).apply {
            nextOutcome = HumanityOutcome.ERROR
        }
        val republisher = FakeRepublisher()
        val vm = HumanityProofViewModel(repo, republisher, clock)
        advanceUntilIdle()
        vm.onAction(HumanityProofAction.Check)
        advanceUntilIdle()
        assertEquals(HumanityOutcome.ERROR, vm.state.value.result)
        assertTrue(vm.state.value.verified) // a previous verification stays
        assertEquals(0, republisher.calls)
    }

    @Test
    fun `pieces are joined with exactly one space`() {
        assertEquals("a b c", joinPieces("a ", "b", " c"))
    }
}
