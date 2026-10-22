package space.einundzwanzig.meetup.data.calendar.widget

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Widget text helpers (Dart `WidgetService`, Home `_daysUntil`). */
object WidgetFormat {

    /** `––` (two U+2013): placeholder of every missing value. */
    const val DASH: String = "––"

    /** Dart `_fmtInt`: `.` every three digits from the right (`95000` → `95.000`). */
    fun groupInt(v: Long): String {
        val s = v.toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append('.')
            sb.append(s[i])
        }
        return sb.toString()
    }

    /** Calendar-day difference from [today] to the local date of [start] (0 today, 1 tomorrow, < 0 past). */
    fun daysUntil(start: Instant, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        ChronoUnit.DAYS.between(today, start.atZone(zone).toLocalDate())

    /** The countdown step of [days] (localize in the UI). */
    sealed interface Countdown {
        /** `days <= 0` ("Heute"). */
        data object Today : Countdown

        /** `days == 1` ("Morgen"). */
        data object Tomorrow : Countdown

        /** "in N Tagen". */
        data class InDays(val days: Long) : Countdown
    }

    /** Classifies [days]. */
    fun countdown(days: Long): Countdown = when {
        days <= 0 -> Countdown.Today
        days == 1L -> Countdown.Tomorrow
        else -> Countdown.InDays(days)
    }

    /**
     * The German string the Flutter widget stores in `meetupCountdown` (`Heute`, `Morgen`,
     * `in N Tagen`); kept for renderers that only read the legacy key.
     */
    fun germanCountdown(days: Long): String = when (val c = countdown(days)) {
        Countdown.Today -> "Heute"
        Countdown.Tomorrow -> "Morgen"
        is Countdown.InDays -> "in ${c.days} Tagen"
    }
}
