package space.einundzwanzig.meetup.core.nostr.relay

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import space.einundzwanzig.meetup.core.network.di.WebSocketClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.resumeWithException

/**
 * Opens relay WebSockets (Dart `RelaySocket.connect`).
 *
 * Also the "relay opener" seam for protocols that keep their own sockets (NIP-46 client,
 * NIP-42 chat): inject it, or pass a `testing.FakeRelayConnector` in tests.
 */
fun interface RelayConnector {
    /**
     * Opens [url] and suspends **until the handshake completed**. Failures (invalid URL, DNS,
     * refused, HTTP error instead of an upgrade) throw [RelayConnectException] at the call site,
     * so the caller's `withTimeout` covers the whole connect. Cancelling the coroutine aborts a
     * pending handshake (OkHttp `cancel()`); a socket that opens after the abort is cancelled
     * immediately.
     */
    suspend fun connect(url: String): RelaySocket
}

/** The WebSocket to [url] could not be opened. */
class RelayConnectException(val url: String, cause: Throwable?) :
    IOException("Relay $url nicht erreichbar: ${cause?.message ?: cause?.javaClass?.simpleName}", cause)

/** OkHttp implementation of [RelayConnector] on the `@WebSocketClient` client of `:core:network`. */
internal class OkHttpRelayConnector @Inject constructor(
    @param:WebSocketClient private val client: OkHttpClient,
) : RelayConnector {

    override suspend fun connect(url: String): RelaySocket {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            throw RelayConnectException(url, e)
        }
        return suspendCancellableCoroutine { cont ->
            val socket = OkHttpRelaySocket(url)
            val settled = AtomicBoolean(false)
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket.attach(webSocket)
                    if (settled.compareAndSet(false, true)) {
                        cont.resume(socket) { _, s, _ -> s.cancel() }
                    } else {
                        // Opened after the caller gave up (timeout/cancel): tear it down.
                        webSocket.cancel()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) = socket.onText(text)

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Binary frames carry nothing the app understands (Dart callers cast to String and drop them).
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Answer the peer's close frame and end the stream (Dart onDone).
                    runCatching { webSocket.close(NORMAL_CLOSURE, null) }
                    socket.onEnded(null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = socket.onEnded(null)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (settled.compareAndSet(false, true)) {
                        cont.resumeWithException(RelayConnectException(url, t))
                    } else {
                        socket.onEnded(t)
                    }
                }
            }
            val webSocket = client.newWebSocket(request, listener)
            socket.attach(webSocket)
            cont.invokeOnCancellation {
                if (settled.compareAndSet(false, true)) webSocket.cancel()
            }
        }
    }

    internal companion object {
        const val NORMAL_CLOSURE = 1000
    }
}

/** [RelaySocket] over an OkHttp [WebSocket]. */
internal class OkHttpRelaySocket(url: String) : BufferedRelaySocket(url) {

    @Volatile
    private var webSocket: WebSocket? = null

    fun attach(ws: WebSocket) {
        webSocket = ws
    }

    fun onText(text: String) = deliverFrame(text)

    fun onEnded(error: Throwable?) = deliverDone(error)

    override fun send(text: String): Boolean {
        if (isClosed) return false
        return try {
            webSocket?.send(text) ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun closeTransport() {
        webSocket?.close(OkHttpRelayConnector.NORMAL_CLOSURE, null)
    }

    override fun cancelTransport() {
        webSocket?.cancel()
    }
}
