package space.einundzwanzig.meetup.core.nostr.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import space.einundzwanzig.meetup.core.common.log.AppLogger

/**
 * An open WebSocket connection to one relay (Dart `RelaySocket`).
 *
 * Obtain one from [RelayConnector.connect], which suspends until the handshake completed. Most
 * code should not touch sockets at all and use [RelayClient] instead; sockets are for
 * long-lived protocols with their own state machine (NIP-42 AUTH, NIP-46 RPC, live chat).
 *
 * Semantics (shared by every implementation):
 * - **One listener** ([listen] or [frames], once). Frames that arrive before it is registered
 *   are buffered (at most [MAX_PENDING_FRAMES]; further ones are dropped and logged) and
 *   delivered first, in order. Callbacks are never invoked concurrently. Register promptly.
 * - Text frames longer than [MAX_MESSAGE_CHARS] UTF-16 chars are dropped before delivery
 *   (Security Audit M4, debug log `Frame über 524288 verworfen`). Binary frames are ignored.
 * - `onDone` fires exactly once: `null` after a normal close (by either side, including a local
 *   [close]/[cancel]), or the failure. Nothing is delivered after it.
 * - No pings, no reconnects.
 */
interface RelaySocket {

    /** The relay URL this socket was opened for (as passed to [RelayConnector.connect]). */
    val url: String

    /** `true` once the connection ended (see `onDone`). */
    val isClosed: Boolean

    /**
     * Registers the single listener (Dart `ws.listen(onData, onError:, onDone:)`).
     *
     * Frames buffered before this call are delivered **synchronously on the calling thread**
     * during [listen] (so registering from Main runs them on Main); later frames run on a
     * transport thread (or on the thread of a [send]/[close] that synchronously produced them,
     * e.g. in fakes). Keep [onFrame] short and non-blocking, hand heavy work to a coroutine.
     * @throws IllegalStateException if a listener is already registered.
     */
    fun listen(onFrame: (String) -> Unit, onDone: (Throwable?) -> Unit = {})

    /**
     * The incoming frames as a Flow (Dart `ws.stream`), built on [listen] — collect at most
     * once. Completes after a normal close; throws the failure otherwise. Cancelling the
     * collector does **not** close the socket.
     */
    val frames: Flow<String>
        get() = callbackFlow {
            listen(
                onFrame = { trySend(it) },
                onDone = { error -> close(error) },
            )
            awaitClose()
        }.buffer(Channel.UNLIMITED)

    /** Queues [text] (Dart `ws.add`). `false` if the socket is closing/closed. Never throws. */
    fun send(text: String): Boolean

    /** Starts a normal close (close frame) without waiting for it; idempotent, never throws. */
    fun close()

    /** Tears the connection down immediately (also aborts a pending handshake); idempotent. */
    fun cancel()

    /** Suspends until the connection ended; returns the failure or `null`. */
    suspend fun awaitClosed(): Throwable?

    /**
     * Runs [handler] once when the connection ended (immediately if it already has), with the
     * failure or `null`. Independent of the listener; may run on a transport thread. An
     * exception thrown by [handler] is logged, never propagated.
     */
    fun invokeOnClose(handler: (Throwable?) -> Unit)

    companion object {
        /** Frame limit in UTF-16 chars (Dart `RelaySocket.maxMessageChars`, 512 KiB). */
        const val MAX_MESSAGE_CHARS: Int = 512 * 1024

        /**
         * Cap on frames buffered before a listener registers; later frames are dropped (warn
         * log once per socket). Guards against a socket nobody listens to growing without bound.
         */
        const val MAX_PENDING_FRAMES: Int = 1_000
    }
}

/**
 * Base class implementing the buffering, single-listener, frame-limit and once-only `onDone`
 * rules of [RelaySocket]. Transports call [deliverFrame] and [deliverDone]; subclasses implement
 * [send], [closeTransport] and [cancelTransport].
 */
abstract class BufferedRelaySocket(
    final override val url: String,
    private val maxFrameChars: Int = RelaySocket.MAX_MESSAGE_CHARS,
    private val maxPendingFrames: Int = RelaySocket.MAX_PENDING_FRAMES,
) : RelaySocket {

    private val lock = Any()
    private var frameListener: ((String) -> Unit)? = null
    private var doneListener: ((Throwable?) -> Unit)? = null

    /** Frames (String) and the end marker ([Ended]) waiting for delivery, in arrival order. */
    private val queue = ArrayDeque<Any>()
    private var draining = false
    private var finished = false
    private var doneDelivered = false
    private var overflowLogged = false
    private val closedSignal = CompletableDeferred<Throwable?>()

    private class Ended(val error: Throwable?)

    override val isClosed: Boolean get() = closedSignal.isCompleted

    override fun listen(onFrame: (String) -> Unit, onDone: (Throwable?) -> Unit) {
        synchronized(lock) {
            check(frameListener == null && !doneDelivered) { "RelaySocket $url hat bereits einen Listener" }
            frameListener = onFrame
            doneListener = onDone
            if (draining) return
            draining = true
        }
        drain()
    }

    /** Called by the transport for every incoming text frame. */
    protected fun deliverFrame(text: String) {
        if (text.length > maxFrameChars) {
            AppLogger.debug(LOG_TAG, "Frame über $maxFrameChars verworfen")
            return
        }
        enqueue(text)
    }

    /** Called by the transport (or by [close]/[cancel]) when the connection ended. */
    protected fun deliverDone(error: Throwable?) {
        synchronized(lock) {
            if (finished) return
            finished = true
        }
        closedSignal.complete(error)
        enqueue(Ended(error))
    }

    /**
     * Queues [item] and drains the queue unless another call is already draining it. Listeners
     * therefore run one at a time and in arrival order, also when a listener synchronously
     * causes new frames (fakes answering a `send`) or closes the socket.
     */
    private fun enqueue(item: Any) {
        synchronized(lock) {
            if (item is String && finished) return
            if (item is String && frameListener == null && queue.size >= maxPendingFrames) {
                // Nobody listens yet and the buffer is full: drop (log once per socket).
                if (!overflowLogged) {
                    overflowLogged = true
                    AppLogger.warn(LOG_TAG, "$url: mehr als $maxPendingFrames Frames ohne Listener, weitere verworfen")
                }
                return
            }
            queue.addLast(item)
            if (draining || frameListener == null) return
            draining = true
        }
        drain()
    }

    private fun drain() {
        while (true) {
            val item: Any
            val onFrame: ((String) -> Unit)?
            val onDone: ((Throwable?) -> Unit)?
            synchronized(lock) {
                if (queue.isEmpty() || frameListener == null) {
                    draining = false
                    return
                }
                item = queue.removeFirst()
                onFrame = frameListener
                onDone = doneListener
                if (item is Ended) {
                    doneDelivered = true
                    frameListener = null
                    doneListener = null
                    queue.clear()
                }
            }
            try {
                if (item is Ended) onDone?.invoke(item.error) else onFrame?.invoke(item as String)
            } catch (e: Exception) {
                // Like an uncaught error in a Dart stream listener: report it, keep the socket alive.
                AppLogger.warn(LOG_TAG, "Listener von $url warf eine Ausnahme", e)
            }
        }
    }

    final override fun close() {
        try {
            closeTransport()
        } catch (_: Exception) {
            // Closing must never throw (Dart callers wrap ws.close() in try/catch).
        }
        deliverDone(null)
    }

    final override fun cancel() {
        try {
            cancelTransport()
        } catch (_: Exception) {
        }
        deliverDone(null)
    }

    override suspend fun awaitClosed(): Throwable? = closedSignal.await()

    override fun invokeOnClose(handler: (Throwable?) -> Unit) {
        // A throwing handler must not escape close()/cancel() or a transport callback
        // (CompletableDeferred would rethrow it as CompletionHandlerException).
        closedSignal.invokeOnCompletion {
            try {
                handler(closedSignal.getCompleted())
            } catch (e: Exception) {
                AppLogger.warn(LOG_TAG, "onClose-Handler von $url warf eine Ausnahme", e)
            }
        }
    }

    /** Starts the transport's normal close. */
    protected abstract fun closeTransport()

    /** Aborts the transport. */
    protected abstract fun cancelTransport()

    private companion object {
        const val LOG_TAG = "RelaySocket"
    }
}
