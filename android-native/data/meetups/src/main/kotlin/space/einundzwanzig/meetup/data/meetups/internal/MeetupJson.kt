package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.json.dartNumOrNull
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalUrls

/**
 * Field mapping of `GET /api/mobile/meetups` entries (spec 09 §3.1, §8.1; Dart
 * `MeetupService.fetchMeetups`).
 *
 * `??` is null-coalescing only: an empty `city: ""` stays `""`. Unlike Dart, which throws on an
 * unexpected field type and loses the whole list, every field is coerced on its own (Dart
 * `toString()` of primitives); that is strictly more robust (spec 09 §14 item 18).
 */
internal object MeetupJson {

    fun parse(json: JsonObject): Meetup {
        val image = firstNonEmpty(json, "cover", "image", "logo")

        var portalLink = json.stringOrNull("portalLink")?.dartTrim() ?: ""
        val slug = json.stringOrNull("slug")?.dartTrim() ?: ""
        if (portalLink.isEmpty() && slug.isNotEmpty()) portalLink = PortalUrls.meetupPageFor(slug)

        return Meetup(
            id = json.stringOrNull("id") ?: json.stringOrNull("name") ?: "unknown",
            name = json.stringOrNull("name") ?: "",
            city = json.stringOrNull("city") ?: json.stringOrNull("name") ?: "Unbekannt",
            country = json.stringOrNull("country") ?: "DE",
            telegramLink = json.stringOrNull("url") ?: "",
            logoUrl = json.stringOrNull("logo") ?: "",
            description = json.stringOrNull("intro") ?: "",
            website = json.stringOrNull("website") ?: "",
            portalLink = portalLink,
            twitterUsername = json.stringOrNull("twitter_username") ?: "",
            nostrNpub = json.stringOrNull("nostr") ?: "",
            lat = firstNum(json["latitude"], json["lat"]),
            lng = firstNum(json["longitude"], json["lon"], json["lng"]),
            coverImagePath = image,
        )
    }

    /**
     * Dart `_firstNum`: the first candidate that is a number (`toDouble()`) or a string Dart's
     * `double.tryParse` accepts; `0.0` if none is.
     */
    fun firstNum(vararg candidates: JsonElement?): Double {
        for (c in candidates) {
            if (c !is JsonPrimitive || c.isDartNull) continue
            if (c.isString) {
                dartTryParseDouble(c.content)?.let { return it }
            } else {
                c.dartNumOrNull()?.let { return it }
            }
        }
        return 0.0
    }

    /** Dart `if (json[k] != null && json[k].toString().isNotEmpty) image = json[k]` over [keys]. */
    private fun firstNonEmpty(json: JsonObject, vararg keys: String): String {
        for (k in keys) {
            val v = json.stringOrNull(k)
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }
}
