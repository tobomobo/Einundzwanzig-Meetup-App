package space.einundzwanzig.meetup.data.calendar

import space.einundzwanzig.meetup.data.calendar.model.FavoriteCards
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.model.MyMeetupDate
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent

/**
 * Data of "Meine Termine" and the Home favorites (spec 10 §2.5, spec 12 §8.3–§8.5). Never throws.
 */
interface MyEventsService {

    /**
     * Nostr events the user accepted (Home `_loadMyEvents`): own RSVPs with status `accepted`,
     * each fetched by address (up to 4 in parallel), unknown/cancelled ones and those starting before
     * now − 1 day dropped, sorted by start.
     */
    suspend fun loadAcceptedEvents(): List<NostrCalendarEvent>

    /**
     * Portal dates of the favorites the user RSVP'd to (Home `_loadMeetupRsvps`): favorites =
     * ordered `{homeMeetupId, …favoriteMeetupIds}`; candidates = [dates] with a portal id from
     * now − 1 day to now + 90 days matching a favorite; kept if `getRsvpCached` says going.
     * Sorted by start. [dates] defaults to `fetchPortalFirst()`.
     */
    suspend fun loadMeetupRsvps(dates: List<MeetupDate>? = null): List<MyMeetupDate>

    /** Withdraws an accepted event: publishes `declined` for its address. `null` = success. */
    suspend fun withdraw(event: NostrCalendarEvent): RsvpError?

    /**
     * Home favorite cards (spec 12 §8.3): per stored favorite the next date from today's local
     * midnight on; sorted (with date by start, then without date by city). Empty without
     * favorites. When [updateWidget] is set, the front card feeds the home-screen widget
     * (`meetupCity`/countdown) — an empty city when there are no favorites (spec 10 §14 item 18).
     * When no dates are available at all (portal and iCal unreachable), the stored widget meetup
     * is left untouched instead of being cleared ([FavoriteCards.Loaded.datesAvailable] `false`).
     */
    suspend fun loadFavoriteCards(dates: List<MeetupDate>? = null, updateWidget: Boolean = true): FavoriteCards

    companion object {
        /** Days ahead the favorite RSVP scan looks. */
        const val RSVP_HORIZON_DAYS: Long = 90
    }
}
