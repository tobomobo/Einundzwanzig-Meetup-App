package space.einundzwanzig.meetup.data.mempool

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Port of `test/mempool_web_headers_test.dart`, native branch (spec 13 §10.1 tests 5–7).
 * The web branch (no Cache-Control / no User-Agent / cache buster) does not exist on Android.
 */
class MempoolWebHeadersTest {

    private val env = TestEnv().apply { fake.respond = FakeMempool.healthy() }

    @After
    fun tearDown() = env.close()

    @Test
    fun cacheControlStaysSoTheCdnDetourIsPrevented() = runBlocking {
        env.service.getBlockHeight()
        assertEquals("no-cache", env.fake.seen.single().request.headers["Cache-Control"])
    }

    @Test
    fun ownUserAgentInsteadOfTheLibraryDefault() = runBlocking {
        env.service.getPrices()
        val ua = env.fake.seen.single().request.headers["User-Agent"]
        assertEquals("21Meetup/1.3 (Einundzwanzig Meetup App)", ua)
        assertEquals(true, ua!!.contains("21Meetup"))
    }

    @Test
    fun urlStaysUnchanged() = runBlocking {
        env.service.getBlockHeight()
        val url = env.fake.seen.single().request.url
        assertNull(url.query)
        assertEquals("/api/blocks/tip/height", url.encodedPath)
        assertEquals("mempool.space", env.fake.seen.single().origHost)
    }

    @Test
    fun acceptHeaderOfDashboardCalls() = runBlocking {
        env.service.getDashboardData()
        assertEquals(6, env.fake.seen.size)
        for (s in env.fake.seen) {
            assertEquals("application/json, text/plain, */*", s.request.headers["Accept"])
            assertEquals("no-cache", s.request.headers["Cache-Control"])
            assertEquals(MempoolService.USER_AGENT, s.request.headers["User-Agent"])
            assertNull(s.request.url.query)
        }
    }

    @Test
    fun connectionTestSendsNoCacheControl() = runBlocking {
        assertEquals(900_000, env.service.testHost("mempool.space"))
        val r = env.fake.seen.single().request
        assertEquals("text/plain, */*", r.headers["Accept"])
        assertNull(r.headers["Cache-Control"])
        assertEquals(MempoolService.USER_AGENT, r.headers["User-Agent"])
    }
}
