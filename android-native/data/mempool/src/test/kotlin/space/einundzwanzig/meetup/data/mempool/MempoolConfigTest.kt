package space.einundzwanzig.meetup.data.mempool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.mempool.internal.MempoolConfigImpl
import java.util.concurrent.atomic.AtomicInteger

/** Spec 13 §10.2 mode derivation, §10.3 test 13 (migrated prefs) and §14 item 7. */
class MempoolConfigTest {

    private var env = TestEnv()

    @After
    fun tearDown() = env.close()

    @Test
    fun modeDerivation() = runBlocking {
        env.config.setHost("mempool.space")
        assertEquals(MempoolMode.CLEARNET, env.config.mode)
        env.config.setHost("mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion")
        assertEquals(MempoolMode.TOR, env.config.mode)
        env.config.setHost("")
        assertEquals(MempoolMode.CLEARNET, env.config.mode)
        env.config.setHost("https://mempool.emzy.de")
        assertEquals(MempoolMode.CUSTOM, env.config.mode)
        assertEquals("https://mempool.emzy.de", env.store.getString(MempoolPrefs.HOST))
        assertEquals("https://mempool.emzy.de/api", env.config.apiBase)
    }

    @Test
    fun migratedPrefsAreRenormalized() = runBlocking {
        env.close()
        env = TestEnv(
            mapOf(
                MempoolPrefs.HOST to PrefValue.StringValue("https://umbrel.local:3006/api/"),
                MempoolPrefs.LAST_HEIGHT to PrefValue.IntValue(912_345L),
            ),
        )
        env.config.ensureLoaded()
        assertEquals("https://umbrel.local:3006", env.config.host)
        assertEquals(MempoolMode.CUSTOM, env.config.mode)
        assertEquals(912_345L, env.heights.load())
    }

    @Test
    fun blankStoredHostIsClearnet() = runBlocking {
        env.close()
        env = TestEnv(mapOf(MempoolPrefs.HOST to PrefValue.StringValue("   ")))
        env.config.ensureLoaded()
        assertEquals(MempoolHosts.CLEARNET, env.config.host)
    }

    @Test
    fun sessionOverrideIsNotPersistedAndClearedBySetHost() = runBlocking {
        env.config.ensureLoaded()
        env.config.setSessionOverride("mempool.ninja/")
        assertEquals("https://mempool.ninja", env.config.host)
        assertEquals(MempoolHosts.CLEARNET, env.config.persistedHost)
        assertEquals(MempoolMode.CLEARNET, env.config.mode)
        assertEquals(null, env.store.getString(MempoolPrefs.HOST))

        env.config.setMode(MempoolMode.TOR)
        assertEquals(MempoolHosts.TOR, env.config.host)

        env.config.setSessionOverride("x.y")
        env.config.setSessionOverride("  ")
        assertEquals(MempoolHosts.TOR, env.config.host)
    }

    @Test
    fun followsStoreChangesAndReset() = runBlocking {
        env.config.setMode(MempoolMode.TOR)
        env.store.clear() // app reset wipes the prefs
        withTimeout(5_000) { env.config.hostFlow.first { it == MempoolHosts.CLEARNET } }
        assertEquals(MempoolMode.CLEARNET, env.config.mode)

        env.config.setMode(MempoolMode.CUSTOM, "node.example.com")
        env.config.resetToDefaults()
        assertEquals(MempoolHosts.CLEARNET, env.config.host)
        assertEquals(null, env.store.getString(MempoolPrefs.HOST))
    }

    /** A cancelled first [MempoolConfig.ensureLoaded] must not leave the default host loaded. */
    @Test
    fun cancelledLoadIsRetried() = runBlocking {
        val inner = InMemoryKeyValueStore(mapOf(MempoolPrefs.HOST to PrefValue.StringValue(MempoolHosts.TOR)))
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val store = object : KeyValueStore by inner {
            // Delegation forwards the default getters to [inner], so both entry points are gated.
            override suspend fun get(key: String): PrefValue? {
                gate(key)
                return inner.get(key)
            }

            override suspend fun getString(key: String): String? {
                gate(key)
                return inner.getString(key)
            }

            private suspend fun gate(key: String) {
                if (key == MempoolPrefs.HOST && calls.getAndIncrement() == 0) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        // A dead scope: the store observer never runs, so only ensureLoaded() reads the store.
        val deadScope = CoroutineScope(Job().apply { cancel() })
        val config = MempoolConfigImpl(store, env.log, deadScope)
        val first = launch(Dispatchers.IO) { config.ensureLoaded() }
        withTimeout(5_000) { entered.await() }
        first.cancelAndJoin()

        config.ensureLoaded()
        assertEquals(MempoolHosts.TOR, config.host)
        assertEquals(MempoolMode.TOR, config.mode)
    }

    @Test
    fun rapidSetHostEndsOnTheLastChoice() = runBlocking {
        env.config.ensureLoaded()
        repeat(20) { i ->
            env.config.setHost(if (i % 2 == 0) MempoolHosts.TOR else "node$i.example.com")
        }
        env.config.setMode(MempoolMode.TOR)
        kotlinx.coroutines.delay(200) // let late store emissions arrive
        assertEquals(MempoolHosts.TOR, env.config.host)
        assertEquals(MempoolHosts.TOR, env.config.hostFlow.value)
    }
}
