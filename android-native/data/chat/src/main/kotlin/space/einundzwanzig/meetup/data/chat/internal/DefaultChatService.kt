package space.einundzwanzig.meetup.data.chat.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnectException
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishStatus
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryStatus
import space.einundzwanzig.meetup.core.nostr.relay.RelaySubscriptionEvent
import space.einundzwanzig.meetup.data.chat.ChatKinds
import space.einundzwanzig.meetup.data.chat.ChatMessage
import space.einundzwanzig.meetup.data.chat.ChatReadMarkStore
import space.einundzwanzig.meetup.data.chat.ChatRelayUnavailable
import space.einundzwanzig.meetup.data.chat.ChatRoom
import space.einundzwanzig.meetup.data.chat.ChatRules
import space.einundzwanzig.meetup.data.chat.ChatService
import space.einundzwanzig.meetup.data.chat.GROUP_RELAY
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.nip46.Nip46Timeouts
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** [ChatService] on [RelayClient] with the [Nip42Authenticator]. */
internal class DefaultChatService @Inject constructor(
    private val relayClient: RelayClient,
    private val signing: SigningService,
    private val readMarks: ChatReadMarkStore,
    private val time: TimeSource,
    private val networkStatus: NetworkStatus,
) : ChatService {

    /** Room list cache: rooms + fetch time (ms). Only non-empty answers are stored. */
    @Volatile
    private var roomCache: Pair<List<ChatRoom>, Long>? = null

    /**
     * Single-flight for the room list: concurrent callers (dashboard lookups, unread + room
     * lookup) share one authenticated fetch instead of one connection (and one Amber prompt) each.
     */
    private val roomsMutex = Mutex()

    /**
     * Sequence numbers of the room fetches (all written under [roomsMutex]): [startedSeq] counts
     * started fetches, [lastFetchSeq] is the number of the fetch that produced [lastFetch].
     */
    @Volatile
    private var startedSeq = 0L

    @Volatile
    private var lastFetchSeq = 0L
    private var lastFetch: Result<List<ChatRoom>>? = null

    /**
     * Rooms found by [findRoomForMeetupId] + lookup time (ms), per portal id. Positive answers
     * only: `null` ("no room") and [ChatRelayUnavailable] are never cached (spec 11 §14.17), so a
     * newly created room or a recovered relay shows up on the next lookup.
     */
    private val roomByIdCache = ConcurrentHashMap<String, Pair<ChatRoom, Long>>()

    /** Serialises concurrent lookups of the same portal id (one connection / Amber prompt at a time). */
    private val roomByIdLocks = ConcurrentHashMap<String, Mutex>()

    // ------------------------------------------------------------------ rooms

    override suspend fun loadMeetupRooms(force: Boolean): List<ChatRoom> {
        // An explicit reload also drops the per-meetup-id answers.
        if (force) roomByIdCache.clear()
        if (!force) roomCache?.takeIf(::isFresh)?.let { return it.first }
        val startedAtCall = startedSeq
        val finishedAtCall = lastFetchSeq
        return roomsMutex.withLock {
            // Share the outcome (rooms, or ChatRelayUnavailable) of a fetch instead of opening
            // another authenticated connection. A normal call takes any fetch that finished while
            // it waited; force only one that started after this call began, so an explicit
            // reload never returns an answer from before it was asked.
            val last = lastFetch
            if (last != null) {
                val shared = if (force) lastFetchSeq > startedAtCall else lastFetchSeq != finishedAtCall
                if (shared) return@withLock last.getOrThrow()
            }
            val cached = roomCache
            if (!force && cached != null && isFresh(cached)) return@withLock cached.first
            val seq = ++startedSeq
            val outcome = runSuspendCatching { fetchRooms(cached) }
            lastFetch = outcome
            lastFetchSeq = seq
            outcome.getOrThrow()
        }
    }

    private fun isFresh(cached: Pair<*, Long>): Boolean =
        time.nowMillis() - cached.second < CACHE_TTL.inWholeMilliseconds

    private suspend fun fetchRooms(cached: Pair<List<ChatRoom>, Long>?): List<ChatRoom> {
        val fresh = try {
            loadMeetupRoomsUncached()
        } catch (e: ChatRelayUnavailable) {
            // No answer: an older list is better than none.
            if (cached != null) return cached.first
            throw e
        }
        // An empty answer is not cached: it may just as well mean the relay was flaky.
        if (fresh.isNotEmpty()) roomCache = fresh to time.nowMillis()
        return fresh
    }

    private suspend fun loadMeetupRoomsUncached(): List<ChatRoom> {
        val events = when (
            val answer = queryStrict(jsonObjectOf("kinds" to listOf(ChatKinds.ROOM_META), "#t" to listOf("meetup"), "limit" to 500))
        ) {
            is Answer.None -> throw ChatRelayUnavailable(offline = answer.offline, cause = answer.error)
            is Answer.Events -> answer.events
        }
        val rooms = LinkedHashMap<String, ChatRoom>()
        val bestAt = HashMap<String, Long>()
        for (e in events) {
            val at = EventGuard.replaceableCreatedAt(e, time) ?: continue
            val room = ChatRoom.fromEvent(e) ?: continue
            val prev = bestAt[room.h]
            if (prev != null && at < prev) continue
            bestAt[room.h] = at
            rooms[room.h] = room
        }
        AppLogger.debug(TAG, "${rooms.size} Meetup-Räume geladen.")
        return rooms.values.toList()
    }

    override suspend fun findMeetupRoom(slug: String): ChatRoom? {
        if (slug.isEmpty()) return null
        val needle = slug.dartLower()
        val room = loadMeetupRooms().firstOrNull { it.meetupSlug.dartLower() == needle }
        if (room == null) AppLogger.debug(TAG, "Kein Raum für Meetup-Slug \"$slug\".")
        return room
    }

    override suspend fun findRoomForMeetupId(portalId: String): ChatRoom? {
        if (portalId.isEmpty()) return null
        roomByIdCache[portalId]?.takeIf(::isFresh)?.let { return it.first }
        return roomByIdLocks.computeIfAbsent(portalId) { Mutex() }.withLock {
            // A caller that waited may now find the room another lookup just produced.
            roomByIdCache[portalId]?.takeIf(::isFresh)?.let { return@withLock it.first }
            val room = findRoomForMeetupIdUncached(portalId)
            if (room != null) roomByIdCache[portalId] = room to time.nowMillis()
            room
        }
    }

    private suspend fun findRoomForMeetupIdUncached(portalId: String): ChatRoom? {
        val events = when (
            val answer = queryStrict(
                jsonObjectOf("kinds" to listOf(ChatKinds.ROOM_META), "#i" to listOf("meetup:$portalId"), "limit" to 5),
            )
        ) {
            is Answer.None -> throw ChatRelayUnavailable(offline = answer.offline, cause = answer.error)
            is Answer.Events -> answer.events
        }
        var best: ChatRoom? = null
        var bestAt = -1L
        for (e in events) {
            val at = EventGuard.replaceableCreatedAt(e, time)
            if (at == null || at < bestAt) continue
            val room = ChatRoom.fromEvent(e) ?: continue
            bestAt = at
            best = room
        }
        if (best != null) {
            AppLogger.debug(TAG, "Raum fuer Meetup $portalId: ${best.h}")
        } else {
            AppLogger.diag(TAG, "Relay antwortete: kein Raum mit i-Tag meetup:$portalId.")
        }
        return best
    }

    override suspend fun findRoomForCity(city: String): ChatRoom? {
        val needle = ChatRules.slugify(city)
        if (needle.isEmpty()) return null
        val room = ChatRules.matchCity(loadMeetupRooms(), city)
        if (room == null) AppLogger.diag(TAG, "Relay antwortete: kein Raum fuer Stadt \"$city\" (gesucht: $needle).")
        return room
    }

    // ---------------------------------------------------------- read / unread

    override suspend fun lastReadFor(key: String): Long = readMarks.lastReadFor(key)

    override suspend fun markRead(key: String, until: Instant) = readMarks.markRead(key, until)

    override suspend fun unreadCounts(hs: List<String>): Map<String, Int> {
        if (hs.isEmpty()) return emptyMap()
        val marks = readMarks.all()
        val me = signing.pubkeyHex()
        // Rooms never opened get a 7-day window: show that something is going on, not months of history.
        val fallback = Math.floorDiv(time.nowMillis() - 7.days.inWholeMilliseconds, 1000L)
        var since = fallback
        for (h in hs) {
            val m = marks[h] ?: fallback
            if (m < since) since = m
        }
        val events = query(
            jsonObjectOf("kinds" to listOf(ChatKinds.MESSAGE), "#h" to hs, "since" to since, "limit" to 500),
            UNREAD_TIMEOUT,
        )
        val counts = countUnread(events, hs, tagName = "h", me = me, dedupeById = false) { h -> marks[h] ?: fallback }
        AppLogger.debug(TAG, "Ungelesen: $counts")
        return counts
    }

    // ------------------------------------------------------------- membership

    override suspend fun isMember(h: String): Boolean {
        val me = signing.pubkeyHex() ?: return false
        val events = query(jsonObjectOf("kinds" to listOf(ChatKinds.ROOM_MEMBERS), "#d" to listOf(h), "limit" to 5))
        var newest: JsonObject? = null
        var bestAt = -1L
        for (e in events) {
            val at = EventGuard.replaceableCreatedAt(e, time)
            if (at == null || at < bestAt) continue
            bestAt = at
            newest = e
        }
        return newest?.tagLists().orEmpty().any { t -> t.isNotEmpty() && t.tagName(0) == "p" && t.size > 1 && t.tagName(1) == me }
    }

    override suspend fun join(h: String): String? {
        val err = signAndPublish(ChatKinds.JOIN, h, "")
        AppLogger.debug(TAG, "Beitritt zu $h: ${err ?: "angenommen"}")
        return err
    }

    override suspend fun leave(h: String): String? = signAndPublish(ChatKinds.LEAVE, h, "")

    // --------------------------------------------------------------- messages

    override suspend fun loadMessages(h: String, limit: Int): List<ChatMessage> =
        query(jsonObjectOf("kinds" to listOf(ChatKinds.MESSAGE), "#h" to listOf(h), "limit" to limit))
            .mapNotNull(ChatMessage::fromEvent)
            // A relay may send the same EVENT twice in one REQ; the UI keys rows by id.
            .distinctBy { it.id }
            .sortedBy { it.createdAt }

    override fun subscribe(h: String, since: Instant?): Flow<ChatMessage> = flow {
        emitAll(liveMessages(h, since))
    }

    private suspend fun liveMessages(h: String, since: Instant?): Flow<ChatMessage> {
        val filter = liveFilter(ChatKinds.MESSAGE, "#h", h, since)
        val options = RelayQueryOptions(
            connectTimeout = CONNECT_TIMEOUT,
            subscriptionId = LIVE_SUBSCRIPTION,
            logTag = TAG,
            authenticator = newAuthenticator(),
            // Dart keeps the socket open after a CLOSED; so do we.
            stopOnClosed = false,
        )
        return relayClient.subscribe(GROUP_RELAY, listOf(filter), options)
            .mapNotNull { item -> (item as? RelaySubscriptionEvent.Event)?.let { ChatMessage.fromEvent(it.event) } }
            // Only upstream failures: an exception of the collector is never swallowed here.
            .catch { e ->
                // Without AUTH no live updates; the chat stays readable (Dart returns a no-op unsubscribe).
                if (e is IOException) AppLogger.debug(TAG, "Live-Abo beendet: ${e.message}") else throw e
            }
    }

    override suspend fun send(h: String, text: String): String? {
        val body = text.dartTrim()
        if (body.isEmpty()) return null
        return signAndPublish(ChatKinds.MESSAGE, h, body)
    }

    // --------------------------------------------------------------- plumbing

    private sealed interface Answer {
        class Events(val events: List<JsonObject>) : Answer
        /** No information; [error] is the underlying connect/AUTH/socket failure, if any. */
        class None(val offline: Boolean, val error: Throwable? = null) : Answer
    }

    /** Dart `_query`: "no answer" reads as an empty list. */
    private suspend fun query(filter: JsonObject, timeout: Duration = QUERY_TIMEOUT): List<JsonObject> =
        (queryStrict(filter, timeout) as? Answer.Events)?.events ?: emptyList()

    /**
     * Dart `_queryStrict`: events (possibly partial) or [Answer.None] when there was no
     * information (no connection, AUTH failed, CLOSED, timeout without EOSE and events). A CLOSED
     * starting with `auth-required` is retried exactly once.
     */
    private suspend fun queryStrict(filter: JsonObject, timeout: Duration = QUERY_TIMEOUT, isRetry: Boolean = false): Answer {
        val options = RelayQueryOptions(
            connectTimeout = CONNECT_TIMEOUT,
            responseTimeout = timeout,
            subscriptionId = QUERY_SUBSCRIPTION,
            logTag = TAG,
            authenticator = newAuthenticator(),
        )
        val result = relayClient.query(GROUP_RELAY, listOf(filter), options)
        return when (result.status) {
            RelayQueryStatus.EOSE, RelayQueryStatus.LIMIT -> Answer.Events(result.events)
            RelayQueryStatus.CONNECT_FAILED -> {
                val offline = isOffline(result.error)
                AppLogger.warn(TAG, if (offline) "Kein Netz — Relay nicht erreichbar" else "Verbindung fehlgeschlagen", result.error)
                Answer.None(offline, result.error)
            }
            RelayQueryStatus.AUTH_FAILED -> Answer.None(offline = false, error = result.error)
            RelayQueryStatus.CLOSED -> {
                val reason = result.closedReason ?: "ohne Grund"
                AppLogger.warn(TAG, "Abfrage abgelehnt: $reason")
                if (!isRetry && reason.startsWith("auth-required")) {
                    AppLogger.diag(TAG, "Anmeldung kam zu spaet — zweiter Versuch.")
                    queryStrict(filter, timeout, isRetry = true)
                } else {
                    Answer.None(offline = false, error = IOException("CLOSED: $reason"))
                }
            }
            RelayQueryStatus.TIMEOUT, RelayQueryStatus.DISCONNECTED ->
                if (result.events.isEmpty()) {
                    AppLogger.warn(TAG, "Keine Antwort vom Relay binnen ${timeout.inWholeSeconds} s.")
                    Answer.None(offline = false, error = result.error)
                } else {
                    Answer.Events(result.events)
                }
        }
    }

    private suspend fun signAndPublish(kind: Int, h: String, content: String): String? {
        val signed: NostrEvent = try {
            signing.signEvent(kind, listOf(listOf("h", h)), content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return errorText(e)
        }
        return publish(signed)
    }

    /** Dart `_publish`: `null` on OK true, else the reason. */
    private suspend fun publish(signed: NostrEvent): String? {
        val options = RelayPublishOptions(
            connectTimeout = CONNECT_TIMEOUT,
            ackTimeout = PUBLISH_TIMEOUT,
            authenticator = newAuthenticator(),
        )
        val result = relayClient.publish(GROUP_RELAY, signed, options)
        return when (result.status) {
            RelayPublishStatus.ACCEPTED, RelayPublishStatus.SENT -> null
            RelayPublishStatus.REJECTED -> result.message
            RelayPublishStatus.TIMEOUT, RelayPublishStatus.DISCONNECTED -> "Zeitüberschreitung"
            RelayPublishStatus.AUTH_FAILED -> AUTH_FAILED_TEXT
            RelayPublishStatus.CONNECT_FAILED -> {
                val error = result.error
                if (error is RelayConnectException) {
                    // The connection never opened (Dart `_connectAuthed` returned null).
                    val offline = isOffline(error)
                    AppLogger.warn(TAG, if (offline) "Kein Netz — Relay nicht erreichbar" else "Verbindung fehlgeschlagen", error)
                    AUTH_FAILED_TEXT
                } else {
                    // Authenticated, but the EVENT frame could not be sent (connection dropped):
                    // Dart's `ws.add` threw inside `_publish`'s try and returned `e.toString()`.
                    AppLogger.warn(TAG, "Senden fehlgeschlagen", error)
                    error?.let(::errorText) ?: "Zeitüberschreitung"
                }
            }
        }
    }

    /**
     * Connect failure classification: the exception heuristics of Dart `_looksOffline`, plus the
     * native ConnectivityManager check (no active network with `INTERNET` at all, e.g. a connect
     * timeout right after airplane mode). A connected but unvalidated network is not offline.
     */
    private fun isOffline(error: Throwable?): Boolean = looksOffline(error) || !networkStatus.isOnline()

    /** A fresh handshake state machine with the signer-dependent deadline (Dart `_connectAuthed`). */
    private suspend fun newAuthenticator(): Nip42Authenticator =
        Nip42Authenticator(GROUP_RELAY, authTimeout(signing), signing::signEvent)

    companion object {
        private const val TAG = "Chat"
        private const val QUERY_SUBSCRIPTION = "q"
        private const val LIVE_SUBSCRIPTION = "live"

        /** Dart `_publish` when `_connectAuthed` gave no socket. */
        private const val AUTH_FAILED_TEXT = "Anmeldung am Relay fehlgeschlagen"

        /** WebSocket connect deadline. */
        val CONNECT_TIMEOUT: Duration = 5.seconds

        /** EOSE wait of queries and OK wait of publishes (Dart `_timeout`). */
        val QUERY_TIMEOUT: Duration = 8.seconds
        val PUBLISH_TIMEOUT: Duration = 8.seconds

        /** EOSE wait of the unread query. */
        val UNREAD_TIMEOUT: Duration = 6.seconds

        /** Lifetime of the cached room list. */
        val CACHE_TTL: Duration = 5.minutes

        /** AUTH deadline with a local key. */
        val AUTH_TIMEOUT_LOCAL: Duration = 8.seconds

        /** AUTH deadline for Amber / NIP-07. */
        val AUTH_TIMEOUT_EXTERNAL: Duration = 25.seconds

        /** Added to the NIP-46 sign timeout (60 s) for the AUTH deadline. */
        val AUTH_NIP46_EXTRA: Duration = 20.seconds

        /** Dart: local 8 s, NIP-46 `signTimeout + 20 s` (80 s), everything else 25 s. */
        internal suspend fun authTimeout(signing: SigningService): Duration =
            when (runSuspendCatching { signing.getMode() }.getOrNull()) {
                SigningMode.LOCAL -> AUTH_TIMEOUT_LOCAL
                SigningMode.NIP46 -> Nip46Timeouts().sign + AUTH_NIP46_EXTRA
                else -> AUTH_TIMEOUT_EXTERNAL
            }
    }
}

/** REQ filter of a live subscription: `{"kinds":[kind],"<tag>":[value]}` + optional `since` (Dart order). */
internal fun liveFilter(kind: Int, tagKey: String, value: String, since: Instant?): JsonObject {
    val pairs = buildList<Pair<String, Any?>> {
        add("kinds" to listOf(kind))
        add(tagKey to listOf(value))
        if (since != null) add("since" to Math.floorDiv(since.toEpochMilli(), 1000L))
    }
    return jsonObjectOf(*pairs.toTypedArray())
}

/**
 * The unread counting rule shared by rooms and event threads (spec 11 §8.8): skip events whose
 * `created_at` is not an int (and, with [dedupeById], empty or already seen ids), skip own
 * events, take the FIRST [tagName] tag; an unrequested value skips the event; count when
 * `created_at > mark`.
 */
internal fun countUnread(
    events: Iterable<JsonObject>,
    keys: List<String>,
    tagName: String,
    me: String?,
    dedupeById: Boolean,
    markOf: (String) -> Long,
): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>()
    for (k in keys) counts[k] = 0
    val seen = HashSet<String>()
    for (e in events) {
        val ts = e["created_at"].dartIntOrNullOf() ?: continue
        if (dedupeById) {
            val id = e.stringField("id")
            if (id.isEmpty() || !seen.add(id)) continue
        }
        val pubkey = e.stringField("pubkey")
        if (me != null && pubkey == me) continue
        val tag = e.tagLists().firstOrNull { t -> t.size >= 2 && t.tagName(0) == tagName } ?: continue
        val key = tag.tagValue(1)
        val current = counts[key] ?: continue
        if (ts > markOf(key)) counts[key] = current + 1
    }
    return counts
}

/** Dart `e.toString()` of a failure as shown inside `chatSendFailed`/`chatJoinFailed`. */
internal fun errorText(e: Throwable): String = e.message?.takeIf { it.isNotEmpty() } ?: e.toString()
