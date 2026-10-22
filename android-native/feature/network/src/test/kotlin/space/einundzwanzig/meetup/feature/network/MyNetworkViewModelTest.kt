package space.einundzwanzig.meetup.feature.network

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkBuildEvent
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class MyNetworkViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val source = FakeNetworkSource()
    private val time = FakeTime(Instant.parse("2026-10-21T18:00:00Z").toEpochMilli())

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): MyNetworkViewModel =
        MyNetworkViewModel(source, time).also {
            it.zone = ZoneOffset.UTC
            advanceUntilIdle()
        }

    private fun stage(net: MyNetwork, depth: Int) = NetworkBuildEvent.Stage(net.copy(updatedAt = null), depth)

    @Test
    fun `without profile npub the empty state is shown and nothing is built`() = runTest(dispatcher) {
        source.npub = ""
        val vm = vm()
        val s = vm.state.value
        assertFalse(s.loading)
        assertNull(s.network)
        assertEquals(MyNetworkUiState.Body.Empty, s.body)
        assertTrue(source.builds.isEmpty())
    }

    @Test
    fun `initial state is first load`() = runTest(dispatcher) {
        val vm = MyNetworkViewModel(source, time)
        assertEquals(MyNetworkUiState.Body.FirstLoad, vm.state.value.body)
    }

    @Test
    fun `stages are shown while building and the final network replaces them`() = runTest(dispatcher) {
        val final = sampleNetwork()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        source.stages = listOf(stage(final, 1), stage(final, 2), NetworkBuildEvent.Done(final))
        source.failed = 3
        val vm = vm()
        assertTrue(vm.state.value.updating)
        assertEquals(MyNetworkUiState.Body.FirstLoad, vm.state.value.body)
        assertEquals(0, vm.state.value.doneDepth)

        gate.complete(Unit)
        advanceUntilIdle()
        val s = vm.state.value
        assertSame(final, s.network)
        assertEquals(2, s.doneDepth)
        assertFalse(s.updating)
        assertFalse(s.loading)
        assertFalse(s.updateFailed)
        assertEquals(3, s.failedCount)
        assertEquals("08:15", s.standTime)
        assertEquals(MyNetworkUiState.Body.Content, s.body)
        assertEquals(listOf(false), source.builds)
    }

    @Test
    fun `a build that ends after a stage without Done keeps the stage and flags update failed`() = runTest(dispatcher) {
        source.stages = listOf(stage(sampleNetwork(), 1))
        val vm = vm()
        val s = vm.state.value
        assertEquals(1, s.doneDepth)
        assertFalse(s.updating)
        assertTrue(s.updateFailed)
        assertEquals(MyNetworkUiState.Body.Content, s.body)
    }

    @Test
    fun `cached network is shown before the build`() = runTest(dispatcher) {
        val cached = sampleNetwork()
        source.cached = cached
        source.gate = CompletableDeferred()
        val vm = vm()
        val s = vm.state.value
        assertSame(cached, s.network)
        assertFalse(s.loading)
        assertTrue(s.updating)
        assertEquals(MyNetworkUiState.Body.Content, s.body)
    }

    @Test
    fun `stale empty result keeps the shown network and flags update failed`() = runTest(dispatcher) {
        val cached = sampleNetwork()
        source.cached = cached
        source.stages = listOf(NetworkBuildEvent.Done(MyNetwork.empty(ME, stale = true)))
        val vm = vm()
        val s = vm.state.value
        assertSame(cached, s.network)
        assertTrue(s.updateFailed)
        assertFalse(s.updating)
    }

    @Test
    fun `stale non-empty result replaces the network`() = runTest(dispatcher) {
        source.cached = sampleNetwork()
        val stale = sampleNetwork(stale = true)
        source.stages = listOf(NetworkBuildEvent.Done(stale))
        val vm = vm()
        assertSame(stale, vm.state.value.network)
        assertTrue(vm.state.value.updateFailed)
    }

    @Test
    fun `build error without network shows empty state without saved hint`() = runTest(dispatcher) {
        source.buildError = IOException("offline")
        val vm = vm()
        val s = vm.state.value
        assertFalse(s.loading)
        assertFalse(s.updating)
        assertFalse(s.updateFailed)
        assertEquals(MyNetworkUiState.Body.Empty, s.body)
    }

    @Test
    fun `build error with cached network flags update failed`() = runTest(dispatcher) {
        source.cached = sampleNetwork()
        source.buildError = IOException("offline")
        val vm = vm()
        assertTrue(vm.state.value.updateFailed)
        assertEquals(MyNetworkUiState.Body.Content, vm.state.value.body)
    }

    @Test
    fun `names come from cache then fetch, non-empty only, each npub asked once`() = runTest(dispatcher) {
        val net = sampleNetwork()
        source.cached = net
        source.stages = listOf(NetworkBuildEvent.Done(net))
        source.cachedNames = mapOf(hex(1) to "Anna", hex(2) to "")
        source.fetchedNames = mapOf(hex(2) to "Ben", hex(3) to "")
        val vm = vm()
        assertEquals(mapOf(np(1) to "Anna", np(2) to "Ben"), vm.state.value.names)
        // Asked once for the cached network; the final (same) network asks nobody new.
        assertEquals(1, source.fetchRequests.size)
        assertEquals(listOf(hex(1), hex(2), hex(3), hex(4), hex(5)), source.fetchRequests.single())
    }

    @Test
    fun `refresh forces a full build and toggles refreshing`() = runTest(dispatcher) {
        source.stages = listOf(NetworkBuildEvent.Done(sampleNetwork()))
        val vm = vm()
        source.gate = CompletableDeferred()
        vm.onAction(MyNetworkAction.Refresh)
        runCurrent()
        assertTrue(vm.state.value.refreshing)
        source.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf(false, true), source.builds)
    }

    @Test
    fun `retry reports fixed of total and reloads`() = runTest(dispatcher) {
        source.failed = 2
        source.fixed = 1
        source.stages = listOf(NetworkBuildEvent.Done(sampleNetwork()))
        val vm = vm()
        assertEquals(2, vm.state.value.failedCount)
        vm.events.test {
            source.failed = 1
            vm.onAction(MyNetworkAction.Retry)
            runCurrent()
            assertEquals(MyNetworkEvent.RetryResult(1, 2), awaitItem())
            advanceUntilIdle()
        }
        assertEquals(1, source.retries)
        assertFalse(vm.state.value.retrying)
        assertEquals(1, vm.state.value.failedCount)
        assertEquals(listOf(false, false), source.builds)
    }

    @Test
    fun `retry sets retrying while running`() = runTest(dispatcher) {
        source.failed = 1
        source.stages = listOf(NetworkBuildEvent.Done(sampleNetwork()))
        val vm = vm()
        vm.onAction(MyNetworkAction.Retry)
        assertTrue(vm.state.value.retrying)
        advanceUntilIdle()
        assertFalse(vm.state.value.retrying)
    }

    @Test
    fun `ui actions update filter, query, expanded, highlight and info sheet`() = runTest(dispatcher) {
        source.stages = listOf(NetworkBuildEvent.Done(sampleNetwork()))
        val vm = vm()
        vm.onAction(MyNetworkAction.SetFilter(2))
        vm.onAction(MyNetworkAction.SetQuery("an"))
        vm.onAction(MyNetworkAction.Expand(1))
        vm.onAction(MyNetworkAction.OpenDetail(np(1)))
        vm.onAction(MyNetworkAction.OpenInfo)
        var s = vm.state.value
        assertEquals(2, s.filter)
        assertEquals("an", s.query)
        assertEquals(setOf(1), s.expanded)
        assertEquals(np(1), s.highlight)
        assertTrue(s.infoSheetOpen)
        vm.onAction(MyNetworkAction.CloseDetail)
        vm.onAction(MyNetworkAction.CloseInfo)
        s = vm.state.value
        assertNull(s.highlight)
        assertFalse(s.infoSheetOpen)
    }

    @Test
    fun `slow name lookup does not hold back the build or the refresh indicator`() = runTest(dispatcher) {
        val cached = sampleNetwork()
        val final = sampleNetwork(updatedAt = Instant.parse("2026-10-21T17:00:00Z"))
        source.cached = cached
        source.namesGate = CompletableDeferred()
        source.fetchedNames = mapOf(hex(1) to "Anna")
        val buildGate = CompletableDeferred<Unit>()
        source.gate = buildGate
        source.stages = listOf(stage(final, 1), NetworkBuildEvent.Done(final))
        val vm = vm()
        // Names for the cached network are still pending, the build already runs.
        assertEquals(1, source.fetchRequests.size)
        assertEquals(listOf(false), source.builds)
        assertTrue(vm.state.value.updating)

        buildGate.complete(Unit)
        advanceUntilIdle()
        assertSame(final, vm.state.value.network)
        assertFalse(vm.state.value.updating)

        // Pull-to-refresh ends with the build even though a name lookup is pending.
        source.cached = null
        val refreshGate = CompletableDeferred<Unit>()
        source.gate = refreshGate
        vm.onAction(MyNetworkAction.Refresh)
        runCurrent()
        assertTrue(vm.state.value.refreshing)
        refreshGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf(false, true), source.builds)
        assertTrue(vm.state.value.names.isEmpty())

        source.namesGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(mapOf(np(1) to "Anna"), vm.state.value.names)
    }

    @Test
    fun `a refresh during the name lookup does not lose the names`() = runTest(dispatcher) {
        val net = sampleNetwork()
        source.cached = net
        source.stages = listOf(NetworkBuildEvent.Done(net))
        source.namesGate = CompletableDeferred()
        source.fetchedNames = mapOf(hex(2) to "Ben")
        val vm = vm()
        vm.onAction(MyNetworkAction.Refresh)
        advanceUntilIdle()
        source.namesGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("Ben", vm.state.value.names[np(2)])
    }

    @Test
    fun `a forced load replacing another keeps refreshing until it ends`() = runTest(dispatcher) {
        source.stages = listOf(NetworkBuildEvent.Done(sampleNetwork()))
        val vm = vm()
        val first = CompletableDeferred<Unit>()
        source.gate = first
        vm.onAction(MyNetworkAction.Refresh)
        runCurrent()
        val second = CompletableDeferred<Unit>()
        source.gate = second
        vm.onAction(MyNetworkAction.Refresh)
        runCurrent()
        // The cancelled first refresh must not clear the flag of the running second one.
        assertTrue(vm.state.value.refreshing)
        second.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun `a retry reload replacing a running refresh keeps the forced rebuild and ends refreshing`() = runTest(dispatcher) {
        source.failed = 1
        source.fixed = 1
        source.stages = listOf(NetworkBuildEvent.Done(sampleNetwork()))
        val vm = vm()
        val refreshGate = CompletableDeferred<Unit>()
        source.gate = refreshGate
        vm.onAction(MyNetworkAction.Refresh)
        runCurrent()
        assertTrue(vm.state.value.refreshing)
        // The retry finishes while the refresh build still waits and starts a non-forced load.
        source.gate = null
        vm.onAction(MyNetworkAction.Retry)
        advanceUntilIdle()
        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf(false, true, true), source.builds)
        assertTrue(source.builds.last())
    }

    @Test
    fun `onStart recomputes the stand time against the current day`() = runTest(dispatcher) {
        source.stages = listOf(NetworkBuildEvent.Done(sampleNetwork()))
        val vm = vm()
        assertEquals("08:15", vm.state.value.standTime)
        time.millis = Instant.parse("2026-10-22T07:00:00Z").toEpochMilli()
        vm.onStart()
        assertEquals("21.10. 08:15", vm.state.value.standTime)
    }

    @Test
    fun `detail sheet keeps the network it was opened with`() = runTest(dispatcher) {
        val first = sampleNetwork()
        source.cached = first
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val later = sampleNetwork(updatedAt = Instant.parse("2026-10-21T17:00:00Z"))
        source.stages = listOf(stage(later, 1), NetworkBuildEvent.Done(later))
        val vm = vm()
        vm.onAction(MyNetworkAction.OpenDetail(np(5)))
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(later, vm.state.value.network)
        assertSame(first, vm.state.value.detailNetwork)
        vm.onAction(MyNetworkAction.CloseDetail)
        assertNull(vm.state.value.detailNetwork)
    }
}
