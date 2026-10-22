package space.einundzwanzig.meetup.core.camera

/**
 * Filters the stream of decoded QR values (the analyzer sees the same code up to 30 times per
 * second). A value is passed on when
 * - the scanner is not [stopped] (a consumer returned `true`: "ignore after first hit", spec 02 §2.2),
 * - it differs from the last passed value or [sameValueWindowMillis] have passed since then, and
 * - at least [minIntervalMillis] have passed since any value was passed.
 *
 * Not thread-safe: use it from the main thread only (ML Kit delivers results there).
 */
class ScanDebouncer(
    private val sameValueWindowMillis: Long = DEFAULT_SAME_VALUE_WINDOW_MILLIS,
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
) {
    private var lastValue: String? = null
    private var lastAcceptedAt: Long = Long.MIN_VALUE

    var stopped: Boolean = false
        private set

    /** Returns true when [value], seen at [nowMillis] (monotonic clock), should be delivered. */
    fun accept(value: String, nowMillis: Long): Boolean {
        if (stopped) return false
        if (lastAcceptedAt != Long.MIN_VALUE) {
            val elapsed = nowMillis - lastAcceptedAt
            if (elapsed < minIntervalMillis) return false
            if (value == lastValue && elapsed < sameValueWindowMillis) return false
        }
        lastValue = value
        lastAcceptedAt = nowMillis
        return true
    }

    /** Called when a consumer accepted a value: nothing is delivered until [reset]. */
    fun stop() {
        stopped = true
    }

    /** Re-arms the scanner (for example after the demo dialog, spec 04 §2.3). */
    fun reset() {
        stopped = false
        lastValue = null
        lastAcceptedAt = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_SAME_VALUE_WINDOW_MILLIS = 1_500L
        const val DEFAULT_MIN_INTERVAL_MILLIS = 250L
    }
}
