package space.einundzwanzig.meetup.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnectException
import space.einundzwanzig.meetup.core.nostr.relay.RelaySocket
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.core.nostr.toJsonObject
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException

/** "Now" of the tests: 2026-10-17 (seconds). */
const val NOW_SECONDS: Long = 1_792_150_000L

val fixedTime: TimeSource = object : TimeSource {
    override fun nowMillis(): Long = NOW_SECONDS * 1000
}

/** A [SigningService] proxy: `signEvent` (real BIP-340), `pubkeyHex`, `npub`, `getMode`. */
class FakeSigner(val keyPair: KeyPair? = KeyPair.generate(), var mode: SigningMode = SigningMode.LOCAL) {
    var signCount = 0
    var failSigning: Exception? = null
    val signedKinds = CopyOnWriteArrayList<Int>()

    val service: SigningService = Proxy.newProxyInstance(
        SigningService::class.java.classLoader,
        arrayOf(SigningService::class.java),
    ) { _, method, args ->
        when (method.name) {
            "pubkeyHex" -> keyPair?.publicKeyHex
            "getMode" -> mode
            "signEvent" -> {
                failSigning?.let { e ->
                    // A proxy cannot throw a checked exception: fail the suspend call through its continuation.
                    @Suppress("UNCHECKED_CAST")
                    (args.last() as Continuation<Any?>).resumeWithException(e)
                    return@newProxyInstance COROUTINE_SUSPENDED
                }
                signCount++
                signedKinds += args[0] as Int
                @Suppress("UNCHECKED_CAST")
                EventIds.sign(keyPair!!, NOW_SECONDS, args[0] as Int, args[1] as List<List<String>>, args[2] as String)
            }
            "toString" -> "FakeSigner"
            "hashCode" -> 0
            "equals" -> false
            else -> throw UnsupportedOperationException(method.name)
        }
    } as SigningService
}

/** Read targets for the event chat. */
class FakeTargets(private val relays: List<String>) : LightningAddressLookup {
    override suspend fun fetchLightningAddress(pubkeyHex: String): String? = null
    override suspend fun readTargets(): List<String> = relays
}

/** A signed event as the relay would deliver it. */
fun relayEvent(key: KeyPair, kind: Int, tags: List<List<String>>, content: String = "", createdAt: Long = NOW_SECONDS - 60): JsonObject =
    EventIds.sign(key, createdAt, kind, tags, content).toJsonObject()

/**
 * A scripted relay (group relay with NIP-42, or a plain relay with [requireAuth] = `false`).
 * Every connection gets its own [FakeRelaySocket]; the relay answers REQs with [onReq] and
 * events with [onEvent].
 */
class ScriptedRelay(val url: String, var requireAuth: Boolean = true) {
    var acceptAuth = true
    var onReq: (sub: String, filter: JsonObject, socket: FakeRelaySocket) -> Unit = { sub, _, s -> s.push(listOf("EOSE", sub)) }
    var onEvent: (event: JsonObject, socket: FakeRelaySocket) -> Unit = { ev, s -> s.push(listOf("OK", ev.id(), true, "")) }
    var unreachable: IOException? = null

    /** Runs right after the relay answered an AUTH (e.g. drop the connection after the OK). */
    var afterAuth: (socket: FakeRelaySocket) -> Unit = {}

    val reqs = CopyOnWriteArrayList<String>()
    val auths = CopyOnWriteArrayList<JsonObject>()
    val published = CopyOnWriteArrayList<JsonObject>()
    val sockets = CopyOnWriteArrayList<FakeRelaySocket>()
    private var challenges = 0

    fun open(): RelaySocket {
        unreachable?.let { throw RelayConnectException(url, it) }
        val socket = FakeRelaySocket(url) { frame, s -> handle(frame, s) }
        if (requireAuth) socket.push(listOf("AUTH", "challenge-${++challenges}"))
        sockets += socket
        return socket
    }

    private fun handle(frame: String, s: FakeRelaySocket) {
        val msg = DartJson.decode(frame) as JsonArray
        when (msg[0].jsonPrimitive.content) {
            "AUTH" -> {
                val ev = msg[1] as JsonObject
                auths += ev
                s.push(listOf("OK", ev.id(), acceptAuth, if (acceptAuth) "" else "auth-required: denied"))
                afterAuth(s)
            }
            "REQ" -> {
                reqs += frame
                onReq(msg[1].jsonPrimitive.content, msg[2] as JsonObject, s)
            }
            "EVENT" -> {
                val ev = msg[1] as JsonObject
                published += ev
                onEvent(ev, s)
            }
        }
    }
}

fun JsonObject.id(): String = this["id"]!!.jsonPrimitive.content

/** Connector routing each URL to its [ScriptedRelay]; unknown URLs are unreachable. */
fun connectorFor(vararg relays: ScriptedRelay): FakeRelayConnector {
    val byUrl = relays.associateBy { it.url }
    return FakeRelayConnector { url ->
        byUrl[url]?.open() ?: throw RelayConnectException(url, IOException("Connection refused"))
    }
}

/** Shortcut for the signed event's JSON. */
fun NostrEvent.json(): JsonObject = toJsonObject()
