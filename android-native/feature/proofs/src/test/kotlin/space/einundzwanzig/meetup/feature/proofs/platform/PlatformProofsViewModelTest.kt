package space.einundzwanzig.meetup.feature.proofs.platform

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateError
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateResult
import space.einundzwanzig.meetup.feature.proofs.testing.FakePlatformProofRepository
import space.einundzwanzig.meetup.feature.proofs.testing.FakeRepublisher

class PlatformProofsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val telegram = PlatformProof("telegram", "satoshi", "a".repeat(128), 1_700_000_000)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(
        repo: FakePlatformProofRepository = FakePlatformProofRepository(),
        republisher: FakeRepublisher = FakeRepublisher(),
    ) = PlatformProofsViewModel(repo, republisher, CoroutineScope(SupervisorJob() + dispatcher))

    private fun PlatformProofsViewModel.typeUsername(text: String) =
        state.value.sheet!!.username.setTextAndPlaceCursorAtEnd(text)

    private fun PlatformProofsViewModel.typeCustomName(text: String) =
        state.value.sheet!!.customPlatformName.setTextAndPlaceCursorAtEnd(text)

    @Test
    fun `loading until proofs arrive, then lists them in stored order`() = runTest(dispatcher) {
        val repo = FakePlatformProofRepository(initial = null)
        val vm = vm(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.isLoading)
        val nostr = PlatformProof("nostr", "n", "b".repeat(128), 1)
        repo.stored.value = listOf(telegram, nostr)
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        assertEquals(listOf(telegram, nostr), vm.state.value.proofs)
        assertEquals(setOf("telegram", "nostr"), vm.state.value.linkedPlatforms)
    }

    @Test
    fun `changing the chip clears the username but keeps the custom name`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(PlatformProofsAction.OpenSheet)
        vm.onAction(PlatformProofsAction.SelectPlatform("other"))
        vm.typeCustomName("Bisq")
        vm.typeUsername("alice")
        vm.onAction(PlatformProofsAction.SelectPlatform("telegram"))
        val sheet = vm.state.value.sheet!!
        assertEquals("telegram", sheet.selectedPlatform)
        assertEquals("", sheet.username.text.toString())
        assertEquals("Bisq", sheet.customPlatformName.text.toString())
    }

    @Test
    fun `silent validation - blank username or blank custom name does nothing`() = runTest(dispatcher) {
        val repo = FakePlatformProofRepository()
        val vm = vm(repo)
        advanceUntilIdle()
        vm.onAction(PlatformProofsAction.OpenSheet)
        vm.onAction(PlatformProofsAction.SelectPlatform("telegram"))
        vm.typeUsername("   ")
        vm.onAction(PlatformProofsAction.Create)
        vm.onAction(PlatformProofsAction.SelectPlatform("other"))
        vm.typeUsername("alice")
        vm.typeCustomName("  ")
        vm.onAction(PlatformProofsAction.Create)
        advanceUntilIdle()
        assertTrue(repo.createCalls.isEmpty())
        assertTrue(vm.state.value.sheet != null)
    }

    @Test
    fun `create trims input, closes the sheet, republishes and emits LinkSaved`() = runTest(dispatcher) {
        val repo = FakePlatformProofRepository()
        val republisher = FakeRepublisher()
        val vm = vm(repo, republisher)
        advanceUntilIdle()
        repo.createGate = CompletableDeferred()
        vm.events.test {
            vm.onAction(PlatformProofsAction.OpenSheet)
            vm.onAction(PlatformProofsAction.SelectPlatform("telegram"))
            vm.typeUsername("  satoshi ")
            vm.onAction(PlatformProofsAction.Create)
            assertNull(vm.state.value.sheet)
            assertTrue(vm.state.value.isCreating)
            advanceUntilIdle()
            assertTrue(vm.state.value.isCreating)
            repo.createGate!!.complete(Unit)
            assertEquals(PlatformProofsEvent.LinkSaved, awaitItem())
        }
        assertFalse(vm.state.value.isCreating)
        assertEquals(listOf(Triple("telegram", "satoshi", null as String?)), repo.createCalls)
        assertEquals(1, republisher.calls)
        assertEquals(listOf("telegram"), vm.state.value.proofs.map { it.platform })
    }

    @Test
    fun `leaving the screen while the signer is open still stores and republishes the proof`() = runTest(dispatcher) {
        val repo = FakePlatformProofRepository()
        val republisher = FakeRepublisher()
        val vm = vm(repo, republisher)
        advanceUntilIdle()
        repo.createGate = CompletableDeferred()
        vm.onAction(PlatformProofsAction.OpenSheet)
        vm.onAction(PlatformProofsAction.SelectPlatform("telegram"))
        vm.typeUsername("satoshi")
        vm.onAction(PlatformProofsAction.Create)
        advanceUntilIdle()
        // Back pressed during the wavy progress: Nav3 clears the ViewModel.
        vm.viewModelScope.cancel()
        repo.createGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("telegram"), repo.stored.value!!.map { it.platform })
        assertEquals(1, republisher.calls)
    }

    @Test
    fun `leaving right after confirming a revoke still removes and republishes`() = runTest(dispatcher) {
        val repo = FakePlatformProofRepository(listOf(telegram))
        val republisher = FakeRepublisher()
        val vm = vm(repo, republisher)
        advanceUntilIdle()
        vm.onAction(PlatformProofsAction.RequestRevoke(telegram))
        vm.onAction(PlatformProofsAction.ConfirmRevoke)
        vm.viewModelScope.cancel()
        advanceUntilIdle()
        assertTrue(repo.stored.value!!.isEmpty())
        assertEquals(1, republisher.calls)
    }

    @Test
    fun `custom platform passes the trimmed custom name`() = runTest(dispatcher) {
        val repo = FakePlatformProofRepository()
        val vm = vm(repo)
        vm.events.test {
            vm.onAction(PlatformProofsAction.OpenSheet)
            vm.onAction(PlatformProofsAction.SelectPlatform("other"))
            vm.typeCustomName(" Bisq Market ")
            vm.typeUsername("alice")
            vm.onAction(PlatformProofsAction.Create)
            assertEquals(PlatformProofsEvent.LinkSaved, awaitItem())
        }
        assertEquals(Triple("other", "alice", "Bisq Market"), repo.createCalls.single())
        assertEquals("bisq_market", vm.state.value.proofs.single().platform)
    }

    @Test
    fun `create failure emits the error and does not republish`() = runTest(dispatcher) {
        val repo = FakePlatformProofRepository().apply {
            createResult = ProofCreateResult.Failure(ProofCreateError.SIGNER_OR_UNKNOWN, "Permission denied")
        }
        val republisher = FakeRepublisher()
        val vm = vm(repo, republisher)
        vm.events.test {
            vm.onAction(PlatformProofsAction.OpenSheet)
            vm.onAction(PlatformProofsAction.SelectPlatform("nostr"))
            vm.typeUsername("bob")
            vm.onAction(PlatformProofsAction.Create)
            assertEquals(
                PlatformProofsEvent.CreateFailed(ProofCreateError.SIGNER_OR_UNKNOWN, "Permission denied"),
                awaitItem(),
            )
        }
        assertEquals(0, republisher.calls)
        assertFalse(vm.state.value.isCreating)
    }

    @Test
    fun `revoke - dismiss keeps the proof, confirm removes it and republishes`() = runTest(dispatcher) {
        val repo = FakePlatformProofRepository(listOf(telegram))
        val republisher = FakeRepublisher()
        val vm = vm(repo, republisher)
        advanceUntilIdle()
        vm.onAction(PlatformProofsAction.RequestRevoke(telegram))
        assertEquals(telegram, vm.state.value.pendingRevoke)
        vm.onAction(PlatformProofsAction.DismissRevoke)
        advanceUntilIdle()
        assertNull(vm.state.value.pendingRevoke)
        assertTrue(repo.removeCalls.isEmpty())

        vm.onAction(PlatformProofsAction.RequestRevoke(telegram))
        vm.onAction(PlatformProofsAction.ConfirmRevoke)
        assertNull(vm.state.value.pendingRevoke)
        advanceUntilIdle()
        assertEquals(listOf("telegram"), repo.removeCalls)
        assertEquals(1, republisher.calls)
        assertTrue(vm.state.value.proofs.isEmpty())
    }

    @Test
    fun `failed revoke write reports RevokeFailed without republishing`() = runTest(dispatcher) {
        val repo = FakePlatformProofRepository(listOf(telegram)).apply { removeSucceeds = false }
        val republisher = FakeRepublisher()
        val vm = vm(repo, republisher)
        advanceUntilIdle()
        vm.events.test {
            vm.onAction(PlatformProofsAction.RequestRevoke(telegram))
            vm.onAction(PlatformProofsAction.ConfirmRevoke)
            assertEquals(PlatformProofsEvent.RevokeFailed, awaitItem())
        }
        assertEquals(0, republisher.calls)
        assertEquals(listOf(telegram), vm.state.value.proofs)
    }

    @Test
    fun `proof date is day-month-year without padding`() {
        assertEquals("5.3.2024", proofDate(1_709_640_000, java.time.ZoneOffset.UTC))
    }
}
