package space.einundzwanzig.meetup.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeStoreKeys
import space.einundzwanzig.meetup.data.badgesync.RestoreHoldException
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.profile.UserProfile

/** Restore executor flows (spec 08 §2.5, §8.6–§8.10). */
class RestoreEngineTest {

    private val plaintext = Fixtures["enc_v2"].jsonObject.getValue("plaintext").jsonPrimitive.content

    private fun badge(id: String, event: String = id, claimSig: String = "") = MeetupBadge(
        id = id,
        meetupName = "Ort",
        meetupEventId = event,
        date = DartDateTime.utc(2026, 10, 11, 19),
        iconPath = "assets/badge_icon.png",
        claimSig = claimSig,
    )

    private fun badgesJson(vararg b: MeetupBadge) = b.joinToString(",", "[", "]") { it.toJsonString() }

    private fun nostrJson(k: KeyPair, mode: String = "local", active: String = "") =
        """{"nsec":"${k.nsec}","npub":"${k.npub}","priv_hex":"${k.privateKeyHex}","has_key":true,
            "signing_mode":"$mode","active_npub":"$active","nip46_bunker_uri":""}"""

    private val yes: suspend (RestoreConfirmation) -> Boolean = { true }

    private suspend fun BackupHarness.withLocalKey(k: KeyPair, vararg badges: MeetupBadge) {
        identity.saveKeys(k.nsec, k.npub, k.privateKeyHex)
        identity.calls.clear()
        profiles.stored = UserProfile(nickname = "Ada", nostrNpub = k.npub, isNostrVerified = true, hasNostrKey = true)
        assertTrue(repo.saveBadges(badges.toList()))
    }

    @Before
    fun setUp() = runTest { AppLogger.clear() }

    // ---- parse

    @Test
    fun `parse distinguishes invalid JSON from a foreign file`() {
        assertEquals(ParseResult.NotValid, BackupDocument.parseBlocking("kein json"))
        assertEquals(ParseResult.NotValid, BackupDocument.parseBlocking("[1,2]"))
        assertEquals(ParseResult.NotEinundzwanzig, BackupDocument.parseBlocking("""{"user":{}}"""))
        assertTrue(BackupDocument.parseBlocking("""{"user":{},"badges":[]}""") is ParseResult.Ok)
        assertEquals(1L, doc("""{"user":{},"badges":[]}""").version)
    }

    @Test
    fun `suspending parse runs on the given dispatcher and matches the blocking one`() = runTest {
        assertEquals(ParseResult.NotValid, BackupDocument.parse("kein json", kotlinx.coroutines.Dispatchers.Default))
        val ok = BackupDocument.parse("""{"user":{},"badges":[],"version":5}""") as ParseResult.Ok
        assertEquals(5L, ok.document.version)
    }

    // ---- fresh

    @Test
    fun `fresh device restores the fixture backup`() = runTest {
        val h = BackupHarness()
        var asked = false
        val out = h.engine.restore(doc(plaintext)) { asked = true; true }

        assertFalse(asked)
        assertEquals(RestoreOutcome.Restored(badges = 1, nostrKey = false, platformProofs = null, humanity = false), out)
        assertEquals("Profil, 1 Badges", (out as RestoreOutcome.Restored).germanItems)
        assertEquals("Ada", h.profiles.stored.nickname)
        assertEquals(listOf("Aschaffenburg"), h.profiles.stored.favoriteMeetupIds)
        assertEquals(listOf("aschaffenburg"), h.repo.loadBadges().map { it.id })
        assertEquals(listOf("begin::[]:false", "end"), h.sync.calls)
    }

    @Test
    fun `fresh device takes a consistent local key and switches to local mode`() = runTest {
        val h = BackupHarness()
        val k = KeyPair.generate()
        val json = """{"version":5,"user":{"nickname":"Bob"},"badges":[],"nostr":${nostrJson(k)}}"""

        val out = h.engine.restore(doc(json), yes) as RestoreOutcome.Restored

        assertTrue(out.nostrKey)
        assertEquals(listOf("saveKeys:${k.npub}", "local"), h.identity.calls)
        assertEquals(k.npub, h.profiles.stored.nostrNpub)
        assertTrue(h.profiles.stored.isNostrVerified)
        assertEquals("Profil, 0 Badges, Nostr-Key", out.germanItems)
        // Native fix: the owner-less proofs are cleared before the restored ones are written, so
        // settling the hold (from = "") keeps the restored proofs.
        assertEquals(listOf("begin::[]:false", "clearProofs", "end"), h.sync.calls)
    }

    @Test
    fun `inconsistent key is rejected and the profile follows the keystore`() = runTest {
        val h = BackupHarness()
        val k = KeyPair.generate()
        val other = KeyPair.generate()
        val nostr = """{"nsec":"${k.nsec}","npub":"${other.npub}","priv_hex":"${k.privateKeyHex}","has_key":true}"""
        val json = """{"version":5,"user":{"nickname":"Bob","nostrNpub":"${other.npub}","isNostrVerified":true},"badges":[],"nostr":$nostr}"""

        h.engine.restore(doc(json), yes)

        assertTrue(h.identity.calls.isEmpty())
        assertNull(h.identity.keyNpub)
        assertEquals("", h.profiles.stored.nostrNpub)
        assertFalse(h.profiles.stored.isNostrVerified)
        assertTrue(AppLogger.entries.value.any { it.message.contains("inkonsistent") })
    }

    @Test
    fun `version 1 backups ignore the nostr block`() = runTest {
        val h = BackupHarness()
        val k = KeyPair.generate()
        h.engine.restore(doc("""{"version":1,"user":{},"badges":[],"nostr":${nostrJson(k)}}"""), yes)
        assertTrue(h.identity.calls.isEmpty())
    }

    @Test
    fun `bunker backup restores identity without a client key and an invalid address only warns`() = runTest {
        val bunker = KeyPair.generate().npub
        val good = BackupHarness()
        val nostr = """{"nsec":"","npub":"$bunker","priv_hex":"","has_key":false,"signing_mode":"nip46",
            "active_npub":"","nip46_bunker_uri":"bunker://pk?relay=wss://r","nip46_client_sk":"ff"}"""
        good.engine.restore(doc("""{"version":5,"user":{},"badges":[],"nostr":$nostr}"""), yes)
        assertEquals(SigningMode.NIP46, good.identity.mode)
        assertEquals(bunker, good.profiles.stored.nostrNpub)
        assertFalse(good.profiles.stored.hasNostrKey)

        val bad = BackupHarness()
        val out = bad.engine.restore(doc("""{"version":5,"user":{},"badges":[],"nostr":${nostr.replace("bunker://pk?relay=wss://r", "")}}"""), yes)
        assertTrue(out is RestoreOutcome.Restored)
        assertEquals(SigningMode.LOCAL, bad.identity.mode)
        assertTrue(AppLogger.entries.value.any { it.message.contains("Externer Signer (nip46)") })
    }

    @Test
    fun `mixed backup restores the local key and the amber identity`() = runTest {
        val h = BackupHarness()
        val k = KeyPair.generate()
        val amber = KeyPair.generate().npub
        h.engine.restore(doc("""{"version":5,"user":{},"badges":[],"nostr":${nostrJson(k, "amber", amber)}}"""), yes)
        assertEquals(listOf("saveKeys:${k.npub}", "amber:$amber"), h.identity.calls)
        assertEquals(amber, h.profiles.stored.nostrNpub)
        assertTrue(h.profiles.stored.hasNostrKey)
    }

    // ---- additive

    @Test
    fun `same identity merges badges and keeps the device profile`() = runTest {
        val h = BackupHarness()
        val k = KeyPair.generate()
        h.withLocalKey(k, badge("x"))
        val same = """{"version":5,"user":{"nickname":"Alt","fullName":"Ada L"},
            "badges":${badgesJson(badge("x-variant", "x"), badge("y"))},
            "nostr":{"npub":"${k.npub.uppercase()}","has_key":false}}"""

        val out = h.engine.restore(doc(same), yes)

        assertEquals(RestoreOutcome.Merged(1), out)
        assertEquals("Ada", h.profiles.stored.nickname)
        assertEquals("Ada L", h.profiles.stored.fullName)
        assertEquals(setOf("x", "y"), h.repo.loadBadges().map { it.id }.toSet())
        assertTrue(h.identity.calls.isEmpty())
        assertEquals(listOf("begin:${k.npub}:[]:false", "end"), h.sync.calls)
    }

    @Test
    fun `backup without identity needs the add confirmation`() = runTest {
        val h = BackupHarness()
        val k = KeyPair.generate()
        h.withLocalKey(k, badge("x"))
        val json = """{"user":{},"badges":${badgesJson(badge("y"))}}"""
        var seen: RestoreConfirmation? = null

        assertEquals(RestoreOutcome.Cancelled, h.engine.restore(doc(json)) { seen = it; false })
        assertEquals(RestoreConfirmation.AddUnknown(k.npub), seen)
        assertTrue(h.sync.calls.isEmpty())
        assertTrue(h.profiles.saves.isEmpty())

        assertEquals(RestoreOutcome.Merged(1), h.engine.restore(doc(json), yes))
        assertEquals(setOf("x", "y"), h.repo.loadBadges().map { it.id }.toSet())
    }

    // ---- replace

    @Test
    fun `other identity replaces after confirmation and archives the old collection`() = runTest {
        val h = BackupHarness()
        val a = KeyPair.generate()
        val b = KeyPair.generate()
        h.withLocalKey(a, badge("alt"))
        val json = """{"version":5,"user":{"nickname":"Bea"},"badges":${badgesJson(badge("neu"))},"nostr":${nostrJson(b)}}"""
        var seen: RestoreConfirmation? = null

        val out = h.engine.restore(doc(json)) { seen = it; true }

        assertEquals(RestoreConfirmation.Replace(a.npub, b.npub), seen)
        assertEquals(RestoreOutcome.Restored(1, true, null, false), out)
        assertEquals(b.npub, h.identity.keyNpub)
        assertEquals(listOf("neu"), h.repo.loadBadges().map { it.id })
        val former = h.repo.formerCollections()
        assertEquals(1, former.size)
        assertEquals(listOf("alt"), former[0].badges?.map { it.id })
        assertEquals("Bea", h.profiles.stored.nickname)
        assertEquals(b.npub, h.profiles.stored.nostrNpub)
        assertEquals(listOf("begin:${a.npub}:[${b.npub.lowercase()}]:true", "clearProofs", "end"), h.sync.calls)
    }

    @Test
    fun `declined replace writes nothing`() = runTest {
        val h = BackupHarness()
        val a = KeyPair.generate()
        h.withLocalKey(a, badge("alt"))
        val json = """{"version":5,"user":{},"badges":${badgesJson(badge("neu"))},"nostr":${nostrJson(KeyPair.generate())}}"""

        assertEquals(RestoreOutcome.Cancelled, h.engine.restore(doc(json)) { false })
        assertEquals(a.npub, h.identity.keyNpub)
        assertEquals(listOf("alt"), h.repo.loadBadges().map { it.id })
        assertTrue(h.sync.calls.isEmpty())
    }

    @Test
    fun `replace whose key is not taken keeps the collection and drops the staged buffer`() = runTest {
        val h = BackupHarness()
        val a = KeyPair.generate()
        val b = KeyPair.generate()
        h.withLocalKey(a, badge("alt"))
        val savesBefore = h.profiles.saves.size
        val broken = """{"nsec":"${b.nsec}","npub":"${KeyPair.generate().npub}","priv_hex":"${b.privateKeyHex}","has_key":true}"""
        val json = """{"version":5,"user":{"nickname":"Fremd"},"badges":${badgesJson(badge("neu"))},"nostr":$broken}"""

        val out = h.engine.restore(doc(json), yes)

        assertEquals(RestoreOutcome.Failed(RestoreError.KeyNotTaken), out)
        assertEquals(listOf("alt"), h.repo.loadBadges().map { it.id })
        assertNull(h.prefs.getString(BadgeStoreKeys.INCOMING))
        assertEquals(savesBefore, h.profiles.saves.size)
        assertEquals("end", h.sync.calls.last())
    }

    @Test
    fun `replace into an unreadable npub reports KeyUnreadable`() = runTest {
        val h = BackupHarness()
        h.sync.pending = "npub1pending"
        val b = KeyPair.generate()
        // No key in the keystore and the backup has no key: nothing readable afterwards.
        val json = """{"version":5,"user":{},"badges":${badgesJson(badge("neu"))},"nostr":{"npub":"${b.npub}","has_key":false}}"""
        val out = h.engine.restore(doc(json), yes)
        assertEquals(RestoreOutcome.Failed(RestoreError.KeyUnreadable), out)
        assertTrue(h.sync.calls.first().startsWith("begin:npub1pending:"))
    }

    @Test
    fun `replace by an amber backup switches through the external signer`() = runTest {
        val h = BackupHarness()
        val a = KeyPair.generate()
        val c = KeyPair.generate().npub
        h.withLocalKey(a, badge("alt"))
        val nostr = """{"nsec":"","npub":"$c","priv_hex":"","has_key":false,"signing_mode":"amber","active_npub":""}"""
        val out = h.engine.restore(doc("""{"version":5,"user":{},"badges":${badgesJson(badge("neu"))},"nostr":$nostr}"""), yes)
        assertEquals(RestoreOutcome.Restored(1, false, null, false), out)
        assertEquals(listOf("amber:$c"), h.identity.calls)
        assertEquals(listOf("neu"), h.repo.loadBadges().map { it.id })
    }

    @Test
    fun `empty replace marks the clear and empties the wallet once switched`() = runTest {
        val h = BackupHarness()
        val a = KeyPair.generate()
        val b = KeyPair.generate()
        h.withLocalKey(a, badge("alt"))
        val out = h.engine.restore(doc("""{"version":5,"user":{},"badges":[],"nostr":${nostrJson(b)}}"""), yes)
        assertEquals(RestoreOutcome.Restored(0, true, null, false), out)
        assertTrue(h.repo.loadBadges().isEmpty())
        assertEquals(listOf("alt"), h.repo.formerCollections().single().badges?.map { it.id })
        assertTrue(h.sync.calls.containsAll(listOf("markClear", "clearProofs", "clearMark")))
    }

    @Test
    fun `a pending switch blocks a non-replace import`() = runTest {
        val h = BackupHarness()
        h.sync.holdThrows = RestoreHoldException(RestoreError.SwitchPending.german)
        val out = h.engine.restore(doc("""{"user":{},"badges":[]}"""), yes)
        assertEquals(RestoreOutcome.Failed(RestoreError.SwitchPending), out)
        assertTrue(h.profiles.saves.isEmpty())
        assertFalse("end" in h.sync.calls)
    }

    // ---- validation before writing

    @Test
    fun `a malformed badge aborts before anything is written`() = runTest {
        val h = BackupHarness()
        val out = h.engine.restore(doc("""{"user":{"nickname":"X"},"badges":[{"id":"a","meetupName":"M","date":"2026-01-01","blockHeight":1.0}]}"""), yes)
        assertTrue(out is RestoreOutcome.Failed && out.error is RestoreError.InvalidContent)
        assertTrue(h.profiles.saves.isEmpty())
        assertTrue(h.sync.calls.isEmpty())
    }

    @Test
    fun `missing iconPath gets the default asset`() = runTest {
        val h = BackupHarness()
        h.engine.restore(doc("""{"user":{},"badges":[{"id":"a","meetupName":"M","date":"2026-01-01T00:00:00.000Z","iconPath":""}]}"""), yes)
        assertEquals("assets/badge_icon.png", h.repo.loadBadges().single().iconPath)
    }

    @Test
    fun `a float version is rejected like Dart's int cast`() = runTest {
        val out = BackupHarness().engine.restore(doc("""{"version":5.0,"user":{},"badges":[]}"""), yes)
        assertTrue(out is RestoreOutcome.Failed && out.error is RestoreError.InvalidContent)
    }

    @Test
    fun `malformed optional sections fail before anything is written`() = runTest {
        val k = KeyPair.generate()
        val bad = listOf(
            """"nostr":"x"""",
            """"nostr":{"has_key":"true"}""",
            """"nostr":{"has_key":true,"nsec":1,"npub":"${k.npub}","priv_hex":"${k.privateKeyHex}"}""",
            """"nostr":{"has_key":true,"nsec":"${k.nsec}","npub":["x"],"priv_hex":"${k.privateKeyHex}"}""",
            """"nostr":{"has_key":true,"nsec":"${k.nsec}","npub":"${k.npub}","priv_hex":false}""",
            """"admin_registry":{}""",
            """"platform_proofs":"[]"""",
            """"humanity_proof":[]""",
            """"humanity_proof":{"verified":"true"}""",
            """"humanity_proof":{"verified":true,"receipt_event_id":1}""",
            """"humanity_proof":{"verified":true,"first_zap_at":1.7e9}""",
            """"humanity_proof":{"verified":true,"first_zap_at":1700000000.0}""",
            """"humanity_proof":{"verified":true,"checked_at":"1700000000"}""",
        )
        for (section in bad) {
            val h = BackupHarness()
            val out = h.engine.restore(doc("""{"version":5,"user":{"nickname":"X"},"badges":[],$section}"""), yes)
            assertTrue(section, out is RestoreOutcome.Failed && out.error is RestoreError.InvalidContent)
            assertTrue(section, h.profiles.saves.isEmpty())
            assertTrue(section, h.sync.calls.isEmpty())
            assertTrue(section, h.identity.calls.isEmpty())
        }
    }

    @Test
    fun `a non-list platform_proofs in replace mode fails before the confirmation and the switch`() = runTest {
        val h = BackupHarness()
        val a = KeyPair.generate()
        val b = KeyPair.generate()
        h.withLocalKey(a, badge("old"))
        var asked = false
        val json = """{"version":5,"user":{},"badges":[${badge("new").toJsonString()}],"nostr":${nostrJson(b)},"platform_proofs":{}}"""
        val out = h.engine.restore(doc(json)) { asked = true; true }
        assertTrue(out is RestoreOutcome.Failed && out.error is RestoreError.InvalidContent)
        assertFalse(asked)
        assertEquals(a.npub, h.identity.keyNpub)
        assertTrue(h.sync.calls.isEmpty())
        assertEquals(listOf("old"), h.repo.loadBadges().map { it.id })
    }

    @Test
    fun `fields Flutter never reads are not validated`() = runTest {
        // has_key false: nsec/npub/priv_hex are not read; additive mode never reads `nostr`;
        // version 1 never reads `nostr` or `admin_registry`.
        val h = BackupHarness()
        val out = h.engine.restore(doc("""{"version":5,"user":{},"badges":[],"nostr":{"has_key":false,"nsec":1}}"""), yes)
        assertTrue(out is RestoreOutcome.Restored)

        val k = KeyPair.generate()
        val same = BackupHarness().apply { withLocalKey(k) }
        val merged = same.engine.restore(
            doc("""{"version":5,"user":{},"badges":[],"nostr":"x"}"""),
            yes,
        )
        assertTrue(merged.toString(), merged !is RestoreOutcome.Failed)

        val v1 = BackupHarness().engine.restore(doc("""{"version":1,"user":{},"badges":[],"nostr":"x","admin_registry":1}"""), yes)
        assertTrue(v1 is RestoreOutcome.Restored)
    }

    @Test
    fun `humanity fields are only checked where Flutter casts them`() = runTest {
        val loose = """"humanity_proof":{"verified":false,"receipt_event_id":1,"first_zap_at":1.5,"checked_at":"x"}"""
        val fresh = BackupHarness()
        assertTrue(fresh.engine.restore(doc("""{"version":5,"user":{},"badges":[],$loose}"""), yes) is RestoreOutcome.Restored)

        // Additive onto an already verified device: Flutter skips the whole block.
        val bad = """"humanity_proof":{"verified":true,"first_zap_at":1.5,"checked_at":"x","receipt_event_id":[]}"""
        val same = BackupHarness()
        val k = KeyPair.generate()
        same.withLocalKey(k)
        same.proofs.humanityVerified = true
        val out = same.engine.restore(doc("""{"version":5,"user":{},"badges":[],"nostr":{"npub":"${k.npub}"},$bad}"""), yes)
        assertTrue(out.toString(), out is RestoreOutcome.Merged)
        assertTrue(same.proofs.restoredHumanity.isEmpty())

        // Null values are Dart's `?? default`.
        val nulls = BackupHarness()
        val ok = """"humanity_proof":{"verified":true,"first_zap_at":null,"checked_at":null,"receipt_event_id":null}"""
        assertTrue(nulls.engine.restore(doc("""{"version":5,"user":{},"badges":[],$ok}"""), yes) is RestoreOutcome.Restored)
    }

    @Test
    fun `additive import with a non-list favoriteMeetupIds fails before the confirmation and the hold`() = runTest {
        val k = KeyPair.generate()
        // sameIdentity (no confirmation) and addToIdentity (confirmation).
        val backups = listOf(
            """{"version":5,"user":{"favoriteMeetupIds":"x"},"badges":[],"nostr":{"npub":"${k.npub}"}}""",
            """{"version":5,"user":{"favoriteMeetupIds":"x"},"badges":[]}""",
        )
        for (json in backups) {
            val h = BackupHarness()
            h.withLocalKey(k, badge("old"))
            var asked = false
            val out = h.engine.restore(doc(json)) { asked = true; true }
            assertTrue(out.toString(), out is RestoreOutcome.Failed && out.error is RestoreError.InvalidContent)
            assertFalse(asked)
            assertTrue(h.sync.calls.isEmpty())
            assertTrue(h.profiles.saves.isEmpty())
            assertEquals(listOf("old"), h.repo.loadBadges().map { it.id })
        }
    }

    @Test
    fun `a failing endRestore does not replace the outcome`() = runTest {
        val h = BackupHarness()
        h.sync.endThrows = RestoreHoldException(RestoreError.ProofsNotCleared.german)
        val out = h.engine.restore(doc(plaintext), yes)
        assertTrue(out.toString(), out is RestoreOutcome.Restored)
        assertEquals("end", h.sync.calls.last())
        assertTrue(logText().contains("Restore-Riegel nicht aufgelöst"))

        // A failed restore keeps its own error as well.
        val failing = BackupHarness()
        failing.sync.endThrows = IllegalStateException("Speicher voll")
        val k = KeyPair.generate()
        failing.withLocalKey(k)
        val other = KeyPair.generate()
        failing.identity.onSaveKeys = { throw java.io.IOException("Keystore gesperrt") }
        val failed = failing.engine.restore(
            doc("""{"version":5,"user":{},"badges":[],"nostr":${nostrJson(other)}}"""),
            yes,
        )
        assertEquals(RestoreOutcome.Failed(RestoreError.Other("Keystore gesperrt")), failed)
        assertEquals("end", failing.sync.calls.last())
    }

        @Test
    fun `cancelling the caller after the confirmation does not interrupt the write phase`() = runTest {
        val h = BackupHarness()
        val k = KeyPair.generate()
        val sig = "ab".repeat(64)
        val json = """{"version":5,"user":{"nickname":"Bob"},"badges":[],"nostr":${nostrJson(k)},
            "platform_proofs":[{"platform":"telegram","username":"a","proof_sig":"$sig","created_at":1}]}"""
        var outcome: RestoreOutcome? = null
        val job = launch { outcome = h.engine.restore(doc(json), yes) }
        h.identity.onSaveKeys = { job.cancel() }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(outcome is RestoreOutcome.Restored)
        assertEquals(listOf("saveKeys:${k.npub}", "local"), h.identity.calls)
        assertEquals(k.npub, h.profiles.stored.nostrNpub)
        assertEquals(listOf("telegram"), h.proofs.platform.map { it["platform"]!!.jsonPrimitive.content })
        assertEquals("end", h.sync.calls.last())
    }

    @Test
    fun `cancelling during the confirmation writes nothing`() = runTest {
        val h = BackupHarness()
        val a = KeyPair.generate()
        h.withLocalKey(a)
        val gate = CompletableDeferred<Boolean>()
        val job = launch {
            h.engine.restore(doc("""{"version":5,"user":{},"badges":[],"nostr":${nostrJson(KeyPair.generate())}}""")) { gate.await() }
        }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertTrue(h.sync.calls.isEmpty())
        assertTrue(h.identity.calls.isEmpty())
        assertTrue(h.profiles.saves.isEmpty())
    }

    // ---- admins, proofs, humanity

    @Test
    fun `admin registry entries are validated and revalidated`() = runTest {
        val h = BackupHarness()
        h.admins.refreshThrows = true
        val good = KeyPair.generate().npub
        val organic = KeyPair.generate().npub
        val json = """{"version":2,"user":{},"badges":[],"admin_registry":[
            {"npub":"$good","meetup":"Bonn","name":"A","added_at":1},
            {"npub":"npub1kaputt","meetup":"x"},
            {"npub":"$organic","meetup":"y","name":"Organic (12 Badges)"},
            "kein objekt"]}"""
        assertTrue(h.engine.restore(doc(json), yes) is RestoreOutcome.Restored)
        assertEquals(listOf(good), h.admins.list.map { it.npub })
        assertEquals(1, h.admins.refreshes)
        assertTrue(AppLogger.entries.value.any { it.message.contains("Revalidierung fehlgeschlagen") })
    }

    @Test
    fun `platform proofs - replace reports only valid entries whose write succeeded`() = runTest {
        val h = BackupHarness()
        val sig = "ab".repeat(64)
        val json = """{"version":5,"user":{},"badges":[],"platform_proofs":[
            {"platform":"telegram","username":"a","proof_sig":"$sig","created_at":1},
            {"platform":"x","username":"b","proof_sig":"kurz","created_at":2}]}"""
        val out = h.engine.restore(doc(json), yes) as RestoreOutcome.Restored
        assertEquals(1, out.platformProofs)
        assertEquals(listOf("telegram"), h.proofs.platform.map { it["platform"]!!.jsonPrimitive.content })
        assertEquals("Profil, 0 Badges, 1 Plattform-Proofs", out.germanItems)
    }

    @Test
    fun `failed platform write reports incomplete restore and allows a retry without losing badges`() = runTest {
        val h = BackupHarness()
        val json = """{"version":5,"user":{},"badges":${badgesJson(badge("new"))},
            "platform_proofs":[{"platform":"telegram","username":"a","proof_sig":"${"ab".repeat(64)}","created_at":1}],
            "humanity_proof":{"verified":true,"first_zap_at":5,"receipt_event_id":"r","checked_at":6}}"""
        h.proofs.platformWriteSucceeds = false

        assertEquals(RestoreOutcome.Failed(RestoreError.ProofsNotSaved), h.engine.restore(doc(json), yes))
        assertEquals(listOf("new"), h.repo.loadBadges().map { it.id })
        assertTrue(h.proofs.platform.isEmpty())
        assertEquals(1, h.proofs.restoredHumanity.size)
        assertEquals("end", h.sync.calls.last())

        h.proofs.platformWriteSucceeds = true
        val retried = h.engine.restore(doc(json), yes) as RestoreOutcome.Restored
        assertEquals(1, retried.platformProofs)
        assertTrue(retried.humanity)
        assertEquals(listOf("new"), h.repo.loadBadges().map { it.id })
    }

    @Test
    fun `failed additive platform write does not report a successful merge`() = runTest {
        val h = BackupHarness()
        val k = KeyPair.generate()
        h.withLocalKey(k, badge("old"))
        h.proofs.platformWriteSucceeds = false
        val json = """{"version":5,"user":{},"badges":${badgesJson(badge("new"))},"nostr":{"npub":"${k.npub}"},
            "platform_proofs":[{"platform":"telegram","username":"a","proof_sig":"${"ab".repeat(64)}","created_at":1}]}"""

        assertEquals(RestoreOutcome.Failed(RestoreError.ProofsNotSaved), h.engine.restore(doc(json), yes))
        assertEquals(setOf("old", "new"), h.repo.loadBadges().map { it.id }.toSet())
        assertEquals("end", h.sync.calls.last())
    }

    @Test
    fun `failed humanity write does not report the proof restored`() = runTest {
        val h = BackupHarness()
        h.proofs.humanityWriteSucceeds = false
        val json = """{"version":5,"user":{},"badges":[],
            "humanity_proof":{"verified":true,"first_zap_at":5,"receipt_event_id":"r","checked_at":6}}"""

        assertEquals(RestoreOutcome.Failed(RestoreError.ProofsNotSaved), h.engine.restore(doc(json), yes))
        assertEquals(1, h.proofs.restoredHumanity.size)
        assertEquals("end", h.sync.calls.last())
    }

    @Test
    fun `platform proofs - additive union keeps the device entry`() = runTest {
        val h = BackupHarness()
        val k = KeyPair.generate()
        h.withLocalKey(k)
        val dev = "cd".repeat(64)
        val sig = "ab".repeat(64)
        h.proofs.platform = listOf(DartJson.decode("""{"platform":"telegram","username":"dev","proof_sig":"$dev","created_at":1}""").jsonObject)
        val json = """{"version":5,"user":{},"badges":[],"nostr":{"npub":"${k.npub}"},"platform_proofs":[
            {"platform":"Telegram","username":"bak","proof_sig":"$sig","created_at":2},
            {"platform":"x","username":"bak","proof_sig":"$sig","created_at":3}]}"""
        assertEquals(RestoreOutcome.Merged(0), h.engine.restore(doc(json), yes))
        assertEquals(listOf("dev", "bak"), h.proofs.platform.map { it["username"]!!.jsonPrimitive.content })
    }

    @Test
    fun `humanity proof is restored as pending unless already verified in additive mode`() = runTest {
        val hp = """"humanity_proof":{"verified":true,"first_zap_at":5,"receipt_event_id":"r","checked_at":6}"""
        val fresh = BackupHarness()
        val out = fresh.engine.restore(doc("""{"version":5,"user":{},"badges":[],$hp}"""), yes) as RestoreOutcome.Restored
        assertTrue(out.humanity)
        assertEquals(1, fresh.proofs.restoredHumanity.size)
        assertFalse(fresh.proofs.restoredHumanity.single().second)

        val same = BackupHarness()
        val k = KeyPair.generate()
        same.withLocalKey(k)
        same.proofs.humanityVerified = true
        same.engine.restore(doc("""{"version":5,"user":{},"badges":[],"nostr":{"npub":"${k.npub}"},$hp}"""), yes)
        assertTrue(same.proofs.restoredHumanity.isEmpty())
    }

    // ---- round trip

    @Test
    fun `export then import on a new device restores identity, profile and badges`() = runTest {
        val source = BackupHarness()
        val k = KeyPair.generate()
        source.withLocalKey(k, badge("a"), badge("b"))
        val ready = source.exporter.export("passwort123") as ExportResult.Ready

        val target = BackupHarness()
        val opened = BackupCrypto.open(ready.content, password = { "passwort123" }) as OpenResult.Ok
        val out = target.engine.restore((BackupDocument.parseBlocking(opened.json) as ParseResult.Ok).document, yes)

        assertEquals(RestoreOutcome.Restored(2, true, null, false), out)
        assertEquals(k.npub, target.identity.keyNpub)
        assertEquals(k.privateKeyHex, target.identity.privHex)
        assertEquals("Ada", target.profiles.stored.nickname)
        assertEquals(setOf("a", "b"), target.repo.loadBadges().map { it.id }.toSet())
    }
}
