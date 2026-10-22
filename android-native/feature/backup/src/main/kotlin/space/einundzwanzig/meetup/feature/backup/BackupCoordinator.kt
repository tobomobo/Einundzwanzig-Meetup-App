package space.einundzwanzig.meetup.feature.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.ui.share.ShareResult
import space.einundzwanzig.meetup.data.backup.BackupCrypto
import space.einundzwanzig.meetup.data.backup.BackupDocument
import space.einundzwanzig.meetup.data.backup.BackupExporter
import space.einundzwanzig.meetup.data.backup.BackupPasswordRules
import space.einundzwanzig.meetup.data.backup.BackupReadException
import space.einundzwanzig.meetup.data.backup.BackupSession
import space.einundzwanzig.meetup.data.backup.ExportResult
import space.einundzwanzig.meetup.data.backup.OpenResult
import space.einundzwanzig.meetup.data.backup.ParseResult
import space.einundzwanzig.meetup.data.backup.RestoreConfirmation
import space.einundzwanzig.meetup.data.backup.RestoreEngine
import space.einundzwanzig.meetup.data.backup.RestoreError
import space.einundzwanzig.meetup.data.backup.RestoreOutcome
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform side of the flows, implemented by [BackupHost] (it owns the activity-result launchers
 * and the activity context). SAF results come back through [BackupCoordinator.onDocumentResult],
 * so a result delivered to a recreated activity still reaches the waiting flow.
 *
 * The coordinator calls [launchCreateDocument] and [launchOpenDocument] on the main thread.
 */
interface BackupPlatform {
    /** Starts SAF `CreateDocument` with [fileName]; `false` if no picker could be started. */
    fun launchCreateDocument(fileName: String): Boolean

    /** Starts SAF `OpenDocument` (`*` / `*`); `false` if no picker could be started. */
    fun launchOpenDocument(): Boolean

    /** Shares the backup file via the system chooser and waits for its outcome. */
    suspend fun share(fileName: String, bytes: ByteArray): ShareResult
}

/**
 * Orchestrates export and import (spec 08 §2.4, §2.5, §13 `BackupCoordinator`): it suspends the
 * calling flow on the dialogs that [BackupHost] renders from [ui], and emits [messages] for the
 * snackbars. All data logic lives in `:data:backup`.
 */
@Singleton
class BackupCoordinator @Inject internal constructor(
    private val session: BackupSession,
    private val exporter: BackupExporter,
    private val engine: RestoreEngine,
    private val documents: BackupDocuments,
    private val log: DiagnosticLog,
    @ApplicationScope private val appScope: CoroutineScope,
    @DefaultDispatcher private val cpuDispatcher: CoroutineDispatcher,
) : BackupLauncher {

    private val state = MutableStateFlow(BackupUiState())

    /** Dialog and overlay state for [BackupHost]. */
    val ui: StateFlow<BackupUiState> = state.asStateFlow()

    private val messageChannel = Channel<BackupMessage>(Channel.BUFFERED)

    /** Snackbar events (each delivered once). */
    val messages: Flow<BackupMessage> = messageChannel.receiveAsFlow()

    override val busy: StateFlow<Boolean> get() = session.busy

    /** The mounted host plus a signal that completes when it leaves (activity destroyed). */
    private class Attachment(val platform: BackupPlatform) {
        val detached = CompletableDeferred<Unit>()
    }

    @Volatile
    private var attachment: Attachment? = null

    private val platform: BackupPlatform? get() = attachment?.platform

    /** `true` while a [BackupHost] is mounted (tests). */
    internal val isAttached: Boolean get() = attachment != null

    @Volatile
    private var pendingAnswer: CompletableDeferred<BackupAction>? = null

    @Volatile
    private var pendingDocument: CompletableDeferred<String?>? = null

    /** Called by [BackupHost] when it enters the composition. */
    fun attach(platform: BackupPlatform) {
        val previous = attachment
        if (previous?.platform === platform) return
        attachment = Attachment(platform)
        previous?.detached?.complete(Unit)
    }

    /** Called by [BackupHost] when it leaves the composition. */
    fun detach(platform: BackupPlatform) {
        val current = attachment ?: return
        if (current.platform !== platform) return
        attachment = null
        current.detached.complete(Unit)
    }

    /** Dialog input from [BackupHost]. */
    fun onAction(action: BackupAction) {
        pendingAnswer?.complete(action)
    }

    /** SAF result (`CreateDocument` or `OpenDocument`); `null` = cancelled. */
    fun onDocumentResult(uri: String?) {
        pendingDocument?.complete(uri)
    }

    override suspend fun export(): Boolean = session.tryRun { runExport() } ?: false

    override suspend fun restore(): Boolean = session.tryRun { runRestore() } ?: false

    override fun launchExport(onResult: (Boolean) -> Unit) {
        appScope.launch {
            val result = export()
            withContext(Dispatchers.Main.immediate) { onResult(result) }
        }
    }

    override fun launchRestore(onResult: (Boolean) -> Unit) {
        appScope.launch {
            val result = restore()
            withContext(Dispatchers.Main.immediate) { onResult(result) }
        }
    }

    // ---------------------------------------------------------------- export (spec 08 §2.4)

    private suspend fun runExport(): Boolean {
        // Like the restore path: without a mounted host nobody renders the password dialog, and
        // waiting for it would keep the busy guard set until the process dies.
        if (attachment == null) {
            log.warn(TAG_APP, "Backup: keine Oberfläche für den Passwortdialog")
            return false
        }
        val password = askPassword(isExport = true) ?: return false
        val ready = try {
            setLoading(true)
            when (val result = exporter.export(password)) {
                is ExportResult.Ready -> result
                ExportResult.BadgesUnreadable -> {
                    setLoading(false)
                    emit(BackupMessage.BadgesUnreadable)
                    return false
                }
                ExportResult.PasswordTooShort -> return false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG_APP, "Backup Fehler", e)
            emit(BackupMessage.ExportFailed(e.message ?: e.toString()))
            return false
        } finally {
            setLoading(false)
        }

        val uri = pickDocument { it.launchCreateDocument(ready.fileName) }
        if (uri != null) {
            try {
                documents.write(uri, ready.content)
                log.info(TAG_APP, "Backup gespeichert: $uri")
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG_APP, "Speicherdialog nicht verfuegbar: ${e.message}")
                // Do not leave an empty or partial .21bkp at the chosen location.
                documents.delete(uri)
            }
        }

        // Fallback: share sheet. Never report success without a file or a chosen target.
        val host = attachment ?: return false
        return try {
            shareUntilDetached(host, ready.fileName, ready.bytes).isNotDismissed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG_APP, "Backup Fehler", e)
            emit(BackupMessage.ExportFailed(e.message ?: e.toString()))
            false
        }
    }

    // ---------------------------------------------------------------- import (spec 08 §2.5)

    private suspend fun runRestore(): Boolean {
        log.info(TAG_RESTORE, "Wiederherstellung gestartet; warte auf Dateiauswahl.")
        val uri = pickDocument { it.launchOpenDocument() } ?: return false
        return try {
            val content = try {
                documents.read(uri)
            } catch (e: BackupReadException) {
                log.warn(TAG_APP, "Import Fehler", e)
                emit(BackupMessage.ImportFailed(ImportFailure.Unreadable(e.message)))
                return false
            }
            val opened = BackupCrypto.open(
                content = content,
                password = { askPassword(isExport = false) },
                onKeyDerivation = { setLoading(it) },
                dispatcher = cpuDispatcher,
            )
            val json = when (opened) {
                OpenResult.Cancelled -> return false
                OpenResult.WrongPasswordOrCorrupt -> {
                    emit(BackupMessage.WrongPassword)
                    return false
                }
                is OpenResult.Ok -> opened.json
            }
            val document = when (val parsed = BackupDocument.parse(json, cpuDispatcher)) {
                is ParseResult.Ok -> parsed.document
                ParseResult.NotValid -> return importFailed(ImportFailure.NotValid)
                ParseResult.NotEinundzwanzig -> return importFailed(ImportFailure.NotEinundzwanzig)
            }
            when (val outcome = engine.restore(document) { confirm(it) }) {
                RestoreOutcome.Cancelled -> false
                is RestoreOutcome.Merged -> {
                    emit(BackupMessage.Merged(outcome.added))
                    true
                }
                is RestoreOutcome.Restored -> {
                    emit(BackupMessage.Loaded(outcome))
                    true
                }
                is RestoreOutcome.Failed -> {
                    (outcome.error as? RestoreError.InvalidContent)?.let {
                        log.warn(TAG_APP, "Import Fehler: ${it.detail}", null)
                    }
                    importFailed(ImportFailure.Restore(outcome.error))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG_APP, "Import Fehler", e)
            importFailed(ImportFailure.Unexpected(e.message ?: e.toString()))
        } finally {
            setLoading(false)
        }
    }

    private fun importFailed(reason: ImportFailure): Boolean {
        emit(BackupMessage.ImportFailed(reason))
        return false
    }

    private suspend fun confirm(confirmation: RestoreConfirmation): Boolean {
        val dialog = when (confirmation) {
            is RestoreConfirmation.Replace ->
                BackupDialog.ConfirmReplace(confirmation.existingShort, confirmation.backupShort)
            is RestoreConfirmation.AddUnknown -> BackupDialog.ConfirmAddUnknown(confirmation.existingShort)
        }
        while (true) {
            when (ask(dialog)) {
                BackupAction.Confirm -> return true
                BackupAction.Decline, BackupAction.CancelPassword -> return false
                is BackupAction.SubmitPassword -> Unit // not for this dialog: keep asking
            }
        }
    }

    // ---------------------------------------------------------------- dialogs & platform

    /** The password dialog; `null` = cancelled. Invalid submissions are ignored (the dialog prevents them). */
    private suspend fun askPassword(isExport: Boolean): String? {
        val dialog = BackupDialog.Password(isExport)
        while (true) {
            when (val action = ask(dialog)) {
                is BackupAction.SubmitPassword -> {
                    val pw = action.password
                    val valid = if (isExport) BackupPasswordRules.canSubmit(true, pw, pw) else pw.isNotEmpty()
                    if (valid) return pw
                }
                BackupAction.CancelPassword, BackupAction.Decline -> return null
                BackupAction.Confirm -> Unit
            }
        }
    }

    private suspend fun ask(dialog: BackupDialog): BackupAction {
        val answer = CompletableDeferred<BackupAction>()
        pendingAnswer = answer
        state.update { it.copy(dialog = dialog) }
        try {
            return answer.await()
        } finally {
            if (pendingAnswer === answer) pendingAnswer = null
            state.update { if (it.dialog == dialog) it.copy(dialog = null) else it }
        }
    }

    /**
     * The share sheet waits on the host activity's lifecycle. If that activity is destroyed while
     * the chooser is open (rotation), it never resumes again; count that as dismissed so the flow
     * (and the busy guard) always ends.
     */
    private suspend fun shareUntilDetached(host: Attachment, fileName: String, bytes: ByteArray): ShareResult =
        coroutineScope {
            val share = async { host.platform.share(fileName, bytes) }
            select {
                share.onAwait { it }
                host.detached.onAwait {
                    share.cancel()
                    log.debug(TAG_APP, "Teilen abgebrochen: Oberfläche geschlossen")
                    ShareResult.Dismissed
                }
            }
        }

    private suspend fun pickDocument(launch: (BackupPlatform) -> Boolean): String? {
        val host = platform ?: run {
            log.warn(TAG_APP, "Backup: keine Oberfläche für die Dateiauswahl")
            return null
        }
        val result = CompletableDeferred<String?>()
        pendingDocument = result
        try {
            val started = try {
                // Activity-result launchers and startActivity are main-thread APIs; the flows
                // may run on the application scope (Dispatchers.Default).
                withContext(Dispatchers.Main.immediate) { launch(host) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG_APP, "Dateiauswahl nicht verfuegbar: ${e.message}")
                false
            }
            if (!started) return null
            return result.await()
        } finally {
            if (pendingDocument === result) pendingDocument = null
        }
    }

    private fun setLoading(loading: Boolean) {
        state.update { it.copy(loading = loading) }
    }

    private fun emit(message: BackupMessage) {
        messageChannel.trySend(message)
    }

    private companion object {
        const val TAG_APP = "App"
        const val TAG_RESTORE = "BackupRestore"
    }
}
