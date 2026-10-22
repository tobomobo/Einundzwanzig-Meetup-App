package space.einundzwanzig.meetup.data.meetups

import space.einundzwanzig.meetup.data.meetups.internal.dartLower
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetupEvent

/**
 * Pure list rules of the meetup screens (spec 09 §2.1, §2.3, §2.4) that need Dart `toLowerCase`
 * semantics (spec 00 item 12), so `:feature:meetups` does not re-implement case mapping.
 */
object MeetupScreenRules {

    /**
     * Full-screen favorites picker search (Dart `MeetupSelectionScreen`): meetups whose `city` or
     * `country` contains [query] case-insensitively (NOT the group name). Empty query → all.
     */
    fun selectionFilter(meetups: List<Meetup>, query: String): List<Meetup> {
        if (query.isEmpty()) return meetups
        val q = query.dartLower()
        return meetups.filter { it.city.dartLower().contains(q) || it.country.dartLower().contains(q) }
    }

    /**
     * Meetup details date filter (Dart `MeetupDetailsScreen._load`): the date's title or location
     * contains the city, case-insensitively, no trim.
     */
    fun detailsDateMatches(title: String, location: String, city: String): Boolean {
        val c = city.dartLower()
        return title.dartLower().contains(c) || location.dartLower().contains(c)
    }

    /**
     * `true` when the nearby card shows the date's location: non-empty and not the placeholder
     * "ort unbekannt" (case-insensitive).
     */
    fun showsEventLocation(location: String): Boolean =
        location.isNotEmpty() && location.dartLower() != "ort unbekannt"

    /**
     * The dates of portal meetup [meetupId] in the order of "Termine verwalten" (Dart
     * `ManageEventsScreen`): lexicographic on the raw `start` string, stable.
     */
    fun eventsOfMeetup(events: List<PortalMeetupEvent>, meetupId: Int): List<PortalMeetupEvent> =
        events.filter { it.meetupId == meetupId }.sortedBy { it.start }

    /**
     * Resolves the date an editor was opened for (ROUTES.md `PortalEventEditor`): the date at
     * [eventIndex] if its id equals [eventId], else the date whose id is [eventId] (non-null), else
     * `null`.
     */
    fun resolveEditedEvent(
        events: List<PortalMeetupEvent>,
        meetupId: Int,
        eventId: Int?,
        eventIndex: Int?,
    ): PortalMeetupEvent? {
        val list = eventsOfMeetup(events, meetupId)
        if (eventIndex != null) {
            val atIndex = list.getOrNull(eventIndex)
            if (atIndex != null && atIndex.id == eventId) return atIndex
        }
        if (eventId != null) return list.firstOrNull { it.id == eventId }
        return null
    }
}
