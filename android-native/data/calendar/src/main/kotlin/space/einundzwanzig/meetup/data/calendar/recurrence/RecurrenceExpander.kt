package space.einundzwanzig.meetup.data.calendar.recurrence

import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.data.calendar.internal.dartTryParseInt
import space.einundzwanzig.meetup.data.calendar.internal.dartUpper
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Expands iCal `RRULE`s of the fallback feed into single dates (Dart `RecurrenceExpander`,
 * spec 10 §8.3): `WEEKLY`/`MONTHLY` with `INTERVAL`, `BYDAY`, `COUNT`, `UNTIL`; anything else
 * keeps only the base date.
 *
 * Deviation (documented, display only): occurrences are computed on local dates and wall-clock
 * times in [ZoneId], so a weekly series keeps its time across a DST change (Dart added absolute
 * 24 h durations and drifted by an hour). The Dart quirks are kept: `COUNT=1` (and `0`) still
 * yields base + 1 occurrence, a 31st skips shorter months, the 200-occurrence cap counts from
 * the series start.
 */
object RecurrenceExpander {

    /** How far ahead occurrences are generated. */
    const val HORIZON_DAYS: Int = 366

    /** Hard cap of returned dates (and of generated occurrences). */
    const val MAX_OCCURRENCES: Int = 200

    private const val MONTH_SAFETY = 120

    private val WEEKDAYS = mapOf(
        "MO" to 1, "TU" to 2, "WE" to 3, "TH" to 4, "FR" to 5, "SA" to 6, "SU" to 7,
    )
    private val RRULE_PREFIX = Regex("^RRULE:", RegexOption.IGNORE_CASE)
    private val MONTHLY_BYDAY = Regex("^(-?\\d+)?(MO|TU|WE|TH|FR|SA|SU)$")
    private val NON_DIGITS = Regex("[^0-9]")

    /** All dates of [base] under [rrule], sorted, at most [MAX_OCCURRENCES]; `[base]` for no/unknown/broken rules. */
    fun expand(base: MeetupDate, rrule: String?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<MeetupDate> {
        if (rrule == null || rrule.dartTrim().isEmpty()) return listOf(base)
        return try {
            val rule = parseRule(rrule)
            val freq = rule["FREQ"]
            if (freq != "WEEKLY" && freq != "MONTHLY") return listOf(base)
            val interval = (dartTryParseInt(rule["INTERVAL"] ?: "1") ?: 1L).coerceAtLeast(1L)
            val count = dartTryParseInt(rule["COUNT"] ?: "")
            val until = parseUntil(rule["UNTIL"])
            val horizon = now.plus(Duration.ofDays(HORIZON_DAYS.toLong()))
            val limit = if (until != null && until.isBefore(horizon)) until else horizon

            val results = mutableListOf(base)
            if (freq == "WEEKLY") {
                expandWeekly(base, interval, rule["BYDAY"], count, limit, zone, results)
            } else {
                expandMonthly(base, interval, rule["BYDAY"], count, limit, zone, results)
            }
            results.sortedBy { it.start }.take(MAX_OCCURRENCES)
        } catch (_: Exception) {
            listOf(base)
        }
    }

    /** `KEY=VALUE` pairs; keys upper-cased and trimmed, values only trimmed. */
    internal fun parseRule(rrule: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val clean = rrule.replaceFirst(RRULE_PREFIX, "")
        for (part in clean.split(';')) {
            val kv = part.split('=')
            if (kv.size == 2) map[kv[0].dartUpper().dartTrim()] = kv[1].dartTrim()
        }
        return map
    }

    /** `UNTIL` digits (≥ 8) → 23:59:59 UTC of that date; else `null`. */
    internal fun parseUntil(until: String?): Instant? {
        if (until == null) return null
        val s = until.replace(NON_DIGITS, "")
        if (s.length < 8) return null
        return runCatching {
            DartDateTime.utc(s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt(), 23, 59, 59)
                .toInstant()
        }.getOrNull()
    }

    private fun expandWeekly(
        base: MeetupDate,
        interval: Long,
        byDay: String?,
        count: Long?,
        limit: Instant,
        zone: ZoneId,
        out: MutableList<MeetupDate>,
    ) {
        val startLocal = base.start.atZone(zone).toLocalDateTime()
        val weekdays = LinkedHashSet<Int>()
        if (!byDay.isNullOrEmpty()) {
            for (d in byDay.split(',')) WEEKDAYS[d.dartTrim().dartUpper()]?.let { weekdays += it }
        }
        if (weekdays.isEmpty()) weekdays += startLocal.dayOfWeek.value

        var produced = 1L
        var weekStart: LocalDate = startLocal.toLocalDate().minusDays(startLocal.dayOfWeek.value - 1L)
        var weekIndex = 0L
        while (true) {
            if (weekIndex % interval == 0L) {
                for (wd in weekdays) {
                    val occ = weekStart.plusDays(wd - 1L)
                        .atTime(startLocal.hour, startLocal.minute)
                        .atZone(zone).toInstant()
                    if (occ.isAfter(base.start) && !occ.isAfter(limit)) {
                        out += base.at(occ)
                        produced++
                        if (count != null && produced >= count) return
                    }
                }
            }
            weekStart = weekStart.plusDays(7)
            weekIndex++
            if (weekStart.atStartOfDay(zone).toInstant().isAfter(limit)) return
            if (produced >= MAX_OCCURRENCES) return
        }
    }

    private fun expandMonthly(
        base: MeetupDate,
        interval: Long,
        byDay: String?,
        count: Long?,
        limit: Instant,
        zone: ZoneId,
        out: MutableList<MeetupDate>,
    ) {
        val startLocal = base.start.atZone(zone).toLocalDateTime()
        var produced = 1L
        var ordinal: Long? = null
        var weekday: Int? = null
        if (!byDay.isNullOrEmpty()) {
            MONTHLY_BYDAY.find(byDay.dartTrim().dartUpper())?.let { m ->
                ordinal = m.groups[1]?.value?.let { dartTryParseInt(it) }
                weekday = WEEKDAYS[m.groupValues[2]]
            }
        }
        val startMonth = YearMonth.from(startLocal)
        var monthIndex = interval
        var safety = 0
        while (true) {
            if (++safety > MONTH_SAFETY) return
            val month = startMonth.plusMonths(monthIndex)
            if (month.atDay(1).atStartOfDay(zone).toInstant().isAfter(limit)) return

            val wd = weekday
            val ord = ordinal
            val occDate: LocalDate? = if (wd != null && ord != null) {
                nthWeekdayOfMonth(month, wd, ord)
            } else {
                startLocal.dayOfMonth.takeIf { it <= month.lengthOfMonth() }?.let { month.atDay(it) }
            }
            val occ = occDate?.atTime(startLocal.hour, startLocal.minute)?.atZone(zone)?.toInstant()
            if (occ != null && occ.isAfter(base.start) && !occ.isAfter(limit)) {
                out += base.at(occ)
                produced++
                if (count != null && produced >= count) return
            }
            monthIndex += interval
            if (produced >= MAX_OCCURRENCES) return
        }
    }

    /** n-th [weekday] (1 = Monday) of [month]; ordinal −1 = last; other ordinals ≤ 0 → `null`. */
    internal fun nthWeekdayOfMonth(month: YearMonth, weekday: Int, ordinal: Long): LocalDate? {
        val lastDay = month.lengthOfMonth()
        return when {
            ordinal > 0 -> {
                val first = month.atDay(1).dayOfWeek.value
                val offset = Math.floorMod(weekday - first, 7)
                val day = 1 + offset + (ordinal - 1) * 7
                if (day > lastDay) null else month.atDay(day.toInt())
            }
            ordinal == -1L -> {
                val last = month.atDay(lastDay).dayOfWeek.value
                val offset = Math.floorMod(last - weekday, 7)
                month.atDay(lastDay - offset)
            }
            else -> null
        }
    }
}
