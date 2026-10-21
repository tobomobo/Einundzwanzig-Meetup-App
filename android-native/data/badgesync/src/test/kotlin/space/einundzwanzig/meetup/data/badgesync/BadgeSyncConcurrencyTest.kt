package space.einundzwanzig.meetup.data.badgesync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.crypto.DartBase64
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip44
import space.einundzwanzig.meetup.core.data.prefs.KeyValueEditor
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository

/**
 * Native concurrency paths of the sync engine that Dart's single-threaded event loop never hit:
 * the restore-hold handover, a scan saved during a pass, the empty-replace clear and a trigger
 * arriving while a pass runs.
 */
class BadgeSyncConcurrencyTest {

    private val keyA = KeyPair.generate()
    private val keyB = KeyPair.generate()

    private fun badge(id: String, day: Int = 11) = MeetupBadge(
        id = id,
        meetupName = "Aschaffenburg",
        meetupEventId = id,
        date = DartDateTime.utc(2026, 10, day, 19),
        iconPath = "assets/badge_icon.png",
    )

    private fun test(body: suspend TestScope.(SyncHarness) -> Unit) = runTest {
        val h = SyncHarness(this)
        h.identity.useKey(keyA)
        body(h)
    }

    /** Pauses the first edit that leaves a persisted hold behind, after the write, until [gate]. */
    private class GatedStore(private val inner: KeyValueStore) : KeyValueStore by inner {
        @Volatile var armed = false
        @Volatile var waiting = false
        val gate = CompletableDeferred<Unit>()

        override suspend fun edit(block: KeyValueEditor.() -> Unit): Boolean {
            val ok = inner.edit(block)
            if (armed && inner.getBool(BadgeSyncKeys.RESTORE_HOLD) == true) {
                armed = false
                waiting = true
                gate.await()
            }
            return ok
        }
    }

    /** Saves [scan] right after the sync read the local file (between read and merge write). */
    private class ScanAfterLoad(private val inner: BadgeRepository) : BadgeRepository by inner {
        @Volatile var scan: MeetupBadge? = null

        override suspend fun loadBadges(): List<MeetupBadge> {
            val local = inner.loadBadges()
            scan?.let {
                scan = null
                inner.saveBadges(local + it)
            }
            return local
        }
    }

    @Test
    fun `a settle racing beginRestoreHold never removes the fresh hold`() = test { h ->
        h.repo.saveBadges(listOf(badge("a")))
        val gated = GatedStore(h.prefs)
        val sync = h.newProcess(store = gated)
        gated.armed = true
        val begin = launch { sync.beginRestoreHold(fromNpub = keyA.npub) }
        runCurrent()
        assertTrue("hold written, begin not yet returned", gated.waiting)

        // Nothing switched (from == the active npub): without the flag this settle would remove the hold.
        sync.settleRestore()
        assertTrue(sync.syncNow())
        assertEquals(true, h.prefs.getBool(BadgeSyncKeys.RESTORE_HOLD))
        assertEquals(0, h.relays.connects)

        gated.gate.complete(Unit)
        begin.join()
        assertEquals(true, h.prefs.getBool(BadgeSyncKeys.RESTORE_HOLD))
        assertEquals(keyA.npub, h.prefs.getString(BadgeSyncKeys.RESTORE_FROM))
        h.prefs.setBool(BadgeSyncKeys.DIRTY, true)
        sync.retryIfDirty()
        assertEquals(0, h.relays.connects)
    }

    @Test
    fun `a scan saved between read and merge write survives and is published`() = runTest {
        val prefs = InMemoryKeyValueStore()
        val secure = InMemorySecureStore()
        val time = FakeTime()
        val repo = ScanAfterLoad(BadgeRepository.create(prefs, secure, time, Dispatchers.Unconfined))
        val h = SyncHarness(this, prefs = prefs, secure = secure, time = time, repo = repo)
        h.identity.useKey(keyA)

        // The relays hold "r" from another device with the same key.
        val other = SyncHarness(this, relays = h.relays, time = time)
        other.identity.useKey(keyA)
        other.repo.saveBadges(listOf(badge("r", 12)))
        assertTrue(other.sync.syncNow())
        val before = h.relays.published.size

        repo.saveBadges(listOf(badge("a")))
        repo.scan = badge("scan", 13)
        assertTrue(h.sync.syncNow())

        assertEquals(setOf("a", "r", "scan"), repo.loadBadges().map { it.id }.toSet())
        val pushed = h.relays.published.drop(before).last().second
        val ck = Nip44.conversationKey(keyA.privateKeyHex, keyA.publicKeyHex)
        val plain = Nip44.decrypt(pushed.getValue("content").jsonPrimitive.content, ck)
        val env = BadgeSyncFormat.parseEnvelope(BadgeSyncCodec.inflate(DartBase64.decode(plain)))
        assertEquals(setOf("a", "r", "scan"), env.badges.map { it.id }.toSet())
    }

    @Test
    fun `the empty-replace clear of a leftover hold publishes nothing`() = test { h ->
        h.repo.saveBadges(listOf(badge("alt")))
        assertTrue(h.sync.syncNow())
        val before = h.relays.published.size
        assertEquals(1, before)

        // Import of an empty backup for identity B; the process dies after the key switch.
        h.sync.beginRestoreHold(fromNpub = keyA.npub)
        h.sync.markRestoreClear()
        h.identity.useKey(keyB)

        val restarted = h.newProcess()
        restarted.init() // settles: empty save -> savedEvents -> trigger
        runCurrent()

        assertNull(h.prefs.getBool(BadgeSyncKeys.RESTORE_HOLD))
        assertNull(h.prefs.getBool(BadgeSyncKeys.RESTORE_CLEAR))
        assertTrue(h.repo.loadBadges().isEmpty())
        assertTrue(restarted.runs.get() >= 1)
        // Neither the old collection under the old key nor anything under the new one.
        assertEquals(before, h.relays.published.size)
        assertFalse(
            h.relays.published.drop(before).any { it.second.getValue("pubkey").jsonPrimitive.content == keyA.publicKeyHex },
        )
    }

    @Test
    fun `a trigger during the fetch runs a second pass and releases busy`() = test { h ->
        h.repo.saveBadges(listOf(badge("a")))
        val gate = CompletableDeferred<Unit>()
        h.relays.beforeConnect = { gate.await() }

        val first = async { h.sync.syncNow() }
        runCurrent()
        assertEquals(1, h.sync.runs.get())
        assertEquals(0, h.relays.reqs.get())

        h.sync.trigger() // folds into the running pass (rerun)
        runCurrent()
        assertEquals(1, h.sync.runs.get())

        gate.complete(Unit)
        assertTrue(first.await())
        val relays = BadgeSync.SYNC_RELAYS.size
        assertEquals("two passes read the relays", 2 * relays, h.relays.reqs.get())
        assertEquals(1, h.relays.published.size)

        // busy released: a new call starts a new run.
        assertTrue(h.sync.syncNow())
        assertEquals(2, h.sync.runs.get())
        assertEquals(3 * relays, h.relays.reqs.get())
    }

    @Test
    fun `a run publishing during an identity switch does not write its hashes over forgetLocalState`() = test { h ->
        h.repo.saveBadges(listOf(badge("a")))
        val relays = BadgeSync.SYNC_RELAYS.size
        val gate = CompletableDeferred<Unit>()
        val publishing = CompletableDeferred<Unit>()
        var connects = 0
        h.relays.beforeConnect = {
            // The connects after the fetch belong to the chunk publish.
            if (++connects > relays) {
                publishing.complete(Unit)
                gate.await()
            }
        }

        val run = async { h.sync.syncNow() }
        runCurrent()
        assertTrue("run is inside the chunk publish", publishing.isCompleted)

        // Full restore of identity B while the old run waits for the relay.
        h.sync.beginRestoreHold(fromNpub = keyA.npub)
        h.identity.useKey(keyB)
        h.sync.endRestore() // settles: switched -> forgetLocalState, hold removed
        assertNull(h.prefs.getBool(BadgeSyncKeys.RESTORE_HOLD))
        assertNull(h.prefs.getStringList(BadgeSyncKeys.CHUNK_HASHES))

        gate.complete(Unit)
        assertFalse(run.await())
        assertNull("old identity's hashes must not come back", h.prefs.getStringList(BadgeSyncKeys.CHUNK_HASHES))
        assertNull(h.prefs.getInt(BadgeSyncKeys.LAST_GEN))
        assertNull(h.prefs.getBool(BadgeSyncKeys.DIRTY))

        // The new identity publishes its chunk instead of skipping it as "unchanged".
        h.relays.beforeConnect = null
        assertTrue(h.sync.syncNow())
        assertTrue(
            h.relays.published.any { it.second.getValue("pubkey").jsonPrimitive.content == keyB.publicKeyHex },
        )
    }

    @Test
    fun `retryIfDirty while a run is busy does not force a second pass`() = test { h ->
        h.repo.saveBadges(listOf(badge("a")))
        h.prefs.setBool(BadgeSyncKeys.DIRTY, true)
        val gate = CompletableDeferred<Unit>()
        h.relays.beforeConnect = { gate.await() }

        val first = async { h.sync.syncNow() }
        runCurrent()
        h.sync.retryIfDirty() // start-up retry next to init(): returns at once
        gate.complete(Unit)
        assertTrue(first.await())

        assertEquals(1, h.sync.runs.get())
        assertEquals(BadgeSync.SYNC_RELAYS.size, h.relays.reqs.get())
        assertEquals(false, h.prefs.getBool(BadgeSyncKeys.DIRTY))
    }

    @Test
    fun `endRestore in a cancelled import still settles and replays the queued trigger`() = test { h ->
        h.repo.saveBadges(listOf(badge("a")))
        h.sync.beginRestoreHold(fromNpub = keyA.npub)
        h.sync.trigger() // queued while suspended
        assertEquals(0, h.sync.runs.get())

        val import = launch {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                h.sync.endRestore()
            }
        }
        runCurrent()
        import.cancel()
        import.join()
        runCurrent()

        assertNull(h.prefs.getBool(BadgeSyncKeys.RESTORE_HOLD))
        assertEquals(1, h.sync.runs.get())
    }
}
