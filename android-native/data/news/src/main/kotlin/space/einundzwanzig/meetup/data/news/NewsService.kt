package space.einundzwanzig.meetup.data.news

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * News feed, unread counter and NIP-23 full text (Dart `NewsService`, spec 11 §2.2/§2.3, §5 N1,
 * §6.6, §8.3–§8.5). No method throws except for cancellation.
 */
interface NewsService {

    /**
     * Fetches the RSS feed (N1, [FEED_TIMEOUT]) and returns at most [limit] articles in feed order
     * (newest first). Non-200, network or parse errors → empty list. Bypasses the cache (the news
     * screen and its pull-to-refresh use this).
     */
    suspend fun fetchArticles(limit: Int = DEFAULT_LIMIT): List<NewsArticle>

    /**
     * [fetchArticles] behind a process-wide [CACHE_TTL] RAM cache for the dashboard tile and the
     * unread counter. Only non-empty results are cached; [limit] is ignored on a cache hit (Dart parity).
     */
    suspend fun cachedArticles(limit: Int = DEFAULT_LIMIT): List<NewsArticle>

    /**
     * Number of [cachedArticles] newer than the stored mark `news_last_read_ts`. On the very
     * first call (no mark) the newest `publishedAt` is stored and 0 returned. Errors → 0.
     */
    suspend fun unreadCount(): Int

    /**
     * Stores the newest `publishedAt` of [cachedArticles] as read mark (dashboard News tile only;
     * the community hub path does not call it). No-op when there are no articles.
     *
     * Runs in the process-wide `@ApplicationScope`, so it completes even when the calling
     * coroutine is cancelled (like Dart's un-awaited Future); callers may fire and forget it
     * from any scope.
     */
    suspend fun markRead()

    /**
     * The Markdown body of the kind-30023 event addressed by [guid] (`30023:<pubkey>:<d>`), from
     * [NewsLinks.ARTICLE_RELAYS] in parallel (sub `article`, connect [ARTICLE_CONNECT_TIMEOUT],
     * total [ARTICLE_TIMEOUT]). The newest `created_at` wins (later arrival on ties). `null` for an
     * unparseable guid, no event, or empty content.
     */
    suspend fun fetchArticleContent(guid: String): String?

    companion object {
        /** Prefs key (Flutter name) of the read mark: unix seconds of the newest article seen. */
        const val KEY_LAST_READ_TS: String = "news_last_read_ts"

        /** Default number of articles. */
        const val DEFAULT_LIMIT: Int = 50

        /** RAM cache lifetime of [cachedArticles]. */
        val CACHE_TTL: Duration = 10.minutes

        /** Whole-call budget of the feed request. */
        val FEED_TIMEOUT: Duration = 12.seconds

        /** Per-relay connect budget of [fetchArticleContent]. */
        val ARTICLE_CONNECT_TIMEOUT: Duration = 6.seconds

        /** Total budget of [fetchArticleContent]. */
        val ARTICLE_TIMEOUT: Duration = 12.seconds

        /** Subscription id of the full-text query. */
        const val ARTICLE_SUBSCRIPTION_ID: String = "article"
    }
}
