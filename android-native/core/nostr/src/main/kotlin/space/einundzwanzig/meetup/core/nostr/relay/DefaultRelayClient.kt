package space.einundzwanzig.meetup.core.nostr.relay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.RelayParseTally
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.time.Duration

/**
 * Creates a [RelayClient] on top of [connector] — for tests of data modules
 * (`RelayClient(FakeRelayConnector { … })`). Production code injects [RelayClient].
 */
fun RelayClient(
    connector: RelayConnector,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): RelayClient = DefaultRelayClient(connector, dispatcher)

internal class DefaultRelayClient @Inject constructor(
    private val connector: RelayConnector,
    @param:DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : RelayClient {

    // ---------------------------------------------------------------- query

    override suspend fun query(
        relay: String,
        filters: List<JsonObject>,
        options: RelayQueryOptions,
        onEvent: (suspend (JsonObject) -> Unit)?,
    ): RelayQueryResult = withContext(dispatcher) {
        val subId = options.subscriptionId ?: SubscriptionIds.secure("q")
        val events = mutableListOf<JsonObject>()
        when (val opened = open(relay, options.connectTimeout, options.authenticator, options.parseTally)) {
            is Opened.Failed -> RelayQueryResult(relay, emptyList(), opened.status.toQueryStatus(), error = opened.error)
            is Opened.Session -> opened.use { session ->
                if (!session.socket.send(RelayFrames.req(subId, filters))) {
                    return@use RelayQueryResult(
                        relay, emptyList(), RelayQueryStatus.DISCONNECTED,
                        error = session.socketError(),
                        unparsedFrames = session.unparsed.get(),
                        framesReceived = session.frames.get(),
                    )
                }
                var closedReason: String? = null
                val status = withTimeoutOrNull(options.responseTimeout) {
                    for (message in session.messages) {
                        when (message) {
                            is RelayMessage.Event -> {
                                if (message.subscriptionId != subId) continue
                                val event = accept(message, filters, options) ?: continue
                                events += event
                                onEvent?.invoke(event)
                                if (events.size >= options.maxEvents) return@withTimeoutOrNull RelayQueryStatus.LIMIT
                            }
                            is RelayMessage.Eose ->
                                if (message.subscriptionId == subId) return@withTimeoutOrNull RelayQueryStatus.EOSE
                            is RelayMessage.Closed ->
                                if (message.subscriptionId == subId && options.stopOnClosed) {
                                    closedReason = message.reason
                                    return@withTimeoutOrNull RelayQueryStatus.CLOSED
                                }
                            else -> Unit
                        }
                    }
                    RelayQueryStatus.DISCONNECTED
                } ?: RelayQueryStatus.TIMEOUT
                RelayQueryResult(
                    relay = relay,
                    events = events.toList(),
                    status = status,
                    closedReason = closedReason,
                    error = if (status == RelayQueryStatus.DISCONNECTED) session.socketError() else null,
                    unparsedFrames = session.unparsed.get(),
                    framesReceived = session.frames.get(),
                )
            }
        }
    }

    override suspend fun queryAll(
        relays: List<String>,
        filters: List<JsonObject>,
        options: RelayQueryOptions,
        fanOut: RelayFanOut,
        onEvent: (suspend (relay: String, event: JsonObject) -> Unit)?,
    ): List<RelayQueryResult> {
        // Serialize the callback so callers can keep plain mutable state, also for PARALLEL.
        val lock = Mutex()
        return fan(relays, fanOut) { relay ->
            query(relay, filters, options, onEvent?.let { callback -> { event -> lock.withLock { callback(relay, event) } } })
        }
    }

    override suspend fun queryMulti(
        relay: String,
        requests: List<RelaySubscriptionRequest>,
        options: RelayQueryOptions,
        onEvent: (suspend (subscriptionId: String, event: JsonObject) -> Unit)?,
    ): RelayMultiQueryResult {
        require(requests.isNotEmpty()) { "queryMulti braucht mindestens eine Subscription" }
        val byId = requests.associateBy { it.subscriptionId }
        require(byId.size == requests.size) { "Subscription-IDs von queryMulti müssen eindeutig sein" }
        return withContext(dispatcher) {
            when (val opened = open(relay, options.connectTimeout, options.authenticator, options.parseTally)) {
                is Opened.Failed -> {
                    val status = opened.status.toQueryStatus()
                    RelayMultiQueryResult(relay, emptyList(), status, byId.mapValues { status }, error = opened.error)
                }
                is Opened.Session -> opened.use { session -> runMulti(relay, session, requests, byId, options, onEvent) }
            }
        }
    }

    private suspend fun runMulti(
        relay: String,
        session: Opened.Session,
        requests: List<RelaySubscriptionRequest>,
        byId: Map<String, RelaySubscriptionRequest>,
        options: RelayQueryOptions,
        onEvent: (suspend (String, JsonObject) -> Unit)?,
    ): RelayMultiQueryResult {
        val events = mutableListOf<RelayTaggedEvent>()
        val done = LinkedHashMap<String, RelayQueryStatus>()
        val closedReasons = LinkedHashMap<String, String?>()
        val counts = HashMap<String, Int>()
        fun result(status: RelayQueryStatus) = RelayMultiQueryResult(
            relay = relay,
            events = events.toList(),
            status = status,
            subscriptionStatus = requests.associate { it.subscriptionId to (done[it.subscriptionId] ?: status) },
            closedReasons = closedReasons.toMap(),
            error = if (status == RelayQueryStatus.DISCONNECTED) session.socketError() else null,
            unparsedFrames = session.unparsed.get(),
            framesReceived = session.frames.get(),
        )
        for (request in requests) {
            if (!session.socket.send(RelayFrames.req(request.subscriptionId, request.filters))) {
                return result(RelayQueryStatus.DISCONNECTED)
            }
        }
        val status = withTimeoutOrNull(options.responseTimeout) {
            for (message in session.messages) {
                when (message) {
                    is RelayMessage.Event -> {
                        val sub = message.subscriptionId ?: continue
                        val request = byId[sub] ?: continue
                        val state = done[sub]
                        if (state == RelayQueryStatus.LIMIT || state == RelayQueryStatus.CLOSED) continue
                        val event = accept(message, request.filters, options) ?: continue
                        events += RelayTaggedEvent(sub, event)
                        onEvent?.invoke(sub, event)
                        val count = (counts[sub] ?: 0) + 1
                        counts[sub] = count
                        if (count >= options.maxEvents) done[sub] = RelayQueryStatus.LIMIT
                    }
                    is RelayMessage.Eose -> {
                        val sub = message.subscriptionId
                        if (sub != null && sub in byId && sub !in done) done[sub] = RelayQueryStatus.EOSE
                    }
                    is RelayMessage.Closed -> {
                        val sub = message.subscriptionId
                        if (sub != null && sub in byId && options.stopOnClosed && sub !in done) {
                            done[sub] = RelayQueryStatus.CLOSED
                            closedReasons[sub] = message.reason
                        }
                    }
                    else -> Unit
                }
                if (done.size == requests.size) return@withTimeoutOrNull RelayQueryStatus.EOSE
            }
            RelayQueryStatus.DISCONNECTED
        } ?: RelayQueryStatus.TIMEOUT
        return result(status)
    }

    private fun accept(message: RelayMessage.Event, filters: List<JsonObject>, options: RelayQueryOptions): JsonObject? {
        val event = if (options.verifySignatures) {
            EventGuard.verifiedEvent(message.event, options.logTag)
        } else {
            message.event as? JsonObject
        } ?: return null
        if (options.matchFilters && !EventGuard.answersAnyFilter(event, filters)) return null
        return event
    }

    // -------------------------------------------------------------- publish

    override suspend fun publish(relay: String, event: NostrEvent, options: RelayPublishOptions): RelayPublishResult =
        publishFrame(relay, event.id, RelayFrames.event(event), options)

    override suspend fun publishRaw(relay: String, rawEvent: JsonObject, options: RelayPublishOptions): RelayPublishResult =
        publishFrame(relay, rawEvent["id"].dartStringOrNull() ?: "", RelayFrames.event(rawEvent), options)

    override suspend fun publishAll(
        relays: List<String>,
        event: NostrEvent,
        options: RelayPublishOptions,
        fanOut: RelayFanOut,
    ): List<RelayPublishResult> {
        val frame = RelayFrames.event(event)
        return fan(relays, fanOut) { publishFrame(it, event.id, frame, options) }
    }

    private suspend fun publishFrame(
        relay: String,
        eventId: String,
        frame: String,
        options: RelayPublishOptions,
    ): RelayPublishResult = withContext(dispatcher) {
        when (val opened = open(relay, options.connectTimeout, options.authenticator, tally = null)) {
            is Opened.Failed -> RelayPublishResult(relay, opened.status, error = opened.error)
            is Opened.Session -> opened.use { session ->
                if (!session.socket.send(frame)) {
                    return@use RelayPublishResult(relay, RelayPublishStatus.CONNECT_FAILED, error = session.socketError())
                }
                if (!options.awaitOk) {
                    // Dart fire-and-linger: Future.delayed(2 s), close, count as sent.
                    delay(options.linger)
                    return@use RelayPublishResult(relay, RelayPublishStatus.SENT)
                }
                withTimeoutOrNull(options.ackTimeout) { awaitOk(relay, session, eventId, options) }
                    ?: RelayPublishResult(relay, RelayPublishStatus.TIMEOUT)
            }
        }
    }

    private suspend fun awaitOk(
        relay: String,
        session: Opened.Session,
        eventId: String,
        options: RelayPublishOptions,
    ): RelayPublishResult {
        val inactivity = options.ackInactivityTimeout
        while (true) {
            val next = if (inactivity == null) {
                session.messages.receiveCatching()
            } else {
                // Restarted by every frame, parsed or not (Dart Stream.timeout).
                withTimeoutOrNull(inactivity) { session.messages.receiveCatching() }
                    ?: return RelayPublishResult(relay, RelayPublishStatus.TIMEOUT)
            }
            if (next.isClosed) return RelayPublishResult(relay, RelayPublishStatus.DISCONNECTED, error = session.socketError())
            val message = next.getOrNull() as? RelayMessage.Ok ?: continue
            if (options.matchEventId && message.eventId != eventId) continue
            val status = if (message.accepted) RelayPublishStatus.ACCEPTED else RelayPublishStatus.REJECTED
            return RelayPublishResult(relay, status, message.message)
        }
    }

    // ------------------------------------------------------------ subscribe

    override fun subscribe(
        relay: String,
        filters: List<JsonObject>,
        options: RelayQueryOptions,
    ): Flow<RelaySubscriptionEvent> = channelFlow {
        val subId = options.subscriptionId ?: SubscriptionIds.secure("live")
        val session = when (val opened = open(relay, options.connectTimeout, options.authenticator, options.parseTally)) {
            is Opened.Failed -> throw when (opened.status) {
                RelayPublishStatus.AUTH_FAILED -> RelayAuthException(relay, opened.error)
                else -> opened.error as? RelayConnectException ?: RelayConnectException(relay, opened.error)
            }
            is Opened.Session -> opened
        }
        session.use {
            if (!session.socket.send(RelayFrames.req(subId, filters))) {
                throw RelayConnectionLostException(relay, session.socketError())
            }
            for (message in session.messages) {
                when (message) {
                    is RelayMessage.Event -> if (message.subscriptionId == subId) {
                        accept(message, filters, options)?.let { send(RelaySubscriptionEvent.Event(it)) }
                    }
                    is RelayMessage.Eose ->
                        if (message.subscriptionId == subId) send(RelaySubscriptionEvent.EndOfStoredEvents)
                    is RelayMessage.Closed -> if (message.subscriptionId == subId) {
                        send(RelaySubscriptionEvent.Closed(message.reason))
                        if (options.stopOnClosed) return@channelFlow
                    }
                    is RelayMessage.Notice -> send(RelaySubscriptionEvent.Notice(message.message))
                    else -> Unit
                }
            }
            session.socketError()?.let { throw RelayConnectionLostException(relay, it) }
        }
    }.flowOn(dispatcher)

    override fun subscribeFirstReachable(
        relays: List<String>,
        filters: List<JsonObject>,
        options: RelayQueryOptions,
    ): Flow<Pair<String, RelaySubscriptionEvent>> = flow {
        var lastFailure: RelayConnectException? = null
        for (relay in relays) {
            var emitted = false
            try {
                subscribe(relay, filters, options).collect {
                    emitted = true
                    emit(relay to it)
                }
                return@flow
            } catch (e: RelayConnectException) {
                if (emitted) throw e
                AppLogger.debug(TAG, "Abo: $relay nicht erreichbar, nächstes Relay")
                lastFailure = e
            }
        }
        lastFailure?.let { throw it }
    }

    // -------------------------------------------------------------- plumbing

    private sealed interface Opened {
        class Failed(val status: RelayPublishStatus, val error: Throwable?) : Opened

        /**
         * An open connection. [messages] carries one item per received frame: the decoded
         * message, or `null` for a frame that was not a non-empty JSON array (so inactivity
         * timers see every frame); it closes when the connection ends.
         */
        class Session(socket: RelaySocket, val messages: ReceiveChannel<RelayMessage?>) : Opened {
            /** The connection; replaced by the authenticator's result after a NIP-42 handshake. */
            @Volatile
            var socket: RelaySocket = socket

            val unparsed = AtomicInteger()
            val frames = AtomicInteger()

            @Volatile
            var error: Throwable? = null

            fun socketError(): Throwable? = error

            inline fun <T> use(block: (Session) -> T): T = try {
                block(this)
            } finally {
                socket.close()
            }
        }
    }

    /**
     * Connects within [connectTimeout], then either listens itself or lets [authenticator] run
     * the handshake. Decoded messages arrive on [Opened.Session.messages], which closes when the
     * connection ends.
     */
    private suspend fun open(
        relay: String,
        connectTimeout: Duration,
        authenticator: RelayAuthenticator?,
        tally: RelayParseTally?,
    ): Opened {
        // Assigned inside the block: withTimeout may still throw after connect() returned a
        // socket (asynchronous timeout), which must then be torn down here.
        var connected: RelaySocket? = null
        try {
            withTimeout(connectTimeout) { connected = connector.connect(relay) }
        } catch (e: TimeoutCancellationException) {
            connected?.cancel()
            return Opened.Failed(RelayPublishStatus.CONNECT_FAILED, RelayConnectException(relay, e))
        } catch (e: CancellationException) {
            connected?.cancel()
            throw e
        } catch (e: Exception) {
            return Opened.Failed(RelayPublishStatus.CONNECT_FAILED, e)
        }
        val socket = connected ?: return Opened.Failed(RelayPublishStatus.CONNECT_FAILED, RelayConnectException(relay, null))
        val channel = Channel<RelayMessage?>(Channel.UNLIMITED)
        val session = Opened.Session(socket, channel)
        fun deliver(message: RelayMessage?) {
            session.frames.incrementAndGet()
            tally?.message()
            if (message == null) {
                session.unparsed.incrementAndGet()
                tally?.failed(UNPARSED_FRAME)
            }
            channel.trySend(message)
        }
        if (authenticator == null) {
            socket.listen(
                onFrame = { frame -> deliver(RelayMessage.parse(frame)) },
                onDone = { error ->
                    session.error = error
                    channel.close()
                },
            )
            return session
        }
        val authed = try {
            authenticator.authenticate(socket) { array -> deliver(RelayMessage.of(array)) }
        } catch (e: CancellationException) {
            socket.close()
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "NIP-42-Anmeldung an $relay fehlgeschlagen", e)
            socket.close()
            return Opened.Failed(RelayPublishStatus.AUTH_FAILED, e)
        }
        if (authed == null) {
            socket.close()
            return Opened.Failed(RelayPublishStatus.AUTH_FAILED, null)
        }
        // The authenticator owns the listener; end our channel when the connection ends.
        session.socket = authed
        authed.invokeOnClose { error ->
            session.error = error
            channel.close()
        }
        return session
    }

    private suspend fun <T> fan(relays: List<String>, fanOut: RelayFanOut, block: suspend (String) -> T): List<T> =
        when (fanOut) {
            RelayFanOut.SEQUENTIAL -> relays.map { block(it) }
            RelayFanOut.PARALLEL -> coroutineScope { relays.map { async { block(it) } }.awaitAll() }
        }

    private fun RelayPublishStatus.toQueryStatus(): RelayQueryStatus = when (this) {
        RelayPublishStatus.AUTH_FAILED -> RelayQueryStatus.AUTH_FAILED
        else -> RelayQueryStatus.CONNECT_FAILED
    }

    private companion object {
        const val TAG = "RelayClient"

        /** Sample error handed to [RelayParseTally.failed] for frames that are not JSON arrays. */
        const val UNPARSED_FRAME = "Frame ist kein nicht-leeres JSON-Array"
    }
}
