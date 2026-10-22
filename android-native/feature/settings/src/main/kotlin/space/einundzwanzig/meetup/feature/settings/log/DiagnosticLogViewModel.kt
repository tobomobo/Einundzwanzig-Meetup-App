package space.einundzwanzig.meetup.feature.settings.log

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.LogEntry
import space.einundzwanzig.meetup.core.common.log.problemsOnly
import space.einundzwanzig.meetup.feature.settings.Nip44Probe
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.data.migration.FlutterMigration
import space.einundzwanzig.meetup.data.migration.MigrationReport
import space.einundzwanzig.meetup.data.migration.MigrationState

/** What the diagnostic log screen shows (spec 12 §2.8). */
@Immutable
data class DiagnosticLogUiState(
    /** Entries after the filter, chronological. The footer counts these. */
    val entries: List<LogEntry> = emptyList(),
    /** "Nur Warnungen/Fehler" (ERROR + WARN; SEC is hidden too). */
    val onlyProblems: Boolean = false,
    val verbose: Boolean = false,
    /** NIP-44 probe running: the key button is disabled. */
    val probing: Boolean = false,
    val migration: MigrationDiagnosticState = MigrationDiagnosticState(),
)

data class MigrationDiagnosticState(
    val report: MigrationReport? = null,
    val busy: Boolean = false,
    val confirmAbandon: Boolean = false,
    val failed: Boolean = false,
)

/** Snackbar texts of the log screen. */
enum class LogMessage { ProbeOk, ProbeFailed, VerboseOn, VerboseOff, Copied }

/** One-shot effects that need the Android context. */
sealed interface DiagnosticLogEvent {
    data class Snackbar(val message: LogMessage) : DiagnosticLogEvent

    /** Copy [text] to the clipboard, then show [LogMessage.Copied]. */
    data class Copy(val text: String) : DiagnosticLogEvent

    /** Share [text] (subject `21Meetup Diagnose-Log`); fall back to [Copy] on failure. */
    data class Share(val text: String) : DiagnosticLogEvent
}

/** User actions of the log screen. */
sealed interface DiagnosticLogAction {
    data object RetryMigration : DiagnosticLogAction
    data object RequestAbandonMigration : DiagnosticLogAction
    data object ConfirmAbandonMigration : DiagnosticLogAction
    data object DismissAbandonMigration : DiagnosticLogAction
    data object Back : DiagnosticLogAction
    data object Probe : DiagnosticLogAction
    data object ToggleFilter : DiagnosticLogAction
    data object ToggleVerbose : DiagnosticLogAction
    data object Copy : DiagnosticLogAction
    data object Share : DiagnosticLogAction
    data object Clear : DiagnosticLogAction
}

/** Diagnostic log viewer (port of `log_screen.dart`). */
@HiltViewModel
class DiagnosticLogViewModel @Inject constructor(
    private val log: DiagnosticLog,
    private val probe: Nip44Probe,
    private val migration: FlutterMigration,
) : ViewModel() {

    private val onlyProblems = MutableStateFlow(false)
    private val probing = MutableStateFlow(false)
    private val migrationState = MutableStateFlow(MigrationDiagnosticState())
    private val events = Channel<DiagnosticLogEvent>(Channel.BUFFERED)

    val uiEvents: Flow<DiagnosticLogEvent> = events.receiveAsFlow()

    val uiState: StateFlow<DiagnosticLogUiState> = combine(
        log.entries,
        log.verbose,
        onlyProblems,
        probing,
        migrationState,
    ) { entries, verbose, filter, busy, migration ->
        DiagnosticLogUiState(
            entries = if (filter) entries.problemsOnly() else entries,
            onlyProblems = filter,
            verbose = verbose,
            probing = busy,
            migration = migration,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DiagnosticLogUiState(entries = log.entries.value, verbose = log.verbose.value),
    )

    init {
        viewModelScope.launch {
            try {
                migration.gate.await()
                val report = migration.currentReport()
                migrationState.update { it.copy(report = report) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                migrationState.update { it.copy(failed = true) }
            }
        }
    }

    fun onAction(action: DiagnosticLogAction) {
        when (action) {
            DiagnosticLogAction.RetryMigration -> runMigration(abandon = false)
            DiagnosticLogAction.RequestAbandonMigration -> if (!migrationState.value.busy && migrationState.value.report?.state == MigrationState.IMPORTED_WITH_ERRORS) {
                migrationState.update { it.copy(confirmAbandon = true) }
            }
            DiagnosticLogAction.ConfirmAbandonMigration -> if (migrationState.value.confirmAbandon) runMigration(abandon = true)
            DiagnosticLogAction.DismissAbandonMigration -> migrationState.update { it.copy(confirmAbandon = false) }

            DiagnosticLogAction.Back -> Unit
            DiagnosticLogAction.Probe -> runProbe()
            DiagnosticLogAction.ToggleFilter -> onlyProblems.update { !it }
            DiagnosticLogAction.ToggleVerbose -> toggleVerbose()
            DiagnosticLogAction.Copy -> send(DiagnosticLogEvent.Copy(log.exportText()))
            DiagnosticLogAction.Share -> {
                // Copy and share always export the full buffer, not the filtered view.
                val text = log.exportText()
                if (text.isNotEmpty()) send(DiagnosticLogEvent.Share(text))
            }
            DiagnosticLogAction.Clear -> viewModelScope.launch { log.clear() }
        }
    }

    private fun runMigration(abandon: Boolean) {
        if (migrationState.value.busy) return
        migrationState.update { it.copy(busy = true, failed = false, confirmAbandon = false) }
        viewModelScope.launch {
            try {
                val report = if (abandon) migration.abandonPendingSecrets() else migration.retryNow()
                migrationState.update { it.copy(report = report) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                migrationState.update { it.copy(failed = true) }
            } finally {
                migrationState.update { it.copy(busy = false) }
            }
        }
    }

    private fun runProbe() {
        if (probing.value) return
        probing.value = true
        viewModelScope.launch {
            val ok = try {
                probe.canNip44()
            } finally {
                probing.value = false
            }
            events.send(DiagnosticLogEvent.Snackbar(if (ok) LogMessage.ProbeOk else LogMessage.ProbeFailed))
        }
    }

    private fun toggleVerbose() {
        val on = !log.isVerbose
        log.setVerbose(on)
        send(DiagnosticLogEvent.Snackbar(if (on) LogMessage.VerboseOn else LogMessage.VerboseOff))
    }

    private fun send(event: DiagnosticLogEvent) {
        viewModelScope.launch { events.send(event) }
    }
}
