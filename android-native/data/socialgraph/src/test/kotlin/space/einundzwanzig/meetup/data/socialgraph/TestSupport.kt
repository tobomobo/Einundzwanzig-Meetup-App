package space.einundzwanzig.meetup.data.socialgraph

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnectException
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.data.admin.signing.AdminSigner
import space.einundzwanzig.meetup.data.socialgraph.coattendance.AttendanceKeys
import space.einundzwanzig.meetup.data.socialgraph.coattendance.CoAttNode
import space.einundzwanzig.meetup.data.socialgraph.coattendance.CoAttendanceRecord
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceFetcher
import space.einundzwanzig.meetup.data.socialgraph.internal.RelayAnswer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Settable clock; starts at the real wall clock so 10-digit `created_at` rules hold. */
class FakeClock(start: Long = System.currentTimeMillis()) : TimeSource {
    private val now = AtomicLong(start)
    override fun nowMillis(): Long = now.get()
    fun advance(ms: Long) {
        now.addAndGet(ms)
    }
}

/** [AdminSigner] backed by a local key pair. */
class FakeSigner(
    val keyPair: KeyPair = KeyPair.generate(),
    private val clock: TimeSource = FakeClock(),
    private val fail: Boolean = false,
) : AdminSigner {
    val signed = CopyOnWriteArrayList<NostrEvent>()
    override suspend fun canSign(): Boolean = true
    override suspend fun npub(): String = keyPair.npub
    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        if (fail) error("Signieren abgelehnt")
        return EventIds.sign(keyPair, clock.nowSeconds(), kind, tags, content).also { signed += it }
    }
}

/** Collects log lines in memory. */
class RecordingLog : DiagnosticLog by space.einundzwanzig.meetup.core.common.log.AppLogger {
    val lines = CopyOnWriteArrayList<String>()
    override fun debug(tag: String, message: String) { lines += "DEBUG $tag $message" }
    override fun info(tag: String, message: String) { lines += "INFO $tag $message" }
    override fun diag(tag: String, message: String) { lines += "DIAG $tag $message" }
    override fun warn(tag: String, message: String, error: Any?) { lines += "WARN $tag $message" }
}

/** Deterministic npub `n` (pubkey hex = byte [n] repeated); Nip19 needs no secp256k1. */
fun npubOf(n: Int): String = Nip19.encodeNpub(String.format("%02x", n).repeat(32))

/** A graph from `npub → meetup keys`. */
fun graphOf(vararg entries: Pair<String, List<String>>): LinkedHashMap<String, CoAttNode> {
    val out = LinkedHashMap<String, CoAttNode>()
    for ((npub, keys) in entries) {
        val node = CoAttNode(npub)
        for (k in keys) if (AttendanceKeys.isEventKey(k)) node.events.add(k) else node.meetups.add(k)
        out[npub] = node
    }
    return out
}

/**
 * In-memory relays answering co-attendance queries from a list of records: author queries
 * return the authors' records, key queries the records whose key is one of the wanted `#d` values.
 * A relay URL in [down] gives no answer.
 */
internal class FakeCoAttRelays(private val records: List<CoAttendanceRecord>) : CoAttendanceFetcher {
    val down = HashSet<String>()
    val calls = CopyOnWriteArrayList<String>()

    override suspend fun fetch(relay: String, keys: Set<String>?, authorsHex: List<String>?, since: Long?): RelayAnswer? {
        calls += "$relay authors=${authorsHex?.size} keys=${keys?.size} since=$since"
        if (relay in down) return null
        val out = when {
            !authorsHex.isNullOrEmpty() -> records.filter { Nip19.decodeNpub(it.npub) in authorsHex }
            !keys.isNullOrEmpty() -> {
                val wanted = AttendanceKeys.wantedKeys(keys)
                records.filter { it.meetupEventId in wanted }
            }
            else -> records
        }
        return RelayAnswer(out, complete = true)
    }
}

fun rec(npub: String, key: String): CoAttendanceRecord = CoAttendanceRecord(npub, key, 0)

/** The `["EVENT", sub, event]` frame of a signed event. */
fun FakeRelaySocket.pushEvent(sub: String, event: NostrEvent) = push(listOf("EVENT", sub, event.toMap()))

/**
 * Scripted relays on [FakeRelayConnector]: a URL without a script is unreachable. [onReq] gets the
 * subscription id, the filter and the socket; [onEvent] the published event JSON.
 */
class ScriptedRelays {
    val onReq = HashMap<String, (sub: String, filter: JsonObject, socket: FakeRelaySocket) -> Unit>()
    val onEvent = HashMap<String, (event: JsonObject, socket: FakeRelaySocket) -> Unit>()

    /** Every client frame as `url frame`, in order. */
    val frames = CopyOnWriteArrayList<Pair<String, String>>()

    val connector = FakeRelayConnector { url ->
        if (url !in onReq && url !in onEvent) throw RelayConnectException(url, java.io.IOException("refused"))
        FakeRelaySocket(url) { frame, socket ->
            frames += url to frame
            val msg = DartJson.decode(frame) as JsonArray
            when (msg[0].jsonPrimitive.content) {
                "REQ" -> onReq[url]?.invoke(msg[1].jsonPrimitive.content, msg[2] as JsonObject, socket)
                "EVENT" -> onEvent[url]?.invoke(msg[1] as JsonObject, socket)
            }
        }
    }
}
