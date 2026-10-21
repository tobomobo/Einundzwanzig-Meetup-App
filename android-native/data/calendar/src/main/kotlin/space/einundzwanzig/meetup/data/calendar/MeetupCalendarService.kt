package space.einundzwanzig.meetup.data.calendar

import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import java.time.Instant
import java.time.ZoneId

/**
 * Meetup dates: portal first, iCal feed as fallback (Dart `MeetupCalendarService`, spec 10 §8.2,
 * §8.5). Every screen should use [fetchPortalFirst]. Crest logos found on the way are put into
 * `:data:meetups`' `PortalLogoRegistry` (use its `logoFor`/`absoluteImageUrl`).
 */
interface MeetupCalendarService {

    /**
     * `PortalApiService.getAllMeetupEvents()` mapped to [MeetupDate]s from now − 6 h on, sorted
     * by start; logos recorded. Zero dates or an exception → [fetchIcs]. Never throws.
     */
    suspend fun fetchPortalFirst(): List<MeetupDate>

    /**
     * `GET https://portal.einundzwanzig.space/stream-calendar` (15 s), parsed and RRULE-expanded,
     * sorted, without past filter; any failure → `[]`.
     */
    suspend fun fetchIcs(): List<MeetupDate>

    companion object {
        /** iCal fallback feed. */
        const val CALENDAR_URL: String = "https://portal.einundzwanzig.space/stream-calendar"

        /** Hours a started portal date stays listed. */
        const val PAST_HOURS: Long = 6

        /**
         * Pure mapping of the raw portal events (§8.5): `start` via `PortalStart`, cutoff
         * [now] − 6 h, nested `meetup{…}` or flat `meetup.*` keys, title fallback `Meetup`,
         * url = `link` or `meetup.portalLink`, `meetupId` = `meetup.id`, `portalEventId` = int `id`,
         * list RSVP counts. [onLogo] receives `(name, rawLogo)` for non-empty logos. Sorted by start.
         */
        fun mapPortalEvents(
            raw: List<JsonObject>,
            now: Instant,
            zone: ZoneId = ZoneId.systemDefault(),
            onLogo: (name: String, logo: String) -> Unit = { _, _ -> },
        ): List<MeetupDate> = space.einundzwanzig.meetup.data.calendar.internal.PortalDates.map(raw, now, zone, onLogo)
    }
}
