package space.einundzwanzig.meetup.core.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.crypto.SignedNostrEvent

/**
 * A NIP-01 event with all seven wire fields (`id, pubkey, created_at, kind, tags, content, sig`).
 *
 * This is the `:core:crypto` [SignedNostrEvent] (id computation, BIP-340 signing and
 * `Event.isValid()` live there); `:core:nostr` only adds the JSON bridge in [NostrEvents] and
 * [toJsonObject]. `toJson()` (a Dart-`jsonEncode`d string), `toEventMessage()` and `isValid()`
 * are members of the crypto class.
 */
typealias NostrEvent = SignedNostrEvent

/** JSON conversion of [NostrEvent]s. */
object NostrEvents {

    /**
     * Parses a complete event object with strict Dart types, or returns `null`.
     *
     * Every field must be present with its JSON type: `id`, `pubkey`, `content`, `sig` strings;
     * `created_at` and `kind` Dart ints (`1.0` is not an int); `tags` an array of arrays of
     * strings. The event is **not** verified — use [isValid][SignedNostrEvent.isValid] or
     * [EventGuard.verifiedEvent] for data that came from a relay or a cache.
     */
    fun fromJsonOrNull(element: JsonElement?): NostrEvent? {
        if (element !is JsonObject) return null
        val id = element["id"].dartStringOrNull() ?: return null
        val pubkey = element["pubkey"].dartStringOrNull() ?: return null
        val createdAt = element["created_at"].dartIntOrNull() ?: return null
        val kind = element["kind"].dartIntOrNull()?.toIntExactOrNull() ?: return null
        val tags = strictTags(element["tags"]) ?: return null
        val content = element["content"].dartStringOrNull() ?: return null
        val sig = element["sig"].dartStringOrNull() ?: return null
        return SignedNostrEvent(id, pubkey, createdAt, kind, tags, content, sig)
    }

    /** [fromJsonOrNull] on a JSON text; `null` if the text is not valid JSON. */
    fun fromJsonStringOrNull(text: String): NostrEvent? =
        runCatching { DartJson.decode(text) }.getOrNull()?.let(::fromJsonOrNull)

    private fun strictTags(element: JsonElement?): List<List<String>>? {
        if (element !is JsonArray) return null
        return element.map { tag ->
            if (tag !is JsonArray) return null
            tag.map { it.dartStringOrNull() ?: return null }
        }
    }
}

/**
 * The event as a [JsonObject] in the app's key order `id, pubkey, created_at, kind, tags,
 * content, sig` (`Event.toJson()`). Encode it with `DartJson.encode` for byte parity.
 */
fun NostrEvent.toJsonObject(): JsonObject = JsonObject(
    linkedMapOf(
        "id" to JsonPrimitive(id),
        "pubkey" to JsonPrimitive(pubkey),
        "created_at" to JsonPrimitive(createdAt),
        "kind" to JsonPrimitive(kind),
        "tags" to JsonArray(tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }),
        "content" to JsonPrimitive(content),
        "sig" to JsonPrimitive(sig),
    ),
)

internal fun Long.toIntExactOrNull(): Int? = if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else null

/** `JsonNull` and absent keys both read as Dart `null`. */
internal fun JsonElement?.isDartNull(): Boolean = this == null || this is JsonNull
