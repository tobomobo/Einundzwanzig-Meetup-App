package space.einundzwanzig.meetup.core.common.log

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

/**
 * Static facade over the process-wide [DiagnosticLogger] — the Kotlin counterpart of the Dart
 * `AppLogger` class with static methods. Use it where injection is not practical (parsers,
 * models, [RelayParseTally], crash handlers); inject [DiagnosticLog] everywhere else. Both
 * reach the same buffer ([logger]).
 *
 * Logging works before [init]: entries stay in memory and are merged with the stored buffer
 * once [init] has run.
 *
 * App start (Dart `main`):
 * ```
 * AppLogger.attach(context)                 // first line of Application.onCreate
 * AppLogger.installCrashHandler()
 * appScope.launch { AppLogger.init(context) } // loads the persisted buffer on IO
 * ```
 */
object AppLogger : DiagnosticLog {

    /** The process-wide logger behind this facade (Hilt provides the same instance). */
    val logger: DiagnosticLogger = DiagnosticLogger()

    /** Use with `CoroutineScope(SupervisorJob() + AppLogger.coroutineExceptionHandler)`. */
    val coroutineExceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
        logger.error("Async", "Unbehandelter Fehler", e)
    }

    override val entries: StateFlow<List<LogEntry>> get() = logger.entries
    override val verbose: StateFlow<Boolean> get() = logger.verbose

    /**
     * Attaches the file store in `filesDir` (no I/O) and enables the logcat echo for debuggable
     * builds. Call as early as possible so [flushBlocking] can save a crash during start-up.
     */
    fun attach(context: Context) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) logger.echo = LogcatEcho
        logger.attachStore(FileLogStore.forContext(context.applicationContext ?: context))
    }

    /** [attach] + load the persisted buffer (Dart `AppLogger.init()`). */
    suspend fun init(context: Context) {
        attach(context)
        logger.init(FileLogStore.forContext(context.applicationContext ?: context))
    }

    /** Loads the buffer from [store] (tests, custom storage). See [DiagnosticLogger.init]. */
    suspend fun init(store: LogStore) = logger.init(store)

    /** See [DiagnosticLogger.flushBlocking]. */
    fun flushBlocking() = logger.flushBlocking()

    /** See [DiagnosticLogger.importLegacy] (Flutter → native migration). */
    suspend fun importLegacy(rawDartJson: String?, verbose: Boolean?) = logger.importLegacy(rawDartJson, verbose)

    /** Redacts [secret] from all future log lines, see [SecretRedactor.registerSecret]. */
    fun registerSecret(secret: String) = SecretRedactor.Default.registerSecret(secret)

    /** See [SecretRedactor.unregisterSecret]. */
    fun unregisterSecret(secret: String) = SecretRedactor.Default.unregisterSecret(secret)

    /**
     * Logs uncaught exceptions as `ERROR [Async] Unbehandelter Fehler (Thread …)`, writes the
     * buffer synchronously (the process is about to die, a debounced write would never run),
     * then hands over to the previous handler. Idempotent.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
    }

    override fun debug(tag: String, message: String) = logger.debug(tag, message)

    override fun info(tag: String, message: String) = logger.info(tag, message)

    override fun diag(tag: String, message: String) = logger.diag(tag, message)

    override fun warn(tag: String, message: String, error: Any?) = logger.warn(tag, message, error)

    override fun error(tag: String, message: String, error: Any?, stack: Array<StackTraceElement>?) =
        logger.error(tag, message, error, stack)

    override fun security(tag: String, message: String) = logger.security(tag, message)

    override fun section(title: String) = logger.section(title)

    override fun setVerbose(on: Boolean) = logger.setVerbose(on)

    override fun exportText(zone: ZoneId): String = logger.exportText(zone)

    override suspend fun clear() = logger.clear()

    private class CrashHandler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                logger.error("Async", "Unbehandelter Fehler (Thread ${thread.name})", error)
                logger.flushBlocking()
            } catch (_: Throwable) {
                // Never let logging hide the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
