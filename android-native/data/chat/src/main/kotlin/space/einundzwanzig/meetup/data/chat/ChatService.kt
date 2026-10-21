package space.einundzwanzig.meetup.data.chat

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * NIP-29 meetup group chat on [GROUP_RELAY] (Dart `ChatService`, spec 11 §6.3–§6.4, §8.8).
 *
 * The relay requires NIP-42 AUTH even for reads; every query, publish and live subscription
 * opens its own authenticated connection (signer-dependent AUTH deadline: 8 s local key,
 * NIP-46 sign timeout + 20 s, 25 s Amber). Membership is relay-authoritative (kind 39002).
 *
 * Error contract:
 * - Room lookups ([loadMeetupRooms], [findRoomForMeetupId], [findRoomForCity], [findMeetupRoom])
 *   throw [ChatRelayUnavailable] when the relay gave **no** answer; `null` / empty means the
 *   relay answered and there is nothing.
 * - [loadMessages], [isMember], [unreadCounts] treat "no answer" as empty / `false` / `0`.
 * - [send], [join], [leave] return `null` on success, else the raw reason text shown inside
 *   `chatSendFailed` / `chatJoinFailed` (`"Zeitüberschreitung"`, `"Anmeldung am Relay
 *   fehlgeschlagen"`, the relay's OK message, or the signer error text).
 */
interface ChatService {

    /**
     * All meetup rooms (`{"kinds":[39000],"#t":["meetup"],"limit":500}`), newest per `h`.
     * Cached in memory for 5 minutes (non-empty results only); [force] bypasses the cache.
     * Without an answer the stale cache is returned, or [ChatRelayUnavailable] is thrown.
     *
     * Single-flight: concurrent calls (also via [findMeetupRoom] / [findRoomForCity]) share one
     * authenticated fetch and its outcome, so parallel lookups cause at most one signer prompt.
     * The other queries open their own connection each; run dashboard lookups sequentially.
     */
    suspend fun loadMeetupRooms(force: Boolean = false): List<ChatRoom>

    /** Room whose `meetup_slug` equals [slug] case-insensitively (unused by the Flutter UI). */
    suspend fun findMeetupRoom(slug: String): ChatRoom?

    /**
     * Room bound to the portal meetup [portalId] (`{"kinds":[39000],"#i":["meetup:<id>"],"limit":5}`,
     * newest wins, ties → later). `null` if none or [portalId] is empty.
     * @throws ChatRelayUnavailable no answer from the relay (do NOT fall back to the city then).
     */
    suspend fun findRoomForMeetupId(portalId: String): ChatRoom?

    /**
     * Legacy fallback by city name ([ChatRules.matchCity] over [loadMeetupRooms]).
     * @throws ChatRelayUnavailable no answer from the relay and no cached room list.
     */
    suspend fun findRoomForCity(city: String): ChatRoom?

    /** Whether the own pubkey is in the newest kind-39002 member list of [h]. */
    suspend fun isMember(h: String): Boolean

    /** Publishes a kind-9021 join request `[["h", h]]`; `null` = accepted by the relay. */
    suspend fun join(h: String): String?

    /** Publishes a kind-9022 leave request `[["h", h]]` (not exposed in the Flutter UI). */
    suspend fun leave(h: String): String?

    /** The newest [limit] messages of [h], oldest first (stable for equal timestamps). */
    suspend fun loadMessages(h: String, limit: Int = 100): List<ChatMessage>

    /**
     * Live messages of [h] (`["REQ","live",{"kinds":[9],"#h":[h],"since":…}]`), `since` inclusive
     * (callers drop known ids). Collecting opens the authenticated socket, cancelling closes it.
     * Connection or AUTH failures complete the flow silently (the chat stays readable).
     */
    fun subscribe(h: String, since: Instant? = null): Flow<ChatMessage>

    /** Sends the trimmed [text] as kind 9 `[["h", h]]`; empty text is a no-op returning `null`. */
    suspend fun send(h: String, text: String): String?

    /**
     * Unread messages of others per room since the local read mark (7-day window for rooms
     * never opened). One query for all rooms; every requested `h` is in the result.
     */
    suspend fun unreadCounts(hs: List<String>): Map<String, Int>

    /** [ChatReadMarkStore.lastReadFor] (Dart `ChatService.lastReadFor`). */
    suspend fun lastReadFor(key: String): Long

    /** [ChatReadMarkStore.markRead]; for rooms the key is `h`, for event threads [EventChatService.readKey]. */
    suspend fun markRead(key: String, until: Instant)
}
