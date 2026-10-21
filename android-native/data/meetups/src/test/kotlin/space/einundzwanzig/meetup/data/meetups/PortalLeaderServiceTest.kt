package space.einundzwanzig.meetup.data.meetups

import kotlinx.coroutines.Dispatchers
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

class PortalLeaderServiceTest {

    private val server = FakeServer()
    private val prefs = InMemoryKeyValueStore()
    private val time = FakeTime()
    private fun service(store: InMemoryKeyValueStore = prefs) = PortalLeaderServiceImpl(server.client, store, time, testLog())

    @After
    fun tearDown() = server.close()

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
        assertEquals(1, server.seen.size) // first call always hits the network
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
}
