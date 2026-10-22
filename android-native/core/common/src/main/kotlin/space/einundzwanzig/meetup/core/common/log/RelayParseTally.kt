package space.einundzwanzig.meetup.core.common.log

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Counts unparseable messages of ONE relay subscription and reports them once at the end
 * instead of per message (Dart `RelayParseTally`).
 *
 * "3 von 47 nicht auswertbar" says more than either silently dropping broken events or one warn
 * line per message (which used to flood the 800-entry buffer). With no failures it stays silent.
 *
 * ```
 * val tally = RelayParseTally("AdminRegistry", "Admin-Liste von $relayUrl")
 * try {
 *     socket.messages.collect { raw ->
 *         tally.message()
 *         try { handle(raw) } catch (e: Exception) { tally.failed(e) }
 *     }
 * } finally {
 *     tally.report()
 * }
 * ```
 *
 * Thread-safe (socket callbacks and the reporting coroutine may run on different threads).
 */
class RelayParseTally(
    val tag: String,
    val label: String,
    private val log: DiagnosticLog = AppLogger,
) {
    private val seenCount = AtomicInteger()
    private val failedCount = AtomicInteger()
    private val firstError = AtomicReference<Any?>(null)
    private val reported = AtomicBoolean(false)

    /** Messages seen so far. */
    val seen: Int get() = seenCount.get()

    /** Failures so far. */
    val failures: Int get() = failedCount.get()

    /** Call for every incoming relay message. */
    fun message() {
        seenCount.incrementAndGet()
    }

    /**
     * Call in the catch block of the message handler. Only the first non-null [error] is kept
     * and reported (on `debug`) as a sample of the cause.
     */
    fun failed(error: Any? = null) {
        failedCount.incrementAndGet()
        if (error != null) firstError.compareAndSet(null, error)
    }

    /**
     * Call when the subscription ends — preferably in `finally`, so timeouts and closes count
     * too. Silent without failures; otherwise one `WARN "$label: N von M Relay-Nachrichten nicht
     * auswertbar"` plus, in verbose mode, a `DEBUG` with the first error. Later calls are no-ops
     * once something was reported.
     */
    fun report() {
        val failed = failedCount.get()
        if (failed == 0 || !reported.compareAndSet(false, true)) return
        log.warn(tag, "$label: $failed von ${seenCount.get()} Relay-Nachrichten nicht auswertbar")
        firstError.get()?.let { log.debug(tag, "$label — erster Parse-Fehler: ${errorText(it)}") }
    }

    private fun errorText(error: Any): String = if (error is Throwable) describeError(error) else error.toString()
}
