package space.einundzwanzig.meetup.core.common.log

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.SystemTimeSource
import space.einundzwanzig.meetup.core.common.TimeSource

/**
 * The [DiagnosticLog] implementation: in-memory ring buffer, debounced persistence through a
 * [LogStore], redaction, and an optional logcat echo.
 *
 * Production code uses the single process-wide instance [AppLogger.logger] (Hilt provides the
 * same object), so static calls and injected calls share one buffer. Tests create their own
 * instance with a fake [TimeSource], a test scope and an [InMemoryLogStore].
 *
 * Persistence (port of the Dart rules, see `AppLogger._schedulePersist`):
 * - Nothing is written before [init] has loaded the stored buffer — otherwise an early crash
 *   report could overwrite the whole history.
 * - [init] merges `stored + recorded-so-far` and schedules a write if anything was recorded early.
 * - Writes are debounced ([persistDebounceMillis] after the last entry) but happen at the latest
 *   [persistMaxDelayMillis] after the first unsaved entry, so a steady stream of verbose lines
 *   cannot postpone saving forever.
 * - [flushBlocking] writes synchronously (crash handler).
 * - An unreadable stored buffer is moved aside ([LogStore.quarantine]) before anything is
 *   written; if that fails too, nothing is persisted for the rest of the process.
 * - A verbose flag set before the store was loaded ([setVerbose], [importLegacy]) wins over the
 *   stored one and is written back once the store is loaded.
 *
 * Thread-safe; logging never blocks on I/O.
 */
class DiagnosticLogger(
    private val timeSource: TimeSource = SystemTimeSource(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("DiagnosticLogger")),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val redactor: SecretRedactor = SecretRedactor.Default,
    private val maxEntries: Int = MAX_ENTRIES,
    private val persistDebounceMillis: Long = PERSIST_DEBOUNCE_MILLIS,
    private val persistMaxDelayMillis: Long = PERSIST_MAX_DELAY_MILLIS,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : DiagnosticLog {

    /** Receives every line (also unrecorded debug lines) after redaction, e.g. [LogcatEcho]. */
    @Volatile
    var echo: LogEcho? = null

    private val bufferLock = Any()
    private val storeLock = Any()
    private val buffer = ArrayDeque<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    private val _verbose = MutableStateFlow(false)

    @Volatile
    private var store: LogStore? = null

    @Volatile
    private var loaded = false

    // Set when the stored buffer could neither be read nor moved aside: writing would destroy it.
    @Volatile
    private var persistenceDisabled = false

    // Guarded by bufferLock (together with _verbose): the flag was chosen in this process.
    private var verboseSetExplicitly = false

    // Guarded by bufferLock.
    private var persistJob: Job? = null
    private var firstUnsavedAt: Long? = null

    override val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()
    override val verbose: StateFlow<Boolean> = _verbose.asStateFlow()

    /** `true` once [init] has loaded the stored buffer. */
    val isInitialized: Boolean get() = loaded

    /**
     * Attaches [store] and loads the persisted buffer and verbose flag (Dart `AppLogger.init`).
     * Entries recorded before are kept after the stored ones. Only the first call has an effect.
     */
    suspend fun init(store: LogStore) {
        attachStore(store)
        withContext(ioDispatcher) { ensureLoaded() }
    }

    /**
     * Attaches [store] without reading it, so [flushBlocking] works even if a crash happens while
     * [init] is still loading. Ignored if a store is already attached.
     */
    fun attachStore(store: LogStore) {
        if (this.store != null) return
        synchronized(storeLock) { if (this.store == null) this.store = store }
    }

    /**
     * Writes the buffer now, on the calling thread. For the uncaught-exception handler, where the
     * process dies before a debounced write would run. Loads the store first if [init] has not
     * finished; does nothing without a store.
     */
    fun flushBlocking() {
        if (store == null) return
        ensureLoaded()
        synchronized(bufferLock) {
            persistJob?.cancel()
            persistJob = null
        }
        writeSnapshot()
    }

    /**
     * Merges the Flutter buffer (`diagnostic_log` JSON array, see [LogEntry.listFromDartJson]) in
     * front of the current entries and adopts the Flutter verbose flag. Used once by the data
     * migration; imported lines are redacted. A corrupt buffer is not imported and leaves a WARN
     * entry, a successful import an INFO entry with the count.
     */
    suspend fun importLegacy(rawDartJson: String?, verbose: Boolean?) {
        withContext(ioDispatcher) {
            if (store != null) ensureLoaded()
            val parsed = if (rawDartJson == null) {
                emptyList()
            } else {
                try {
                    LogEntry.listFromDartJson(rawDartJson)
                } catch (e: Exception) {
                    warn("Log", "Flutter-Diagnose-Log nicht lesbar — nicht uebernommen", e)
                    emptyList()
                }
            }
            // Flutter never redacted, so the imported lines go through the redactor now.
            val legacy = parsed.map { it.copy(tag = redactor.redact(it.tag), message = capLength(redactor.redact(it.message))) }
            if (legacy.isNotEmpty()) {
                synchronized(bufferLock) {
                    val current = buffer.toList()
                    buffer.clear()
                    buffer.addAll(legacy)
                    buffer.addAll(current)
                    trimAndPublishLocked()
                }
                info("Log", "${legacy.size} Eintraege aus der Flutter-App uebernommen")
            }
            if (verbose != null) {
                synchronized(bufferLock) {
                    verboseSetExplicitly = true
                    _verbose.value = verbose
                }
                store?.let { st -> synchronized(storeLock) { writeVerboseLocked(st) } }
            }
            if (loaded) writeSnapshot()
        }
    }

    override fun debug(tag: String, message: String) {
        emit(LogLevel.DEBUG.code, tag, message, record = _verbose.value)
    }

    override fun info(tag: String, message: String) {
        emit(LogLevel.INFO.code, tag, message)
    }

    override fun diag(tag: String, message: String) {
        emit(LogLevel.INFO.code, tag, message)
    }

    override fun warn(tag: String, message: String, error: Any?) {
        emit(LogLevel.WARN.code, tag, if (error != null) "$message — ${describeError(error)}" else message)
    }

    override fun error(tag: String, message: String, error: Any?, stack: Array<StackTraceElement>?) {
        val text = StringBuilder(message)
        if (error != null) text.append(" — ").append(describeError(error))
        if (stack != null) {
            val frames = stack.asSequence().map { it.toString().trim() }.filter { it.isNotEmpty() }.take(2).toList()
            if (frames.isNotEmpty()) text.append(" | bei ").append(frames.joinToString(" <- "))
        }
        emit(LogLevel.ERROR.code, tag, text.toString())
    }

    override fun security(tag: String, message: String) {
        emit(LogLevel.SEC.code, tag, message)
    }

    override fun section(title: String) {
        emit(LogLevel.INFO.code, "ooo", "───── $title ─────")
    }

    /**
     * Switches verbose mode and persists the flag. Each save writes the flag's value at the time
     * the save runs, under the store lock, so quick toggles always end with the last choice
     * stored. Before the store is loaded the choice is kept and written back by [init].
     */
    override fun setVerbose(on: Boolean) {
        synchronized(bufferLock) {
            verboseSetExplicitly = true
            _verbose.value = on
        }
        emit(
            LogLevel.INFO.code,
            "Log",
            if (on) {
                "Ausfuehrliches Log EINGESCHALTET — auch Detailmeldungen werden erfasst."
            } else {
                "Ausfuehrliches Log ausgeschaltet."
            },
        )
        val st = store ?: return
        scope.launch(ioDispatcher) { synchronized(storeLock) { writeVerboseLocked(st) } }
    }

    override suspend fun clear() {
        synchronized(bufferLock) {
            buffer.clear()
            _entries.value = emptyList()
            persistJob?.cancel()
            persistJob = null
            firstUnsavedAt = null
        }
        val st = store ?: return
        withContext(ioDispatcher) {
            synchronized(storeLock) {
                try {
                    st.clearEntries()
                    // The user discarded the log, including a buffer that could not be moved aside.
                    persistenceDisabled = false
                } catch (e: Exception) {
                    warn("Log", "Gespeicherten Log nicht geloescht", e)
                }
            }
        }
    }

    /**
     * Test support only: empties the buffer, switches verbose off and detaches the store, so tests
     * that use the shared [AppLogger.logger] start from a clean state.
     */
    internal fun resetForTests() {
        synchronized(storeLock) {
            synchronized(bufferLock) {
                persistJob?.cancel()
                persistJob = null
                firstUnsavedAt = null
                buffer.clear()
                _entries.value = emptyList()
                _verbose.value = false
                verboseSetExplicitly = false
                store = null
                loaded = false
                persistenceDisabled = false
            }
        }
    }

    private fun emit(level: String, tag: String, message: String, record: Boolean = true) {
        val echo = echo
        if (!record && echo == null) return
        val safeTag = redactor.redact(tag)
        val safeMessage = capLength(redactor.redact(message))
        echo?.let { runCatching { it.echo(level, safeTag, safeMessage) } }
        if (!record) return
        val entry = LogEntry(timeSource.nowMillis(), level, safeTag, safeMessage)
        synchronized(bufferLock) {
            buffer.addLast(entry)
            trimAndPublishLocked()
            schedulePersistLocked()
        }
    }

    private fun trimAndPublishLocked() {
        while (buffer.size > maxEntries) buffer.removeFirst()
        _entries.value = buffer.toList()
    }

    private fun schedulePersistLocked() {
        if (!loaded || store == null || persistenceDisabled) return
        val now = monotonicMillis()
        val first = firstUnsavedAt ?: now.also { firstUnsavedAt = it }
        val wait = minOf(persistDebounceMillis, maxOf(0L, first + persistMaxDelayMillis - now))
        persistJob?.cancel()
        persistJob = scope.launch(ioDispatcher) {
            delay(wait)
            writeSnapshot()
        }
    }

    /**
     * Loads the store once (Dart `init` body). Safe to call from any thread. The verbose flag and
     * the buffer are read independently; an unreadable buffer is moved aside before anything can
     * overwrite it.
     */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(storeLock) {
            if (loaded) return
            val st = store ?: return
            var verboseFailure: Exception? = null
            var readFailure: Exception? = null
            var quarantineFailure: Exception? = null
            val storedVerbose = try {
                st.readVerbose()
            } catch (e: Exception) {
                verboseFailure = e
                null
            }
            val stored = try {
                st.readEntries()
            } catch (e: Exception) {
                readFailure = e
                null
            }
            if (readFailure != null) {
                try {
                    st.quarantine()
                } catch (e: Exception) {
                    quarantineFailure = e
                    persistenceDisabled = true
                }
            }
            val writeBackVerbose: Boolean
            synchronized(bufferLock) {
                val early = buffer.toList()
                buffer.clear()
                stored?.let { buffer.addAll(it.entries) }
                buffer.addAll(early)
                trimAndPublishLocked()
                writeBackVerbose = verboseSetExplicitly
                if (!writeBackVerbose) storedVerbose?.let { _verbose.value = it }
                loaded = true
                if (early.isNotEmpty()) schedulePersistLocked()
            }
            if (writeBackVerbose) writeVerboseLocked(st)
            verboseFailure?.let { warn("Log", "Gespeicherte Log-Einstellung nicht lesbar", it) }
            when {
                quarantineFailure != null -> warn(
                    "Log",
                    "Gespeicherter Log nicht lesbar und nicht verschiebbar — Speichern in dieser Sitzung aus",
                    quarantineFailure,
                )
                readFailure != null -> warn("Log", "Gespeicherter Log nicht lesbar — beiseitegelegt", readFailure)
            }
            val corrupt = stored?.corruptEntries ?: 0
            if (corrupt > 0) warn("Log", "$corrupt beschaedigte Log-Eintraege beim Laden verworfen.")
        }
    }

    /** Persists the current verbose flag. Caller holds [storeLock]. */
    private fun writeVerboseLocked(st: LogStore) {
        try {
            st.writeVerbose(_verbose.value)
        } catch (e: Exception) {
            warn("Log", "Log-Einstellung nicht gespeichert", e)
        }
    }

    private fun writeSnapshot() {
        synchronized(storeLock) {
            val st = store ?: return
            if (!loaded || persistenceDisabled) return
            val snapshot = synchronized(bufferLock) {
                firstUnsavedAt = null
                buffer.toList()
            }
            try {
                if (snapshot.isEmpty()) st.clearEntries() else st.writeEntries(snapshot)
            } catch (e: Exception) {
                // Best effort like Dart. Logging this into the buffer would only schedule the
                // next failing write, so it goes to the echo (logcat) only.
                echo?.let { runCatching { it.echo(LogLevel.WARN.code, "Log", "Speichern fehlgeschlagen — ${describeError(e)}") } }
            }
        }
    }

    private fun capLength(message: String): String {
        if (message.length <= MAX_MESSAGE_CHARS) return message
        val cut = if (Character.isHighSurrogate(message[MAX_MESSAGE_CHARS - 1])) MAX_MESSAGE_CHARS - 1 else MAX_MESSAGE_CHARS
        return message.substring(0, cut) + " … (+${message.length - cut} Zeichen gekuerzt)"
    }

    companion object {
        /** Ring buffer size (Dart `_maxEntries`). */
        const val MAX_ENTRIES = 800

        /** Debounce after the last entry (Dart: 2 s timer). */
        const val PERSIST_DEBOUNCE_MILLIS = 2_000L

        /** Upper bound between the first unsaved entry and the write (native addition). */
        const val PERSIST_MAX_DELAY_MILLIS = 10_000L

        /** Longest stored message; longer ones are cut (native addition, protects the 800-entry export). */
        const val MAX_MESSAGE_CHARS = 4_000
    }
}

/** Receives every redacted log line, e.g. to mirror it to logcat in debug builds. */
fun interface LogEcho {
    fun echo(level: String, tag: String, message: String)
}

/** Mirrors log lines to logcat (tag `E21.<tag>`). Install only in debuggable builds. */
object LogcatEcho : LogEcho {
    override fun echo(level: String, tag: String, message: String) {
        val priority = when (level) {
            "DEBUG" -> Log.DEBUG
            "WARN" -> Log.WARN
            "ERROR" -> Log.ERROR
            else -> Log.INFO
        }
        Log.println(priority, "E21.$tag", message)
    }
}
