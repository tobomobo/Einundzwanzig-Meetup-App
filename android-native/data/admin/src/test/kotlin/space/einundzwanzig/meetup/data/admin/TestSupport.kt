package space.einundzwanzig.meetup.data.admin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnectException
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistry
import space.einundzwanzig.meetup.data.admin.registry.AdminRelayFetch
import space.einundzwanzig.meetup.data.admin.signing.AdminSigner
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Settable clock; starts at the real wall clock so 10-digit `created_at` rules hold. */
class FakeClock(start: Long = System.currentTimeMillis()) : TimeSource {
    private val now = AtomicLong(start)
    override fun nowMillis(): Long = now.get()
    fun advance(ms: Long) { now.addAndGet(ms) }
}

/** [AdminSigner] backed by a local key pair (or none). */
class FakeAdminSigner(
    private val keyPair: KeyPair? = KeyPair.generate(),
    private val clock: TimeSource = FakeClock(),
) : AdminSigner {
    val signed = mutableListOf<NostrEvent>()
    override suspend fun canSign(): Boolean = keyPair != null
    override suspend fun npub(): String? = keyPair?.npub
    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        val kp = keyPair ?: error("Kein Nostr-Key vorhanden.")
        return EventIds.sign(kp, clock.nowSeconds(), kind, tags, content).also { signed += it }
    }
}

val superHex: String = Nip19.decodeNpub(AdminConstants.SUPER_ADMIN_NPUB)

fun freshNpub(): String = KeyPair.generate().npub

fun prefs(vararg entries: Pair<String, Any>): InMemoryKeyValueStore = InMemoryKeyValueStore(
    entries.associate { (k, v) ->
        k to when (v) {
            is String -> PrefValue.StringValue(v)
            is Int -> PrefValue.IntValue(v.toLong())
            is Long -> PrefValue.IntValue(v)
            is Boolean -> PrefValue.BoolValue(v)
            else -> error("unsupported $v")
        }
    },
)

fun TestScope.registry(
    store: KeyValueStore,
    fetch: AdminRelayFetch?,
    clock: TimeSource = FakeClock(),
    signer: AdminSigner = FakeAdminSigner(),
    connector: RelayConnector = FakeRelayConnector.unreachable(),
    relayDispatcher: CoroutineDispatcher = Dispatchers.Default,
    zone: ZoneId = ZoneOffset.UTC,
): AdminRegistry = AdminRegistry.create(
    store = store,
    relayClient = RelayClient(connector, relayDispatcher),
    signer = signer,
    timeSource = clock,
    backgroundScope = backgroundScope,
    relayFetch = fetch,
    zone = { zone },
)

/** The `["EVENT", sub, event]` frame of a signed event. */
fun FakeRelaySocket.pushEvent(sub: String, event: NostrEvent) = push(listOf("EVENT", sub, event.toMap()))

/**
 * Scripted relays on top of [FakeRelayConnector]: a URL without a script is unreachable (connect
 * failure). [onReq] gets the subscription id and the socket; [onEvent] the published event JSON.
 */
class ScriptedRelays {
    val onReq = HashMap<String, (sub: String, socket: FakeRelaySocket) -> Unit>()
    val onEvent = HashMap<String, (event: JsonObject, socket: FakeRelaySocket) -> Unit>()

    /** `(url, frame type)` of every client frame, in order. */
    val frames = CopyOnWriteArrayList<Pair<String, String>>()

    val connector = FakeRelayConnector { url ->
        if (url !in onReq && url !in onEvent) throw RelayConnectException(url, java.io.IOException("refused"))
        FakeRelaySocket(url) { frame, socket ->
            val msg = DartJson.decode(frame) as JsonArray
            val type = msg[0].jsonPrimitive.content
            frames += url to type
            when (type) {
                "REQ" -> onReq[url]?.invoke(msg[1].jsonPrimitive.content, socket)
                "EVENT" -> onEvent[url]?.invoke(msg[1] as JsonObject, socket)
            }
        }
    }
}

/** Relay [i] of [AdminConstants.RELAYS]. */
fun relay(i: Int): String = AdminConstants.RELAYS[i]
