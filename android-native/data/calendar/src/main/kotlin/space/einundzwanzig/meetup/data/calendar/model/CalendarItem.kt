package space.einundzwanzig.meetup.data.calendar.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One row of the event calendar (Dart `_CalItem`, spec 10 §3.5): a Nostr event, a portal meetup
 * date or a course date.
 *
 * @property url course link (meetup URLs are deliberately not copied, as in Dart).
 * @property nostr the source event of Nostr rows.
 * @property meetup the source date of meetup rows (crest lookup, RSVP).
 */
data class CalendarItem(
    val title: String,
    val description: String,
    val location: String,
    val start: Instant,
    val end: Instant? = null,
    val allDay: Boolean = false,
    val isMeetup: Boolean = false,
    val isCourse: Boolean = false,
    val url: String = "",
    val nostr: NostrCalendarEvent? = null,
    val meetup: MeetupDate? = null,
) {
    /** Row type (UI color: course grey, meetup orange, event purple). */
    val type: CalendarItemType
        get() = when {
            isCourse -> CalendarItemType.COURSE
            isMeetup -> CalendarItemType.MEETUP
            else -> CalendarItemType.NOSTR_EVENT
        }

    /** The event has a badge (Nostr rows only). */
    val hasBadge: Boolean get() = nostr?.badgeEnabled ?: false

    /** Badge image URL of Nostr rows, else `""`. */
    val badgeImage: String get() = nostr?.badgeImageUrl ?: ""

    /** Local date of [start] (grouping key of the month view). */
    fun day(zone: ZoneId = ZoneId.systemDefault()): LocalDate = nostr?.startDate ?: start.atZone(zone).toLocalDate()

    companion object {
        /** Row of a Nostr calendar event (`isMeetup = false`). */
        fun fromNostr(e: NostrCalendarEvent): CalendarItem = CalendarItem(
            title = e.title,
            description = e.description,
            location = e.location,
            start = e.start,
            end = e.end,
            allDay = e.allDay,
            nostr = e,
        )

        /** Row of a portal/ICS meetup date (no end, not all-day, URL dropped). */
        fun fromMeetup(m: MeetupDate): CalendarItem = CalendarItem(
            title = m.title,
            description = m.description,
            location = m.location,
            start = m.start,
            isMeetup = true,
            meetup = m,
        )
    }
}

/** Kind of a [CalendarItem]. */
enum class CalendarItemType { MEETUP, NOSTR_EVENT, COURSE }

/** Type filter of the event calendar (Dart `typeFilter` 0..3). */
enum class CalendarTypeFilter {
    /** 0: everything. */
    ALL,

    /** 1: portal meetup dates. */
    MEETUPS,

    /** 2: Nostr events ("Externe"). */
    EXTERNAL,

    /** 3: course dates. */
    COURSES,
}

/**
 * Input of [space.einundzwanzig.meetup.data.calendar.CalendarEventService.publishEvent]
 * (the Dart named parameters of `publishEvent`).
 *
 * [start]/[end] carry the zone: time events publish the instant, all-day events publish the
 * local date (`YYYY-MM-DD`) of the zoned value.
 *
 * @property title used as passed (the editor trims it).
 * @property description trimmed on publish.
 * @property location trimmed on publish; empty → no `location` tag.
 * @property badgeEnabled without it no badge, `g` or `p` tags are written at all.
 * @property issuers 64-char hex keys (other lengths are skipped).
 * @property formerIssuers hex → removal unix time.
 */
data class CalendarEventDraft(
    val title: String,
    val description: String,
    val location: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime? = null,
    val allDay: Boolean = false,
    val badgeEnabled: Boolean = false,
    val badgeImageUrl: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val issuers: List<String> = emptyList(),
    val formerIssuers: Map<String, Long> = emptyMap(),
) {
    companion object {
        /**
         * Draft that republishes [event] unchanged except for [issuers]/[formerIssuers]
         * (Dart `updateIssuers`). All-day dates are taken from the stored dates.
         */
        fun of(
            event: NostrCalendarEvent,
            issuers: List<String> = event.issuers,
            formerIssuers: Map<String, Long> = event.formerIssuers,
            zone: ZoneId = ZoneId.systemDefault(),
        ): CalendarEventDraft = CalendarEventDraft(
            title = event.title,
            description = event.description,
            location = event.location,
            start = event.startDate?.atStartOfDay(zone) ?: event.start.atZone(zone),
            end = event.endDate?.atStartOfDay(zone) ?: event.end?.atZone(zone),
            allDay = event.allDay,
            badgeEnabled = event.badgeEnabled,
            badgeImageUrl = event.badgeImageUrl,
            lat = event.lat,
            lng = event.lng,
            issuers = issuers,
            formerIssuers = formerIssuers,
        )
    }
}
