package space.einundzwanzig.meetup.data.backup

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import java.time.Instant

/** The v5 document (spec 08 §3.2, §8.1) and the export pipeline (§2.4). */
class BackupExporterTest {

    private val v2 = Fixtures["enc_v2"].jsonObject
    private val plaintext = v2.getValue("plaintext").jsonPrimitive.content
    private val fixtureBadge = MeetupBadge.fromJson(DartJson.decode(plaintext).jsonObject.getValue("badges").jsonArray[0])

    private suspend fun fixtureDevice(): BackupHarness {
        val h = BackupHarness()
        h.time.millis = Instant.parse("2026-10-20T12:00:00Z").toEpochMilli()
        h.profiles.stored = UserProfile(nickname = "Ada", homeMeetupId = "Aschaffenburg", favoriteMeetupIds = listOf("Aschaffenburg"))
        assertTrue(h.repo.saveBadges(listOf(fixtureBadge)))
        return h
    }

    private fun nostrOf(json: String): JsonObject = DartJson.decode(json).jsonObject.getValue("nostr").jsonObject

    @Test
    fun `document is byte-identical to the Flutter v5 fixture`() = runTest {
        val h = fixtureDevice()
        val built = h.exporter.buildDocument() as BuildResult.Ok
        assertEquals(plaintext, built.json)
        assertEquals("Ada", built.nickname)
    }

    @Test
    fun `export encrypts with enc_v2 and suggests the file name`() = runTest {
        val h = fixtureDevice()
        val ready = h.exporter.export("korrekt pferd batterie klammer") as ExportResult.Ready
        assertEquals("Backup_2026-10-20_1200_21MeetupApp_Ada.21bkp", ready.fileName)
        assertTrue(ready.content.startsWith("enc_v2:"))
        assertEquals(
            OpenResult.Ok(plaintext),
            BackupCrypto.open(ready.content, password = { "korrekt pferd batterie klammer" }),
        )
    }

    @Test
    fun `export refuses short passwords`() = runTest {
        assertEquals(ExportResult.PasswordTooShort, fixtureDevice().exporter.export("1234567"))
    }

    @Test
    fun `unreadable wallet blocks the export entirely`() = runTest {
        val h = fixtureDevice()
        h.secure.failRead = { true }
        h.secure.failReadAll = true
        val fresh = BackupHarness(prefs = h.prefs, secure = h.secure)
        assertEquals(BuildResult.BadgesUnreadable, fresh.exporter.buildDocument())
        assertEquals(ExportResult.BadgesUnreadable, fresh.exporter.export("passwort123"))
    }

    @Test
    fun `local key is exported with keystore npub`() = runTest {
        val h = fixtureDevice()
        val k = KeyPair.generate()
        h.identity.saveKeys(k.nsec, k.npub, k.privateKeyHex)
        val nostr = nostrOf((h.exporter.buildDocument() as BuildResult.Ok).json)
        assertEquals(k.nsec, nostr["nsec"]!!.jsonPrimitive.content)
        assertEquals(k.npub, nostr["npub"]!!.jsonPrimitive.content)
        assertEquals(k.privateKeyHex, nostr["priv_hex"]!!.jsonPrimitive.content)
        assertTrue(nostr["has_key"]!!.jsonPrimitive.boolean)
        assertEquals("local", nostr["signing_mode"]!!.jsonPrimitive.content)
        assertEquals("", nostr["active_npub"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mixed state keeps the local key and records the external npub`() = runTest {
        val h = fixtureDevice()
        val k = KeyPair.generate()
        val amber = KeyPair.generate().npub
        h.identity.saveKeys(k.nsec, k.npub, k.privateKeyHex)
        h.identity.mode = SigningMode.AMBER
        h.identity.externalNpub = amber
        val nostr = nostrOf((h.exporter.buildDocument() as BuildResult.Ok).json)
        assertEquals(k.nsec, nostr["nsec"]!!.jsonPrimitive.content)
        assertEquals(k.npub, nostr["npub"]!!.jsonPrimitive.content)
        assertEquals("amber", nostr["signing_mode"]!!.jsonPrimitive.content)
        assertEquals(amber, nostr["active_npub"]!!.jsonPrimitive.content)
    }

    @Test
    fun `bunker backup carries npub and address but no key`() = runTest {
        val h = fixtureDevice()
        val bunker = KeyPair.generate().npub
        h.identity.mode = SigningMode.NIP46
        h.identity.externalNpub = bunker
        h.identity.bunkerUri = "bunker://abc?relay=wss://r"
        val nostr = nostrOf((h.exporter.buildDocument() as BuildResult.Ok).json)
        assertEquals("", nostr["nsec"]!!.jsonPrimitive.content)
        assertEquals(bunker, nostr["npub"]!!.jsonPrimitive.content)
        assertEquals("", nostr["priv_hex"]!!.jsonPrimitive.content)
        assertEquals(false, nostr["has_key"]!!.jsonPrimitive.boolean)
        assertEquals("nip46", nostr["signing_mode"]!!.jsonPrimitive.content)
        assertEquals("", nostr["active_npub"]!!.jsonPrimitive.content)
        assertEquals("bunker://abc?relay=wss://r", nostr["nip46_bunker_uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `admins, proofs and humanity keep Dart key order and int types`() = runTest {
        val h = fixtureDevice()
        val admin = KeyPair.generate().npub
        h.admins.list += AdminEntry(admin, "Bonn", "Ada", 1_700_000_000)
        val sig = "ab".repeat(64)
        h.proofs.platform = listOf(
            DartJson.decode("""{"platform":"telegram","username":"ada","proof_sig":"$sig","created_at":1700000001}""").jsonObject,
        )
        h.proofs.humanity = DartJson.decode(
            """{"verified":true,"first_zap_at":1700000002,"receipt_event_id":"ee","checked_at":1700000003}""",
        ).jsonObject
        val json = (h.exporter.buildDocument() as BuildResult.Ok).json
        assertTrue(json.contains(""""admin_registry":[{"npub":"$admin","meetup":"Bonn","name":"Ada","added_at":1700000000}]"""))
        assertTrue(json.contains(""""platform_proofs":[{"platform":"telegram","username":"ada","proof_sig":"$sig","created_at":1700000001}]"""))
        assertTrue(
            json.endsWith(
                """"humanity_proof":{"verified":true,"first_zap_at":1700000002,"receipt_event_id":"ee","checked_at":1700000003}}""",
            ),
        )
    }
}
