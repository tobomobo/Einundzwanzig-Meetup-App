package space.einundzwanzig.meetup.data.admin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.prefs.asString
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.admin.model.AdminCheckResult
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.admin.registry.AdminRelayFetch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native concurrency guarantees (no Dart counterpart): relay saves and local cache edits are
 * serialized (no lost update), and concurrent blocking lookups in `checkAdmin` share one crawl.
 */
class AdminRegistryConcurrencyTest {

    private val clock = FakeClock()

    /** Store whose next read of the admin cache suspends until [release] (one-shot). */
    private class GatedStore(private val inner: InMemoryKeyValueStore) : KeyValueStore by inner {
        private val armed = AtomicBoolean(false)
        private val gate = CompletableDeferred<Unit>()

        fun arm() = armed.set(true)
        fun release() = gate.complete(Unit)

        override suspend fun get(key: String): PrefValue? {
            if (key == AdminConstants.KEY_CACHE && armed.compareAndSet(true, false)) gate.await()
            return inner.get(key)
        }

        override suspend fun getString(key: String): String? = get(key).asString
    }

    private val own = freshNpub()
    private val other = freshNpub()
    private val newcomer = freshNpub()

    private fun cachedStore() = prefs(
        AdminConstants.KEY_CACHE to AdminEntry.listToJson(listOf(AdminEntry(own, "M", "Own", 1), AdminEntry(other, "M", "Other", 1))),
        AdminConstants.KEY_CACHE_TIMESTAMP to clock.nowMillis() - 2 * 3_600_000L,
    )

    private val freshRelayList = AdminRelayFetch { _, _ ->
        mapOf(superHex to listOf(AdminEntry(own, "M", "Own", 1), AdminEntry(other, "M", "Other", 1), AdminEntry(newcomer, "M", "New", 2)))
    }

    @Test
    fun `removeFromCache mit veralteter Liste ueberschreibt einen neueren Relay-Save nicht`() = runTest {
        val store = GatedStore(cachedStore())
        val registry = registry(store, freshRelayList, clock)

        store.arm()
        val remove = launch { registry.removeFromCache(own) } // holds the lock, blocked after its read
        runCurrent()
        val refresh = launch { registry.forceRefresh() } // relay save must wait for the lock
        runCurrent()
        store.release()
        advanceUntilIdle()
        assertTrue(remove.isCompleted && refresh.isCompleted)

        // The relay save ran last: the fresh list (incl. the newcomer) survives; before the fix the
        // stale "old list minus own npub" overwrote it and the newcomer was lost.
        assertEquals(listOf(own, other, newcomer), registry.getAdminList().map { it.npub })
    }

    @Test
    fun `Relay-Save, danach removeFromCache - Relay-Liste ohne die eine npub`() = runTest {
        val registry = registry(cachedStore(), freshRelayList, clock)
        assertEquals(3, registry.forceRefresh())
        registry.removeFromCache(own)
        assertEquals(listOf(other, newcomer), registry.getAdminList().map { it.npub })
    }

    @Test
    fun `gleichzeitige Cache-Misses teilen einen Relay-Crawl`() = runTest {
        val calls = AtomicInteger()
        val a = freshNpub()
        val b = freshNpub()
        val fetch = AdminRelayFetch { _, _ ->
            calls.incrementAndGet()
            delay(1_000)
            mapOf(superHex to listOf(AdminEntry(a, "MA", "A", 1), AdminEntry(b, "MB", "B", 1)))
        }
        val registry = registry(prefs(), fetch, clock)

        val first = async { registry.checkAdmin(a) }
        val second = async { registry.checkAdmin(b) }
        val third = async { registry.checkAdmin(freshNpub()) }

        assertEquals(AdminCheckResult(true, "MA", "A", AdminCheckResult.SOURCE_NOSTR_RELAY), first.await())
        assertEquals(AdminCheckResult(true, "MB", "B", AdminCheckResult.SOURCE_NOSTR_RELAY), second.await())
        assertEquals(AdminCheckResult.SOURCE_NOT_FOUND, third.await().source)
        assertEquals(1, calls.get())

        // After it finished, a new miss crawls again.
        registry.clearCache()
        registry.checkAdmin(freshNpub())
        assertEquals(2, calls.get())
    }

    @Test
    fun `abgebrochener erster Aufrufer - der wartende startet einen eigenen Crawl`() = runTest {
        val calls = AtomicInteger()
        val a = freshNpub()
        val fetch = AdminRelayFetch { _, _ ->
            calls.incrementAndGet()
            delay(1_000)
            mapOf(superHex to listOf(AdminEntry(a, "MA", "A", 1)))
        }
        val registry = registry(prefs(), fetch, clock)

        val owner = launch { registry.checkAdmin(a) }
        runCurrent()
        val waiter = async { registry.checkAdmin(a) }
        runCurrent()
        owner.cancel()

        assertEquals(AdminCheckResult.SOURCE_NOSTR_RELAY, waiter.await().source)
        assertEquals(2, calls.get())
    }
}
