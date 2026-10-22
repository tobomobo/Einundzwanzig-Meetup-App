package space.einundzwanzig.meetup.core.common.log

import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * The diagnostic log API (Dart `AppLogger`): a persistent ring buffer the user can view, copy
 * and share from the "Diagnose-Log" screen. Inject it, or call the same functions statically on
 * [AppLogger] where injection is not available.
 *
 * Rules (same as the Flutter app):
 * - [debug] lines are kept only while [isVerbose] is on; [info]/[diag], [security], [warn] and
 *   [error] are always kept.
 * - The newest [DiagnosticLogger.MAX_ENTRIES] entries survive; older ones roll out.
 * - **Never log secrets** (keys, nsec, seeds, tokens, full NIP-46 `auth_url`s). Every message
 *   additionally passes through [SecretRedactor].
 */
interface DiagnosticLog {
    /** The buffer, oldest first. */
    val entries: StateFlow<List<LogEntry>>

    /** Verbose mode ("Ausführliches Log"). */
    val verbose: StateFlow<Boolean>

    /** Current verbose mode (Dart `AppLogger.isVerbose`). */
    val isVerbose: Boolean get() = verbose.value

    /** Detail message; stored only in verbose mode (always echoed to logcat in debug builds). */
    fun debug(tag: String, message: String)

    /** Normal flow message; always stored. */
    fun info(tag: String, message: String)

    /** Diagnostic event (portal, admin, widget flows); stored as `INFO` like Dart's `diag`. */
    fun diag(tag: String, message: String)

    /**
     * Warning. A non-null [error] is appended **with its message**, not just its type:
     * `"$message — IllegalStateException: details"`.
     */
    fun warn(tag: String, message: String, error: Any? = null)

    /**
     * Error. Appends `" — Type: message"` for [error] and `" | bei frame0 <- frame1"` for the
     * first two non-empty frames of [stack], all in one line.
     */
    fun error(
        tag: String,
        message: String,
        error: Any? = null,
        stack: Array<StackTraceElement>? = (error as? Throwable)?.stackTrace,
    )

    /** Security-relevant event; stored with level `SEC`. */
    fun security(tag: String, message: String)

    /** Separator line marking the start of a flow: `INFO [ooo] ───── title ─────`. */
    fun section(title: String)

    /**
     * Switches verbose mode, records the switch (`INFO [Log] …`) and persists the flag.
     */
    fun setVerbose(on: Boolean)

    /** The whole buffer in [LogEntry.format] lines joined by `\n` (copy/share content). */
    fun exportText(zone: ZoneId = ZoneId.systemDefault()): String =
        entries.value.joinToString("\n") { it.format(zone) }

    /** Empties the buffer and the persisted copy (no confirmation; Dart `AppLogger.clear`). */
    suspend fun clear()
}

/**
 * Dart `AppLogger.measure`: runs [action], logs `"$label ok (N ms)"` on [DiagnosticLog.debug]
 * or `"$label FEHLGESCHLAGEN nach N ms"` with the cause on [DiagnosticLog.error], and returns
 * the result — or `null` on failure unless [rethrowError] is set.
 *
 * Coroutine cancellation is never logged or swallowed; JVM `Error`s (OOM, …) propagate.
 */
suspend fun <T> DiagnosticLog.measure(
    tag: String,
    label: String,
    rethrowError: Boolean = false,
    action: suspend () -> T,
): T? {
    val start = System.nanoTime()
    return try {
        val result = action()
        debug(tag, "$label ok (${(System.nanoTime() - start) / 1_000_000} ms)")
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error(tag, "$label FEHLGESCHLAGEN nach ${(System.nanoTime() - start) / 1_000_000} ms", e)
        if (rethrowError) throw e
        null
    }
}

/** Only the problems (`ERROR`, `WARN`) — the log screen filter. */
fun List<LogEntry>.problemsOnly(): List<LogEntry> = filter { it.isProblem }

/**
 * Formats an error like Dart's `'${error.runtimeType}: $error'`, adapted to Kotlin:
 * `SimpleClassName: message`, plus the first cause (`(Ursache: Type: message)`) when there is
 * one. Line breaks are collapsed so a log entry stays one line.
 */
fun describeError(error: Any): String {
    val text = when (error) {
        is Throwable -> buildString {
            append(typeName(error))
            error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            val cause = error.cause
            if (cause != null && cause !== error) {
                append(" (Ursache: ").append(typeName(cause))
                cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                append(')')
            }
        }
        else -> "${typeName(error)}: $error"
    }
    return LINE_BREAKS.replace(text, " ")
}

private fun typeName(value: Any): String =
    value::class.simpleName?.takeIf { it.isNotEmpty() } ?: value.javaClass.name

private val LINE_BREAKS = Regex("""\s*[\r\n]+\s*""")
