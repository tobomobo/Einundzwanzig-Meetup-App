package space.einundzwanzig.meetup.core.common.dart

import space.einundzwanzig.meetup.core.common.SystemTimeSource
import space.einundzwanzig.meetup.core.common.TimeSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * A Dart `DateTime`: an instant with microsecond precision plus the `isUtc` flag.
 *
 * Like in Dart, a *local* value stores the instant, and its calendar fields are computed in the
 * device time zone when they are read. That is what makes [toIso8601String] byte-identical to
 * Dart for badge hashes, exports and portal payloads:
 *
 * - UTC values print `…Z`, local values print no offset at all;
 * - microseconds are printed only when non-zero (`.345` vs `.345678`);
 * - a local wall time that does not exist (DST gap) moves forward, e.g. parsing
 *   `2026-03-29T02:30:00` in Europe/Berlin gives `2026-03-29T03:30:00.000`, exactly like Dart.
 *
 * Every function that needs calendar fields takes a [ZoneId] that defaults to the device zone;
 * tests pass a fixed zone. UTC values ignore it.
 *
 * Equality is Dart's `==`: same instant **and** same [isUtc]. Ordering ([compareTo]) only looks
 * at the instant, like Dart's `compareTo`.
 */
data class DartDateTime(
    /** Dart `microsecondsSinceEpoch`. */
    val microsecondsSinceEpoch: Long,
    /** Dart `isUtc`. */
    val isUtc: Boolean,
) : Comparable<DartDateTime> {

    init {
        require(abs(microsecondsSinceEpoch) <= MAX_MICROS) { "DateTime out of range: $microsecondsSinceEpoch µs" }
    }

    /** Dart `millisecondsSinceEpoch` (floor division, `-1 µs → -1 ms`). */
    val millisecondsSinceEpoch: Long get() = Math.floorDiv(microsecondsSinceEpoch, DartDuration.MICROS_PER_MILLI)

    /** The instant as a `java.time.Instant`. */
    fun toInstant(): Instant = Instant.ofEpochSecond(
        Math.floorDiv(microsecondsSinceEpoch, DartDuration.MICROS_PER_SECOND),
        Math.floorMod(microsecondsSinceEpoch, DartDuration.MICROS_PER_SECOND) * 1_000,
    )

    /** Calendar fields (UTC for UTC values, otherwise in [zone]); nanos are a multiple of 1000. */
    fun fields(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        LocalDateTime.ofInstant(toInstant(), if (isUtc) ZoneOffset.UTC else zone)

    /** Dart `year` (device zone for local values). */
    val year: Int get() = fields().year

    /** Dart `month`, 1..12. */
    val month: Int get() = fields().monthValue

    /** Dart `day`, 1..31. */
    val day: Int get() = fields().dayOfMonth

    /** Dart `hour`. */
    val hour: Int get() = fields().hour

    /** Dart `minute`. */
    val minute: Int get() = fields().minute

    /** Dart `second`. */
    val second: Int get() = fields().second

    /** Dart `millisecond`, 0..999. */
    val millisecond: Int get() = Math.floorMod(microsecondsSinceEpoch, DartDuration.MICROS_PER_SECOND).toInt() / 1000

    /** Dart `microsecond`, 0..999. */
    val microsecond: Int get() = Math.floorMod(microsecondsSinceEpoch, DartDuration.MICROS_PER_MILLI).toInt()

    /** Dart `weekday`: Monday = 1 … Sunday = 7. */
    val weekday: Int get() = fields().dayOfWeek.value

    /**
     * Dart `toIso8601String()`: `yyyy-MM-ddTHH:mm:ss.mmm[uuu][Z]`. Years outside -9999..9999 use
     * a signed six-digit form (`+010000`).
     */
    fun toIso8601String(zone: ZoneId = ZoneId.systemDefault()): String {
        val f = fields(zone)
        val year = if (f.year in -9999..9999) fourDigits(f.year) else sixDigits(f.year)
        return buildString(27) {
            append(year).append('-').append(two(f.monthValue)).append('-').append(two(f.dayOfMonth))
            append('T')
            appendTime(f)
            if (isUtc) append('Z')
        }
    }

    /** Dart `toString()`: like [toIso8601String] but with a space and a four-digit-or-longer year. */
    fun toDartString(zone: ZoneId = ZoneId.systemDefault()): String {
        val f = fields(zone)
        return buildString(27) {
            append(fourDigits(f.year)).append('-').append(two(f.monthValue)).append('-').append(two(f.dayOfMonth))
            append(' ')
            appendTime(f)
            if (isUtc) append('Z')
        }
    }

    private fun StringBuilder.appendTime(f: LocalDateTime) {
        append(two(f.hour)).append(':').append(two(f.minute)).append(':').append(two(f.second))
        append('.').append(three(millisecond))
        if (microsecond != 0) append(three(microsecond))
    }

    /** Dart `toUtc()`. */
    fun toUtc(): DartDateTime = if (isUtc) this else DartDateTime(microsecondsSinceEpoch, true)

    /** Dart `toLocal()`. */
    fun toLocal(): DartDateTime = if (!isUtc) this else DartDateTime(microsecondsSinceEpoch, false)

    /** Dart `add(duration)`: adds to the instant (no calendar arithmetic). */
    fun add(duration: DartDuration): DartDateTime =
        DartDateTime(microsecondsSinceEpoch + duration.inMicroseconds, isUtc)

    /** Dart `subtract(duration)`. */
    fun subtract(duration: DartDuration): DartDateTime =
        DartDateTime(microsecondsSinceEpoch - duration.inMicroseconds, isUtc)

    /** Dart `difference(other)`: `this - other` on the instants. */
    fun difference(other: DartDateTime): DartDuration =
        DartDuration(microsecondsSinceEpoch - other.microsecondsSinceEpoch)

    /** Dart `isBefore`. */
    fun isBefore(other: DartDateTime): Boolean = microsecondsSinceEpoch < other.microsecondsSinceEpoch

    /** Dart `isAfter`. */
    fun isAfter(other: DartDateTime): Boolean = microsecondsSinceEpoch > other.microsecondsSinceEpoch

    /** Dart `isAtSameMomentAs`. */
    fun isAtSameMomentAs(other: DartDateTime): Boolean = microsecondsSinceEpoch == other.microsecondsSinceEpoch

    /** Dart `compareTo`: instants only. */
    override fun compareTo(other: DartDateTime): Int = microsecondsSinceEpoch.compareTo(other.microsecondsSinceEpoch)

    /** Same as [toIso8601String] in the device zone (debug output only; never rely on it for wire data). */
    override fun toString(): String = toIso8601String()

    companion object {
        /** Dart's limit: ±8 640 000 000 000 000 ms around the epoch. */
        const val MAX_MILLIS: Long = 8_640_000_000_000_000L
        private const val MAX_MICROS: Long = MAX_MILLIS * 1000

        /**
         * Dart `DateTime.parse`. Accepts `[+-]yyyy[yy]-?MM-?dd` optionally followed by
         * `[T ]HH(:?mm(:?ss([.,]fraction)?)?)?` and `Z`/`z`/`±HH(:?mm)?` (optionally after a space).
         * Fractions are truncated to microseconds; out-of-range fields overflow like in Dart
         * (`2026-13-01` → `2027-01-01`, `T24:00` → next day). With a zone designator the result is
         * UTC, otherwise local in [zone].
         *
         * @throws DartFormatException for anything Dart rejects.
         */
        fun parse(formattedString: String, zone: ZoneId = ZoneId.systemDefault()): DartDateTime {
            val m = PARSE_FORMAT.matchEntire(formattedString)
                ?: throw DartFormatException("Invalid date format", formattedString)
            val g = m.groups
            val years = g[1]!!.value.toInt()
            val month = g[2]!!.value.toInt()
            val day = g[3]!!.value.toInt()
            val hour = g[4]?.value?.toInt() ?: 0
            var minute = g[5]?.value?.toInt() ?: 0
            val second = g[6]?.value?.toInt() ?: 0
            val fraction = g[7]?.value
            var milliAndMicros = 0
            for (i in 0 until 6) {
                milliAndMicros *= 10
                if (fraction != null && i < fraction.length) milliAndMicros += fraction[i].code xor 0x30
            }
            var isUtc = false
            if (g[8] != null) {
                isUtc = true
                val tzSign = g[9]?.value
                if (tzSign != null) {
                    val sign = if (tzSign == "-") -1 else 1
                    val minuteDifference = (g[11]?.value?.toInt() ?: 0) + 60 * g[10]!!.value.toInt()
                    minute -= sign * minuteDifference
                }
            }
            val micros = brokenDownToMicros(
                years, month, day, hour, minute, second,
                milliAndMicros / 1000, milliAndMicros % 1000, isUtc, zone,
            ) ?: throw DartFormatException("Time out of range", formattedString)
            return DartDateTime(micros, isUtc)
        }

        /** Dart `DateTime.tryParse`: [parse] or `null`. */
        fun tryParse(formattedString: String, zone: ZoneId = ZoneId.systemDefault()): DartDateTime? =
            try {
                parse(formattedString, zone)
            } catch (_: DartFormatException) {
                null
            }

        /**
         * Dart `DateTime(year, month, day, …)`: a local value; fields may overflow
         * (`month 13`, `day 0`, `hour -1`). A wall time inside a DST gap moves forward, an
         * ambiguous one picks the earlier instant — both like Dart.
         *
         * @throws IllegalArgumentException if the result is outside Dart's range.
         */
        fun local(
            year: Int,
            month: Int = 1,
            day: Int = 1,
            hour: Int = 0,
            minute: Int = 0,
            second: Int = 0,
            millisecond: Int = 0,
            microsecond: Int = 0,
            zone: ZoneId = ZoneId.systemDefault(),
        ): DartDateTime = DartDateTime(
            brokenDownToMicros(year, month, day, hour, minute, second, millisecond, microsecond, false, zone)
                ?: throw IllegalArgumentException("($year, $month, $day, $hour, $minute, $second, $millisecond, $microsecond)"),
            false,
        )

        /** Dart `DateTime.utc(year, month, day, …)`; fields may overflow like in Dart. */
        fun utc(
            year: Int,
            month: Int = 1,
            day: Int = 1,
            hour: Int = 0,
            minute: Int = 0,
            second: Int = 0,
            millisecond: Int = 0,
            microsecond: Int = 0,
        ): DartDateTime = DartDateTime(
            brokenDownToMicros(year, month, day, hour, minute, second, millisecond, microsecond, true, ZoneOffset.UTC)
                ?: throw IllegalArgumentException("($year, $month, $day, $hour, $minute, $second, $millisecond, $microsecond)"),
            true,
        )

        /** Dart `DateTime.fromMillisecondsSinceEpoch(ms, isUtc:)`. */
        fun fromMillisecondsSinceEpoch(millisecondsSinceEpoch: Long, isUtc: Boolean = false): DartDateTime {
            require(abs(millisecondsSinceEpoch) <= MAX_MILLIS) { "millisecondsSinceEpoch out of range: $millisecondsSinceEpoch" }
            return DartDateTime(millisecondsSinceEpoch * 1000, isUtc)
        }

        /** Dart `DateTime.fromMicrosecondsSinceEpoch(µs, isUtc:)`. */
        fun fromMicrosecondsSinceEpoch(microsecondsSinceEpoch: Long, isUtc: Boolean = false): DartDateTime =
            DartDateTime(microsecondsSinceEpoch, isUtc)

        /** A local value for [instant] (sub-microsecond digits are dropped). */
        fun fromInstant(instant: Instant, isUtc: Boolean = false): DartDateTime =
            DartDateTime(Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000L), instant.nano / 1000L), isUtc)

        /**
         * Dart `DateTime.now()`: a local value with microsecond precision
         * ([TimeSource.nowMicros]), so [toIso8601String] prints six fraction digits like Flutter
         * (`2026-10-20T12:00:00.123456`) whenever the microsecond field is non-zero.
         */
        fun now(timeSource: TimeSource = SystemTimeSource()): DartDateTime =
            fromMicrosecondsSinceEpoch(timeSource.nowMicros())

        /** Dart `DateTime.timestamp()` / `DateTime.now().toUtc()`, microsecond precision. */
        fun nowUtc(timeSource: TimeSource = SystemTimeSource()): DartDateTime =
            fromMicrosecondsSinceEpoch(timeSource.nowMicros(), isUtc = true)

        // Dart 3.13 `DateTime._parseFormat`. `\d` is ASCII-only in both engines; matchEntire
        // replaces Dart's `^…$` (Java's `$` would also match before a trailing newline).
        private val PARSE_FORMAT = Regex(
            """([+-]?\d{4,6})-?(\d\d)-?(\d\d)""" +
                """(?:[ T](\d\d)(?::?(\d\d)(?::?(\d\d)(?:[.,](\d+))?)?)?""" +
                """( ?[zZ]| ?([-+])(\d\d)(?::?(\d\d))?)?)?""",
        )

        /**
         * Dart `_brokenDownDateToValue`: normalises overflowing fields, then converts a local
         * wall time with [zone]. `null` when outside Dart's range.
         */
        private fun brokenDownToMicros(
            year: Int,
            month: Int,
            day: Int,
            hour: Int,
            minute: Int,
            second: Int,
            millisecond: Int,
            microsecond: Int,
            isUtc: Boolean,
            zone: ZoneId,
        ): Long? = try {
            val monthIndex = month - 1L
            val normalizedYear = year + Math.floorDiv(monthIndex, 12L)
            val normalizedMonth = Math.floorMod(monthIndex, 12L).toInt() + 1
            if (abs(normalizedYear) > 300_000) return null
            val epochDay = LocalDate.of(normalizedYear.toInt(), normalizedMonth, 1).toEpochDay() + (day - 1L)
            var wallMicros = Math.multiplyExact(epochDay, DartDuration.MICROS_PER_DAY)
            wallMicros = Math.addExact(wallMicros, hour * DartDuration.MICROS_PER_HOUR)
            wallMicros = Math.addExact(wallMicros, minute * DartDuration.MICROS_PER_MINUTE)
            wallMicros = Math.addExact(wallMicros, second * DartDuration.MICROS_PER_SECOND)
            wallMicros = Math.addExact(wallMicros, millisecond * DartDuration.MICROS_PER_MILLI)
            wallMicros = Math.addExact(wallMicros, microsecond.toLong())
            val micros = if (isUtc) {
                wallMicros
            } else {
                if (abs(wallMicros) > MAX_MICROS + DartDuration.MICROS_PER_DAY) return null
                val seconds = Math.floorDiv(wallMicros, DartDuration.MICROS_PER_SECOND)
                val subMicros = Math.floorMod(wallMicros, DartDuration.MICROS_PER_SECOND)
                val wall = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC)
                // java.time resolves gaps forward and overlaps to the earlier offset, which is
                // what Dart's _toLocalTimeOffset does for whole-hour transitions.
                wall.atZone(zone).toEpochSecond() * DartDuration.MICROS_PER_SECOND + subMicros
            }
            if (abs(micros) > MAX_MICROS) null else micros
        } catch (_: ArithmeticException) {
            null
        } catch (_: java.time.DateTimeException) {
            null
        }

        private fun two(n: Int): String = if (n >= 10) n.toString() else "0$n"

        private fun three(n: Int): String = when {
            n >= 100 -> n.toString()
            n >= 10 -> "0$n"
            else -> "00$n"
        }

        private fun fourDigits(n: Int): String {
            val absN = abs(n)
            val sign = if (n < 0) "-" else ""
            return when {
                absN >= 1000 -> n.toString()
                absN >= 100 -> "${sign}0$absN"
                absN >= 10 -> "${sign}00$absN"
                else -> "${sign}000$absN"
            }
        }

        private fun sixDigits(n: Int): String {
            val absN = abs(n)
            val sign = if (n < 0) "-" else "+"
            return if (absN >= 100_000) "$sign$absN" else "${sign}0$absN"
        }
    }
}

/** Dart `FormatException` raised by [DartDateTime.parse]; [source] is the rejected input. */
class DartFormatException(message: String, val source: String) : IllegalArgumentException("$message: $source")
