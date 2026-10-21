package space.einundzwanzig.meetup.data.news.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.RelayParseTally
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.core.network.getCapped
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayFanOut
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.data.news.ArticleAddress
import space.einundzwanzig.meetup.data.news.NewsArticle
import space.einundzwanzig.meetup.data.news.NewsLinks
import space.einundzwanzig.meetup.data.news.NewsService
import space.einundzwanzig.meetup.data.news.NewsService.Companion.ARTICLE_CONNECT_TIMEOUT
import space.einundzwanzig.meetup.data.news.NewsService.Companion.ARTICLE_SUBSCRIPTION_ID
import space.einundzwanzig.meetup.data.news.NewsService.Companion.ARTICLE_TIMEOUT
import space.einundzwanzig.meetup.data.news.NewsService.Companion.CACHE_TTL
import space.einundzwanzig.meetup.data.news.NewsService.Companion.FEED_TIMEOUT
import space.einundzwanzig.meetup.data.news.NewsService.Companion.KEY_LAST_READ_TS
import space.einundzwanzig.meetup.data.news.RssParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Port of `lib/services/news_service.dart` (without the unused `htmlToMarkdown`). */
@Singleton
internal class DefaultNewsService(
    private val client: OkHttpClient,
    private val relayClient: RelayClient,
    private val store: KeyValueStore,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    private val parseDispatcher: CoroutineDispatcher,
    /** Runs [markRead] so it completes like Dart's un-awaited Future, whatever the caller's scope. */
    private val appScope: CoroutineScope,
    /** Feed URL; replaceable in tests (loopback server). */
    private val feedUrl: String,
) : NewsService {

    @Inject
    constructor(
        client: OkHttpClient,
        relayClient: RelayClient,
        store: KeyValueStore,
        time: TimeSource,
        log: DiagnosticLog,
        @DefaultDispatcher parseDispatcher: CoroutineDispatcher,
        @ApplicationScope appScope: CoroutineScope,
    ) : this(client, relayClient, store, time, log, parseDispatcher, appScope, NewsLinks.FEED_URL)

    private class Cached(val articles: List<NewsArticle>, val atMillis: Long)

    @Volatile
    private var cache: Cached? = null

    override suspend fun fetchArticles(limit: Int): List<NewsArticle> {
        try {
            val result = client.getCapped(
                feedUrl,
                headers = mapOf("Accept" to NewsLinks.FEED_ACCEPT, "User-Agent" to NewsLinks.FEED_USER_AGENT),
                maxBytes = FEED_MAX_BYTES,
                timeout = FEED_TIMEOUT,
            )
            val response = when (result) {
                is HttpResult.Response -> result
                is HttpResult.TooLarge -> {
                    log.debug(TAG, "Feed-Fehler: Antwort ueber ${result.maxBytes} Bytes")
                    return emptyList()
                }
                is HttpResult.Failure -> {
                    log.debug(TAG, "Feed-Fehler: ${describeError(result.cause)}")
                    return emptyList()
                }
            }
            if (response.status != 200) {
                log.debug(TAG, "Feed HTTP ${response.status}")
                return emptyList()
            }
            val now = time.nowSeconds()
            val articles = withContext(parseDispatcher) { RssParser.parse(response.text(), now) }
            return if (articles.size > limit) articles.subList(0, maxOf(limit, 0)).toList() else articles
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "Feed-Fehler: ${describeError(e)}")
            return emptyList()
        }
    }

    override suspend fun cachedArticles(limit: Int): List<NewsArticle> {
        val c = cache
        if (c != null && time.nowMillis() - c.atMillis < CACHE_TTL.inWholeMilliseconds) return c.articles
        val fresh = fetchArticles(limit)
        if (fresh.isNotEmpty()) cache = Cached(fresh, time.nowMillis())
        return fresh
    }

    override suspend fun unreadCount(): Int {
        try {
            val stored = store.get(KEY_LAST_READ_TS)
            // Dart `prefs.getInt` throws on a value of another type → caught → 0.
            if (stored != null && stored !is PrefValue.IntValue) {
                log.warn(TAG, "Ungelesen-Zaehler fehlgeschlagen", IllegalStateException("news_last_read_ts ist kein int"))
                return 0
            }
            val lastRead = stored?.value
            val articles = cachedArticles()
            if (articles.isEmpty()) return 0
            if (lastRead == null) {
                storeNewest(articles)
                return 0
            }
            return articles.count { it.publishedAt > lastRead }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Ungelesen-Zaehler fehlgeschlagen", e)
            return 0
        }
    }

    // In the application scope: a dashboard viewModelScope that dies on navigation must not drop
    // the write of news_last_read_ts (the unread badge would come back).
    override suspend fun markRead(): Unit = appScope.async { markReadNow() }.await()

    private suspend fun markReadNow() {
        try {
            val articles = cachedArticles()
            if (articles.isNotEmpty()) storeNewest(articles)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "markRead fehlgeschlagen", e)
        }
    }

    private suspend fun storeNewest(articles: List<NewsArticle>) {
        val newest = articles.maxOf { it.publishedAt }
        if (!store.setInt(KEY_LAST_READ_TS, newest)) {
            log.warn(TAG, "news_last_read_ts konnte nicht gespeichert werden")
        }
    }

    override suspend fun fetchArticleContent(guid: String): String? {
        val address = ArticleAddress.parse(guid) ?: return null
        // Dart map literal order: kinds, authors, #d, limit.
        val filter = jsonObjectOf(
            "kinds" to listOf(ArticleAddress.KIND_LONG_FORM),
            "authors" to listOf(address.pubkey),
            "#d" to listOf(address.d),
            "limit" to 1,
        )
        // One tally for all relays of this fetch, reported at the end (also on timeout).
        val tally = RelayParseTally(TAG, "Artikel-Volltext", log)
        var bestAt = -1L
        var bestContent: String? = null
        try {
            withTimeoutOrNull(ARTICLE_TIMEOUT) {
                relayClient.queryAll(
                    NewsLinks.ARTICLE_RELAYS,
                    listOf(filter),
                    RelayQueryOptions(
                        connectTimeout = ARTICLE_CONNECT_TIMEOUT,
                        responseTimeout = ARTICLE_TIMEOUT,
                        subscriptionId = ARTICLE_SUBSCRIPTION_ID,
                        logTag = EVENT_LOG_TAG,
                        parseTally = tally,
                    ),
                    RelayFanOut.PARALLEL,
                ) { _, event ->
                    val at = EventGuard.replaceableCreatedAt(event, time) ?: return@queryAll
                    if (at < bestAt) return@queryAll
                    bestAt = at
                    bestContent = contentOf(event["content"])
                }
            }
        } finally {
            tally.report()
        }
        return bestContent?.takeIf { it.isNotEmpty() }
    }

    /** Dart `(event['content'] ?? '').toString()`. */
    private fun contentOf(value: kotlinx.serialization.json.JsonElement?): String = when (value) {
        null, is JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    companion object {
        const val TAG: String = "NewsService"

        /** `RelaySocket.verifiedEvent(tag: 'News')`. */
        const val EVENT_LOG_TAG: String = "News"

        /** Body cap of the feed (the live feed is a few hundred KB). */
        const val FEED_MAX_BYTES: Long = 8L * 1024 * 1024
    }
}
