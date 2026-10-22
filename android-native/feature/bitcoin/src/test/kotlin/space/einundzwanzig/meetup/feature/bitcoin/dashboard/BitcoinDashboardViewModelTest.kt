package space.einundzwanzig.meetup.feature.bitcoin.dashboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.data.mempool.MempoolHosts
import space.einundzwanzig.meetup.feature.bitcoin.FakeCurrencyRepository
import space.einundzwanzig.meetup.feature.bitcoin.FakeMempoolConfig
import space.einundzwanzig.meetup.feature.bitcoin.FakeMempoolService
import space.einundzwanzig.meetup.feature.bitcoin.MainDispatcherRule
import space.einundzwanzig.meetup.feature.bitcoin.SchedulerTime
import space.einundzwanzig.meetup.feature.bitcoin.dashboard

@OptIn(ExperimentalCoroutinesApi::class)
class BitcoinDashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val service = FakeMempoolService()
    private val config = FakeMempoolConfig()
    private val currency = FakeCurrencyRepository()

    private fun TestScope.vm(): BitcoinDashboardViewModel {
        val vm = BitcoinDashboardViewModel(service, config, currency, SchedulerTime(testScheduler))
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun `starts with the cached dashboard and loading`() = runTest(dispatcher) {
        val cached = dashboard(height = 900_000)
        service.lastDashboard.value = cached
        val vm = vm()
        runCurrent()
        val s = vm.uiState.value
        assertEquals(cached, s.data)
        assertTrue(s.loading)
        assertFalse(s.showFullScreenLoading)
        assertEquals(DashboardStatus.LIVE, s.status)
    }

    @Test
    fun `no cache shows the full-screen loading state`() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        assertNull(vm.uiState.value.data)
        assertTrue(vm.uiState.value.showFullScreenLoading)
        assertEquals(DashboardStatus.UNKNOWN, vm.uiState.value.status)
    }

    @Test
    fun `visible screen loads at once, glows for 2 s and refreshes every 60 s`() = runTest(dispatcher) {
        service.nextDashboard = dashboard(height = 912_000)
        val vm = vm()
        val auto = launch { vm.autoRefreshWhileVisible() }
        runCurrent()
        assertEquals(1, service.dashboardCalls)
        assertEquals(912_000, vm.uiState.value.data?.blockHeight)
        assertFalse(vm.uiState.value.loading)
        assertTrue(vm.uiState.value.fresh)
        advanceTimeBy(2_001)
        assertFalse(vm.uiState.value.fresh)
        service.nextDashboard = dashboard(height = 912_001)
        advanceTimeBy(58_000)
        assertEquals(2, service.dashboardCalls)
        assertEquals(912_001, vm.uiState.value.data?.blockHeight)
        advanceTimeBy(60_000)
        assertEquals(3, service.dashboardCalls)
        auto.cancel()
    }

    @Test
    fun `returning within a minute does not reload, after the settings it does`() = runTest(dispatcher) {
        val vm = vm()
        var auto = launch { vm.autoRefreshWhileVisible() }
        runCurrent()
        assertEquals(1, service.dashboardCalls)
        auto.cancel()
        advanceTimeBy(10_000)

        auto = launch { vm.autoRefreshWhileVisible() }
        runCurrent()
        assertEquals(1, service.dashboardCalls)
        auto.cancel()

        vm.onAction(BitcoinDashboardAction.ChangeServer)
        auto = launch { vm.autoRefreshWhileVisible() }
        runCurrent()
        assertEquals(2, service.dashboardCalls)
        auto.cancel()
    }

    @Test
    fun `returning after more than a minute reloads`() = runTest(dispatcher) {
        val vm = vm()
        var auto = launch { vm.autoRefreshWhileVisible() }
        runCurrent()
        auto.cancel()
        advanceTimeBy(61_000)
        auto = launch { vm.autoRefreshWhileVisible() }
        runCurrent()
        assertEquals(2, service.dashboardCalls)
        auto.cancel()
    }

    @Test
    fun `pull to refresh shows the indicator until the load finished`() = runTest(dispatcher) {
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        service.dashboardGate = gate
        vm.onAction(BitcoinDashboardAction.PullRefresh)
        runCurrent()
        assertTrue(vm.uiState.value.refreshing)
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.refreshing)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `refresh while a load runs does not start a second one`() = runTest(dispatcher) {
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        service.dashboardGate = gate
        vm.onAction(BitcoinDashboardAction.Refresh)
        runCurrent()
        vm.onAction(BitcoinDashboardAction.Refresh)
        runCurrent()
        assertEquals(1, service.dashboardCalls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, service.dashboardCalls)
    }

    @Test
    fun `a source change during a running load loads again afterwards`() = runTest(dispatcher) {
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        service.dashboardGate = gate
        vm.onAction(BitcoinDashboardAction.Refresh)
        runCurrent()
        vm.onAction(BitcoinDashboardAction.ChangeServer)
        val auto = launch { vm.autoRefreshWhileVisible() }
        runCurrent()
        assertEquals(1, service.dashboardCalls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, service.dashboardCalls)
        auto.cancel()
    }

    @Test
    fun `dead data keeps the values and reports the dead status`() = runTest(dispatcher) {
        service.nextDashboard = dashboard(sourcesOk = 0, blocked = true, lastError = "HTTP 403")
        val vm = vm()
        vm.onAction(BitcoinDashboardAction.Refresh)
        runCurrent()
        val s = vm.uiState.value
        assertEquals(DashboardStatus.DEAD, s.status)
        assertTrue(s.data!!.blocked)
        assertEquals(912_345, s.data!!.blockHeight)
    }

    @Test
    fun `currency and footer host follow their repositories`() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        assertEquals("EUR", vm.uiState.value.currency)
        assertEquals("mempool.space", vm.uiState.value.hostLabel)
        currency.current.value = "CHF"
        config.setSessionOverride("https://mempool.emzy.de")
        runCurrent()
        assertEquals("CHF", vm.uiState.value.currency)
        assertEquals(MempoolHosts.label("https://mempool.emzy.de"), vm.uiState.value.hostLabel)
    }

    @Test
    fun `status mapping`() {
        assertEquals(DashboardStatus.UNKNOWN, DashboardStatus.of(null))
        assertEquals(DashboardStatus.DEAD, DashboardStatus.of(dashboard(sourcesOk = 0)))
        assertEquals(DashboardStatus.PARTIAL, DashboardStatus.of(dashboard(sourcesOk = 1)))
        assertEquals(DashboardStatus.PARTIAL, DashboardStatus.of(dashboard(sourcesOk = 5)))
        assertEquals(DashboardStatus.LIVE, DashboardStatus.of(dashboard(sourcesOk = 6)))
    }
}
