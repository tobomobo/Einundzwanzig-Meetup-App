package space.einundzwanzig.meetup.data.meetups.nearby

import space.einundzwanzig.meetup.data.meetups.model.Meetup
import java.time.Instant

/** How the nearby search limits dates (Dart `DateMode`). */
enum class DateMode { ANY, SINGLE_DAY, RANGE }

/** Search center source of the nearby screen: GPS ("Hier & jetzt") or a geocoded place ("Geplant"). */
enum class SearchMode { HERE, PLANNED }

/**
 * A meetup date as the nearby search needs it — the fields of the calendar domain's
 * `CalendarEvent`/`MeetupDate` used by spec 09 (§3.3). `:data:calendar` maps its own model onto
 * this type in its [MeetupEventsSource] binding.
 *
 * @property start start instant (Dart `startTime`, a local DateTime).
 * @property portalEventId portal event ID, if the date came from the portal.
 */
data class MeetupEventInfo(
    val title: String,
    val description: String = "",
    val location: String = "",
    val start: Instant,
    val url: String = "",
    val meetupId: String = "",
    val meetupPortalLink: String = "",
    val portalEventId: Int? = null,
)

/**
 * A meetup in the nearby result (Dart `NearbyMeetup`).
 *
 * @property nextEvent the first matching date in the window, if any.
 * @property allEvents all matching dates, ascending, deduplicated by (title, start, location).
 * @property distanceKm distance from the search center.
 */
data class NearbyMeetup(
    val meetup: Meetup,
    val nextEvent: MeetupEventInfo? = null,
    val allEvents: List<MeetupEventInfo> = emptyList(),
    val distanceKm: Double? = null,
) {
    /** `true` unless both coordinates are 0. */
    val hasCoords: Boolean get() = meetup.lat != 0.0 || meetup.lng != 0.0
}

/**
 * Supplies upcoming meetup dates (portal first, ICS fallback) to the nearby search: Dart
 * `MeetupCalendarService().fetchMeetupsPortalFirst()`.
 *
 * Implemented and bound by `:data:calendar` (which depends on this module):
 * ```kotlin
 * @Binds abstract fun meetupEvents(impl: CalendarMeetupEventsSource): MeetupEventsSource
 * ```
 * Without a binding the nearby search runs with no dates (ANY mode still lists meetups).
 */
fun interface MeetupEventsSource {
    /** Upcoming meetup dates; never throws (empty on failure). */
    suspend fun fetchMeetupEvents(): List<MeetupEventInfo>
}
