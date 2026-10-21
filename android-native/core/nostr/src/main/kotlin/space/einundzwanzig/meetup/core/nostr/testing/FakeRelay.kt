package space.einundzwanzig.meetup.core.nostr.testing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.toJsonElement
import space.einundzwanzig.meetup.core.nostr.relay.BufferedRelaySocket
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnectException
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnector
import space.einundzwanzig.meetup.core.nostr.relay.RelaySocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [RelaySocket] for unit tests of other modules (the Dart tests' `TestRelay`).
 *
 * ```
 * val ws = FakeRelaySocket("wss://group.example") { frame, socket ->
 *     if (frame.startsWith("[\"REQ\"")) socket.push(listOf("EOSE", "q"))
 * }
 * ```
 *
 * @param onSend called for every frame the client sends (after it was recorded in [sent]);
 *   use it to script relay answers.
 */
class FakeRelaySocket(
    url: String = "wss://relay.test",
    maxFrameChars: Int = RelaySocket.MAX_MESSAGE_CHARS,
    private val onSend: ((frame: String, socket: FakeRelaySocket) -> Unit)? = null,
) : BufferedRelaySocket(url, maxFrameChars) {

    /** Every frame the client sent, as text. */
    val sent: MutableList<String> = CopyOnWriteArrayList()

    /** Every sent frame decoded as JSON (non-JSON frames are skipped). */
    val sentJson: List<JsonElement> get() = sent.mapNotNull { runCatching { DartJson.decode(it) }.getOrNull() }

    /** `true` once the client called [close] or [cancel]. */
    @Volatile
    var closedByClient: Boolean = false
        private set

    override fun send(text: String): Boolean {
        if (isClosed) return false
        sent += text
        onSend?.invoke(text, this)
        return true
    }

    /** Delivers a raw text frame from the "relay". */
    fun pushFrame(frame: String) = deliverFrame(frame)

    /** Delivers `DartJson.encode(message)` (a list such as `listOf("EVENT", "sub", eventObject)`). */
    fun push(message: List<Any?>) = deliverFrame(DartJson.encode(message.toJsonElement()))

    /** Delivers an already built JSON array. */
    fun push(message: JsonArray) = deliverFrame(DartJson.encode(message))

    /** The relay ends the connection normally (`error == null`) or with [error]. */
    fun finish(error: Throwable? = null) = deliverDone(error)

    override fun closeTransport() {
        closedByClient = true
    }

    override fun cancelTransport() {
        closedByClient = true
    }
}

/**
 * [RelayConnector] for tests: [open] decides per URL whether to return a socket or throw.
 *
 * ```
 * val connector = FakeRelayConnector { url -> FakeRelaySocket(url) { frame, s -> … } }
 * val client = RelayClient(connector)
 * ```
 */
class FakeRelayConnector(
    private val open: suspend (url: String) -> RelaySocket,
) : RelayConnector {

    /** URLs in connect order. */
    val connects: MutableList<String> = CopyOnWriteArrayList()

    /** Every socket handed out. */
    val sockets: MutableList<RelaySocket> = CopyOnWriteArrayList()

    override suspend fun connect(url: String): RelaySocket {
        connects += url
        return open(url).also { sockets += it }
    }

    companion object {
        /** A connector for which every relay is unreachable. */
        fun unreachable(): FakeRelayConnector =
            FakeRelayConnector { url -> throw RelayConnectException(url, java.io.IOException("Connection refused")) }
    }
}
