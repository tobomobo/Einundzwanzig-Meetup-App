package space.einundzwanzig.meetup.data.chat

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * NIP-22 comment threads (kind 1111) on NIP-52 calendar events (Dart `EventChatService`,
 * spec 11 §6.5). Uses the shared read targets (`LightningAddressLookup.readTargets()`: news
 * relays ∪ `relay.nostr.band` ∪ active relays) for reading **and** writing; no AUTH, no
 * membership.
 */
interface EventChatService {

    /**
     * All comments with root `A` = [eventAddress] from every read target in parallel
     * (`{"kinds":[1111],"#A":[address],"limit":200}`, connect 4 s, EOSE 7 s), merged by id,
     * oldest first.
     */
    suspend fun loadMessages(eventAddress: String): List<ChatMessage>

    /**
     * Publishes the trimmed [text] as kind 1111 to every read target in parallel. Tags
     * ([commentTags]): `A`, `K`, `P` (root) then `a`, `k`, `p` (parent), the `P`/`p` tags only
     * for a non-empty [eventAuthorPubkey].
     *
     * @return `null` if at least one relay accepted (or the text was blank), the signer error
     *   text, or `"Kein Relay hat den Beitrag angenommen"`.
     */
    suspend fun send(eventAddress: String, eventAuthorPubkey: String, text: String): String?

    /**
     * Live comments (`["REQ","evtchat",{"kinds":[1111],"#A":[address],"since":…}]`) on the first
     * reachable read target. Completes silently when none is reachable or the connection ends.
     */
    fun subscribe(eventAddress: String, since: Instant? = null): Flow<ChatMessage>

    /**
     * Unread comments of others per address since the local read mark ([readKey]; 14-day
     * window if never opened), de-duplicated by event id across relays.
     */
    suspend fun unreadCounts(addresses: List<String>): Map<String, Int>

    companion object {
        /** Read-mark key of an event thread in [ChatReadMarkStore] (Dart `readKey`). */
        fun readKey(eventAddress: String): String = "evt|$eventAddress"

        /** Dart `send` tags of a flat comment on [eventAddress] (`<kind>:<pubkey>:<d>`). */
        fun commentTags(eventAddress: String, eventAuthorPubkey: String): List<List<String>> {
            // Dart `split(':')` never returns an empty list, so the root kind is always parts[0].
            val rootKind = eventAddress.split(':')[0]
            return buildList {
                add(listOf("A", eventAddress))
                add(listOf("K", rootKind))
                if (eventAuthorPubkey.isNotEmpty()) add(listOf("P", eventAuthorPubkey))
                add(listOf("a", eventAddress))
                add(listOf("k", rootKind))
                if (eventAuthorPubkey.isNotEmpty()) add(listOf("p", eventAuthorPubkey))
            }
        }
    }
}
