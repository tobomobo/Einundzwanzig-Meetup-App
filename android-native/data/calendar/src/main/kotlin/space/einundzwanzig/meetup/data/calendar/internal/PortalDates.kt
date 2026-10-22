package space.einundzwanzig.meetup.data.calendar.internal

import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.data.calendar.MeetupCalendarService
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.time.PortalStart
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Dart `fetchMeetupsPortalFirst` mapping (spec 10 §2.2 step 1, §8.5). */
internal object PortalDates {

    fun map(
        raw: List<JsonObject>,
        now: Instant,
        zone: ZoneId,
        onLogo: (String, String) -> Unit,
    ): List<MeetupDate> {
        val cutoff = now.minus(Duration.ofHours(MeetupCalendarService.PAST_HOURS))
        val out = ArrayList<MeetupDate>()
        for (e in raw) {
            val start = PortalStart.portalStart(e.stringOrEmpty("start"), zone) ?: continue
            if (start.isBefore(cutoff)) continue
            fun mv(key: String): String {
                val nested = e["meetup"]
                if (nested is JsonObject && !nested[key].isDartNull) return nested[key]!!.dartToString()
                val flat = e["meetup.$key"]
                return if (flat.isDartNull) "" else flat!!.dartToString()
            }
            val name = mv("name").dartTrim().ifEmpty { MeetupDate.DEFAULT_TITLE }
            val logo = mv("logo")
            if (logo.isNotEmpty()) onLogo(name, logo)
            val link = e.stringOrEmpty("link")
            out += MeetupDate(
                title = name,
                titleIsFallback = mv("name").dartTrim().isEmpty(),
                description = e.stringOrEmpty("description"),
                location = e.stringOrEmpty("location"),
                start = start,
                url = link.ifEmpty { mv("portalLink") },
                meetupId = mv("id"),
                meetupPortalLink = mv("portalLink"),
                portalEventId = e["id"].dartIntValue(),
                logo = logo,
                attendees = e["attendees"].dartIntValue(),
                mightAttendees = e["might_attendees"].dartIntValue(),
            )
        }
        return out.sortedBy { it.start }
    }
}
