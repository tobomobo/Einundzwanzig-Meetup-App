package space.einundzwanzig.meetup.data.proofs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import mockwebserver3.MockWebServer
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.crypto.Bech32
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.core.crypto.hexToBytes
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnectException
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.core.nostr.toJsonObject
import space.einundzwanzig.meetup.data.proofs.internal.ProofsIdentity
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import java.util.concurrent.CopyOnWriteArrayList

internal val log = AppLogger

/** Stands in for `@DefaultDispatcher` (keeps `runTest` virtual time and ordering deterministic). */
internal val testDispatcher: CoroutineDispatcher = Dispatchers.Unconfined

internal class FakeTime(var millis: Long = 1_760_000_000_000L) : TimeSource {
    override fun nowMillis(): Long = millis
}

/** [ProofsIdentity] backed by a real key (or none). */
internal class FakeIdentity(
    val keyPair: KeyPair? = KeyPair.generate(),
    var npubOverride: String? = null,
    var canSign: Boolean = true,
    var signError: Exception? = null,
) : ProofsIdentity {
    val signed = CopyOnWriteArrayList<NostrEvent>()

    override suspend fun npub(): String? = npubOverride ?: keyPair?.npub

    override suspend fun canSign(): Boolean = canSign && keyPair != null

    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        signError?.let { throw it }
        return EventIds.sign(keyPair!!, 1_760_000_123L, kind, tags, content).also { signed += it }
    }
}

/** Port of the Dart test helper `toWords`: 8 → 5 bit **with** padding. */
internal fun toWords(bytes: ByteArray): IntArray {
    var acc = 0
    var bits = 0
    val out = ArrayList<Int>()
    for (b in bytes) {
        acc = (acc shl 8) or (b.toInt() and 0xff)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            out += (acc shr bits) and 31
        }
    }
    if (bits > 0) out += (acc shl (5 - bits)) and 31
    return out.toIntArray()
}

/** Dart `bolt11WithHash(hashHex)`: hrp `lnbc210n`, 7 timestamp words, `h` field, 104 zero words. */
internal fun bolt11WithHash(hashHex: String, hrp: String = "lnbc210n", prefixFields: List<Int> = emptyList()): String {
    val h = toWords(hashHex.hexToBytes())
    val data = List(7) { 1 } + prefixFields + listOf(23, (h.size shr 5) and 31, h.size and 31) + h.toList() + List(104) { 0 }
    return Bech32.encode(hrp, data.toIntArray(), 4096)
}

/** Dart `zapRequest(sender, recipient)`: signed kind 9734 JSON (`{id,pubkey,created_at,kind,tags,content,sig}`). */
internal fun zapRequest(sender: KeyPair, recipient: String, createdAt: Long = 1_760_000_000L): String =
    DartJson.encode(
        EventIds.sign(sender, createdAt, 9734, listOf(listOf("p", recipient), listOf("relays", "wss://x")), "").toJsonObject(),
    )

/** Dart `receipt(issuer, recipient, description, bolt11?)`: signed kind 9735. */
internal fun receipt(
    issuer: KeyPair,
    recipient: String,
    description: String,
    bolt11: String? = null,
    createdAt: Long = 1_760_000_100L,
): JsonObject {
    val invoice = bolt11 ?: bolt11WithHash(Sha256.hexOfUtf8(description))
    return EventIds.sign(
        issuer,
        createdAt,
        9735,
        listOf(listOf("p", recipient), listOf("bolt11", invoice), listOf("description", description)),
        "",
    ).toJsonObject()
}

/** A NIP-57 receipt with the `P` (sender) tag, as real providers publish it. */
internal fun receiptWithSender(
    issuer: KeyPair,
    sender: KeyPair,
    recipient: String,
    createdAt: Long = 1_760_000_100L,
): JsonObject {
    val description = zapRequest(sender, recipient)
    return EventIds.sign(
        issuer,
        createdAt,
        9735,
        listOf(
            listOf("p", recipient),
            listOf("P", sender.publicKeyHex),
            listOf("bolt11", bolt11WithHash(Sha256.hexOfUtf8(description))),
            listOf("description", description),
        ),
        "",
    ).toJsonObject()
}

/** [LightningAddressLookup] with a fixed map. */
internal class FakeLightningAddresses(val byPubkey: Map<String, String?> = emptyMap()) : LightningAddressLookup {
    val calls = CopyOnWriteArrayList<String>()
    override suspend fun fetchLightningAddress(pubkeyHex: String): String? {
        calls += pubkeyHex
        return byPubkey[pubkeyHex]
    }

    override suspend fun readTargets(): List<String> = emptyList()
}

/** An OkHttp client that sends every request to [server] (scheme/host/port rewritten, path+query kept). */
internal fun clientFor(server: MockWebServer): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
        val req = chain.request()
        val url = req.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
        chain.proceed(req.newBuilder().url(url).header("X-Original-Host", req.url.host).build())
    }
    .build()

/**
 * Scripted relays on [FakeRelayConnector]: every REQ is answered with the stored events of the
 * relay that match the REQ's filter, then EOSE. Unknown relays are unreachable.
 */
internal class FakeRelays {
    val events = HashMap<String, MutableList<JsonObject>>()
    val reqs = CopyOnWriteArrayList<Pair<String, JsonArray>>()
    val reachable = HashSet<String>()

    fun add(relay: String, vararg evs: JsonObject) {
        reachable += relay
        events.getOrPut(relay) { mutableListOf() } += evs
    }

    val connector = FakeRelayConnector { url ->
        if (url !in reachable) throw RelayConnectException(url, java.io.IOException("refused"))
        FakeRelaySocket(url) { frame, socket ->
            val msg = DartJson.decode(frame) as JsonArray
            if (msg[0].jsonPrimitive.content != "REQ") return@FakeRelaySocket
            reqs += url to msg
            val sub = msg[1].jsonPrimitive.content
            val filter = msg[2] as JsonObject
            for (ev in events[url].orEmpty()) {
                if (EventGuard.answersFilter(ev, filter)) socket.push(listOf("EVENT", sub, ev))
            }
            socket.push(listOf("EOSE", sub))
        }
    }
}
