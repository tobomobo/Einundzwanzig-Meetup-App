package space.einundzwanzig.meetup.data.calendar.model

import space.einundzwanzig.meetup.data.meetups.nearby.MeetupEventInfo
import java.time.Instant

/**
 * One meetup date from the portal (`/api/meetup-events/{date}`) or the iCal fallback feed
 * (Dart `CalendarEvent`, spec 10 §3.1).
 *
 * Dart compares these by identity. Do not use data-class equality as a key for per-date UI state
 * (RSVP, busy flags): key by [portalEventId] instead (spec 10 §14 item 13).
 *
 * @property title portal `meetup.name` (trimmed, fallback `"Meetup"`) or ICS `SUMMARY`.
 * @property description portal `description` or the cleaned ICS `DESCRIPTION`.
 * @property location portal `location` (`""` if absent) or ICS `LOCATION` (fallback `"Ort unbekannt"`).
 * @property start start instant (Dart local `startTime`).
 * @property url portal `link`, else `meetup.portalLink`; `""` for ICS dates.
 * @property meetupId portal ID of the **meetup** (`meetup.id`), `""` when unknown.
 * @property meetupPortalLink portal page of the hosting meetup, distinct from [url].
 * @property portalEventId portal ID of this **date** (RSVPs hang on it); `null` for ICS dates.
 * @property logo raw `meetup.logo` (possibly relative; resolve with `PortalLogoRegistry.absoluteImageUrl`).
 * @property attendees portal `attendees` of the list response, if it was an int.
 * @property mightAttendees portal `might_attendees` of the list response, if it was an int.
 */
data class MeetupDate(
    val title: String,
    val description: String,
    val location: String,
    val start: Instant,
    val url: String,
    val meetupId: String = "",
    val meetupPortalLink: String = "",
    val portalEventId: Int? = null,
    val logo: String = "",
    val attendees: Int? = null,
    val mightAttendees: Int? = null,
) {
    /** The fields the nearby search of `:data:meetups` needs. */
    fun toEventInfo(): MeetupEventInfo = MeetupEventInfo(
        title = title,
        description = description,
        location = location,
        start = start,
        url = url,
        meetupId = meetupId,
        meetupPortalLink = meetupPortalLink,
        portalEventId = portalEventId,
    )

    /** Dart `RecurrenceExpander._cloneAt`: every field copied, new [start]. */
    fun at(newStart: Instant): MeetupDate = copy(start = newStart)

    companion object {
        /** Title fallback of portal and ICS dates (Dart literal). */
        const val DEFAULT_TITLE: String = "Meetup"

        /** Location fallback of ICS dates without `LOCATION` (Dart literal). */
        const val UNKNOWN_LOCATION: String = "Ort unbekannt"
    }
}

/**
 * A portal meetup date the user RSVP'd to, shown on "Meine Termine" (Dart `MyMeetupDate`).
 *
 * @property favKey the stored favorite that matched (portal ID or legacy city), else `event.meetupId`.
 * @property label `MeetupService.labelFor(favKey)`.
 * @property attendees `attendees ?? count` of the RSVP map if int, else `-1`.
 */
data class MyMeetupDate(
    val favKey: String,
    val label: String,
    val event: MeetupDate,
    val attendees: Int = -1,
)

/**
 * One favorite card of the Home carousel (spec 12 §8.3), also the source of the widget's
 * "next meetup".
 *
 * @property favKey stored favorite (portal ID or legacy city name).
 * @property label `MeetupService.labelFor(favKey)`.
 * @property city `MeetupService.cityFor(favKey)`.
 * @property next the next date of this meetup from today's local midnight on, or `null`.
 */
data class FavoriteMeetupCard(
    val favKey: String,
    val label: String,
    val city: String,
    val next: MeetupDate?,
)

/**
 * Outcome of `MyEventsService.loadFavoriteCards`, so the UI can tell "no favorites" from
 * "load failed". [cards] is the list to render in every case (empty for [NoFavorites]/[Failed]).
 */
sealed interface FavoriteCards {
    /** Cards to render. */
    val cards: List<FavoriteMeetupCard>

    /** The user has no favorite and no home meetup. */
    data object NoFavorites : FavoriteCards {
        override val cards: List<FavoriteMeetupCard> get() = emptyList()
    }

    /**
     * Cards built. [datesAvailable] is `false` when no meetup dates could be loaded at all
     * (portal and iCal unreachable or empty): every card then has `next = null`, and the
     * stored widget meetup was left untouched.
     */
    data class Loaded(override val cards: List<FavoriteMeetupCard>, val datesAvailable: Boolean) : FavoriteCards

    /** An unexpected error; already logged. */
    data object Failed : FavoriteCards {
        override val cards: List<FavoriteMeetupCard> get() = emptyList()
    }
}
