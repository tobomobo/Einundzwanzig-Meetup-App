package space.einundzwanzig.meetup.core.common.dart

/**
 * Dart `Duration`: a signed span with microsecond precision.
 *
 * All `inX` getters truncate towards zero like Dart's `~/` (`-1 µs → inDays 0`,
 * `-86 400 000 001 µs → inDays -1`). Use this where the Dart code compares `difference(...)`
 * results, e.g. `now.difference(date).inDays`.
 */
@JvmInline
value class DartDuration(val inMicroseconds: Long) : Comparable<DartDuration> {

    val inMilliseconds: Long get() = inMicroseconds / MICROS_PER_MILLI
    val inSeconds: Long get() = inMicroseconds / MICROS_PER_SECOND
    val inMinutes: Long get() = inMicroseconds / MICROS_PER_MINUTE
    val inHours: Long get() = inMicroseconds / MICROS_PER_HOUR
    val inDays: Long get() = inMicroseconds / MICROS_PER_DAY

    /** Dart `Duration.isNegative`. */
    val isNegative: Boolean get() = inMicroseconds < 0

    operator fun plus(other: DartDuration): DartDuration = DartDuration(inMicroseconds + other.inMicroseconds)
    operator fun minus(other: DartDuration): DartDuration = DartDuration(inMicroseconds - other.inMicroseconds)
    operator fun unaryMinus(): DartDuration = DartDuration(-inMicroseconds)

    /** Dart `Duration.abs()`. */
    fun abs(): DartDuration = if (inMicroseconds < 0) DartDuration(-inMicroseconds) else this

    override fun compareTo(other: DartDuration): Int = inMicroseconds.compareTo(other.inMicroseconds)

    /** Dart `Duration.toString()`: `H:MM:SS.ffffff` with a leading `-` when negative. */
    override fun toString(): String {
        var micros = inMicroseconds
        var hours = micros / MICROS_PER_HOUR
        micros %= MICROS_PER_HOUR
        val sign = if (inMicroseconds < 0) "-" else ""
        if (inMicroseconds < 0) {
            hours = -hours
            micros = -micros
        }
        val minutes = micros / MICROS_PER_MINUTE
        micros %= MICROS_PER_MINUTE
        val seconds = micros / MICROS_PER_SECOND
        micros %= MICROS_PER_SECOND
        return sign + hours + ":" + minutes.toString().padStart(2, '0') + ":" +
            seconds.toString().padStart(2, '0') + "." + micros.toString().padStart(6, '0')
    }

    companion object {
        const val MICROS_PER_MILLI: Long = 1_000
        const val MICROS_PER_SECOND: Long = 1_000_000
        const val MICROS_PER_MINUTE: Long = 60 * MICROS_PER_SECOND
        const val MICROS_PER_HOUR: Long = 60 * MICROS_PER_MINUTE
        const val MICROS_PER_DAY: Long = 24 * MICROS_PER_HOUR

        val ZERO: DartDuration = DartDuration(0)

        /** Dart `Duration(days:, hours:, minutes:, seconds:, milliseconds:, microseconds:)`. */
        fun of(
            days: Long = 0,
            hours: Long = 0,
            minutes: Long = 0,
            seconds: Long = 0,
            milliseconds: Long = 0,
            microseconds: Long = 0,
        ): DartDuration = DartDuration(
            days * MICROS_PER_DAY + hours * MICROS_PER_HOUR + minutes * MICROS_PER_MINUTE +
                seconds * MICROS_PER_SECOND + milliseconds * MICROS_PER_MILLI + microseconds,
        )
    }
}
