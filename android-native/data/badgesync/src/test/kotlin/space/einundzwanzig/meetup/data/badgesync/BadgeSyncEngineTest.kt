package space.einundzwanzig.meetup.data.badgesync

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.crypto.DartBase64
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip44
import space.einundzwanzig.meetup.core.nostr.toJsonObject
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.identity.SigningException

/**
 * Native tests of the sync engine (spec 08 §5, §6, §8.12) against in-memory relays with a real
 * local key (NIP-44 v2 to self, BIP-340): wire layout, pull/merge/push, the "never publish an
 * empty list" and dirty rules, NIP-44 refusal, relay failures, hold, generation and verification.
 */
class BadgeSyncEngineTest {

    private val key = KeyPair.generate()

    private fun badge(id: String, day: Int = 11) = MeetupBadge(
        id = id,
        meetupName = "Aschaffenburg",
        meetupEventId = id,
        date = DartDateTime.utc(2026, 10, day, 19),
        iconPath = "assets/badge_icon.png",
        lat = 49.97,
        lng = 9.15,
    )

    private fun test(body: suspend TestScope.(SyncHarness) -> Unit) = runTest {
        val h = SyncHarness(this)
        h.identity.useKey(key)
        body(h)
    }

    private fun decryptChunk(event: JsonObject): Envelope {
        val ck = Nip44.conversationKey(key.privateKeyHex, key.publicKeyHex)
        val plain = Nip44.decrypt(event.getValue("content").jsonPrimitive.content, ck)
        return BadgeSyncFormat.parseEnvelope(BadgeSyncCodec.inflate(DartBase64.decode(plain)))
    }

    @Test
    fun `push writes one kind-30078 chunk per index, encrypted to self, to the first accepting relay`() = test { h ->
        h.repo.saveBadges(listOf(badge("a"), badge("b", 12)))
        assertTrue(h.sync.syncNow())

        assertEquals(1, h.relays.published.size)
        val (relay, ev) = h.relays.published.single()
        assertEquals("wss://nos.lol", relay)
        assertEquals(30078L, ev.getValue("kind").jsonPrimitive.long)
        assertEquals(key.publicKeyHex, ev.getValue("pubkey").jsonPrimitive.content)
        assertEquals(
            listOf(listOf("d", "badges-0"), listOf("client", "einundzwanzig-meetup-app")),
            ev.getValue("tags").jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } },
        )
        val env = decryptChunk(ev)
        assertEquals(listOf("a", "b"), env.badges.map { it.id })
        assertEquals(h.time.nowSeconds(), env.generation)
        assertEquals(false, h.prefs.getBool(BadgeSyncKeys.DIRTY))
        assertEquals(1, h.prefs.getStringList(BadgeSyncKeys.CHUNK_HASHES)!!.size)
        assertEquals(h.time.nowSeconds(), h.prefs.getInt(BadgeSyncKeys.LAST_GEN))
    }

    @Test
    fun `unchanged content is not published again, even with a new generation`() = test { h ->
        h.repo.saveBadges(listOf(badge("a")))
        assertTrue(h.sync.syncNow())
        h.time.millis += 3_600_000
        assertTrue(h.sync.syncNow())
        assertEquals(1, h.relays.published.size)
    }

    @Test
    fun `a second device with the same key pulls the collection and unites it`() = test { h ->
        h.repo.saveBadges(listOf(badge("a"), badge("b", 12)))
        assertTrue(h.sync.syncNow())

        val other = SyncHarness(this, relays = h.relays, time = h.time)
        other.identity.useKey(key)
        other.repo.saveBadges(listOf(badge("c", 13)))
        assertTrue(other.sync.syncNow())
        assertEquals(listOf("a", "b", "c"), other.repo.loadBadges().map { it.id })
        // The united list went back to the relays, so the first device gets "c" too.
        assertTrue(h.sync.syncNow())
        assertEquals(listOf("a", "b", "c"), h.repo.loadBadges().map { it.id })
    }

    @Test
    fun `chunk written by another client (fixture envelope, 0x tags ignored) is read`() = test { h ->
        val plain = SyncFixtures.sync.getValue("envelope_plaintext").jsonPrimitive.content
        val ck = Nip44.conversationKey(key.privateKeyHex, key.publicKeyHex)
        val content = Nip44.encrypt(DartBase64.encode(BadgeSyncCodec.deflate(plain)), ck)
        val ev = EventIds.sign(key, h.time.nowSeconds() - 60, 30078, listOf(listOf("d", "badges-3"), listOf("client", "x")), content)
        h.relays.add("wss://nostr.mom", ev.toJsonObject())

        assertTrue(h.sync.syncNow())
        assertEquals(listOf("aschaffenburg"), h.repo.loadBadges().map { it.id })
        assertEquals(1760961600L.coerceAtLeast(h.time.nowSeconds()), h.prefs.getInt(BadgeSyncKeys.LAST_GEN))
    }

    @Test
    fun `newest event per index wins and future-dated or foreign events are ignored`() = test { h ->
        val ck = Nip44.conversationKey(key.privateKeyHex, key.publicKeyHex)
        fun chunk(ids: List<String>, createdAt: Long, signer: KeyPair = key): JsonObject {
            val plain = BadgeSyncFormat.envelopeJson(ids.map { badge(it) }, 0, 1, 1)
            val content = Nip44.encrypt(DartBase64.encode(BadgeSyncCodec.deflate(plain)), ck)
            return EventIds.sign(signer, createdAt, 30078, listOf(listOf("d", "badges-0")), content).toJsonObject()
        }
        val now = h.time.nowSeconds()
        h.relays.stored["wss://nos.lol"] = mutableListOf(chunk(listOf("old"), now - 100))
        h.relays.stored["wss://relay.primal.net"] = mutableListOf(chunk(listOf("new"), now - 10))
        h.relays.stored["wss://nostr.mom"] = mutableListOf(chunk(listOf("future"), now + 601))
        h.relays.stored["wss://relay.snort.social"] = mutableListOf(chunk(listOf("foreign"), now, KeyPair.generate()))

        assertTrue(h.sync.syncNow())
        assertEquals(listOf("new"), h.repo.loadBadges().map { it.id })
    }

    @Test
    fun `an empty wallet is never published`() = test { h ->
        assertTrue(h.sync.syncNow())
        assertTrue(h.relays.published.isEmpty())
        assertEquals(false, h.prefs.getBool(BadgeSyncKeys.DIRTY))
    }

    @Test
    fun `no relay reachable fails the run and the trigger marks dirty`() = test { h ->
        h.relays.unreachable += BadgeSync.SYNC_RELAYS
        h.repo.saveBadges(listOf(badge("a")))
        assertFalse(h.sync.syncNow())
        h.sync.trigger()
        runCurrent() // background work (trigger) included
        assertEquals(true, h.prefs.getBool(BadgeSyncKeys.DIRTY))

        // retryIfDirty runs once the relays are back.
        h.relays.unreachable.clear()
        h.sync.retryIfDirty()
        assertEquals(false, h.prefs.getBool(BadgeSyncKeys.DIRTY))
        assertEquals(1, h.relays.published.size)
    }

    @Test
    fun `one answering relay is enough to read, the publish falls through to the next relay`() = test { h ->
        h.relays.unreachable += "wss://nos.lol"
        h.relays.reject += "wss://relay.primal.net"
        h.repo.saveBadges(listOf(badge("a")))
        assertTrue(h.sync.syncNow())
        assertEquals(listOf("wss://nostr.mom"), h.relays.published.map { it.first })
    }

    @Test
    fun `a chunk no relay accepted stays dirty and is published again on retry`() = test { h ->
        h.relays.reject += BadgeSync.SYNC_RELAYS
        h.repo.saveBadges(listOf(badge("a")))
        assertFalse(h.sync.syncNow())
        assertEquals(true, h.prefs.getBool(BadgeSyncKeys.DIRTY))
        assertEquals(listOf(""), h.prefs.getStringList(BadgeSyncKeys.CHUNK_HASHES))

        h.relays.reject.clear()
        h.sync.retryIfDirty()
        assertEquals(1, h.relays.published.size)
        assertEquals(false, h.prefs.getBool(BadgeSyncKeys.DIRTY))
    }

    @Test
    fun `refused NIP-44 keeps the sync off and clears dirty`() = test { h ->
        h.prefs.setBool(BadgeSyncKeys.DIRTY, true)
        h.identity.nip44Allowed = false
        h.repo.saveBadges(listOf(badge("a")))
        assertTrue(h.sync.syncNow())
        assertEquals(false, h.prefs.getBool(BadgeSyncKeys.DIRTY))
        assertEquals(0, h.relays.connects)

        h.identity.nip44Allowed = true
        h.identity.nip44Throws = SigningException("abgelehnt")
        assertTrue(h.sync.syncNow())
        h.identity.nip44Throws = UnsupportedOperationException("web only")
        assertTrue(h.sync.syncNow())
        assertEquals(0, h.relays.connects)
    }

    @Test
    fun `no signer is not a failure`() = test { h ->
        h.identity.keyPair = null
        h.repo.saveBadges(listOf(badge("a")))
        assertTrue(h.sync.syncNow())
        assertEquals(0, h.relays.connects)
        assertEquals(null, h.prefs.getBool(BadgeSyncKeys.DIRTY))
    }

    @Test
    fun `while a hold is persisted nothing is published`() = test { h ->
        h.repo.saveBadges(listOf(badge("a")))
        h.sync.beginRestoreHold(fromNpub = key.npub)
        h.repo.stageIncoming(listOf(badge("b")))
        // Another process (the hold is still in the prefs, from = active npub, no switch yet
        // readable because the keystore is unreadable).
        h.identity.npub = null
        val other = h.newProcess()
        assertTrue(other.syncNow())
        assertEquals(0, h.relays.connects)
    }

    @Test
    fun `generation never goes below the last one seen`() = test { h ->
        h.prefs.setInt(BadgeSyncKeys.LAST_GEN, h.time.nowSeconds() + 5000)
        h.repo.saveBadges(listOf(badge("a")))
        assertTrue(h.sync.syncNow())
        assertEquals(h.time.nowSeconds() + 5000, decryptChunk(h.relays.published.single().second).generation)
    }

    @Test
    fun `init triggers once and badge saves trigger again`() = test { h ->
        h.sync.init()
        h.sync.init() // idempotent hooks
        runCurrent() // background work (trigger) included
        val afterInit = h.sync.runs.get()
        assertTrue(afterInit >= 1) // the second trigger may fold into the running pass (rerun)
        h.repo.saveBadges(listOf(badge("a")))
        runCurrent() // background work (trigger) included
        assertTrue(h.sync.runs.get() > afterInit)
        assertEquals(1, h.relays.published.size)
        h.identity.ready.emit(Unit)
        runCurrent() // background work (trigger) included
        assertEquals(1, h.relays.published.size)
    }

    @Test
    fun `too many chunks keeps the sync off and dirty`() = test { h ->
        val big = List(17) { i ->
            MeetupBadge(id = "b$i", meetupName = "M", date = DartDateTime.utc(2026, 1, i + 1), iconPath = "", sig = "y".repeat(29_000))
        }
        h.repo.saveBadges(big)
        assertFalse(h.sync.syncNow())
        assertTrue(h.relays.published.isEmpty())
    }
}
