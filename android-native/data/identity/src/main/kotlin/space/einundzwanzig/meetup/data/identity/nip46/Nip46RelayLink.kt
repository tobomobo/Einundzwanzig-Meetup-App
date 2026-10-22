package space.einundzwanzig.meetup.data.identity.nip46

import space.einundzwanzig.meetup.core.nostr.relay.RelayConnector
import space.einundzwanzig.meetup.core.nostr.relay.RelaySocket
import java.io.IOException

/**
 * What the NIP-46 client needs from one relay (Dart `Nip46RelayLink`). Exists so the whole RPC
 * path (encrypt → event → answer → decrypt → match) is testable without a WebSocket.
 */
interface Nip46RelayLink {
    /**
     * Registers the single listener. [onDone] fires once: `null` after a normal end, the failure
     * otherwise. Frames that arrived before are delivered first.
     */
    fun listen(onFrame: (String) -> Unit, onDone: (Throwable?) -> Unit)

    /** Sends [frame]; throws if the link cannot send (closed / dead socket). */
    fun send(frame: String)

    /** Closes the link. */
    suspend fun close()

    companion object {
        /** Adapts a [RelaySocket] of `:core:nostr`. */
        fun of(socket: RelaySocket): Nip46RelayLink = SocketLink(socket)
    }
}

/** Opens a [Nip46RelayLink] for a relay URL (Dart `Nip46RelayOpener`). Throws if unreachable. */
fun interface Nip46RelayOpener {
    /** Opens [url]; suspends until the handshake completed. */
    suspend fun open(url: String): Nip46RelayLink

    companion object {
        /** The production opener on the shared [RelayConnector] (OkHttp, 512 Ki frame limit). */
        fun from(connector: RelayConnector): Nip46RelayOpener =
            Nip46RelayOpener { url -> Nip46RelayLink.of(connector.connect(url)) }
    }
}

private class SocketLink(private val socket: RelaySocket) : Nip46RelayLink {
    override fun listen(onFrame: (String) -> Unit, onDone: (Throwable?) -> Unit) = socket.listen(onFrame, onDone)

    override fun send(frame: String) {
        if (!socket.send(frame)) throw IOException("Relay-Verbindung ${socket.url} ist geschlossen")
    }

    override suspend fun close() = socket.close()
}
