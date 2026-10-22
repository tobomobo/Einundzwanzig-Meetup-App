package space.einundzwanzig.meetup.feature.settings.log

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.feature.settings.FakeDiagnosticLog
import space.einundzwanzig.meetup.feature.settings.FakeNip44Probe
import space.einundzwanzig.meetup.feature.settings.MainDispatcherRule
import space.einundzwanzig.meetup.feature.settings.entry

class DiagnosticLogViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val log = FakeDiagnosticLog(
        listOf(
            entry("INFO", message = "start"),
            entry("WARN", message = "warn"),
            entry("SEC", message = "sec"),
            entry("ERROR", message = "boom"),
            entry("DEBUG", message = "detail"),
        ),
    )
    private val probe = FakeNip44Probe()

    private fun vm(): DiagnosticLogViewModel {
        val vm = DiagnosticLogViewModel(log, probe)
        CoroutineScope(main.dispatcher).launch { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun `shows every entry in order by default`() {
        val s = vm().uiState.value
        assertEquals(listOf("start", "warn", "sec", "boom", "detail"), s.entries.map { it.message })
        assertFalse(s.onlyProblems)
    }

    @Test
    fun `filter keeps only WARN and ERROR, SEC is hidden too`() {
        val vm = vm()
        vm.onAction(DiagnosticLogAction.ToggleFilter)
        assertTrue(vm.uiState.value.onlyProblems)
        assertEquals(listOf("warn", "boom"), vm.uiState.value.entries.map { it.message })
        vm.onAction(DiagnosticLogAction.ToggleFilter)
        assertEquals(5, vm.uiState.value.entries.size)
    }

    @Test
    fun `new log entries appear live`() {
        val vm = vm()
        log.warn("Chat", "neu")
        assertEquals("neu", vm.uiState.value.entries.last().message)
    }

    @Test
    fun `verbose toggle switches the logger and reports the new state`() = runTest {
        val vm = vm()
        vm.uiEvents.test {
            vm.onAction(DiagnosticLogAction.ToggleVerbose)
            assertEquals(DiagnosticLogEvent.Snackbar(LogMessage.VerboseOn), awaitItem())
            assertTrue(vm.uiState.value.verbose)
            vm.onAction(DiagnosticLogAction.ToggleVerbose)
            assertEquals(DiagnosticLogEvent.Snackbar(LogMessage.VerboseOff), awaitItem())
            assertFalse(log.verbose.value)
        }
    }

    @Test
    fun `probe success and failure snackbars`() = runTest {
        val vm = vm()
        vm.uiEvents.test {
            vm.onAction(DiagnosticLogAction.Probe)
            assertEquals(DiagnosticLogEvent.Snackbar(LogMessage.ProbeOk), awaitItem())
            probe.result = false
            vm.onAction(DiagnosticLogAction.Probe)
            assertEquals(DiagnosticLogEvent.Snackbar(LogMessage.ProbeFailed), awaitItem())
        }
        assertEquals(2, probe.calls)
    }

    @Test
    fun `probe button is disabled while probing and ignores double taps`() {
        val gate = CompletableDeferred<Unit>()
        probe.gate = gate
        val vm = vm()
        vm.onAction(DiagnosticLogAction.Probe)
        assertTrue(vm.uiState.value.probing)
        vm.onAction(DiagnosticLogAction.Probe)
        assertEquals(1, probe.calls)
        gate.complete(Unit)
        assertFalse(vm.uiState.value.probing)
    }

    @Test
    fun `copy and share export the full buffer, not the filtered view`() = runTest {
        val vm = vm()
        vm.onAction(DiagnosticLogAction.ToggleFilter)
        val full = log.exportText()
        vm.uiEvents.test {
            vm.onAction(DiagnosticLogAction.Copy)
            assertEquals(DiagnosticLogEvent.Copy(full), awaitItem())
            vm.onAction(DiagnosticLogAction.Share)
            assertEquals(DiagnosticLogEvent.Share(full), awaitItem())
        }
        assertTrue(full.contains("[T] sec"))
    }

    @Test
    fun `share does nothing for an empty log`() = runTest {
        log.entries.value = emptyList()
        val vm = vm()
        vm.uiEvents.test {
            vm.onAction(DiagnosticLogAction.Share)
            expectNoEvents()
        }
    }

    @Test
    fun `clear empties the log without confirmation`() {
        val vm = vm()
        vm.onAction(DiagnosticLogAction.Clear)
        assertEquals(1, log.clears)
        assertTrue(vm.uiState.value.entries.isEmpty())
    }
}
