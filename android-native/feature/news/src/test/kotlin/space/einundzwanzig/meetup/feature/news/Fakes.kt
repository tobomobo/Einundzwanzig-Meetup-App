package space.einundzwanzig.meetup.feature.news

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetSnapshot
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.news.ArticleLikes
import space.einundzwanzig.meetup.data.news.NewsArticle
import space.einundzwanzig.meetup.data.news.NewsReactionsService
import space.einundzwanzig.meetup.data.news.NewsService
import space.einundzwanzig.meetup.data.news.NewsZapService
import space.einundzwanzig.meetup.data.news.ZapResult

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

val PK_A = "a".repeat(64)

fun article(
    d: String,
    pubkey: String = PK_A,
    title: String = "Titel $d",
    summary: String = "Zusammenfassung $d",
    author: String = "Autor",
    link: String = "https://media.einundzwanzig.space/a/$d",
    id: String = "30023:$pubkey:$d",
    publishedAt: Long = 1_792_226_373,
) = NewsArticle(
    id = id,
    author = author,
    title = title,
    summary = summary,
    image = "",
    content = "",
    publishedAt = publishedAt,
    link = link,
)

class FakeNewsService(
    var feed: List<NewsArticle> = emptyList(),
    var cached: List<NewsArticle> = emptyList(),
    var content: String? = "# Text",
) : NewsService {
    var fetchCalls = 0
    var cachedCalls = 0
    var markReadCalls = 0
    var contentGate: CompletableDeferred<Unit>? = null
    val contentRequests = mutableListOf<String>()

    override suspend fun fetchArticles(limit: Int): List<NewsArticle> {
        fetchCalls++
        return feed.take(limit)
    }

    override suspend fun cachedArticles(limit: Int): List<NewsArticle> {
        cachedCalls++
        return cached
    }

    override suspend fun unreadCount(): Int = 0

    override suspend fun markRead() {
        markReadCalls++
    }

    override suspend fun fetchArticleContent(guid: String): String? {
        contentRequests += guid
        contentGate?.await()
        return content
    }
}

class FakeReactions(
    var likedLocally: Boolean = false,
    var relayLikes: ArticleLikes = ArticleLikes.EMPTY,
    var likeResult: Boolean = true,
) : NewsReactionsService {
    var likesGate: CompletableDeferred<Unit>? = null
    var likeGate: CompletableDeferred<Unit>? = null
    val likeCalls = mutableListOf<Pair<String, String>>()

    override suspend fun readTargets(): List<String> = emptyList()
    override suspend fun writeTargets(): List<String> = emptyList()
    override suspend fun hasLikedLocally(articleAddress: String): Boolean = likedLocally

    override suspend fun fetchLikes(articleAddress: String): ArticleLikes {
        likesGate?.await()
        return relayLikes
    }

    override suspend fun like(articleAddress: String, authorPubkey: String): Boolean {
        likeCalls += articleAddress to authorPubkey
        likeGate?.await()
        return likeResult
    }
}

data class ZapCall(val authorPubkey: String, val address: String, val sats: Long, val comment: String?)

class FakeZaps(var result: ZapResult = ZapResult.Success("lnbc1invoice")) : NewsZapService {
    val calls = mutableListOf<ZapCall>()

    override suspend fun fetchLightningAddress(pubkeyHex: String): String? = null

    override suspend fun createZapInvoice(
        authorPubkey: String,
        articleAddress: String,
        amountSats: Long,
        comment: String?,
    ): ZapResult {
        calls += ZapCall(authorPubkey, articleAddress, amountSats, comment)
        return result
    }
}

class FakeWidgetStore(var fail: Boolean = false) : HomeWidgetStore {
    var markNewsSeenCalls = 0
    override suspend fun snapshot(): HomeWidgetSnapshot = error("unused")
    override suspend fun writeBitcoin(values: Map<String, String>) = Unit
    override suspend fun updateMeetup(city: String, start: Instant?) = Unit
    override suspend fun publishLatestNews(id: String, title: String) = Unit
    override suspend fun markNewsSeen() {
        markNewsSeenCalls++
        if (fail) error("prefs kaputt")
    }
}
