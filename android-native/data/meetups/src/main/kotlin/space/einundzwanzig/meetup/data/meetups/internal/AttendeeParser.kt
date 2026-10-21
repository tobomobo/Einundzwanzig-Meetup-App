package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.data.meetups.portal.Attendee

/** Tolerant attendee list reader of Dart `getRsvpAttendees` (`parse`/`cleanName`, spec 09 §8.9). */
internal object AttendeeParser {
    private val ID_PREFIX = Regex("""^id_\d+\|""")

    /** Removes the portal's `id_<userId>|` prefix (`"id_12|Alice"` → `"Alice"`). */
    fun cleanName(raw: String): String = ID_PREFIX.find(raw)?.let { raw.substring(it.range.last + 1) } ?: raw

    /** Parses a list of attendee objects or bare name strings; anything that is not a list → `[]`. */
    fun parse(list: JsonElement?): List<Attendee> {
        if (list !is JsonArray) return emptyList()
        val out = mutableListOf<Attendee>()
        for (item in list) {
            when {
                item is JsonObject -> {
                    val inner = first(item, "user", "member", "profile") ?: item
                    val src = inner as? JsonObject ?: item
                    val npub = (first(src, "npub", "pubkey", "public_key") ?: first(item, "npub", "pubkey"))
                        ?.dartToString() ?: ""
                    val name = cleanName(
                        (first(src, "name", "nickname", "display_name", "username", "nick") ?: first(item, "name", "nickname"))
                            ?.dartToString() ?: "",
                    )
                    if (npub.isNotEmpty() || name.isNotEmpty()) out += Attendee(npub, name)
                }
                item is JsonPrimitive && item.isString && item.content.isNotEmpty() ->
                    out += Attendee("", cleanName(item.content))
            }
        }
        return out
    }

    /** Dart `a[k1] ?? a[k2] ?? …`. */
    private fun first(obj: JsonObject, vararg keys: String): JsonElement? =
        keys.firstNotNullOfOrNull { k -> obj[k].takeUnless { it.isDartNull } }
}
