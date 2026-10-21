package space.einundzwanzig.meetup.data.calendar.format

import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.data.calendar.internal.two
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Locale-independent text pieces of the calendar screens and details sheets (the non-UI part of
 * `calendar_screen.dart`, `event_calendar_screen.dart`, `my_events_screen.dart` and
 * `event_details_sheet.dart`). Localized words (month names, "Uhr", labels) stay in the UI.
 */
object CalendarFormat {

    private val LINK = Regex("https?://[^\\s)\\]>,]+")

    /** `HH:MM` local. */
    fun hhmm(start: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = start.atZone(zone)
        return "${two(d.hour)}:${two(d.minute)}"
    }

    /** Meetup-date details sheet: `DD.MM.YYYY · HH:MM` (all two-digit padded). */
    fun meetupWhen(start: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = start.atZone(zone)
        return "${two(d.dayOfMonth)}.${two(d.monthValue)}.${d.year} · ${two(d.hour)}:${two(d.minute)}"
    }

    /**
     * Share text of a meetup date: `"$title · $when"` + `"\n$location"` (if non-empty) +
     * `"\n$url"` (if non-empty).
     */
    fun shareText(title: String, `when`: String, location: String, url: String): String = buildString {
        append(title).append(" · ").append(`when`)
        if (location.isNotEmpty()) append('\n').append(location)
        if (url.isNotEmpty()) append('\n').append(url)
    }

    /**
     * Distinct `https?://…` links in `"$description $location"` in first-seen order (details
     * sheet of the event calendar). Open only through `SafeUrl` (http/https with host).
     */
    fun links(description: String, location: String): List<String> =
        LINK.findAll("$description $location").map { it.value }.distinct().toList()

    /** My meetups card: `D.M.YYYY · HH:MM` (day/month unpadded). */
    fun myMeetupWhen(start: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = start.atZone(zone)
        return "${d.dayOfMonth}.${d.monthValue}.${d.year} · ${two(d.hour)}:${two(d.minute)}"
    }

    /** My events card date: `D.M.YYYY`. */
    fun shortDate(day: LocalDate): String = "${day.dayOfMonth}.${day.monthValue}.${day.year}"

    /** Editor date button: `DD.MM.YYYY` (all-day) or `DD.MM.YYYY  HH:MM` (two spaces). */
    fun editorDate(value: Instant, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = value.atZone(zone)
        val date = "${two(d.dayOfMonth)}.${two(d.monthValue)}.${d.year}"
        return if (allDay) date else "$date  ${two(d.hour)}:${two(d.minute)}"
    }

    /** Editor map location: `"lat, lng"` with 5 decimals (Dart `toStringAsFixed(5)`). */
    fun coordinates(lat: Double, lng: Double): String = "${lat.toStringAsFixed(5)}, ${lng.toStringAsFixed(5)}"

    /** Whole days until [start] (Dart `difference(now).inDays`, truncated toward zero). */
    fun daysUntil(start: Instant, now: Instant): Long = Duration.between(now, start).toDays()

    /** Date color step of My events: ≤ 0 days → [Proximity.NOW], ≤ 3 → [Proximity.SOON], else [Proximity.LATER]. */
    fun proximity(start: Instant, now: Instant): Proximity {
        val days = daysUntil(start, now)
        return when {
            days <= 0 -> Proximity.NOW
            days <= 3 -> Proximity.SOON
            else -> Proximity.LATER
        }
    }

    /** Proximity class of a date (orange / orange 80 % / tertiary). */
    enum class Proximity { NOW, SOON, LATER }

    /** Unread badge text: `99+` above 99. */
    fun unreadBadge(count: Int): String = if (count > 99) "99+" else count.toString()

    /** Attendee npub: first 10 + `…` + last 6 when longer than 16. */
    fun shortNpub(npub: String): String =
        if (npub.length > 16) "${npub.substring(0, 10)}…${npub.substring(npub.length - 6)}" else npub

    /** Attendee avatar letter: first character upper-cased, `?` for an empty name. */
    fun initial(name: String): String = if (name.isEmpty()) "?" else name.substring(0, 1).uppercase()
}
