package space.einundzwanzig.meetup.feature.home

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Pure display rules of the dashboard (spec 12 §2.5.4, §2.5.5, §8.2, §10 items 4, 6, 8). */
object HomeFormat {

    /** `_daysUntil`: calendar days from today to the day of [target] in [now]'s zone (DST-safe). */
    fun daysUntil(target: Instant, now: ZonedDateTime): Long {
        val today = now.toLocalDate()
        val day = target.atZone(now.zone).toLocalDate()
        return ChronoUnit.DAYS.between(today, day)
    }

    /** Fav card label font size (sp) by label length: `>20 → 13`, `>15 → 15`, `>10 → 17`, else 22. */
    fun favLabelFontSize(length: Int): Int = when {
        length > 20 -> 13
        length > 15 -> 15
        length > 10 -> 17
        else -> 22
    }

    /** Live session time left: `'${h}h ${mm}m'` (`3725 s → "1h 02m"`). */
    fun sessionTimeLeft(remainingSeconds: Long): String {
        val r = remainingSeconds.coerceAtLeast(0)
        val hours = r / 3600
        val minutes = (r / 60) % 60
        return "${hours}h ${minutes.toString().padStart(2, '0')}m"
    }

    /** Fav card date `d.m.yyyy` without zero padding, independent of the locale. */
    fun favDate(date: LocalDate): String = "${date.dayOfMonth}.${date.monthValue}.${date.year}"

    /** Next local midnight + 2 s after [now] (midnight refresh timer, spec 12 §8.8). */
    fun millisUntilMidnightRefresh(now: ZonedDateTime): Long {
        val next = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusSeconds(2)
        return ChronoUnit.MILLIS.between(now, next).coerceAtLeast(0)
    }

    /** Nostr tile "new" dot: last open (unix seconds) older than 24 h. */
    fun isNostrNew(lastSeenSeconds: Long, nowSeconds: Long): Boolean = lastSeenSeconds < nowSeconds - 86_400

    /** Proximity of a fav card's next date: 0 = today/past, 1 = within 3 days, 2 = later. */
    fun proximity(days: Long): Int = when {
        days <= 0 -> 0
        days <= 3 -> 1
        else -> 2
    }

    /** Local start of the day of [now] (an event stays "next" for the whole calendar day). */
    fun startOfDay(now: ZonedDateTime): Instant = now.toLocalDate().atStartOfDay(now.zone).toInstant()

    /** Today's local date in [zone]. */
    fun today(nowMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

    /** Badge counter on the chat field: `99+` cap. */
    fun badgeText(count: Int): String = if (count > 99) "99+" else count.toString()
}
