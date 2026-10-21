package space.einundzwanzig.meetup.data.badgesync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip44
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.badgesync.internal.DefaultBadgeSync
import space.einundzwanzig.meetup.data.badgesync.internal.IdentityProofsWiper
import space.einundzwanzig.meetup.data.badgesync.internal.SyncIdentity
import space.einundzwanzig.meetup.data.identity.SigningException
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityKeys
import java.util.concurrent.CopyOnWriteArrayList

/** Mutable clock. */
class FakeTime(var millis: Long = 1_760_961_600_000L) : TimeSource {
    override fun nowMillis(): Long = millis
}

/**
 * Identity seam like Dart's `SigningService` in local mode: [npub] is what the keystore holds
 * (`nostr_npub_key`), [keyPair] (if set) signs and does NIP-44.
 */
internal class FakeIdentity(private val time: TimeSource) : SyncIdentity {
    var npub: String? = null
    var keyPair: KeyPair? = null
    var mode: SigningMode = SigningMode.LOCAL
    var privHex: String? = null
    var nip44Allowed: Boolean = true
    var nip44Throws: Exception? = null
    var probes = 0
    val ready = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    fun useKey(kp: KeyPair) {
        keyPair = kp
        npub = kp.npub
    }

    override val signerReady = ready

    override suspend fun npub(): String? = npub

    override suspend fun pubkeyHex(): String? = keyPair?.publicKeyHex

    override suspend fun mode(): SigningMode = mode

    override suspend fun localPrivHex(): String? = privHex

    override suspend fun canNip44(): Boolean {
        probes++
        nip44Throws?.let { throw it }
        return nip44Allowed
    }

    private fun key(peer: String) = Nip44.conversationKey(requireNotNull(keyPair).privateKeyHex, peer)

    override suspend fun nip44Encrypt(peerHex: String, plaintext: String): String = Nip44.encrypt(plaintext, key(peerHex))

    override suspend fun nip44Decrypt(peerHex: String, payload: String): String = Nip44.decrypt(payload, key(peerHex))

    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent =
        EventIds.sign(keyPair ?: throw SigningException("Kein Schlüssel"), time.nowSeconds(), kind, tags, content)
}

/**
 * In-memory relays with replaceable semantics per (pubkey, kind, d): REQ → every stored event,
 * then EOSE; EVENT → stored, `OK true` (or `OK false` if [reject]). Unreachable relays throw on connect.
 */
class FakeRelays {
    val stored = mutableMapOf<String, MutableList<JsonObject>>()
    val published = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val unreachable = mutableSetOf<String>()
    val reject = mutableSetOf<String>()
    var connects = 0

    /** Every REQ the relays answered (all relays, all passes). */
    val reqs = java.util.concurrent.atomic.AtomicInteger(0)

    /** Called (and awaited) on every connect, before the socket exists: lets a test pause a fetch. */
    @Volatile
    var beforeConnect: (suspend (url: String) -> Unit)? = null

    val connector = FakeRelayConnector { url ->
        connects++
        beforeConnect?.invoke(url)
        if (url in unreachable) {
            throw space.einundzwanzig.meetup.core.nostr.relay.RelayConnectException(url, java.io.IOException("refused"))
        }
        FakeRelaySocket(url) { frame, socket ->
            val msg = DartJson.decode(frame).jsonArray
            when (msg[0].jsonPrimitive.content) {
                "REQ" -> {
                    val sub = msg[1].jsonPrimitive.content
                    reqs.incrementAndGet()
                    stored[url].orEmpty().forEach { socket.push(JsonArray(listOf(JsonPrimitive("EVENT"), msg[1], it))) }
                    socket.push(listOf("EOSE", sub))
                }
                "EVENT" -> {
                    val ev = msg[1].jsonObject
                    val id = ev.getValue("id").jsonPrimitive.content
                    if (url in reject) {
                        socket.push(listOf("OK", id, false, "blocked"))
                    } else {
                        published += url to ev
                        add(url, ev)
                        socket.push(listOf("OK", id, true, ""))
                    }
                }
            }
        }
    }

    fun add(url: String, ev: JsonObject) {
        val list = stored.getOrPut(url) { mutableListOf() }
        val d = dOf(ev)
        list.removeAll { it["pubkey"] == ev["pubkey"] && it["kind"] == ev["kind"] && dOf(it) == d }
        list += ev
    }

    private fun dOf(ev: JsonObject): String? =
        ev["tags"]?.jsonArray?.firstOrNull { it.jsonArray[0].jsonPrimitive.content == "d" }?.jsonArray?.get(1)?.jsonPrimitive?.content
}

/** One "device": stores, badge repository, identity, relays and the sync under test. */
internal class SyncHarness(
    val scope: TestScope,
    val prefs: InMemoryKeyValueStore = InMemoryKeyValueStore(),
    val secure: InMemorySecureStore = InMemorySecureStore(),
    val time: FakeTime = FakeTime(),
    val relays: FakeRelays = FakeRelays(),
    val repo: BadgeRepository = BadgeRepository.create(prefs, secure, time, Dispatchers.Unconfined),
    val identity: FakeIdentity = FakeIdentity(time),
    backgroundScope: CoroutineScope = scope.backgroundScope,
) {
    val sync = newProcess(backgroundScope)

    /** A fresh service on the same stores: Dart `debugResetProcess()` (a restart). */
    fun newProcess(
        scope: CoroutineScope = this.scope.backgroundScope,
        store: KeyValueStore = prefs,
    ): DefaultBadgeSync = DefaultBadgeSync(
        store = store,
        badges = repo,
        identity = identity,
        relays = RelayClient(relays.connector, UnconfinedTestDispatcher(this.scope.testScheduler)),
        proofs = IdentityProofsWiper { prefs.edit { HumanityKeys.clearIdentityProofs(this) } },
        time = time,
        scope = scope,
        log = AppLogger,
    )
}
