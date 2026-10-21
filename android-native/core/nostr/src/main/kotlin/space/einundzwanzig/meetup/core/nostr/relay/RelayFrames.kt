package space.einundzwanzig.meetup.core.nostr.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.NostrFilter
import space.einundzwanzig.meetup.core.nostr.toJsonObject

/**
 * Client → relay frames, encoded byte-identically to Dart `jsonEncode([...])`.
 */
object RelayFrames {

    /** `["REQ", subId, filter1, filter2, …]`. */
    fun req(subscriptionId: String, filters: List<JsonObject>): String =
        DartJson.encode(JsonArray(listOf<JsonElement>(JsonPrimitive("REQ"), JsonPrimitive(subscriptionId)) + filters))

    /** `["REQ", subId, filter]`. */
    fun req(subscriptionId: String, filter: JsonObject): String = req(subscriptionId, listOf(filter))

    /** `["REQ", subId, filter]` from a typed filter. */
    fun req(subscriptionId: String, filter: NostrFilter): String = req(subscriptionId, listOf(filter.toJson()))

    /** `["CLOSE", subId]`. */
    fun close(subscriptionId: String): String = DartJson.encode(listOf("CLOSE", subscriptionId))

    /** `["EVENT", <event>]` (= `SignedEvent.toEventMessage()`). */
    fun event(event: NostrEvent): String = event.toEventMessage()

    /** `["EVENT", <raw event object>]` for callers that publish a stored/raw map. */
    fun event(rawEvent: JsonObject): String = DartJson.encode(JsonArray(listOf(JsonPrimitive("EVENT"), rawEvent)))

    /** NIP-42 `["AUTH", <signed kind-22242 event>]`. */
    fun auth(signedAuthEvent: NostrEvent): String =
        DartJson.encode(JsonArray(listOf(JsonPrimitive("AUTH"), signedAuthEvent.toJsonObject())))
}

/**
 * NIP-42 building blocks. The handshake state machine itself (spec 11 §8.2, Dart
 * `ChatService.authenticateSocket`) lives in `:data:chat` and plugs into [RelayClient] as a
 * [RelayAuthenticator].
 */
object Nip42 {
    /** Kind of the client authentication event. */
    const val KIND_CLIENT_AUTH: Int = 22242

    /** Tags of the auth event: `[["relay", relayUrl], ["challenge", challenge]]` (content `""`). */
    fun authTags(relayUrl: String, challenge: String): List<List<String>> =
        listOf(listOf("relay", relayUrl), listOf("challenge", challenge))
}

/**
 * Hook for relays that require NIP-42 before they answer (the group-chat relay).
 *
 * [RelayClient] connects, then hands the fresh socket to [authenticate] instead of listening
 * itself. The authenticator must:
 * 1. register the socket's single listener ([RelaySocket.listen]) and keep it for the socket's
 *    whole lifetime (later AUTH challenges are answered on the same socket),
 * 2. consume the frames that belong to the handshake (`AUTH`, the `OK` of its auth events),
 * 3. forward **every other decoded array** to [onMessage] (also after it returned),
 * 4. return the socket once the relay confirmed the AUTH, or close it and return `null`.
 *
 * [onMessage] is thread-safe and non-blocking.
 */
fun interface RelayAuthenticator {
    suspend fun authenticate(socket: RelaySocket, onMessage: (JsonArray) -> Unit): RelaySocket?
}
