package space.einundzwanzig.meetup.data.news

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnectException
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.core.nostr.toJsonObject
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

internal const val SECRET1 = "0000000000000000000000000000000000000000000000000000000000000001"
internal const val SECRET2 = "0000000000000000000000000000000000000000000000000000000000000002"
internal const val SECRET3 = "0000000000000000000000000000000000000000000000000000000000000003"
internal const val SECRET4 = "0000000000000000000000000000000000000000000000000000000000000004"
internal const val SECRET5 = "0000000000000000000000000000000000000000000000000000000000000005"

/** 2026-10-20T00:00Z. */
internal const val NOW_MILLIS: Long = 1_792_454_400_000L

internal class FakeTime(var millis: Long = NOW_MILLIS) : TimeSource {
    override fun nowMillis(): Long = millis
}

internal fun testLog() = DiagnosticLogger(timeSource = FakeTime())

internal fun pubOf(secret: String): String = KeyPair.fromPrivateKeyHex(secret).publicKeyHex

/** Deterministic signed event (zero aux randomness). */
internal fun signed(
    secret: String = SECRET1,
    kind: Int,
    content: String,
    tags: List<List<String>> = emptyList(),
    createdAt: Long,
): NostrEvent = EventIds.sign(KeyPair.fromPrivateKeyHex(secret), createdAt, kind, tags, content, ByteArray(32))

internal fun NostrEvent.json(): JsonObject = toJsonObject()

/**
 * Scripted relays: REQs are answered with the stored events that match the filter, then EOSE;
 * EVENTs get `["OK", id, accept, msg]` from [okReplies] (no entry = no answer). Unknown relays are
 * unreachable; [silent] relays never answer a REQ.
 */
internal class FakeRelays {
    val events = HashMap<String, MutableList<JsonObject>>()
    val silent = HashSet<String>()
    val reachable = HashSet<String>()
    val okReplies = HashMap<String, Pair<Boolean, String>>()
    val reqs = CopyOnWriteArrayList<Pair<String, JsonArray>>()
    val published = CopyOnWriteArrayList<Pair<String, JsonObject>>()

    fun add(relay: String, vararg evs: JsonObject) {
        events.getOrPut(relay) { mutableListOf() } += evs
    }

    val connector = FakeRelayConnector { url ->
        if (url !in events && url !in silent && url !in reachable && url !in okReplies) {
            throw RelayConnectException(url, java.io.IOException("refused"))
        }
        FakeRelaySocket(url) { frame, socket ->
            val msg = DartJson.decode(frame) as JsonArray
            when (msg[0].jsonPrimitive.content) {
                "REQ" -> {
                    reqs += url to msg
                    if (url in silent) return@FakeRelaySocket
                    val sub = msg[1].jsonPrimitive.content
                    val filter = msg[2] as JsonObject
                    for (ev in events[url].orEmpty()) {
                        if (EventGuard.answersFilter(ev, filter)) socket.push(listOf("EVENT", sub, ev))
                    }
                    socket.push(listOf("EOSE", sub))
                }
                "EVENT" -> {
                    val ev = msg[1] as JsonObject
                    published += url to ev
                    val reply = okReplies[url] ?: return@FakeRelaySocket
                    socket.push(listOf("OK", ev["id"]!!.jsonPrimitive.content, reply.first, reply.second))
                }
            }
        }
    }
}

/** A [SigningService] proxy implementing only `pubkeyHex()` and `signEvent()`. */
internal class FakeSigner(var keyPair: KeyPair? = KeyPair.fromPrivateKeyHex(SECRET1)) {
    var signCount = 0
    var failSigning: Exception? = null
    val signedEvents = CopyOnWriteArrayList<NostrEvent>()

    val service: SigningService = Proxy.newProxyInstance(
        SigningService::class.java.classLoader,
        arrayOf(SigningService::class.java),
    ) { _, method, args ->
        when (method.name) {
            "pubkeyHex" -> keyPair?.publicKeyHex
            "signEvent" -> {
                failSigning?.let { throw it }
                signCount++
                @Suppress("UNCHECKED_CAST")
                EventIds.sign(
                    keyPair!!,
                    createdAt = NOW_MILLIS / 1000,
                    kind = args[0] as Int,
                    tags = args[1] as List<List<String>>,
                    content = args[2] as String,
                    auxRand = ByteArray(32),
                ).also { signedEvents += it }
            }
            "toString" -> "FakeSigner"
            "hashCode" -> 0
            "equals" -> false
            else -> throw UnsupportedOperationException(method.name)
        }
    } as SigningService
}

/** [LightningAddressLookup] with fixed answers. */
internal class FakeLookup(
    var address: String? = null,
    var targets: List<String> = LightningAddressLookup.NEWS_RELAYS + LightningAddressLookup.READ_ONLY_RELAYS,
) : LightningAddressLookup {
    val lookups = CopyOnWriteArrayList<String>()
    override suspend fun fetchLightningAddress(pubkeyHex: String): String? {
        lookups += pubkeyHex
        return address
    }
    override suspend fun readTargets(): List<String> = targets
}
