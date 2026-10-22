package space.einundzwanzig.meetup.core.nostr.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.nostr.internal.dartToLogString

/**
 * A relay → client message (NIP-01 / NIP-42), classified the way the Dart services test them
 * (`msg[0] == 'EVENT' && msg.length >= 3`, …). [raw] is always the decoded array.
 */
sealed interface RelayMessage {
    /** The decoded JSON array. */
    val raw: JsonArray

    /** `["EVENT", <sub>, <event>, …]`; [event] is unverified — pass it to `EventGuard.verifiedEvent`. */
    data class Event(override val raw: JsonArray, val subscriptionId: String?, val event: JsonElement) : RelayMessage

    /** `["EOSE", <sub>]`. */
    data class Eose(override val raw: JsonArray, val subscriptionId: String?) : RelayMessage

    /**
     * `["CLOSED", <sub>, <reason>]`. [reason] is `msg[2].toString()` or `null` when absent
     * (Dart callers print `'ohne Grund'` / `'ohne Angabe'`).
     */
    data class Closed(override val raw: JsonArray, val subscriptionId: String?, val reason: String?) : RelayMessage

    /**
     * `["OK", <event id>, <accepted>, <message>]` (at least 3 elements). [accepted] is
     * `msg[2] == true`; [message] is `msg[3].toString()` or `""`.
     */
    data class Ok(override val raw: JsonArray, val eventId: String?, val accepted: Boolean, val message: String) :
        RelayMessage

    /** `["NOTICE", <message>]`. */
    data class Notice(override val raw: JsonArray, val message: String) : RelayMessage

    /** NIP-42 `["AUTH", <challenge>]` (a string challenge). */
    data class Auth(override val raw: JsonArray, val challenge: String) : RelayMessage

    /** Any other non-empty array (including malformed known types, e.g. `["OK", id]`). */
    data class Other(override val raw: JsonArray) : RelayMessage

    companion object {
        /**
         * Decodes a text frame like Dart `jsonDecode(data) as List`. Returns `null` for invalid
         * JSON, non-arrays and empty arrays.
         */
        fun parse(frame: String): RelayMessage? {
            val element = try {
                DartJson.decode(frame)
            } catch (_: Exception) {
                return null
            }
            return (element as? JsonArray)?.let(::of)
        }

        /** Classifies an already decoded array; `null` if it is empty. */
        fun of(msg: JsonArray): RelayMessage? {
            if (msg.isEmpty()) return null
            val type = msg[0].dartStringOrNull()
            val sub = msg.getOrNull(1).dartStringOrNull()
            return when {
                type == "EVENT" && msg.size >= 3 -> Event(msg, sub, msg[2])
                type == "EOSE" -> Eose(msg, sub)
                type == "CLOSED" -> Closed(msg, sub, msg.getOrNull(2)?.let(::dartToLogString))
                type == "OK" && msg.size >= 3 ->
                    Ok(
                        raw = msg,
                        eventId = sub,
                        accepted = (msg[2] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true,
                        message = msg.getOrNull(3)?.let(::dartToLogString) ?: "",
                    )
                type == "NOTICE" -> Notice(msg, msg.getOrNull(1)?.let(::dartToLogString) ?: "")
                type == "AUTH" && sub != null -> Auth(msg, sub)
                else -> Other(msg)
            }
        }
    }
}
