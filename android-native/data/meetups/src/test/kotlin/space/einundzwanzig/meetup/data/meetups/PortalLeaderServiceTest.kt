package space.einundzwanzig.meetup.data.meetups

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.meetups.internal.PortalLeaderServiceImpl
import space.einundzwanzig.meetup.data.meetups.portal.PortalLeaderService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PortalLeaderServiceTest {

    private val server = FakeServer()
    private val prefs = InMemoryKeyValueStore()
    private val time = FakeTime()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun service(store: InMemoryKeyValueStore = prefs) = PortalLeaderServiceImpl(server.client, store, time, testLog(), scope)

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun cached(json: String, ts: Long) = InMemoryKeyValueStore(
        mapOf(
            PortalLeaderService.CACHE_KEY to PrefValue.StringValue(json),
            PortalLeaderService.CACHE_TS_KEY to PrefValue.IntValue(ts),
        ),
    )

    private val spec = """[{"meetup_id":2,"npubs":[" npub1a ",""]},{"meetup_id":"3","npubs":["x"]}]"""

    @Test
    fun `network parse trims, drops empties and non-int ids`() {
        assertEquals(mapOf(2 to setOf("npub1a")), PortalLeaderServiceImpl.parse(spec, normalize = true))
        assertEquals(mapOf(2 to setOf(" npub1a ", "")), PortalLeaderServiceImpl.parse(spec, normalize = false))
    }

    @Test
    fun `isLeaderOf true false and unknown`() = runTest {
        server.respond = { _, _ -> json(spec) }
        val s = service()
        assertEquals(true, s.isLeaderOf(2, " npub1a"))
        assertEquals(false, s.isLeaderOf(9, "npub1a"))
        assertEquals(false, s.isLeaderOf(2, "npub1b"))
        assertNull(s.isLeaderOf(2, ""))
        assertEquals(1, s.meetupCountFor("npub1a "))
        assertEquals(0, s.meetupCountFor(""))
        assertEquals(spec, prefs.getString(PortalLeaderService.CACHE_KEY))
        assertEquals(time.now, prefs.getInt(PortalLeaderService.CACHE_TS_KEY))
        assertEquals("portal.einundzwanzig.space", server.seen[0].host)
        assertEquals("/api/meetup-leaders", server.seen[0].path)
        assertEquals(1, server.seen.size) // fresh for 24 h
    }

    @Test
    fun `no data means unknown`() = runTest {
        server.respond = { _, _ -> status(500, "") }
        assertNull(service().isLeaderOf(2, "npub1a"))
    }

    @Test
    fun `concurrent callers queued behind a failing fetch share it`() = runTest {
        server.respond = { _, _ ->
            Thread.sleep(300)
            status(500, "")
        }
        val s = service()
        val results = coroutineScope {
            List(5) { async(Dispatchers.Default) { s.isLeaderOf(2, "npub1a") } }.awaitAll()
        }
        assertEquals(List(5) { null }, results)
        assertEquals(1, server.seen.size)
        // A later call (nobody fetching) tries again.
        assertNull(s.isLeaderOf(2, "npub1a"))
        assertEquals(2, server.seen.size)
    }

    @Test
    fun `empty answer keeps the previous state`() = runTest {
        server.respond = { _, _ -> json(spec) }
        val s = service()
        s.ensureLoaded()
        server.respond = { _, _ -> json("[]") }
        s.ensureLoaded(force = true)
        assertEquals(true, s.isLeaderOf(2, "npub1a"))
        assertEquals(spec, prefs.getString(PortalLeaderService.CACHE_KEY))
    }

    @Test
    fun `cache is used offline and the network is still tried`() = runTest {
        val store = InMemoryKeyValueStore(
            mapOf(
                PortalLeaderService.CACHE_KEY to PrefValue.StringValue("""[{"meetup_id":4,"npubs":["npub1c"]}]"""),
                PortalLeaderService.CACHE_TS_KEY to PrefValue.IntValue(time.now - 1000),
            ),
        )
        server.respond = { _, _ -> status(503, "") }
        val s = service(store)
        assertEquals(true, s.isLeaderOf(4, "npub1c"))
        s.awaitRefresh()
        assertEquals(1, server.seen.size) // first call always hits the network (in the background)
        assertFalse(s.isLeaderOf(5, "npub1c")!!)
        assertEquals(1, server.seen.size) // then the fresh cache short-circuits
    }

    @Test
    fun `stale data refreshes after 24 h`() = runTest {
        server.respond = { _, _ -> json(spec) }
        val s = service()
        s.ensureLoaded()
        time.now += PortalLeaderService.TTL_MS
        s.ensureLoaded()
        assertEquals(2, server.seen.size)
        assertTrue(s.isLeaderOf(2, "npub1a")!!)
    }

    @Test
    fun `cached data answers immediately while the refresh runs in the background`() = runTest {
        val gate = CountDownLatch(1)
        server.respond = { _, _ ->
            gate.await(10, TimeUnit.SECONDS)
            json("""[{"meetup_id":4,"npubs":["npub1d"]}]""")
        }
        // Stale cache (older than 24 h): Dart waited for the network before answering.
        val s = service(cached("""[{"meetup_id":4,"npubs":["npub1c"]}]""", time.now - PortalLeaderService.TTL_MS - 1))
        assertEquals(true, s.isLeaderOf(4, "npub1c")) // answered while the request is still blocked
        assertEquals(true, s.isLeaderOf(4, "npub1c")) // no second refresh while one runs
        gate.countDown()
        s.awaitRefresh()
        assertEquals(1, server.seen.size)
        assertEquals(false, s.isLeaderOf(4, "npub1c"))
        assertEquals(true, s.isLeaderOf(4, "npub1d"))
        assertEquals(1, server.seen.size) // fresh now
    }

    @Test
    fun `background refresh never adopts an empty answer`() = runTest {
        server.respond = { _, _ -> json("[]") }
        val s = service(cached("""[{"meetup_id":4,"npubs":["npub1c"]}]""", time.now - PortalLeaderService.TTL_MS - 1))
        assertEquals(true, s.isLeaderOf(4, "npub1c"))
        s.awaitRefresh()
        assertEquals(true, s.isLeaderOf(4, "npub1c"))
        assertEquals(false, s.isLeaderOf(5, "npub1c"))
    }

    @Test
    fun `direct ensureLoaded after a cache-only answer still asks the network`() = runTest {
        server.respond = { _, _ -> status(503, "") }
        val s = service(cached("""[{"meetup_id":4,"npubs":["npub1c"]}]""", time.now - 1000))
        s.ensureLoaded()
        assertEquals(1, server.seen.size)
        assertEquals(true, s.isLeaderOf(4, "npub1c"))
        s.awaitRefresh()
        assertEquals(1, server.seen.size)
    }
}
