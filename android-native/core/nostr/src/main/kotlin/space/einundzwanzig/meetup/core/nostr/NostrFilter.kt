package space.einundzwanzig.meetup.core.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A NIP-01 REQ filter.
 *
 * [tags] maps the tag **name without `#`** to the wanted values, e.g. `mapOf("d" to listOf("x"))`
 * becomes `"#d":["x"]`; names are case-sensitive (`"p"` ≠ `"P"`). [extra] carries any other
 * key verbatim (e.g. `search`). The JSON key order of [toJson] is `ids, kinds, authors, #tags
 * (insertion order), since, until, limit, extra` — relays ignore the order; only the values
 * matter for [EventGuard.answersFilter].
 *
 * Code that already builds the filter as a [JsonObject] (as the Dart services do with maps) can
 * pass that object directly to `RelayClient` and [EventGuard]; this class is a typed builder.
 *
 * **Byte parity:** the key order of [toJson] is fixed and often differs from the Dart map
 * literal (e.g. spec 07 §5.2 batch nicknames send `{"kinds","authors","limit","#d"}`). Where the
 * exact REQ string matters (ported Dart tests that assert the frame), build the filter with
 * `jsonObjectOf(...)` from `:core:common` in Dart insertion order and pass that [JsonObject].
 */
data class NostrFilter(
    val ids: List<String>? = null,
    val kinds: List<Int>? = null,
    val authors: List<String>? = null,
    val tags: Map<String, List<String>> = emptyMap(),
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    /** The filter object as sent in `["REQ", sub, <filter>]`. */
    fun toJson(): JsonObject {
        val map = LinkedHashMap<String, JsonElement>()
        ids?.let { map["ids"] = strings(it) }
        kinds?.let { list -> map["kinds"] = JsonArray(list.map { JsonPrimitive(it) }) }
        authors?.let { map["authors"] = strings(it) }
        for ((name, values) in tags) map["#$name"] = strings(values)
        since?.let { map["since"] = JsonPrimitive(it) }
        until?.let { map["until"] = JsonPrimitive(it) }
        limit?.let { map["limit"] = JsonPrimitive(it) }
        map.putAll(extra)
        return JsonObject(map)
    }

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
}
