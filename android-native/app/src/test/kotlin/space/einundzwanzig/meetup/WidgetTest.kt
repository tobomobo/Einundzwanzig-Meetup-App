package space.einundzwanzig.meetup

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.navigation.BitcoinDashboard
import space.einundzwanzig.meetup.core.navigation.DeepLinks
import space.einundzwanzig.meetup.core.navigation.MeetupCalendar
import space.einundzwanzig.meetup.core.navigation.News
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetSnapshot
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.calendar.widget.WidgetTarget
import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetBridge
import space.einundzwanzig.meetup.data.migration.MigrationGate
import space.einundzwanzig.meetup.data.news.NewsArticle
import space.einundzwanzig.meetup.data.news.NewsService
import space.einundzwanzig.meetup.widget.countdownText
import space.einundzwanzig.meetup.widget.refreshWidget
import space.einundzwanzig.meetup.widget.widgetIntent
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "de")
class WidgetTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 10, 22)
    private val gate = object : MigrationGate {
        override val isComplete = true
        override suspend fun await() = Unit
    }

    private fun startOf(day: LocalDate) = day.atTime(19, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `router target comes from the data host, then the open extra`() {
        val withHost = Intent().setData(Uri.parse("homewidget://news")).putExtra(DeepLinks.EXTRA_OPEN, "bitcoin")
        assertEquals("news", WidgetRouterActivity.targetOf(withHost))
        val extraOnly = Intent().putExtra(DeepLinks.EXTRA_OPEN, "meetup")
        assertEquals("meetup", WidgetRouterActivity.targetOf(extraOnly))
        assertNull(WidgetRouterActivity.targetOf(Intent()))
        assertNull(WidgetRouterActivity.targetOf(null))
    }

    @Test
    fun `widget intents are explicit and distinct per area`() {
        val news = widgetIntent(context, "news")
        assertEquals(WidgetRouterActivity::class.java.name, news.component?.className)
        assertEquals("space.einundzwanzig.meetup.WIDGET_NEWS", news.action)
        assertEquals("homewidget://news", news.dataString)
        assertEquals("news", news.getStringExtra(DeepLinks.EXTRA_OPEN))
        assertTrue(news.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val bitcoin = widgetIntent(context, "bitcoin")
        assertFalse(news.filterEquals(bitcoin))
    }

    @Test
    fun `widget targets route like Flutter`() {
        assertEquals(News(markAsRead = true), DeepLinks.forWidgetTarget(WidgetTarget.NEWS.wire))
        assertEquals(BitcoinDashboard, DeepLinks.forWidgetTarget(WidgetTarget.BITCOIN.wire))
        assertEquals(MeetupCalendar(preselectNextFavorite = true), DeepLinks.forWidgetTarget(WidgetTarget.MEETUP.wire))
        assertNull(DeepLinks.forWidgetTarget(null))
    }

    @Test
    fun `countdown is computed at render time`() {
        fun at(day: LocalDate) = countdownText(context, HomeWidgetSnapshot(meetupCity = "Wien", meetupStartEpochMs = startOf(day)), today, zone)
        assertEquals("Heute", at(today))
        assertEquals("Morgen", at(today.plusDays(1)))
        assertEquals("in 5 Tagen", at(today.plusDays(5)))
        // Past start (stale value): "Heute", as Dart `days <= 0`.
        assertEquals("Heute", at(today.minusDays(2)))
    }

    @Test
    fun `countdown falls back to the stored string and the empty state`() {
        assertEquals(
            "in 3 Tagen",
            countdownText(context, HomeWidgetSnapshot(meetupCity = "Wien", meetupCountdown = "in 3 Tagen"), today, zone),
        )
        assertEquals("In der App wählen", countdownText(context, HomeWidgetSnapshot(), today, zone))
    }

    @Test
    fun `refresh writes bitcoin and the latest article`() = runTest {
        val bridge = FakeBridge()
        val store = FakeWidgetStore()
        val news = FakeNews(listOf(article("a1", "Halving")))
        refreshWidget(gate, bridge, news, store, AppLogger)
        assertEquals(1, bridge.refreshes)
        assertEquals(1, news.lastLimit)
        assertEquals(listOf("a1" to "Halving"), store.published)
    }

    @Test
    fun `empty feed preserves the last headline and new marker, failures are isolated`() = runTest {
        val store = FakeWidgetStore()
        val previous = HomeWidgetSnapshot(newsTitle = "Last good headline", newsIsNew = true)
        store.current = previous
        refreshWidget(gate, FakeBridge(fail = true), FakeNews(emptyList()), store, AppLogger)
        assertTrue(store.published.isEmpty())
        assertEquals(previous, store.snapshot())

        val store2 = FakeWidgetStore()
        val bridge = FakeBridge()
        refreshWidget(gate, bridge, FakeNews(emptyList(), fail = true), store2, AppLogger)
        assertEquals(1, bridge.refreshes)
        assertTrue(store2.published.isEmpty())
    }

    @Test
    fun `slow migration prevents all widget refresh work until it completes`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val pendingGate = object : MigrationGate {
            override val isComplete get() = ready.isCompleted
            override suspend fun await() = ready.await()
        }
        val bridge = FakeBridge()
        val news = FakeNews(listOf(article("a1", "Imported news")))
        val store = FakeWidgetStore()
        val refresh = launch { refreshWidget(pendingGate, bridge, news, store, AppLogger) }
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertEquals(0, bridge.refreshes)
        assertEquals(-1, news.lastLimit)
        assertTrue(store.published.isEmpty())
        ready.complete(Unit)
        refresh.join()
        assertEquals(1, bridge.refreshes)
        assertEquals(listOf("a1" to "Imported news"), store.published)
    }

    @Test
    fun `cancelling a worker while migration is pending never starts refresh`() = runTest {
        val pendingGate = object : MigrationGate {
            override val isComplete = false
            override suspend fun await() = CompletableDeferred<Unit>().await()
        }
        val bridge = FakeBridge()
        val news = FakeNews(emptyList())
        val store = FakeWidgetStore()
        val refresh = launch { refreshWidget(pendingGate, bridge, news, store, AppLogger) }
        testScheduler.runCurrent()
        refresh.cancel()
        refresh.join()
        assertEquals(0, bridge.refreshes)
        assertEquals(-1, news.lastLimit)
        assertTrue(store.published.isEmpty())
    }

    private fun article(id: String, title: String) = NewsArticle(id, "", title, "", "", "", 0, "")

    private class FakeBridge(private val fail: Boolean = false) : BitcoinWidgetBridge {
        var refreshes = 0
        override suspend fun publish(d: BitcoinDashboardData): Boolean = true
        override suspend fun refreshBitcoin(): Boolean {
            refreshes++
            if (fail) throw IOException("offline")
            return true
        }
    }

    private class FakeNews(private val articles: List<NewsArticle>, private val fail: Boolean = false) : NewsService {
        var lastLimit = -1
        override suspend fun fetchArticles(limit: Int): List<NewsArticle> {
            lastLimit = limit
            if (fail) throw IOException("offline")
            return articles.take(limit)
        }
        override suspend fun cachedArticles(limit: Int) = articles
        override suspend fun unreadCount() = 0
        override suspend fun markRead() = Unit
        override suspend fun fetchArticleContent(guid: String): String? = null
    }

    private class FakeWidgetStore : HomeWidgetStore {
        val published = mutableListOf<Pair<String, String>>()
        var current = HomeWidgetSnapshot()
        override suspend fun snapshot() = current
        override suspend fun writeBitcoin(values: Map<String, String>) = Unit
        override suspend fun updateMeetup(city: String, start: java.time.Instant?) = Unit
        override suspend fun publishLatestNews(id: String, title: String) {
            published += id to title
        }
        override suspend fun markNewsSeen() = Unit
    }
}
