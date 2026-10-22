package space.einundzwanzig.meetup.feature.profile.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.nostr.relay.RelayAddResult
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.feature.profile.common.SnackbarMessage
import space.einundzwanzig.meetup.feature.profile.common.UiText
import space.einundzwanzig.meetup.feature.profile.testing.FakeRelayConfig

@OptIn(ExperimentalCoroutinesApi::class)
class RelaySettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val config = FakeRelayConfig()
    private val snackbars = mutableListOf<SnackbarMessage>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): RelaySettingsViewModel {
        val vm = RelaySettingsViewModel(config)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.snackbars.collect { snackbars += it } }
        advanceUntilIdle()
        return vm
    }

    private fun ids() = snackbars.map { (it.text as UiText.Res).id }

    @Test
    fun `loads the defaults in fixed order, custom relays and the active count`() = runTest(dispatcher) {
        config.disabled += RelayConfig.DEFAULT_RELAYS[1]
        config.custom += "wss://mein-relay.de"
        val s = vm().state.value
        assertFalse(s.isLoading)
        assertEquals(RelayConfig.DEFAULT_RELAYS, s.defaultRelays.map { it.url })
        assertEquals(listOf(true, false, true, true), s.defaultRelays.map { it.enabled })
        assertEquals(listOf("wss://mein-relay.de"), s.customRelays)
        assertEquals(RelayConfig.DEFAULT_RELAYS.size, s.activeCount)
    }

    @Test
    fun `toggle and remove persist immediately and reload`() = runTest(dispatcher) {
        config.custom += "wss://x.io"
        val vm = vm()
        RelayConfig.DEFAULT_RELAYS.forEach { vm.onAction(RelaySettingsAction.ToggleDefault(it, false)) }
        advanceUntilIdle()
        assertEquals(1, vm.state.value.activeCount)
        vm.onAction(RelaySettingsAction.RemoveCustom("wss://x.io"))
        advanceUntilIdle()
        assertEquals(0, vm.state.value.activeCount)
        assertTrue(vm.state.value.customRelays.isEmpty())
        assertTrue(snackbars.isEmpty()) // no snackbar for delete
    }

    @Test
    fun `adding tests first, then closes the dialog with a green snackbar`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        config.addGate = gate
        val vm = vm()
        vm.onAction(RelaySettingsAction.OpenAddDialog)
        vm.onAction(RelaySettingsAction.AddRelay("wss://neu.io"))
        advanceUntilIdle()
        assertTrue(vm.state.value.isTestingRelay)
        assertEquals(listOf(L10nR.string.rsTesting), ids())
        vm.onAction(RelaySettingsAction.AddRelay("wss://neu.io")) // ignored while testing
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("wss://neu.io"), config.added)
        val s = vm.state.value
        assertFalse(s.isTestingRelay)
        assertFalse(s.addDialogOpen)
        assertEquals(listOf("wss://neu.io"), s.customRelays)
        assertEquals(listOf(L10nR.string.rsTesting, L10nR.string.rsRelayAdded), ids())
        assertEquals(Tone.Success, snackbars.last().tone)
        assertTrue(snackbars.last().replaceCurrent)
    }

    @Test
    fun `invalid and unreachable keep the dialog open, already present closes it`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(RelaySettingsAction.OpenAddDialog)
        config.addResult = RelayAddResult.INVALID_URL
        vm.onAction(RelaySettingsAction.AddRelay("http://x"))
        advanceUntilIdle()
        assertTrue(vm.state.value.addDialogOpen)
        assertEquals(L10nR.string.rsInvalidUrl, ids().last())
        assertEquals(Tone.Error, snackbars.last().tone)
        config.addResult = RelayAddResult.UNREACHABLE
        vm.onAction(RelaySettingsAction.AddRelay("wss://tot.io"))
        advanceUntilIdle()
        assertTrue(vm.state.value.addDialogOpen)
        assertEquals(L10nR.string.rsRelayUnreachable, ids().last())
        config.addResult = RelayAddResult.ALREADY_PRESENT
        vm.onAction(RelaySettingsAction.AddRelay(RelayConfig.DEFAULT_RELAYS[0]))
        advanceUntilIdle()
        assertFalse(vm.state.value.addDialogOpen)
        assertEquals(L10nR.string.rsRelayAlreadyAdded, ids().last())
        assertEquals(Tone.Neutral, snackbars.last().tone)
    }

    @Test
    fun `dismissing during a test resets the testing flag`() = runTest(dispatcher) {
        config.addGate = CompletableDeferred()
        val vm = vm()
        vm.onAction(RelaySettingsAction.OpenAddDialog)
        vm.onAction(RelaySettingsAction.AddRelay("wss://a.io"))
        advanceUntilIdle()
        vm.onAction(RelaySettingsAction.DismissAddDialog)
        assertFalse(vm.state.value.isTestingRelay)
        assertFalse(vm.state.value.addDialogOpen)
    }

    @Test
    fun `a stale test does not touch a reopened dialog but still reports and reloads`() = runTest(dispatcher) {
        val first = CompletableDeferred<Unit>()
        config.addGate = first
        config.addResult = RelayAddResult.ALREADY_PRESENT
        val vm = vm()
        vm.onAction(RelaySettingsAction.OpenAddDialog)
        vm.onAction(RelaySettingsAction.AddRelay("wss://a.io"))
        advanceUntilIdle()
        vm.onAction(RelaySettingsAction.DismissAddDialog)
        vm.onAction(RelaySettingsAction.OpenAddDialog)
        val second = CompletableDeferred<Unit>()
        config.addGate = second
        vm.onAction(RelaySettingsAction.AddRelay("wss://b.io"))
        advanceUntilIdle()
        assertTrue(vm.state.value.isTestingRelay)

        first.complete(Unit)
        advanceUntilIdle()
        // The first test finished: the new dialog stays open and its test keeps the button disabled.
        assertTrue(vm.state.value.addDialogOpen)
        assertTrue(vm.state.value.isTestingRelay)
        assertEquals(L10nR.string.rsRelayAlreadyAdded, ids().last())

        config.addResult = RelayAddResult.ADDED
        second.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.addDialogOpen)
        assertFalse(vm.state.value.isTestingRelay)
        assertTrue("wss://b.io" in vm.state.value.customRelays)
    }
}
