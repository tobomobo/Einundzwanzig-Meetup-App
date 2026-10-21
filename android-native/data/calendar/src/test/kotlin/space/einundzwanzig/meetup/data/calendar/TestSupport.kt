package space.einundzwanzig.meetup.data.calendar

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/** Settable wall clock. */
class FakeTime(var now: Long = 1_800_000_000_000L) : TimeSource {
    override fun nowMillis(): Long = now
}

/** A test logger that never touches disk. */
fun testLog() = DiagnosticLogger(timeSource = FakeTime())

/**
 * A [SigningService] built with a dynamic proxy: only `npub()`, `pubkeyHex()` and `signEvent()`
 * are implemented (suspend functions return directly).
 */
class FakeSigner(var keyPair: KeyPair? = KeyPair.generate(), var createdAt: Long = 1_800_000_000L) {
    val signed = CopyOnWriteArrayList<NostrEvent>()
    var failSigning: Exception? = null

    val service: SigningService = Proxy.newProxyInstance(
        SigningService::class.java.classLoader,
        arrayOf(SigningService::class.java),
    ) { _, method, args ->
        when (method.name) {
            "npub" -> keyPair?.npub
            "pubkeyHex" -> keyPair?.publicKeyHex
            "signEvent" -> {
                failSigning?.let { throw it }
                @Suppress("UNCHECKED_CAST")
                EventIds.sign(
                    keyPair!!,
                    createdAt = createdAt,
                    kind = args[0] as Int,
                    tags = args[1] as List<List<String>>,
                    content = args[2] as String,
                ).also { signed += it }
            }
            "toString" -> "FakeSigner"
            "hashCode" -> 0
            "equals" -> false
            else -> throw UnsupportedOperationException(method.name)
        }
    } as SigningService
}

/** Signs a raw event object (as a relay would deliver it). */
fun signedRaw(
    keyPair: KeyPair,
    kind: Int,
    tags: List<List<String>>,
    content: String = "",
    createdAt: Long = 1_800_000_000L,
): JsonObject = DartJson.decode(EventIds.sign(keyPair, createdAt, kind, tags, content).toJson()) as JsonObject

/**
 * A scripted relay network: [events] per relay are answered to every REQ (followed by EOSE),
 * every EVENT gets `["OK", id, accept(url)]`. Unreachable relays throw on connect.
 */
class FakeNetwork {
    val events = mutableMapOf<String, List<JsonObject>>()
    var accept: (String) -> Boolean = { true }
    var unreachable: Set<String> = emptySet()
    val published = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val requests = CopyOnWriteArrayList<Pair<String, JsonArray>>()

    val connector = FakeRelayConnector { url ->
        if (url in unreachable) throw space.einundzwanzig.meetup.core.nostr.relay.RelayConnectException(url, java.io.IOException("refused"))
        FakeRelaySocket(url) { frame, socket ->
            val msg = DartJson.decode(frame) as JsonArray
            when (msg[0].jsonPrimitive.content) {
                "REQ" -> {
                    requests += url to msg
                    val sub = msg[1].jsonPrimitive.content
                    for (e in events[url].orEmpty()) socket.push(listOf("EVENT", sub, e))
                    socket.push(listOf("EOSE", sub))
                }
                "EVENT" -> {
                    val ev = msg[1] as JsonObject
                    published += url to ev
                    socket.push(listOf("OK", ev["id"]!!.jsonPrimitive.content, accept(url), ""))
                }
            }
        }
    }

    /** Relay config with the default relays active (store empty). */
    val relayConfig: RelayConfig = RelayConfig(InMemoryKeyValueStore(), connector)
}

/** A meetup directory entry like the Dart tests' `group(...)`. */
fun group(id: String, name: String, city: String, slug: String) = Meetup(
    id = id,
    name = name,
    city = city,
    country = "DE",
    telegramLink = "",
    lat = 0.0,
    lng = 0.0,
    portalLink = "https://portal.einundzwanzig.space/de/meetup/$slug",
)
