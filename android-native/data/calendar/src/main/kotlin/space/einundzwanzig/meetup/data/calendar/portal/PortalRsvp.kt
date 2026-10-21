package space.einundzwanzig.meetup.data.calendar.portal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.data.calendar.internal.dartIntValue
import space.einundzwanzig.meetup.data.calendar.internal.dartToString
import space.einundzwanzig.meetup.data.calendar.internal.isDartNull
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate

/**
 * Tolerant readers of the undocumented portal RSVP map (spec 10 §3.4, §14 item 11). Every key
 * fallback of the Dart code is kept.
 */
object PortalRsvp {

    /** Portal status values. */
    const val ATTENDING: String = "attending"
    const val MAYBE: String = "maybe"
    const val NONE: String = "none"

    private fun JsonObject.firstNonNull(vararg keys: String): JsonElement? =
        keys.firstNotNullOfOrNull { k -> this[k].takeUnless { it.isDartNull } }

    /** `attendees ?? count ?? total ?? rsvps` if int, else `-1` (then no count is shown). */
    fun count(r: JsonObject?): Int = r?.firstNonNull("attendees", "count", "total", "rsvps").dartIntValue() ?: -1

    /** `might ?? might_attendees` if int, else `-1`. */
    fun might(r: JsonObject?): Int = r?.firstNonNull("might", "might_attendees").dartIntValue() ?: -1

    /** `attendees ?? count` if int, else `-1` (My events / Home). */
    fun attendees(r: JsonObject?): Int = r?.firstNonNull("attendees", "count").dartIntValue() ?: -1

    /**
     * Dart `(r['status'] ?? '').toString()`: the status (`attending|maybe|none`), a non-string
     * value printed the Dart way, or `""`. "Is the user going?" is answered only by
     * `:data:meetups` `PortalApiService.isGoing` (single implementation).
     */
    fun status(r: JsonObject?): String = r?.get("status")?.takeUnless { it.isDartNull }?.dartToString() ?: ""

    /** Initial info from the list response: `{count: attendees|0, might: might_attendees|0}`. */
    fun initial(date: MeetupDate): JsonObject = JsonObject(
        mapOf(
            "count" to JsonPrimitive(date.attendees ?: 0),
            "might" to JsonPrimitive(date.mightAttendees ?: 0),
        ),
    )

    /** Dart `{...old, ...response}` (shallow, later keys win). */
    fun merge(old: JsonObject?, update: JsonObject?): JsonObject = JsonObject((old ?: emptyMap()) + (update ?: emptyMap()))

    /** `r['status'] = status` after a successful RSVP. */
    fun withStatus(r: JsonObject?, status: String): JsonObject = merge(r, JsonObject(mapOf("status" to JsonPrimitive(status))))

    /**
     * The status the details-sheet toggle sends: tapping the active button withdraws (`none`),
     * otherwise [pressed] (`attending` or `maybe`).
     */
    fun toggleTarget(current: String, pressed: String): String = if (current == pressed) NONE else pressed
}
