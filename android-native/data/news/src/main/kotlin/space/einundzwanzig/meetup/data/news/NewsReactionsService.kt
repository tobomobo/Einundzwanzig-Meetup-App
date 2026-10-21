package space.einundzwanzig.meetup.data.news

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Hearts on an article (Dart `ArticleLikes`, spec 11 §3.5).
 *
 * @property count distinct pubkeys with a positive reaction (+1 if only the local list knows the own one).
 * @property mine whether the active identity already reacted.
 */
data class ArticleLikes(val count: Int, val mine: Boolean) {
    /** After a successful like: unchanged if already [mine], else `count + 1` and `mine`. */
    fun withMine(): ArticleLikes = if (mine) this else ArticleLikes(count + 1, true)

    companion object {
        /** No hearts, not mine. */
        val EMPTY: ArticleLikes = ArticleLikes(0, false)
    }
}

/**
 * NIP-25 likes on news articles plus the shared "read targets" relay list (Dart
 * `NewsReactionsService`, spec 11 §6.1, §6.7, §8.7). No method throws except for cancellation.
 */
interface NewsReactionsService {

    /**
     * [NEWS_RELAYS] ∪ [READ_ONLY_RELAYS] ∪ the user's active relays (ordered, de-duplicated; the
     * defaults are always included). Also used by event chat, RSVP and the zap-receipt check.
     */
    suspend fun readTargets(): List<String>

    /** [readTargets] without [READ_ONLY_RELAYS]; likes are published here. */
    suspend fun writeTargets(): List<String>

    /**
     * Whether the local list `news_liked_reactions` has `"<myPubkey>|<articleAddress>"`. No
     * network; `false` without identity.
     */
    suspend fun hasLikedLocally(articleAddress: String): Boolean

    /**
     * Counts distinct positive reactors of kind 7 with `#a` = [articleAddress] on all
     * [readTargets] (sub `likes`, connect [FETCH_CONNECT_TIMEOUT], total [TIMEOUT]). The local list
     * beats the relays for the own reaction (§8.7).
     */
    suspend fun fetchLikes(articleAddress: String): ArticleLikes

    /**
     * Signs and publishes `kind 7` `"+"` with [likeTags] to every [writeTargets] relay (connect
     * [PUBLISH_CONNECT_TIMEOUT], total [TIMEOUT], early end when all answered). `true` iff at
     * least one relay answered `OK true`; then `"<signerPubkey>|<address>"` is remembered. If the
     * local list already has the own entry, returns `true` without signing or network.
     *
     * Publishing and remembering run in the process-wide `@ApplicationScope`: if the caller is
     * cancelled (screen closed) after signing, the publish still finishes and an accepted like is
     * still written to `news_liked_reactions` (Dart Futures are not cancelled either).
     *
     * [authorPubkey] goes into the `p` tag when it is non-empty; pass
     * [NewsArticles.zapAuthorPubkey] so malformed ids produce no bogus `p` tag.
     */
    suspend fun like(articleAddress: String, authorPubkey: String): Boolean

    companion object {
        /** Prefs key (Flutter name): `"<hexPubkey>|<articleAddress>"` entries, capped at [LIKED_CAP]. */
        const val KEY_LIKED_REACTIONS: String = "news_liked_reactions"

        /** Maximum number of remembered likes; the oldest drop out. */
        const val LIKED_CAP: Int = 500

        /** Relays the articles come from (read and write). */
        val NEWS_RELAYS: List<String> get() = LightningAddressLookup.NEWS_RELAYS

        /** Read-only aggregator relays (never written to). */
        val READ_ONLY_RELAYS: List<String> get() = LightningAddressLookup.READ_ONLY_RELAYS

        /** Kind of a reaction. */
        const val KIND_REACTION: Int = 7

        /** Content of a like. */
        const val LIKE_CONTENT: String = "+"

        /** Subscription id of [fetchLikes]. */
        const val LIKES_SUBSCRIPTION_ID: String = "likes"

        /** `limit` of the likes filter. */
        const val LIKES_LIMIT: Int = 500

        /** Overall budget of [fetchLikes] and [like]. */
        val TIMEOUT: Duration = 8.seconds

        /** Per-relay connect budget of [fetchLikes]. */
        val FETCH_CONNECT_TIMEOUT: Duration = 4.seconds

        /** Per-relay connect budget of [like]. */
        val PUBLISH_CONNECT_TIMEOUT: Duration = 5.seconds

        /** Local-list entry `"<pubkey>|<address>"`. */
        fun likedEntry(pubkey: String, articleAddress: String): String = "$pubkey|$articleAddress"

        /** Tags of a like: `[["a", address], ["p", author] (if non-empty), ["k", "30023"]]`. */
        fun likeTags(articleAddress: String, authorPubkey: String): List<List<String>> = buildList {
            add(listOf("a", articleAddress))
            if (authorPubkey.isNotEmpty()) add(listOf("p", authorPubkey))
            add(listOf("k", "30023"))
        }

        /** NIP-25: only `"-"` (trimmed) is negative; empty content and any emoji count as positive. */
        fun isPositive(content: String): Boolean = content.dartTrim() != "-"
    }
}
