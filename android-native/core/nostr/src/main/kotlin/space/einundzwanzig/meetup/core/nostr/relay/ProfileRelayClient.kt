package space.einundzwanzig.meetup.core.nostr.relay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.json.jsonArrayOf
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.network.di.WebSocketClient
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Reads the kind-0 and kind-10002 events of one author from one relay (profile discovery seam). */
fun interface ProfileRelayQuery {
    /** Raw, **unverified** event objects (the lookup verifies them); empty on any failure. */
    suspend fun query(relay: String, pubkeyHex: String): List<JsonObject>
}

/**
 * The hardened, bounded profile query of the picture discovery (Dart `ProfileRelayClient` +
 * `profile_relay_connection_io.dart`, spec 07 §5.3). Unlike [RelayClient] it talks to relays
 * discovered from *other people's* NIP-65 lists, so everything is capped:
 *
 * - [timeout] (6 s) is the **total** budget including the WebSocket handshake; a stalled upgrade
 *   is aborted (OkHttp `cancel()`), and a socket that opens after the abort is cancelled too.
 * - At most [MAX_MESSAGES] frames; a binary frame, a frame over [MAX_MESSAGE_LENGTH] chars, or
 *   the 65th frame ends the query. Frames that are not arrays with `[1] == "profile"` are ignored
 *   (but counted).
 * - `["EVENT","profile",{…}]` with exactly 3 elements is collected; [MAX_EVENTS] events, `EOSE`
 *   or `CLOSED` end the query.
 * - Partial results survive the deadline; errors yield what was collected (possibly nothing).
 *   The socket is always torn down without waiting for a close handshake.
 *
 * REQ: `["REQ","profile",{"kinds":[0,10002],"authors":["<hex>"],"limit":2}]`.
 */
class ProfileRelayClient(
    private val client: OkHttpClient,
    val timeout: Duration = DEFAULT_TIMEOUT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ProfileRelayQuery {

    /** Hilt constructor with the default 6 s budget; frames are decoded on [dispatcher]. */
    @Inject
    constructor(
        @WebSocketClient client: OkHttpClient,
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ) : this(client, DEFAULT_TIMEOUT, dispatcher)

    /** Runs on the injected background dispatcher, so callers may invoke it from Main. */
    override suspend fun query(relay: String, pubkeyHex: String): List<JsonObject> =
        withContext(dispatcher) { queryOnDispatcher(relay, pubkeyHex) }

    private suspend fun queryOnDispatcher(relay: String, pubkeyHex: String): List<JsonObject> {
        val events = mutableListOf<JsonObject>()
        val request = try {
            Request.Builder().url(relay).build()
        } catch (e: IllegalArgumentException) {
            AppLogger.debug(LOG_TAG, "Profil-Relay $relay: ungültige URL")
            return events
        }
        val inbox = Channel<Any>(Channel.UNLIMITED) // String, ByteString, or End
        val opened = CompletableDeferred<WebSocket>()
        val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (aborted.get()) webSocket.cancel() else opened.complete(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                inbox.trySend(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                inbox.trySend(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                inbox.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbox.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t)
                inbox.close()
            }
        }
        val webSocket = try {
            client.newWebSocket(request, listener)
        } catch (e: Exception) {
            AppLogger.debug(LOG_TAG, "Profil-Relay $relay: Verbindung nicht startbar (${e.javaClass.simpleName})")
            return events
        }
        try {
            val finished = withTimeoutOrNull(timeout) {
                opened.await()
                webSocket.send(DartJson.encode(reqFrame(pubkeyHex)))
                var messages = 0
                for (data in inbox) {
                    if (++messages > MAX_MESSAGES || data !is String || data.length > MAX_MESSAGE_LENGTH) break
                    val message = try {
                        DartJson.decode(data)
                    } catch (_: Exception) {
                        continue // invalid frames must not disturb later valid answers
                    }
                    if (message !is JsonArray || message.size < 2 || message[1].dartStringOrNull() != SUB_ID) continue
                    val type = message[0].dartStringOrNull()
                    if (type == "EVENT" && message.size == 3 && message[2] is JsonObject) {
                        events += message[2] as JsonObject
                        if (events.size >= MAX_EVENTS) break
                    } else if (type == "EOSE" || type == "CLOSED") {
                        break
                    }
                }
                true
            }
            if (finished == null) {
                AppLogger.debug(LOG_TAG, "Profil-Relay $relay: Zeitlimit $timeout nach ${events.size} Events")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Partial answers are still useful (connection refused, reset, …). No pubkey in the log.
            AppLogger.debug(
                LOG_TAG,
                "Profil-Relay $relay: ${e.javaClass.simpleName} nach ${events.size} Events",
            )
        } finally {
            aborted.set(true)
            runCatching { webSocket.close(1000, null) }
            webSocket.cancel()
        }
        return events.toList()
    }

    companion object {
        private const val LOG_TAG = "ProfileRelayClient"

        /** Frame budget per query. */
        const val MAX_MESSAGES: Int = 64

        /** Event budget per query. */
        const val MAX_EVENTS: Int = 8

        /** Frame size limit in UTF-16 chars (64 KiB). */
        const val MAX_MESSAGE_LENGTH: Int = 64 * 1024

        /** Total budget per relay, handshake included. */
        val DEFAULT_TIMEOUT: Duration = 6.seconds

        /** Subscription id (unique on this dedicated connection). */
        const val SUB_ID: String = "profile"

        /** The REQ frame as a JSON array. */
        fun reqFrame(pubkeyHex: String): JsonArray = jsonArrayOf(
            "REQ",
            SUB_ID,
            jsonObjectOf("kinds" to listOf(0, 10002), "authors" to listOf(pubkeyHex), "limit" to 2),
        )
    }
}
