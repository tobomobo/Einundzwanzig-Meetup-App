package space.einundzwanzig.meetup.feature.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.LogEntry
import space.einundzwanzig.meetup.core.ui.share.ShareResult
import space.einundzwanzig.meetup.data.backup.BackupDocument
import space.einundzwanzig.meetup.data.backup.BackupExporter
import space.einundzwanzig.meetup.data.backup.BackupReadException
import space.einundzwanzig.meetup.data.backup.BackupSession
import space.einundzwanzig.meetup.data.backup.BuildResult
import space.einundzwanzig.meetup.data.backup.ExportResult
import space.einundzwanzig.meetup.data.backup.RestoreConfirmation
import space.einundzwanzig.meetup.data.backup.RestoreEngine
import space.einundzwanzig.meetup.data.backup.RestoreOutcome

class FakeSession : BackupSession {
    private val mutex = Mutex()
    private val state = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = state

    override suspend fun <T : Any> tryRun(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        state.value = true
        try {
            return block()
        } finally {
            state.value = false
            mutex.unlock()
        }
    }
}

class FakeExporter : BackupExporter {
    var result: ExportResult = ExportResult.Ready("Backup_2026-10-21_1200_21MeetupApp_Satoshi.21bkp", "enc_v2:a:b:c")
    var error: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    val passwords = mutableListOf<String>()

    override suspend fun buildDocument(): BuildResult = BuildResult.Ok("{}", "Satoshi")

    override suspend fun export(password: String): ExportResult {
        passwords += password
        gate?.await()
        error?.let { throw it }
        return result
    }
}

class FakeEngine : RestoreEngine {
    var confirmation: RestoreConfirmation? = null
    var outcome: RestoreOutcome = RestoreOutcome.Merged(0)
    var error: Exception? = null
    val documents = mutableListOf<BackupDocument>()

    override suspend fun restore(
        document: BackupDocument,
        confirm: suspend (RestoreConfirmation) -> Boolean,
    ): RestoreOutcome {
        documents += document
        confirmation?.let { if (!confirm(it)) return RestoreOutcome.Cancelled }
        error?.let { throw it }
        return outcome
    }
}

class FakeDocuments : BackupDocuments {
    val written = mutableMapOf<String, String>()
    val files = mutableMapOf<String, String>()
    var writeError: Exception? = null
    val deleted = mutableListOf<String>()

    override suspend fun write(uri: String, content: String) {
        writeError?.let { throw it }
        written[uri] = content
    }

    override suspend fun read(uri: String): String =
        files[uri] ?: throw BackupReadException("Datei konnte nicht gelesen werden.")

    override suspend fun delete(uri: String) {
        deleted += uri
    }
}

class FakePlatform : BackupPlatform {
    val created = mutableListOf<String>()
    var opened = 0
    var canLaunch = true
    var shareResult: ShareResult = ShareResult.Success(null)
    val shared = mutableListOf<Pair<String, ByteArray>>()
    /** When set, [share] suspends on it (e.g. a chooser that never reports back). */
    var shareGate: CompletableDeferred<ShareResult>? = null
    /** Thread names of the picker launches. */
    val launchThreads = mutableListOf<String>()

    override fun launchCreateDocument(fileName: String): Boolean {
        launchThreads += Thread.currentThread().name.substringBefore(" @")
        created += fileName
        return canLaunch
    }

    override fun launchOpenDocument(): Boolean {
        launchThreads += Thread.currentThread().name.substringBefore(" @")
        opened++
        return canLaunch
    }

    override suspend fun share(fileName: String, bytes: ByteArray): ShareResult {
        shared += fileName to bytes
        shareGate?.let { return it.await() }
        return shareResult
    }
}

class NoOpLog : DiagnosticLog {
    val lines = mutableListOf<String>()
    override val entries: StateFlow<List<LogEntry>> = MutableStateFlow(emptyList())
    override val verbose: StateFlow<Boolean> = MutableStateFlow(false)
    override fun debug(tag: String, message: String) { lines += "D $tag $message" }
    override fun info(tag: String, message: String) { lines += "I $tag $message" }
    override fun diag(tag: String, message: String) { lines += "I $tag $message" }
    override fun warn(tag: String, message: String, error: Any?) { lines += "W $tag $message" }
    override fun error(tag: String, message: String, error: Any?, stack: Array<StackTraceElement>?) {
        lines += "E $tag $message"
    }
    override fun security(tag: String, message: String) = Unit
    override fun section(title: String) = Unit
    override fun setVerbose(on: Boolean) = Unit
    override suspend fun clear() = Unit
}

/** A coordinator wired to fakes. */
class Harness(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
    val session = FakeSession()
    val exporter = FakeExporter()
    val engine = FakeEngine()
    val documents = FakeDocuments()
    val platform = FakePlatform()
    val log = NoOpLog()
    val coordinator = BackupCoordinator(session, exporter, engine, documents, log, scope, dispatcher)
}

const val VALID_BACKUP = """{"version":5,"user":{"nickname":"Satoshi"},"badges":[]}"""
