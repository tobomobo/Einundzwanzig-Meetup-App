package space.einundzwanzig.meetup.core.nostr.relay

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.NostrFilter
import space.einundzwanzig.meetup.core.common.log.RelayParseTally
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One-shot relay operations, so data modules never hand-roll WebSocket code.
 *
 * Every operation opens **its own socket per relay** and closes it afterwards, exactly like the
 * Dart services (`RelaySocket.connect(url).timeout(t)` → listen → `REQ`/`EVENT` → wait →
 * `ws.close()`). Relay failures never throw (except cancellation): they are reported in the
 * result so one dead relay cannot stop the others. Exceptions thrown by caller-supplied
 * callbacks (`onEvent`) are not caught; they propagate out of the call (the socket is closed). Every incoming event passes
 * `EventGuard.verifiedEvent` and `EventGuard.answersAnyFilter` against the filters that were
 * sent (both can be switched off per call for special protocols).
 *
 * Fan-out over several relays: [RelayFanOut.PARALLEL] (Dart `Future.wait(relays.map(...))`)
 * or [RelayFanOut.SEQUENTIAL] (Dart `for (final relay in relays)`); results are always in relay
 * order. Use whichever the domain spec prescribes.
 */
interface RelayClient {

    /**
     * Sends `["REQ", sub, ...filters]` to [relay] and collects matching events until `EOSE`
     * (or `CLOSED`, end of connection, [RelayQueryOptions.maxEvents], or the response timeout).
     *
     * [onEvent] is called for each accepted event as it arrives (sequentially, on the client's
     * background dispatcher), e.g. to keep a running "newest" without waiting for EOSE.
     */
    suspend fun query(
        relay: String,
        filters: List<JsonObject>,
        options: RelayQueryOptions = RelayQueryOptions(),
        onEvent: (suspend (JsonObject) -> Unit)? = null,
    ): RelayQueryResult

    /**
     * [query] on several relays; results in relay order. [onEvent] receives each accepted event
     * with its relay as it arrives; calls are serialized (never concurrent), also with
     * [RelayFanOut.PARALLEL].
     */
    suspend fun queryAll(
        relays: List<String>,
        filters: List<JsonObject>,
        options: RelayQueryOptions = RelayQueryOptions(),
        fanOut: RelayFanOut = RelayFanOut.PARALLEL,
        onEvent: (suspend (relay: String, event: JsonObject) -> Unit)? = null,
    ): List<RelayQueryResult>

    /**
     * Several REQs with different subscription ids on **one** socket (Dart
     * `humanity_proof_service._searchZapActivity`: `zap-recv`/`zap-req`/`zap-sent`). Sends every
     * `["REQ", subId, ...filters]` in list order, matches each event against the filters of its
     * own subscription, and ends when every subscription is done (EOSE; CLOSED if
     * [RelayQueryOptions.stopOnClosed]; [RelayQueryOptions.maxEvents] reached per subscription),
     * the connection ends, or [RelayQueryOptions.responseTimeout] (total) elapses.
     * [RelayQueryOptions.subscriptionId] is ignored. Events of a subscription that already sent
     * EOSE are still collected until the whole query ends (as in Dart).
     *
     * @throws IllegalArgumentException if [requests] is empty or repeats a subscription id.
     */
    suspend fun queryMulti(
        relay: String,
        requests: List<RelaySubscriptionRequest>,
        options: RelayQueryOptions = RelayQueryOptions(),
        onEvent: (suspend (subscriptionId: String, event: JsonObject) -> Unit)? = null,
    ): RelayMultiQueryResult

    /**
     * Sends `["EVENT", event]` to [relay] and waits for `["OK", event.id, accepted, message]`
     * (or, with [RelayPublishOptions.awaitOk] = `false`, lingers and reports
     * [RelayPublishStatus.SENT]).
     */
    suspend fun publish(
        relay: String,
        event: NostrEvent,
        options: RelayPublishOptions = RelayPublishOptions(),
    ): RelayPublishResult

    /**
     * Like [publish] for an event that only exists as a raw object (re-publishing a stored
     * event verbatim). The OK is matched against `rawEvent["id"]`.
     */
    suspend fun publishRaw(
        relay: String,
        rawEvent: JsonObject,
        options: RelayPublishOptions = RelayPublishOptions(),
    ): RelayPublishResult

    /** [publish] to several relays; results in relay order. */
    suspend fun publishAll(
        relays: List<String>,
        event: NostrEvent,
        options: RelayPublishOptions = RelayPublishOptions(),
        fanOut: RelayFanOut = RelayFanOut.PARALLEL,
    ): List<RelayPublishResult>

    /**
     * A live subscription: connects when collected, sends the REQ and emits stored events,
     * [RelaySubscriptionEvent.EndOfStoredEvents], then live events until the relay closes the
     * connection (flow completes; a transport failure throws [RelayConnectionLostException]).
     * Cancelling the collector closes the socket. Connect failure throws [RelayConnectException],
     * a failed NIP-42 handshake [RelayAuthException].
     *
     * [RelayQueryOptions.responseTimeout] and [RelayQueryOptions.maxEvents] do not apply.
     */
    fun subscribe(
        relay: String,
        filters: List<JsonObject>,
        options: RelayQueryOptions = RelayQueryOptions(),
    ): Flow<RelaySubscriptionEvent>

    /**
     * [subscribe] on the **first reachable** relay of [relays] (Dart `EventChatService` live
     * thread: try each relay in order with its own connect timeout, use the first that connects).
     * Only a [RelayConnectException] before anything was emitted moves on to the next relay; every
     * item carries the relay that serves the subscription. When no relay is reachable the flow
     * throws the last [RelayConnectException] (callers that degrade silently, like Dart, `catch`
     * it); an empty [relays] list completes immediately.
     */
    fun subscribeFirstReachable(
        relays: List<String>,
        filters: List<JsonObject>,
        options: RelayQueryOptions = RelayQueryOptions(),
    ): Flow<Pair<String, RelaySubscriptionEvent>>
}

/** Convenience: [RelayClient.query] with one typed filter. */
suspend fun RelayClient.query(
    relay: String,
    filter: NostrFilter,
    options: RelayQueryOptions = RelayQueryOptions(),
    onEvent: (suspend (JsonObject) -> Unit)? = null,
): RelayQueryResult = query(relay, listOf(filter.toJson()), options, onEvent)

/** Convenience: [RelayClient.queryAll] with one typed filter. */
suspend fun RelayClient.queryAll(
    relays: List<String>,
    filter: NostrFilter,
    options: RelayQueryOptions = RelayQueryOptions(),
    fanOut: RelayFanOut = RelayFanOut.PARALLEL,
    onEvent: (suspend (relay: String, event: JsonObject) -> Unit)? = null,
): List<RelayQueryResult> = queryAll(relays, listOf(filter.toJson()), options, fanOut, onEvent)

/** Convenience: [RelayClient.subscribe] with one typed filter. */
fun RelayClient.subscribe(
    relay: String,
    filter: NostrFilter,
    options: RelayQueryOptions = RelayQueryOptions(),
): Flow<RelaySubscriptionEvent> = subscribe(relay, listOf(filter.toJson()), options)

/** How a multi-relay operation visits its relays. */
enum class RelayFanOut {
    /** All relays at once (Dart `Future.wait`). */
    PARALLEL,

    /** One after the other, in list order (Dart `for` loop with `await`). */
    SEQUENTIAL,
}

/**
 * Per-call knobs of [RelayClient.query]/[RelayClient.subscribe]. Defaults follow
 * `RelayConfig.relayTimeout` (8 s) for both the connect and the wait for EOSE — the two are
 * separate budgets, as in Dart (`connect(url).timeout(t)` then `done.future.timeout(t)`).
 *
 * @property subscriptionId the REQ id; `null` → `SubscriptionIds.secure("q")`. Only `EVENT`,
 *   `EOSE` and `CLOSED` frames carrying this id are considered.
 * @property verifySignatures run `EventGuard.verifiedEvent` (switch off only for protocols that
 *   authenticate differently, e.g. NIP-46 via NIP-44).
 * @property matchFilters require `EventGuard.answersAnyFilter` against the sent filters.
 * @property stopOnClosed end the query (or subscription) on `CLOSED`.
 * @property maxEvents stop after this many accepted events (status [RelayQueryStatus.LIMIT]).
 * @property logTag tag for the rejected-signature debug log.
 * @property authenticator NIP-42 handshake run right after connecting (see [RelayAuthenticator]).
 * @property authTimeout upper bound for the [authenticator] (not part of [connectTimeout]);
 *   expiry → [RelayQueryStatus.AUTH_FAILED] and the socket is closed. `null`: no bound here, the
 *   authenticator must enforce its own deadline (Dart `authenticateSocket(timeout:)`: 8 s local
 *   key, NIP-46 `signTimeout` + 20 s, 25 s Amber). [responseTimeout] starts after the handshake.
 * @property parseTally Dart `RelayParseTally` of the calling service: `message()` is called for
 *   every received frame, `failed()` for every frame that is not a non-empty JSON array (as the
 *   Dart `catch (e) { tally.failed(e); }` did). The caller adds its own failures and calls
 *   `report()` (in `finally`). On the [authenticator] path only the arrays forwarded after the
 *   handshake are seen.
 */
data class RelayQueryOptions(
    val connectTimeout: Duration = RelayConfig.RELAY_TIMEOUT,
    val responseTimeout: Duration = RelayConfig.RELAY_TIMEOUT,
    val subscriptionId: String? = null,
    val verifySignatures: Boolean = true,
    val matchFilters: Boolean = true,
    val stopOnClosed: Boolean = true,
    val maxEvents: Int = Int.MAX_VALUE,
    val logTag: String = "RelaySocket",
    val authenticator: RelayAuthenticator? = null,
    val parseTally: RelayParseTally? = null,
    val authTimeout: Duration? = null,
)

/** Why a [RelayClient.query] ended. */
enum class RelayQueryStatus {
    /** The relay sent EOSE: the answer is complete. */
    EOSE,

    /** [RelayQueryOptions.maxEvents] reached. */
    LIMIT,

    /** The relay sent CLOSED (see [RelayQueryResult.closedReason]). */
    CLOSED,

    /** No EOSE within the response timeout; [RelayQueryResult.events] holds the partial answer. */
    TIMEOUT,

    /** The connection ended before EOSE. */
    DISCONNECTED,

    /** Could not connect within the connect timeout. */
    CONNECT_FAILED,

    /** The [RelayAuthenticator] returned `null` or threw (see [RelayQueryResult.error]). */
    AUTH_FAILED,
}

/**
 * Outcome of one [RelayClient.query].
 *
 * @property events accepted events in arrival order (raw objects; verified and filter-matched
 *   unless switched off).
 * @property framesReceived every frame received on the socket (forwarded arrays on the
 *   authenticator path), the `Y` of the Dart tally line `X von Y`.
 * @property unparsedFrames frames that were not a non-empty JSON array (the `X` part).
 */
data class RelayQueryResult(
    val relay: String,
    val events: List<JsonObject>,
    val status: RelayQueryStatus,
    val closedReason: String? = null,
    val error: Throwable? = null,
    val unparsedFrames: Int = 0,
    val framesReceived: Int = 0,
) {
    /** The relay answered completely (EOSE or limit reached). */
    val isComplete: Boolean get() = status == RelayQueryStatus.EOSE || status == RelayQueryStatus.LIMIT

    /** The relay gave *some* information: complete, or partial events before a timeout/close. */
    val hasAnswer: Boolean get() = isComplete || events.isNotEmpty()
}

/** One subscription of [RelayClient.queryMulti]: `["REQ", subscriptionId, ...filters]`. */
data class RelaySubscriptionRequest(
    val subscriptionId: String,
    val filters: List<JsonObject>,
)

/** An accepted event of [RelayClient.queryMulti] with the subscription it answered. */
data class RelayTaggedEvent(
    val subscriptionId: String,
    val event: JsonObject,
)

/**
 * Outcome of one [RelayClient.queryMulti].
 *
 * @property events accepted events of all subscriptions in arrival order.
 * @property status [RelayQueryStatus.EOSE] when every subscription is done; otherwise why the
 *   query ended early ([RelayQueryStatus.TIMEOUT], [RelayQueryStatus.DISCONNECTED],
 *   [RelayQueryStatus.CONNECT_FAILED], [RelayQueryStatus.AUTH_FAILED]).
 * @property subscriptionStatus per subscription id: `EOSE`, `CLOSED`, `LIMIT`, or the overall
 *   [status] for subscriptions that were not done.
 * @property closedReasons the `CLOSED` reason per subscription that was closed.
 */
data class RelayMultiQueryResult(
    val relay: String,
    val events: List<RelayTaggedEvent>,
    val status: RelayQueryStatus,
    val subscriptionStatus: Map<String, RelayQueryStatus>,
    val closedReasons: Map<String, String?> = emptyMap(),
    val error: Throwable? = null,
    val unparsedFrames: Int = 0,
    val framesReceived: Int = 0,
) {
    /** Every subscription ended (EOSE, CLOSED or limit). */
    val isComplete: Boolean get() = status == RelayQueryStatus.EOSE

    /** The accepted events of one subscription, in arrival order. */
    fun eventsFor(subscriptionId: String): List<JsonObject> =
        events.filter { it.subscriptionId == subscriptionId }.map { it.event }
}

/**
 * Per-call knobs of [RelayClient.publish]. Defaults: `RelayConfig.publishTimeout` (5 s) each.
 *
 * Dart patterns:
 * - wait for the OK (default): `ackTimeout` is the **total** wait for the matching OK.
 * - **inactivity timeout** (Dart AdminRegistry `ws.stream.timeout(5 s)`, restarted by every
 *   frame): `RelayPublishOptions(ackTimeout = Duration.INFINITE, ackInactivityTimeout = 5.seconds,
 *   matchEventId = false)`.
 * - **fire-and-linger** (Dart ReputationPublisher, PromotionClaimService, CalendarEventService:
 *   connect → `ws.add` → wait 2 s → close, success when connect and send did not throw):
 *   `RelayPublishOptions(awaitOk = false)` → [RelayPublishStatus.SENT].
 *
 * @property connectTimeout budget for the WebSocket handshake only (not the NIP-42 handshake).
 * @property ackTimeout total wait for the OK; ignored with [awaitOk] = `false`.
 * @property ackInactivityTimeout if set, the wait for the OK also ends after this long without
 *   any incoming frame (the timer restarts on every frame); [RelayPublishStatus.TIMEOUT].
 * @property matchEventId only an OK for this event's id counts (Dart `msg[1] == id`); `false`
 *   takes the first OK.
 * @property awaitOk `false`: do not wait for an OK; send, wait [linger], close, and report
 *   [RelayPublishStatus.SENT] (also when the relay closes during the linger, as in Dart).
 * @property authenticator NIP-42 handshake run right after connecting (see [RelayAuthenticator]).
 * @property authTimeout upper bound for the [authenticator]; expiry →
 *   [RelayPublishStatus.AUTH_FAILED] and the socket is closed. `null`: the authenticator must
 *   enforce its own deadline. [ackTimeout] starts after the handshake.
 * @property linger how long the socket stays open after sending when [awaitOk] is `false`
 *   (Dart `Future.delayed(2 s)`).
 */
data class RelayPublishOptions(
    val connectTimeout: Duration = RelayConfig.PUBLISH_TIMEOUT,
    val ackTimeout: Duration = RelayConfig.PUBLISH_TIMEOUT,
    val ackInactivityTimeout: Duration? = null,
    val matchEventId: Boolean = true,
    val authenticator: RelayAuthenticator? = null,
    val awaitOk: Boolean = true,
    val linger: Duration = DEFAULT_LINGER,
    val authTimeout: Duration? = null,
) {
    companion object {
        /** The Dart fire-and-linger delay after `ws.add`. */
        val DEFAULT_LINGER: Duration = 2.seconds
    }
}

/** Outcome category of a publish. */
enum class RelayPublishStatus {
    /** `["OK", id, true, …]`. */
    ACCEPTED,

    /** `["OK", id, false, reason]`. */
    REJECTED,

    /** No OK within the ack timeout (or the inactivity timeout). */
    TIMEOUT,

    /** The connection ended before the OK. */
    DISCONNECTED,

    /** Could not connect, or the frame could not be sent. */
    CONNECT_FAILED,

    /** The [RelayAuthenticator] returned `null` or threw (see [RelayPublishResult.error]). */
    AUTH_FAILED,

    /**
     * [RelayPublishOptions.awaitOk] was `false`: connected and the frame was handed to the
     * socket; the relay's verdict is unknown (Dart fire-and-linger counts this as success).
     */
    SENT,
}

/** Outcome of one [RelayClient.publish]; [message] is the OK's reason text (or `""`). */
data class RelayPublishResult(
    val relay: String,
    val status: RelayPublishStatus,
    val message: String = "",
    val error: Throwable? = null,
) {
    /** The relay confirmed the event. */
    val accepted: Boolean get() = status == RelayPublishStatus.ACCEPTED

    /** [accepted], or [RelayPublishStatus.SENT] for fire-and-linger publishes (the Dart success count). */
    val succeeded: Boolean get() = accepted || status == RelayPublishStatus.SENT
}

/** Items of [RelayClient.subscribe]. */
sealed interface RelaySubscriptionEvent {
    /** An accepted (verified, filter-matching) event. */
    data class Event(val event: JsonObject) : RelaySubscriptionEvent

    /** EOSE: stored events are done, live events follow. */
    data object EndOfStoredEvents : RelaySubscriptionEvent

    /** The relay ended the subscription (`CLOSED`). */
    data class Closed(val reason: String?) : RelaySubscriptionEvent

    /** A `NOTICE` from the relay. */
    data class Notice(val message: String) : RelaySubscriptionEvent
}

/** The relay rejected or did not complete the NIP-42 handshake. */
class RelayAuthException(val url: String, cause: Throwable? = null) :
    java.io.IOException("Anmeldung am Relay $url fehlgeschlagen", cause)

/** An open subscription's connection failed. */
class RelayConnectionLostException(val url: String, cause: Throwable?) :
    java.io.IOException("Verbindung zu $url unterbrochen", cause)
