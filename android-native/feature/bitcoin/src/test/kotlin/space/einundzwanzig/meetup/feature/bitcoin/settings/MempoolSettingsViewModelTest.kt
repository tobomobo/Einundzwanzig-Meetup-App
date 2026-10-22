package space.einundzwanzig.meetup.feature.bitcoin.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.data.mempool.HostTestFailure
import space.einundzwanzig.meetup.data.mempool.MempoolHosts
import space.einundzwanzig.meetup.data.mempool.MempoolHttpException
import space.einundzwanzig.meetup.data.mempool.MempoolMode
import space.einundzwanzig.meetup.feature.bitcoin.FakeLog
import space.einundzwanzig.meetup.feature.bitcoin.FakeMempoolConfig
import space.einundzwanzig.meetup.feature.bitcoin.FakeMempoolService
import space.einundzwanzig.meetup.feature.bitcoin.MainDispatcherRule
import space.einundzwanzig.meetup.feature.bitcoin.dashboard
import java.net.SocketTimeoutException

class MempoolSettingsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val service = FakeMempoolService().apply { lastDashboard.value = dashboard() }
    private val log = FakeLog()

    private fun vm(config: FakeMempoolConfig = FakeMempoolConfig()): MempoolSettingsViewModel {
        val vm = MempoolSettingsViewModel(config, service, CoroutineScope(main.dispatcher), log)
        // uiState is WhileSubscribed: keep one subscriber (unconfined main dispatcher).
        CoroutineScope(main.dispatcher).launch { vm.uiState.collect {} }
        return vm
    }

    private fun MempoolSettingsViewModel.state(): MempoolSettingsUiState = uiState.value

    @Test
    fun `opens with the persisted mode`() {
        assertEquals(MempoolMode.CLEARNET, vm().state().mode)
        assertEquals(MempoolMode.TOR, vm(FakeMempoolConfig(MempoolHosts.TOR)).state().mode)
    }

    @Test
    fun `custom mode fills the field with the persisted host`() {
        val vm = vm(FakeMempoolConfig("https://umbrel.local:3006"))
        val s = vm.state()
        assertEquals(MempoolMode.CUSTOM, s.mode)
        assertEquals("https://umbrel.local:3006", s.customText)
        assertEquals(0, s.customRevision)
    }

    @Test
    fun `persisted mode is shown before the config load finishes`() {
        val config = FakeMempoolConfig(MempoolHosts.TOR).apply { loadGate = CompletableDeferred() }
        assertEquals(MempoolMode.TOR, vm(config).state().mode)
    }

    @Test
    fun `user edits before the config load finishes are not overwritten`() {
        val gate = CompletableDeferred<Unit>()
        val config = FakeMempoolConfig("https://a.local").apply { loadGate = gate }
        val vm = vm(config)
        vm.onAction(MempoolSettingsAction.CustomChanged("https://b.local"))
        gate.complete(Unit)
        assertEquals("https://b.local", vm.state().customText)
        assertEquals(MempoolMode.CUSTOM, vm.state().mode)
    }

    @Test
    fun `mode writes run in the application scope`() {
        val config = FakeMempoolConfig()
        val appScope = TestScope(StandardTestDispatcher())
        val vm = MempoolSettingsViewModel(config, service, appScope, log)
        vm.onAction(MempoolSettingsAction.Select(MempoolMode.TOR))
        appScope.testScheduler.advanceUntilIdle()
        assertEquals(MempoolHosts.TOR, config.persistedHost)
        assertNull(service.lastDashboard.value)
    }

    @Test
    fun `quick taps persist in tap order so the last card wins`() {
        val config = FakeMempoolConfig()
        val appScope = TestScope(StandardTestDispatcher())
        val vm = MempoolSettingsViewModel(config, service, appScope, log)
        vm.onAction(MempoolSettingsAction.Select(MempoolMode.TOR))
        vm.onAction(MempoolSettingsAction.Select(MempoolMode.CLEARNET))
        appScope.testScheduler.advanceUntilIdle()
        assertEquals(listOf(MempoolHosts.TOR, MempoolHosts.CLEARNET), config.setHostCalls)
        assertEquals(MempoolHosts.CLEARNET, config.persistedHost)
    }

    @Test
    fun `select and save keep their order`() {
        val config = FakeMempoolConfig()
        val appScope = TestScope(StandardTestDispatcher())
        val vm = MempoolSettingsViewModel(config, service, appScope, log)
        vm.onAction(MempoolSettingsAction.CustomChanged("https://node.local"))
        vm.onAction(MempoolSettingsAction.SaveCustom)
        vm.onAction(MempoolSettingsAction.Select(MempoolMode.TOR))
        appScope.testScheduler.advanceUntilIdle()
        assertEquals(MempoolHosts.TOR, config.persistedHost)
    }

    @Test
    fun `failed save is logged and emits no Saved event`() = runTest {
        val config = FakeMempoolConfig().apply { setHostError = java.io.IOException("disk") }
        val vm = vm(config)
        val events = mutableListOf<MempoolSettingsEvent>()
        val job = launch { vm.uiEvents.collect { events += it } }
        vm.onAction(MempoolSettingsAction.CustomChanged("https://node.local"))
        vm.onAction(MempoolSettingsAction.SaveCustom)
        testScheduler.advanceUntilIdle()
        assertTrue(events.isEmpty())
        assertEquals(1, log.warnings)
        assertEquals("https://node.local", vm.state().customText)
        job.cancel()
    }

    @Test
    fun `selecting another card drops a running connection test`() {
        val gate = CompletableDeferred<Int>()
        val slow = object : space.einundzwanzig.meetup.data.mempool.MempoolService by service {
            override suspend fun testHost(rawHost: String): Int = gate.await()
        }
        val vm = MempoolSettingsViewModel(FakeMempoolConfig(), slow, CoroutineScope(main.dispatcher), log)
        CoroutineScope(main.dispatcher).launch { vm.uiState.collect {} }
        vm.onAction(MempoolSettingsAction.Test)
        assertTrue(vm.state().testing)
        vm.onAction(MempoolSettingsAction.Select(MempoolMode.TOR))
        gate.complete(912_345)
        val s = vm.state()
        assertFalse(s.testing)
        assertNull(s.testResult)
    }

    @Test
    fun `selecting tor persists it and forgets the last dashboard`() {
        val config = FakeMempoolConfig()
        val vm = vm(config)
        vm.onAction(MempoolSettingsAction.Select(MempoolMode.TOR))
        assertEquals(MempoolHosts.TOR, config.persistedHost)
        assertNull(service.lastDashboard.value)
        assertEquals(MempoolMode.TOR, vm.state().mode)
        assertEquals(MempoolHosts.TOR, vm.state().activeHost)
    }

    @Test
    fun `selecting custom with an invalid url only changes the selection`() {
        val config = FakeMempoolConfig()
        val vm = vm(config)
        vm.onAction(MempoolSettingsAction.CustomChanged("http://"))
        vm.onAction(MempoolSettingsAction.Select(MempoolMode.CUSTOM))
        assertEquals(MempoolMode.CUSTOM, vm.state().mode)
        assertTrue(config.setHostCalls.isEmpty())
        assertEquals(0, service.clearCalls)
    }

    @Test
    fun `selecting a mode clears the test result`() {
        val vm = vm()
        vm.onAction(MempoolSettingsAction.Test)
        assertTrue(vm.state().testResult is MempoolTestResult.Ok)
        vm.onAction(MempoolSettingsAction.Select(MempoolMode.TOR))
        assertNull(vm.state().testResult)
    }

    @Test
    fun `saving an invalid url shows the invalid hint`() {
        val config = FakeMempoolConfig()
        val vm = vm(config)
        vm.onAction(MempoolSettingsAction.Select(MempoolMode.CUSTOM))
        config.setHostCalls.clear()
        vm.onAction(MempoolSettingsAction.CustomChanged("http://"))
        vm.onAction(MempoolSettingsAction.SaveCustom)
        assertEquals(MempoolTestResult.InvalidUrl, vm.state().testResult)
        assertTrue(config.setHostCalls.isEmpty())
    }

    @Test
    fun `saving a valid url persists the normalized host and emits Saved`() = runTest {
        val config = FakeMempoolConfig()
        val vm = vm(config)
        val events = mutableListOf<MempoolSettingsEvent>()
        val job = launch { vm.uiEvents.collect { events += it } }
        vm.onAction(MempoolSettingsAction.Select(MempoolMode.CUSTOM))
        vm.onAction(MempoolSettingsAction.CustomChanged("umbrel.local:3006/"))
        vm.onAction(MempoolSettingsAction.SaveCustom)
        testScheduler.advanceUntilIdle()
        val expected = MempoolHosts.normalize("umbrel.local:3006/")
        assertEquals(expected, config.persistedHost)
        val s = vm.state()
        assertEquals(expected, s.customText)
        assertTrue(s.customRevision > 0)
        assertNull(s.testResult)
        assertEquals(listOf<MempoolSettingsEvent>(MempoolSettingsEvent.Saved), events)
        job.cancel()
    }

    @Test
    fun `connection test succeeds with the block height`() {
        val vm = vm()
        vm.onAction(MempoolSettingsAction.Test)
        val s = vm.state()
        assertFalse(s.testing)
        assertEquals(MempoolTestResult.Ok(912_345), s.testResult)
        assertEquals(listOf(MempoolHosts.CLEARNET), service.testedHosts)
    }

    @Test
    fun `connection test uses the unsaved custom text`() {
        val vm = vm()
        vm.onAction(MempoolSettingsAction.Select(MempoolMode.CUSTOM))
        vm.onAction(MempoolSettingsAction.CustomChanged("node.local"))
        vm.onAction(MempoolSettingsAction.Test)
        assertEquals(listOf(MempoolHosts.normalize("node.local")), service.testedHosts)
    }

    @Test
    fun `empty custom text tests mempool space`() {
        val vm = vm()
        vm.onAction(MempoolSettingsAction.Select(MempoolMode.CUSTOM))
        assertEquals(MempoolHosts.CLEARNET, vm.selectedHost())
    }

    @Test
    fun `blocked http answer is classified`() {
        service.testHostResult = { throw MempoolHttpException("/blocks/tip/height", 403, "") }
        val vm = vm()
        vm.onAction(MempoolSettingsAction.Test)
        assertEquals(MempoolTestResult.Failed(HostTestFailure.Blocked(403)), vm.state().testResult)
    }

    @Test
    fun `other http answer is classified`() {
        service.testHostResult = { throw MempoolHttpException("/blocks/tip/height", 500, "boom") }
        val vm = vm()
        vm.onAction(MempoolSettingsAction.Test)
        assertEquals(MempoolTestResult.Failed(HostTestFailure.Http(500)), vm.state().testResult)
    }

    @Test
    fun `network failure on tor is the onion hint, else the type name`() {
        service.testHostResult = { throw SocketTimeoutException("t") }
        val tor = vm(FakeMempoolConfig(MempoolHosts.TOR))
        tor.onAction(MempoolSettingsAction.Test)
        assertEquals(MempoolTestResult.Failed(HostTestFailure.OnionUnreachable), tor.state().testResult)

        val clear = vm()
        clear.onAction(MempoolSettingsAction.Test)
        assertEquals(MempoolTestResult.Failed(HostTestFailure.Other("SocketTimeoutException")), clear.state().testResult)
    }
}
