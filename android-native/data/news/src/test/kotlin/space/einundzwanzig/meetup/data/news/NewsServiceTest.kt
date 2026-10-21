package space.einundzwanzig.meetup.data.news

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.data.news.internal.DefaultNewsService
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Feed (N1), RAM cache, unread counter (§8.5) and NIP-23 full text (§6.6). */
class NewsServiceTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val time = FakeTime()
    private val store = InMemoryKeyValueStore()
    private val relays = FakeRelays()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun feedService(connector: FakeRelayConnector = FakeRelayConnector.unreachable()) = DefaultNewsService(
        client, RelayClient(connector, Dispatchers.Default), store, time, testLog(), Dispatchers.Default,
        appScope, "http://127.0.0.1:${server.port}/feed.xml",
    )

    private fun TestScope.relayService() = DefaultNewsService(
        client, RelayClient(relays.connector, UnconfinedTestDispatcher(testScheduler)), store, time, testLog(),
        UnconfinedTestDispatcher(testScheduler), backgroundScope, "http://127.0.0.1:1/unused",
    )

    private fun item(n: Int, date: String) =
        "<item><title>A$n</title><guid>30023:pk:d$n</guid><pubDate>$date</pubDate><description>s$n</description></item>"

    private fun feed(vararg items: String) = "<rss><channel>${items.joinToString("")}</channel></rss>"

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private val d1 = "Fri, 16 Oct 2026 12:16:17 GMT" // 1792152977
    private val d2 = "Sat, 17 Oct 2026 08:39:33 GMT" // 1792226373

    @Test
    fun `feed request sends the fixed headers and truncates to limit`() = runBlocking {
        server.enqueue(ok(feed(item(1, d2), item(2, d1), item(3, d1))))
        val articles = feedService().fetchArticles(limit = 2)
        assertEquals(listOf("A1", "A2"), articles.map { it.title })
        val req = server.takeRequest()
        assertEquals("Einundzwanzig-Meetup-App/1.0 (Android)", req.headers["User-Agent"])
        assertEquals("application/rss+xml, application/xml, text/xml", req.headers["Accept"])
        assertEquals("/feed.xml", req.target)
    }

    @Test
    fun `non-200 and network errors give an empty list`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).body(feed(item(1, d1))).build())
        assertTrue(feedService().fetchArticles().isEmpty())
        server.close()
        assertTrue(feedService().fetchArticles().isEmpty())
    }

    @Test
    fun `malformed utf8 is replaced, not fatal`() = runBlocking {
        val bytes = "<item><title>Gr".toByteArray() + byteArrayOf(0xC3.toByte()) + "</title></item>".toByteArray()
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(bytes)).build())
        assertEquals("Gr�", feedService().fetchArticles().single().title)
    }

    @Test
    fun `cache holds non-empty results for ten minutes`() = runBlocking {
        val service = feedService()
        server.enqueue(ok(feed()))
        server.enqueue(ok(feed(item(1, d1))))
        server.enqueue(ok(feed(item(2, d2))))
        assertTrue(service.cachedArticles().isEmpty()) // empty is not cached
        assertEquals("A1", service.cachedArticles().single().title)
        time.millis += 9 * 60_000
        assertEquals("A1", service.cachedArticles().single().title)
        time.millis += 60_000
        assertEquals("A2", service.cachedArticles().single().title)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `first unread count stores the newest timestamp and reports zero`() = runBlocking {
        server.enqueue(ok(feed(item(1, d1), item(2, d2))))
        val service = feedService()
        assertEquals(0, service.unreadCount())
        assertEquals(1_792_226_373L, store.getInt(NewsService.KEY_LAST_READ_TS))
    }

    @Test
    fun `unread counts articles newer than the mark and markRead stores the newest`() = runBlocking {
        store.setInt(NewsService.KEY_LAST_READ_TS, 1_792_152_977L)
        server.enqueue(ok(feed(item(1, d2), item(2, d1), item(3, "Sun, 18 Oct 2026 00:00:00 GMT"))))
        val service = feedService()
        assertEquals(2, service.unreadCount())
        service.markRead()
        assertEquals(1_792_281_600L, store.getInt(NewsService.KEY_LAST_READ_TS))
        assertEquals(0, service.unreadCount())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `markRead still stores the mark when the caller is cancelled`() = runBlocking {
        // Dart's un-awaited Future completes even when the dashboard goes away.
        server.enqueue(ok(feed(item(1, d2), item(2, d1))).newBuilder().headersDelay(300, TimeUnit.MILLISECONDS).build())
        val service = feedService()
        val caller = launch(Dispatchers.Default) { service.markRead() }
        server.takeRequest()
        caller.cancel()
        withTimeout(10_000) {
            while (store.getInt(NewsService.KEY_LAST_READ_TS) == null) delay(10)
        }
        assertTrue(caller.isCancelled)
        assertEquals(1_792_226_373L, store.getInt(NewsService.KEY_LAST_READ_TS))
    }

    @Test
    fun `no articles - zero and markRead is a no-op`() = runBlocking {
        server.enqueue(ok(feed()))
        server.enqueue(ok(feed()))
        val service = feedService()
        assertEquals(0, service.unreadCount())
        service.markRead()
        assertNull(store.getInt(NewsService.KEY_LAST_READ_TS))
    }

    @Test
    fun `a mark of another type yields zero without overwriting`() = runBlocking {
        store.set(NewsService.KEY_LAST_READ_TS, PrefValue.StringValue("x"))
        server.enqueue(ok(feed(item(1, d1))))
        assertEquals(0, feedService().unreadCount())
        assertEquals(PrefValue.StringValue("x"), store.get(NewsService.KEY_LAST_READ_TS))
    }

    // ---- NIP-23 full text ----

    private val pk1 = pubOf(SECRET1)

    private fun article(content: String, createdAt: Long, d: String = "my:d", secret: String = SECRET1) =
        signed(secret, 30023, content, listOf(listOf("d", d)), createdAt).json()

    private val now get() = time.millis / 1000

    @Test
    fun `full text - newest wins across relays and the REQ matches Dart`() = runTest {
        relays.add("wss://nostr.einundzwanzig.space", article("old", now - 100))
        relays.add("wss://nos.lol", article("new", now - 10))
        relays.add("wss://relay.damus.io", article("other d", now, d = "x"))
        assertEquals("new", relayService().fetchArticleContent("30023:$pk1:my:d"))
        val (relay, req) = relays.reqs.first()
        assertTrue(relay in NewsLinks.ARTICLE_RELAYS)
        assertEquals("article", req[1].jsonPrimitive.content)
        assertEquals(
            "{\"kinds\":[30023],\"authors\":[\"$pk1\"],\"#d\":[\"my:d\"],\"limit\":1}",
            DartJson.encode(req[2]),
        )
        assertEquals(4, relays.connector.connects.size)
    }

    @Test
    fun `full text - foreign author, future and empty content are not used`() = runTest {
        relays.add("wss://nos.lol", article("a", now - 50), article("future", now + 3600))
        relays.add("wss://relay.nostr.band", article("forged", now, secret = SECRET2))
        assertEquals("a", relayService().fetchArticleContent("30023:$pk1:my:d"))

        relays.events.clear()
        relays.add("wss://nos.lol", article("", now))
        assertNull(relayService().fetchArticleContent("30023:$pk1:my:d"))
    }

    @Test
    fun `full text - bad guid makes no request`() = runTest {
        assertNull(relayService().fetchArticleContent("https://media.example/a"))
        assertTrue(relays.connector.connects.isEmpty())
    }

    @Test
    fun `full text - a silent relay ends at the overall budget with the best so far`() = runTest {
        relays.silent += "wss://relay.damus.io"
        relays.add("wss://nos.lol", article("body", now - 1))
        val start = currentTime
        assertEquals("body", relayService().fetchArticleContent("30023:$pk1:my:d"))
        assertEquals(NewsService.ARTICLE_TIMEOUT.inWholeMilliseconds, currentTime - start)
    }
}
