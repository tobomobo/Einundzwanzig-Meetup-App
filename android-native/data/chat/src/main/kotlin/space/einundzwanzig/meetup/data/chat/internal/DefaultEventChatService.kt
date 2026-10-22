package space.einundzwanzig.meetup.data.chat.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelaySubscriptionEvent
import space.einundzwanzig.meetup.data.chat.ChatKinds
import space.einundzwanzig.meetup.data.chat.ChatMessage
import space.einundzwanzig.meetup.data.chat.ChatReadMarkStore
import space.einundzwanzig.meetup.data.chat.EventChatService
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** [EventChatService] on [RelayClient] and the shared read targets. */
internal class DefaultEventChatService @Inject constructor(
    private val relayClient: RelayClient,
    private val signing: SigningService,
    private val targets: LightningAddressLookup,
    private val readMarks: ChatReadMarkStore,
    private val time: TimeSource,
) : EventChatService {

    override suspend fun loadMessages(eventAddress: String): List<ChatMessage> {
        val relays = targets.readTargets()
        val byId = LinkedHashMap<String, ChatMessage>()
        // All relays at once, de-duplicated by id: the same event comes back from several.
        relayClient.queryAll(
            relays,
            listOf(jsonObjectOf("kinds" to listOf(ChatKinds.COMMENT), "#A" to listOf(eventAddress), "limit" to 200)),
            queryOptions(),
        ) { _, event ->
            ChatMessage.fromEvent(event)?.let { byId[it.id] = it }
        }
        val msgs = byId.values.sortedBy { it.createdAt }
        AppLogger.debug(TAG, "${msgs.size} Beitraege zu $eventAddress.")
        return msgs
    }

    override suspend fun send(eventAddress: String, eventAuthorPubkey: String, text: String): String? {
        val body = text.dartTrim()
        if (body.isEmpty()) return null
        val signed: NostrEvent = try {
            signing.signEvent(ChatKinds.COMMENT, EventChatService.commentTags(eventAddress, eventAuthorPubkey), body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Signieren fehlgeschlagen", e)
            return errorText(e)
        }
        val relays = targets.readTargets()
        val results = relayClient.publishAll(
            relays,
            signed,
            RelayPublishOptions(connectTimeout = CONNECT_TIMEOUT, ackTimeout = RESPONSE_TIMEOUT),
        )
        val accepted = results.count { it.accepted }
        AppLogger.debug(TAG, "Beitrag ${signed.id.take(8)}…: $accepted von ${relays.size} Relays angenommen")
        // One relay is enough: a comment stored anywhere is written.
        return if (accepted == 0) NONE_ACCEPTED else null
    }

    override fun subscribe(eventAddress: String, since: Instant?): Flow<ChatMessage> = flow {
        val relays = targets.readTargets()
        val filter = liveFilter(ChatKinds.COMMENT, "#A", eventAddress, since)
        val options = RelayQueryOptions(
            connectTimeout = CONNECT_TIMEOUT,
            subscriptionId = LIVE_SUBSCRIPTION,
            logTag = TAG,
            stopOnClosed = false,
        )
        val live = relayClient.subscribeFirstReachable(relays, listOf(filter), options)
            .mapNotNull { (relay, item) ->
                when (item) {
                    is RelaySubscriptionEvent.Event -> ChatMessage.fromEvent(item.event)
                    RelaySubscriptionEvent.EndOfStoredEvents -> {
                        AppLogger.debug(TAG, "Abo laeuft ueber $relay")
                        null
                    }
                    else -> null
                }
            }
            // Only upstream failures: an exception of the collector is never swallowed here.
            .catch { e ->
                // None reachable or connection lost: the thread stays readable, just not live.
                if (e is IOException) AppLogger.debug(TAG, "Abo beendet: ${e.message}") else throw e
            }
        emitAll(live)
    }

    override suspend fun unreadCounts(addresses: List<String>): Map<String, Int> {
        if (addresses.isEmpty()) return emptyMap()
        val me = signing.pubkeyHex()
        // Threads never opened get a 14-day window.
        val fallback = Math.floorDiv(time.nowMillis() - 14.days.inWholeMilliseconds, 1000L)
        val stored = readMarks.all()
        val marks = HashMap<String, Long>()
        var since = fallback
        for (a in addresses) {
            val m = stored[EventChatService.readKey(a)] ?: 0L
            val mark = if (m == 0L) fallback else m
            marks[a] = mark
            if (mark < since) since = mark
        }
        val relays = targets.readTargets()
        // Arrival order across relays (first seen wins for the id de-duplication).
        val events = ArrayList<JsonObject>()
        relayClient.queryAll(
            relays,
            listOf(jsonObjectOf("kinds" to listOf(ChatKinds.COMMENT), "#A" to addresses, "since" to since, "limit" to 300)),
            queryOptions(),
        ) { _, event -> events += event }
        val counts = countUnread(events, addresses, tagName = "A", me = me, dedupeById = true) { a -> marks[a] ?: fallback }
        AppLogger.debug(TAG, "Ungelesene Beitraege: $counts")
        return counts
    }

    override suspend fun markRead(eventAddress: String, until: Instant) =
        readMarks.markRead(EventChatService.readKey(eventAddress), until)

    override suspend fun lastReadFor(eventAddress: String): Long =
        readMarks.lastReadFor(EventChatService.readKey(eventAddress))

    private fun queryOptions() = RelayQueryOptions(
        connectTimeout = CONNECT_TIMEOUT,
        responseTimeout = RESPONSE_TIMEOUT,
        subscriptionId = QUERY_SUBSCRIPTION,
        logTag = TAG,
    )

    companion object {
        private const val TAG = "EventChat"
        private const val QUERY_SUBSCRIPTION = "q"
        private const val LIVE_SUBSCRIPTION = "evtchat"

        /** Error text when no relay confirmed a comment. */
        const val NONE_ACCEPTED: String = "Kein Relay hat den Beitrag angenommen"

        /** Per-relay connect deadline. */
        val CONNECT_TIMEOUT: Duration = 4.seconds

        /** Per-relay EOSE / OK deadline (Dart `_timeout`). */
        val RESPONSE_TIMEOUT: Duration = 7.seconds
    }
}
