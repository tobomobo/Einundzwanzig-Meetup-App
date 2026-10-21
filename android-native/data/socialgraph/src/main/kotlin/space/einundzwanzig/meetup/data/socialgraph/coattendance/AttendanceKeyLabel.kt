package space.einundzwanzig.meetup.data.socialgraph.coattendance

import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.badges.util.DartSort
import java.time.LocalDate

/**
 * Human-readable attendance keys (Dart `AttendanceKeyLabel`, spec 05 §8.2.13):
 * `wuerzburg-2026-09-12@u8qf5q534jzc` → `Wuerzburg, 12.09.2026`. Pure.
 */
object AttendanceKeyLabel {

    // Dart: `.` excludes \n, \r, U+2028, U+2029 and `$` is end of input only.
    private val DATED = Regex("""^([^\n\r  ]*)-(\d{4})-(\d{2})-(\d{2})\z""")

    /** Dart `DateTime(0)`: the date used for keys without one when sorting. */
    private val YEAR_ZERO: LocalDate = LocalDate.of(0, 1, 1)

    private fun strip(key: String): String {
        var k = key
        if (k.startsWith(AttendanceKeys.EVENT_PREFIX)) k = k.substring(AttendanceKeys.EVENT_PREFIX.length)
        val i = k.indexOf('@')
        return if (i < 0) k else k.substring(0, i)
    }

    /**
     * The (local) date of [key], or `null`. Out-of-range fields overflow like Dart's
     * `DateTime(y, m, d)` (month 13 → January of the next year, day 0 → last day of the month before).
     */
    fun date(key: String): LocalDate? {
        val m = DATED.find(strip(key)) ?: return null
        val y = m.groupValues[2].toInt()
        val mo = m.groupValues[3].toInt()
        val d = m.groupValues[4].toInt()
        return LocalDate.of(y, 1, 1).plusMonths((mo - 1).toLong()).plusDays((d - 1).toLong())
    }

    /** Place part: words split on `-`, empty ones dropped, first UTF-16 unit upper-cased (Dart rules). */
    fun place(key: String): String {
        val s = strip(key)
        val m = DATED.find(s)
        val raw = m?.groupValues?.get(1) ?: s
        return raw.split('-')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { w -> DartString.upper(w.substring(0, 1)) + w.substring(1) }
    }

    /** `dd.MM.yyyy`, or `dd.MM.` without [withYear]. */
    fun dateText(d: LocalDate, withYear: Boolean = true): String {
        fun two(v: Int): String = v.toString().padStart(2, '0')
        return if (withYear) "${two(d.dayOfMonth)}.${two(d.monthValue)}.${d.year}" else "${two(d.dayOfMonth)}.${two(d.monthValue)}."
    }

    /** `"<place>, <dd.MM.yyyy>"`, or the place alone without a date. */
    fun label(key: String): String {
        val d = date(key)
        val p = place(key)
        return if (d == null) p else "$p, ${dateText(d)}"
    }

    /** Keys sorted by date, newest first (no date = year 0); Dart `List.sort` order for ties. */
    fun newestFirst(keys: Iterable<String>): List<String> =
        DartSort.sorted(keys) { a, b -> (date(b) ?: YEAR_ZERO).compareTo(date(a) ?: YEAR_ZERO) }

    /** The newest date among [keys], or `null` if none carries one. */
    fun newest(keys: Iterable<String>): LocalDate? {
        var best: LocalDate? = null
        for (k in keys) {
            val d = date(k)
            if (d != null && (best == null || d.isAfter(best))) best = d
        }
        return best
    }

    internal fun newestOrYearZero(keys: Iterable<String>): LocalDate = newest(keys) ?: YEAR_ZERO
}
