package space.einundzwanzig.meetup.data.mempool

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.mempool.FakeMempool.Companion.ok
import space.einundzwanzig.meetup.data.mempool.FakeMempool.Companion.status
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Spec 13 §10.3 recommended native tests (MockWebServer + in-memory prefs). */
class MempoolServiceTest {

    private var env = TestEnv()

    @After
    fun tearDown() = env.close()

    private fun withPrefs(prefs: Map<String, PrefValue>) {
        env.close()
        env = TestEnv(prefs)
    }

    private fun failing(path: String, response: MockResponse): (String, String) -> MockResponse {
        val healthy = FakeMempool.healthy()
        return { host, p -> if (p == path) response else healthy(host, p) }
    }

    // 1
    @Test
    fun allSourcesOk() = runBlocking {
        env.fake.respond = FakeMempool.healthy()
        val d = env.service.getDashboardData()
        assertEquals(6, d.sourcesOk)
        assertTrue(d.isLive)
        assertEquals(900_000, d.blockHeight)
        assertEquals(listOf(3, 8, 12), listOf(d.feeLow, d.feeMedium, d.feeHigh))
        assertEquals(95000.0, d.priceEur, 0.0)
        assertEquals(103000.0, d.priceUsd, 0.0)
        assertEquals(7, d.prices.size)
        assertEquals(19_875_000L, d.supply)
        assertEquals("812 EH/s", space.einundzwanzig.meetup.data.mempool.format.BtcFormat.hashrate(d.hashrateEhs))
        assertEquals(3.71, d.difficultyChangePct, 0.0)
        assertEquals(1234, d.difficultyRemainingBlocks)
        assertEquals(5123.45678901, d.lnCapacityBtc, 1e-9)
        assertEquals(15000, d.lnNodeCount)
        assertEquals(50000, d.lnChannelCount)
        assertEquals("", d.lastError)
        assertFalse(d.blocked)
        assertEquals(env.time.now, d.updatedAtMillis)
        assertSame(d, env.service.lastDashboard.value)
    }

    @Test
    fun feesUseMinimumFeeWithoutEconomyFee() = runBlocking {
        env.fake.respond = failing(MempoolService.PATH_FEES, ok("""{"fastestFee":12.9,"halfHourFee":8,"minimumFee":1}"""))
        val d = env.service.getDashboardData()
        assertEquals(listOf(1, 8, 12), listOf(d.feeLow, d.feeMedium, d.feeHigh))
        assertEquals(6, d.sourcesOk)
    }

    @Test
    fun lightningWithoutLatestUsesTheRoot() = runBlocking {
        env.fake.respond = failing(MempoolService.PATH_LIGHTNING, ok("""{"latest":null,"total_capacity":100000000,"node_count":2}"""))
        val d = env.service.getDashboardData()
        assertEquals(1.0, d.lnCapacityBtc, 0.0)
        assertEquals(2, d.lnNodeCount)
        assertEquals(0, d.lnChannelCount)
        assertEquals(6, d.sourcesOk)
    }

    @Test
    fun lightningLatestOfWrongTypeFails() = runBlocking {
        env.fake.respond = failing(MempoolService.PATH_LIGHTNING, ok("""{"latest":5}"""))
        val d = env.service.getDashboardData()
        assertEquals(5, d.sourcesOk)
        assertEquals("TypeError", d.lastError)
        assertEquals(0.0, d.lnCapacityBtc, 0.0)
    }

    // 2
    @Test
    fun failedFeesKeepPreviousValues() = runBlocking {
        env.fake.respond = FakeMempool.healthy()
        env.service.getDashboardData()
        env.fake.respond = failing(MempoolService.PATH_FEES, status(500, """{"error":"x"}"""))
        val d = env.service.getDashboardData()
        assertEquals(5, d.sourcesOk)
        assertEquals(listOf(3, 8, 12), listOf(d.feeLow, d.feeMedium, d.feeHigh))
        assertEquals("HTTP 500", d.lastError)
        assertFalse(d.blocked)
    }

    @Test
    fun failedFeesWithoutPreviousAreZero() = runBlocking {
        env.fake.respond = failing(MempoolService.PATH_FEES, status(500, "{}"))
        val d = env.service.getDashboardData()
        assertEquals(listOf(0, 0, 0), listOf(d.feeLow, d.feeMedium, d.feeHigh))
        assertEquals(5, d.sourcesOk)
    }

    // 3
    @Test
    fun blockedHeight() = runBlocking {
        env.fake.respond = failing(MempoolService.PATH_TIP_HEIGHT, status(403, "<!DOCTYPE html><html>Cloudflare</html>"))
        val d = env.service.getDashboardData()
        assertTrue(d.blocked)
        assertEquals("HTTP 403", d.lastError)
        assertEquals(5, d.sourcesOk)
        assertEquals(0, d.blockHeight)
    }

    // 4
    @Test
    fun emptyPricesCountAsFailure() = runBlocking {
        env.fake.respond = failing(MempoolService.PATH_PRICES, ok("{}"))
        assertEquals(5, env.service.getDashboardData().sourcesOk)
        env.fake.respond = failing(MempoolService.PATH_PRICES, ok("""{"time":1,"XYZ":5,"EUR":"95000"}"""))
        assertEquals(5, env.service.getDashboardData().sourcesOk)
        assertTrue(env.service.getPrices().isEmpty())
    }

    // 5
    @Test
    fun monotonicHeight() = runBlocking {
        withPrefs(mapOf(MempoolPrefs.LAST_HEIGHT to PrefValue.IntValue(900_010)))
        env.fake.respond = FakeMempool.healthy(900_000)
        assertEquals(900_010, env.service.getDashboardData().blockHeight)
        assertEquals(900_010L, env.store.getInt(MempoolPrefs.LAST_HEIGHT))
        assertTrue(env.logMessages().any { it.startsWith("Veraltete Blockhöhe verworfen: Server meldet 900000") })

        env.fake.respond = FakeMempool.healthy(900_020)
        assertEquals(900_020, env.service.getDashboardData().blockHeight)
        assertEquals(900_020L, env.store.getInt(MempoolPrefs.LAST_HEIGHT))

        env.fake.respond = failing(MempoolService.PATH_TIP_HEIGHT, status(500, ""))
        val d = env.service.getDashboardData()
        assertEquals(900_020, d.blockHeight)
        assertEquals(5, d.sourcesOk)
    }

    @Test
    fun rawBlockHeightIgnoresTheMonotonicGuard() = runBlocking {
        withPrefs(mapOf(MempoolPrefs.LAST_HEIGHT to PrefValue.IntValue(900_010)))
        env.fake.respond = FakeMempool.healthy(800_000)
        assertEquals(800_000, env.service.getBlockHeight())
        assertEquals(900_010L, env.store.getInt(MempoolPrefs.LAST_HEIGHT))
    }

    // 6
    @Test
    fun clearnetFallsBackToMirrorsInOrder() = runBlocking {
        env.fake.respond = { host, path ->
            if (host == "mempool.ninja") FakeMempool.healthy()(host, path) else status(403, "blocked")
        }
        val d = env.service.getDashboardData()
        assertEquals(6, d.sourcesOk)
        val hostOrder = env.fake.seen.map { it.origHost }.distinct()
        assertEquals(listOf("mempool.space", "mempool.emzy.de", "mempool.ninja"), hostOrder)
        assertEquals("https://mempool.ninja", env.config.host)
        assertEquals(MempoolMode.CLEARNET, env.config.mode)

        // The override stays for the session: following calls go to the mirror.
        env.fake.seen.clear()
        env.service.getBlockHeight()
        assertEquals("mempool.ninja", env.fake.seen.single().origHost)
    }

    @Test
    fun allMirrorsFailClearsTheOverride() = runBlocking {
        env.fake.respond = { _, _ -> status(503, "down") }
        val d = env.service.getDashboardData()
        assertTrue(d.isDead)
        assertTrue(d.blocked)
        assertEquals(18, env.fake.seen.size)
        assertEquals(MempoolHosts.CLEARNET, env.config.host)
        assertNull(env.service.lastDashboard.value)
    }

    // 7
    @Test
    fun torAndCustomNeverFallBack() = runBlocking {
        env.fake.respond = { _, _ -> status(503, "down") }
        env.config.setMode(MempoolMode.TOR)
        assertTrue(env.service.getDashboardData().isDead)
        assertEquals(setOf("mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"), env.fake.seen.map { it.origHost }.toSet())

        env.fake.seen.clear()
        env.config.setMode(MempoolMode.CUSTOM, "https://node.example.com/mempool/api")
        assertTrue(env.service.getDashboardData().isDead)
        assertEquals(setOf("node.example.com"), env.fake.seen.map { it.origHost }.toSet())
        assertTrue(env.fake.seen.all { it.request.url.encodedPath.startsWith("/mempool/api/") })
    }

    // 8
    @Test
    fun concurrentCallsShareOneRun() = runBlocking {
        env.fake.respond = { host, path ->
            val r = FakeMempool.healthy()(host, path)
            r.newBuilder().headersDelay(200, TimeUnit.MILLISECONDS).build()
        }
        val results = coroutineScope { List(3) { async { env.service.getDashboardData() } }.awaitAll() }
        assertEquals(6, env.fake.seen.size)
        assertSame(results[0], results[1])
        assertSame(results[1], results[2])
    }

    // 9
    @Test
    fun deadRunDoesNotReplaceLastDashboard() = runBlocking {
        env.config.setMode(MempoolMode.CUSTOM, "node.example.com")
        env.fake.respond = FakeMempool.healthy()
        val good = env.service.getDashboardData()
        env.fake.respond = { _, _ -> status(500, "") }
        val dead = env.service.getDashboardData()
        assertTrue(dead.isDead)
        assertSame(good, env.service.lastDashboard.value)
        // Dead data still carries the previous values.
        assertEquals(good.feeHigh, dead.feeHigh)
        assertEquals(good.prices, dead.prices)
        assertEquals(good.blockHeight, dead.blockHeight)
    }

    // 10
    @Test
    fun blockHeightIsZeroOnAnyFailure() = runBlocking {
        for (response in listOf(ok("abc"), ok("0"), ok("-5"), ok("900000.0"), status(500, "x"), status(404, ""))) {
            env.fake.respond = { _, _ -> response }
            assertEquals(0, env.service.getBlockHeight())
        }
        env.fake.respond = { _, _ -> ok(" +900000\n") }
        assertEquals(900_000, env.service.getBlockHeight())
    }

    @Test
    fun slowAnswerWithinTheCapCounts() = runBlocking {
        // The test cannot wait 20 s; it checks that a slow answer within the cap still counts.
        env.fake.respond = { _, _ -> ok("900001").newBuilder().headersDelay(300, TimeUnit.MILLISECONDS).build() }
        assertEquals(900_001, env.service.getBlockHeight())
    }

    // 11
    @Test
    fun testHostRejectsNonIntegerBody() = runBlocking {
        env.fake.respond = { _, _ -> ok("hello") }
        try {
            env.service.testHost("https://umbrel.local:3006/api/")
            fail("expected MempoolHttpException")
        } catch (e: MempoolHttpException) {
            assertEquals(200, e.statusCode)
            assertEquals("Keine Zahl: hello", e.snippet)
            assertEquals(HostTestFailure.Http(200), HostTestFailure.of(e, "umbrel.local:3006"))
        }
        assertEquals("umbrel.local", env.fake.seen.single().origHost)
        // testHost never changes the configuration.
        assertEquals(MempoolHosts.CLEARNET, env.config.host)
    }

    @Test
    fun testHostBlocked() = runBlocking {
        env.fake.respond = { _, _ -> status(429, "slow down") }
        try {
            env.service.testHost("")
            fail("expected MempoolHttpException")
        } catch (e: MempoolHttpException) {
            assertEquals(HostTestFailure.Blocked(429), HostTestFailure.of(e, ""))
        }
    }

    @Test
    fun clearStateForgetsEverything() = runBlocking {
        env.fake.respond = FakeMempool.healthy()
        env.service.getDashboardData()
        assertNotNull(env.service.lastDashboard.value)
        env.store.clear()
        env.service.clearState()
        assertNull(env.service.lastDashboard.value)
        env.fake.respond = FakeMempool.healthy(800_000)
        assertEquals(800_000, env.service.getDashboardData().blockHeight)
    }

    @Test
    fun failuresAreLoggedWithStatusAndSnippet() = runBlocking {
        env.fake.respond = failing(MempoolService.PATH_HASHRATE, status(502, "Bad\n\n  Gateway"))
        env.service.getDashboardData()
        val logs = env.logMessages()
        assertTrue(logs.any { it == "Hashrate FEHLGESCHLAGEN — HTTP 502 @ https://mempool.space/v1/mining/hashrate/3d · Antwort: Bad Gateway" })
        assertTrue(logs.any { it == "Nur 5 von 6 Quellen geliefert." })
    }

    // Privacy race (spec 13 §9.1): a switch to Tor during the fallback loop ends it.
    @Test
    fun torSwitchDuringMirrorLoopNeverReachesFurtherMirrors() = runBlocking {
        val switched = AtomicBoolean(false)
        env.fake.respond = { host, path ->
            when (host) {
                "mempool.ninja" -> FakeMempool.healthy()(host, path)
                "mempool.emzy.de" -> {
                    if (switched.compareAndSet(false, true)) runBlocking { env.config.setMode(MempoolMode.TOR) }
                    status(503, "down")
                }
                else -> status(503, "down")
            }
        }
        val d = env.service.getDashboardData()
        assertTrue(d.isDead)
        assertTrue(switched.get())
        assertTrue(env.fake.pathsFor("mempool.ninja").isEmpty())
        assertEquals(MempoolHosts.TOR, env.config.host)
        assertEquals(MempoolMode.TOR, env.config.mode)
    }

    @Test
    fun torSwitchDuringFirstRoundNeverReachesMirrors() = runBlocking {
        val switched = AtomicBoolean(false)
        env.fake.respond = { host, path ->
            if (host == "mempool.space" && switched.compareAndSet(false, true)) {
                runBlocking { env.config.setMode(MempoolMode.TOR) }
            }
            if (host == "mempool.space" || host.endsWith(".onion")) status(503, "down") else FakeMempool.healthy()(host, path)
        }
        env.service.getDashboardData()
        assertTrue(env.fake.pathsFor("mempool.emzy.de").isEmpty())
        assertTrue(env.fake.pathsFor("mempool.ninja").isEmpty())
        assertEquals(MempoolHosts.TOR, env.config.host)
    }

    // App reset while a run is in flight: nothing is written back (spec 13 §14 item 8).
    @Test
    fun clearStateFencesARunInFlight() = runBlocking {
        env.fake.respond = { host, path ->
            FakeMempool.healthy()(host, path).newBuilder().headersDelay(500, TimeUnit.MILLISECONDS).build()
        }
        val run = async { env.service.getDashboardData() }
        delay(200)
        env.store.clear()
        env.service.clearState()
        assertEquals(6, run.await().sourcesOk)
        assertNull(env.store.getInt(MempoolPrefs.LAST_HEIGHT))
        assertNull(env.service.lastDashboard.value)
    }

    // 10: the 20 s cap of getBlockHeight also applies to onion hosts; other sources keep 45 s.
    @Test
    fun timeoutsPerHostAndSource() = runBlocking {
        env.fake.respond = FakeMempool.healthy()
        env.service.getDashboardData()
        assertTrue(env.fake.seen.all { it.timeoutMs == 20_000L })

        env.fake.seen.clear()
        env.config.setMode(MempoolMode.TOR)
        assertEquals(900_000, env.service.getBlockHeight())
        assertEquals(7, env.service.getPrices().size)
        val byPath = env.fake.seen.associate { it.path to it.timeoutMs }
        assertEquals(20_000L, byPath[MempoolService.PATH_TIP_HEIGHT])
        assertEquals(45_000L, byPath[MempoolService.PATH_PRICES])

        env.fake.seen.clear()
        env.service.getDashboardData()
        val dash = env.fake.seen.associate { it.path to it.timeoutMs }
        assertEquals(20_000L, dash[MempoolService.PATH_TIP_HEIGHT])
        for (p in listOf(MempoolService.PATH_FEES, MempoolService.PATH_PRICES, MempoolService.PATH_HASHRATE, MempoolService.PATH_DIFFICULTY, MempoolService.PATH_LIGHTNING)) {
            assertEquals(p, 45_000L, dash[p])
        }
    }

    // 11: testHost uses the host's full timeout (45 s onion, 20 s clearnet) and a fresh client.
    @Test
    fun testHostTimeoutsAndFreshConnections() = runBlocking {
        env.fake.respond = FakeMempool.healthy()
        assertEquals(900_000, env.service.testHost(MempoolHosts.TOR))
        assertEquals(45_000L, env.fake.seen.single().timeoutMs)

        env.fake.seen.clear()
        env.service.testHost("mempool.space")
        env.service.testHost("mempool.space")
        assertEquals(listOf(20_000L, 20_000L), env.fake.seen.map { it.timeoutMs })
        val (a, b) = env.fake.seen.map { it.request.connectionIndex }
        assertTrue("testHost must not reuse a connection", a != b)

        // Contrast: the pooled service client reuses its keep-alive connection.
        env.fake.seen.clear()
        env.service.getBlockHeight()
        env.service.getBlockHeight()
        val (c, d) = env.fake.seen.map { it.request.connectionIndex }
        assertEquals(c, d)
    }

    @Test
    fun parallelSourcesResetTheClientOnce() = runBlocking {
        env.fake.respond = FakeMempool.healthy()
        env.service.getDashboardData()
        assertEquals(1, env.logMessages().count { it.startsWith("HTTP-Client neu aufgebaut") })
    }
}
