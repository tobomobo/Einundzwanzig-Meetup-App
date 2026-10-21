package space.einundzwanzig.meetup.data.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnectException
import space.einundzwanzig.meetup.core.nostr.toJsonObject
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

internal const val SECRET1 = "0000000000000000000000000000000000000000000000000000000000000001"
internal const val SECRET2 = "0000000000000000000000000000000000000000000000000000000000000002"
internal const val SECRET3 = "0000000000000000000000000000000000000000000000000000000000000003"

/** 2026-10-06T00:00Z, the "now" of the Dart lookup test. */
internal val LOOKUP_NOW: Long = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()

internal class FakeTime(var millis: Long = LOOKUP_NOW) : TimeSource {
    override fun nowMillis(): Long = millis
}

internal fun pubOf(secret: String): String = KeyPair.fromPrivateKeyHex(secret).publicKeyHex

/** Dart `Event.from(...)` with zero aux randomness (deterministic). */
internal fun signed(
    secret: String = SECRET1,
    kind: Int = 0,
    content: String = "{\"picture\":\"https://images.example/avatar.png\"}",
    tags: List<List<String>> = emptyList(),
    createdAt: Long,
): NostrEvent = EventIds.sign(KeyPair.fromPrivateKeyHex(secret), createdAt, kind, tags, content, ByteArray(32))

internal fun NostrEvent.json(): JsonObject = toJsonObject()

/**
 * Scripted relays on [FakeRelayConnector]: every REQ is answered with the stored events of the
 * relay that match the REQ's filter (relay-side filtering like a real relay, using the app's
 * own filter matcher), then EOSE. Unknown relays are unreachable; [silent] relays never answer.
 */
internal class FakeRelays {
    val events = HashMap<String, MutableList<JsonObject>>()
    val unfiltered = HashMap<String, MutableList<JsonObject>>()
    val silent = HashSet<String>()
    val reqs = CopyOnWriteArrayList<Pair<String, JsonArray>>()

    fun add(relay: String, vararg evs: JsonObject) {
        events.getOrPut(relay) { mutableListOf() } += evs
    }

    /** Sent on every REQ regardless of the filter (a misbehaving relay). */
    fun addUnfiltered(relay: String, vararg evs: JsonObject) {
        unfiltered.getOrPut(relay) { mutableListOf() } += evs
    }

    val connector = FakeRelayConnector { url ->
        if (url !in events && url !in unfiltered && url !in silent) {
            throw RelayConnectException(url, java.io.IOException("refused"))
        }
        FakeRelaySocket(url) { frame, socket ->
            val msg = DartJson.decode(frame) as JsonArray
            if (msg[0].jsonPrimitive.content != "REQ") return@FakeRelaySocket
            reqs += url to msg
            if (url in silent) return@FakeRelaySocket
            val sub = msg[1].jsonPrimitive.content
            val filter = msg[2] as JsonObject
            for (ev in events[url].orEmpty()) {
                if (EventGuard.answersFilter(ev, filter)) socket.push(listOf("EVENT", sub, ev))
            }
            for (ev in unfiltered[url].orEmpty()) socket.push(listOf("EVENT", sub, ev))
            socket.push(listOf("EOSE", sub))
        }
    }

    fun connectsTo(relay: String): Int = connector.connects.count { it == relay }
}
