package space.einundzwanzig.meetup.data.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.identity.profile.UserProfile

/** Port of `test/backup_restore_decision_test.dart` + fixture `restoreDecision` + N3 (spec 08 §10.5, §10.8). */
class BackupRestoreDecisionTest {

    private val a = "npub1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val b = "npub1bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    private fun obj(json: String): JsonObject = DartJson.decode(json).jsonObject

    // ---- backupIdentityNpub

    @Test
    fun `aktive Identitaet vor dem lokalen Schluessel`() {
        assertEquals(b, RestorePolicy.backupIdentityNpub(obj("""{"nostr":{"npub":"$a","active_npub":"$b"}}""")))
    }

    @Test
    fun `ohne aktive Identitaet der npub des Schluessels`() {
        assertEquals(a, RestorePolicy.backupIdentityNpub(obj("""{"nostr":{"npub":" $a ","active_npub":""}}""")))
    }

    @Test
    fun `Backup ohne Nostr-Block ist leer`() {
        assertEquals("", RestorePolicy.backupIdentityNpub(obj("""{"user":{}}""")))
        assertEquals("", RestorePolicy.backupIdentityNpub(obj("""{"nostr":null}""")))
    }

    @Test
    fun `Identitaeten eines Backups - aktive und lokale, ohne Doppel`() {
        assertEquals(listOf(b, a), RestorePolicy.backupIdentities(obj("""{"nostr":{"npub":"$a","active_npub":"$b"}}""")))
        assertEquals(
            listOf(a),
            RestorePolicy.backupIdentities(obj("""{"nostr":{"npub":"${a.uppercase()}","active_npub":"$a"}}""")),
        )
        assertTrue(RestorePolicy.backupIdentities(obj("""{"user":{}}""")).isEmpty())
    }

    // ---- restoreDecision

    @Test
    fun `ohne Identitaet auf dem Geraet - wie bisher`() {
        assertEquals(RestoreDecision.FRESH, RestorePolicy.restoreDecision("", a))
    }

    @Test
    fun `dieselbe Identitaet - vereinigen`() {
        assertEquals(RestoreDecision.SAME_IDENTITY, RestorePolicy.restoreDecision(a, a))
    }

    @Test
    fun `andere Identitaet - ersetzen nach Bestaetigung`() {
        assertEquals(RestoreDecision.REPLACES_IDENTITY, RestorePolicy.restoreDecision(a, b))
    }

    @Test
    fun `Backup ohne erkennbare Identitaet wird hinzugefuegt`() {
        assertEquals(RestoreDecision.ADD_TO_IDENTITY, RestorePolicy.restoreDecision(a, ""))
    }

    @Test
    fun `Grossschreibung des npub zaehlt als dieselbe Identitaet`() {
        assertEquals(RestoreDecision.SAME_IDENTITY, RestorePolicy.restoreDecision(a.uppercase(), a))
    }

    @Test
    fun `fixture restoreDecision table`() {
        val cases = Fixtures["restoreDecision"].jsonArray
        assertEquals(6, cases.size)
        for (c in cases) {
            val o = c.jsonObject
            val got = RestorePolicy.restoreDecision(o.getValue("existing").jsonPrimitive.content, o.getValue("backup").jsonPrimitive.content)
            assertEquals(o.toString(), o.getValue("expected").jsonPrimitive.content, got.logName)
        }
    }

    // ---- mergeProfile

    @Test
    fun `Profil ergaenzt nur leere Felder und vereinigt Favoriten`() {
        val device = UserProfile(
            nickname = "Ada",
            fullName = "",
            homeMeetupId = "bonn",
            favoriteMeetupIds = listOf("bonn"),
            nostrNpub = a,
            adminViaPortal = true,
        )
        val merged = RestorePolicy.mergeProfile(
            device,
            obj(
                """{"nickname":"Alt","fullName":"Ada Lovelace","homeMeetupId":"koeln",
                   "favoriteMeetupIds":["koeln","bonn"],"nostrNpub":"npub1other"}""",
            ),
        )
        assertEquals("Ada", merged.nickname)
        assertEquals("Ada Lovelace", merged.fullName)
        assertEquals("bonn", merged.homeMeetupId)
        assertTrue(merged.favoriteMeetupIds.containsAll(listOf("bonn", "koeln")))
        assertEquals(a, merged.nostrNpub)
        assertTrue(merged.adminViaPortal)
        assertFalse(merged.adminViaVouch)
    }

    @Test
    fun `merge takes the backup nickname over Anon and adopts the backup home`() {
        val merged = RestorePolicy.mergeProfile(
            UserProfile(nickname = "Anon", favoriteMeetupIds = listOf("x")),
            obj("""{"nickname":"Bob","homeMeetupId":"koeln","telegramHandle":"  ","twitterHandle":"bob"}"""),
        )
        assertEquals("Bob", merged.nickname)
        assertEquals("koeln", merged.homeMeetupId)
        assertEquals(listOf("x", "koeln"), merged.favoriteMeetupIds)
        assertEquals("", merged.telegramHandle)
        assertEquals("bob", merged.twitterHandle)
    }

    @Test
    fun `fresh profile uses Flutter defaults and maps isAdmin to the vouch source`() {
        val p = RestorePolicy.freshProfile(obj("""{"homeMeetupId":"bonn","isAdmin":true}"""))
        assertEquals("Anon", p.nickname)
        assertEquals(listOf("bonn"), p.favoriteMeetupIds)
        assertTrue(p.adminViaVouch)
        assertFalse(p.adminViaPortal)
        assertFalse(p.isNostrVerified)
    }

    @Test(expected = BackupFormatException::class)
    fun `fresh profile rejects a non-string nickname like Dart`() {
        RestorePolicy.freshProfile(obj("""{"nickname":5}"""))
    }

    // ---- addedBadgeCount

    @Test
    fun `Hinzugefuegte Badges zaehlen Veranstaltungen, nicht die Laenge`() {
        fun scan(id: String, event: String, date: DartDateTime) =
            MeetupBadge(id = id, meetupName = "Ort", meetupEventId = event, date = date, iconPath = "assets/badge_icon.png")
        val before = listOf(scan("neu", "evt-bonn", DartDateTime.utc(2026, 6, 1)))
        val merged = listOf(
            scan("alt", "evt-bonn", DartDateTime.utc(2026, 1, 1)),
            scan("koeln", "evt-koeln", DartDateTime.utc(2026, 3, 1)),
        )
        assertEquals(1, RestorePolicy.addedBadgeCount(before, merged))
    }

    // ---- unionPlatformProofs

    @Test
    fun `Plattform-Proofs - das Geraet behaelt denselben Namen`() {
        val merged = RestorePolicy.unionPlatformProofs(
            DartJson.decode("""[{"platform":"telegram","username":"Ada","proof_sig":"device"}]""").jsonArray,
            DartJson.decode(
                """[{"platform":"telegram","username":"ada","proof_sig":"backup"},
                    {"platform":"x","username":"ada","proof_sig":"extra"}]""",
            ).jsonArray,
        )
        assertEquals(2, merged.size)
        assertEquals("device", merged.first { it["platform"]!!.jsonPrimitive.content == "telegram" }["proof_sig"]!!.jsonPrimitive.content)
    }

    @Test
    fun `platform proof format check keeps only 128 hex signatures`() {
        val sig = "aB".repeat(64)
        val entries = DartJson.decode(
            """[{"platform":"a","proof_sig":"$sig"},{"platform":"b","proof_sig":"${sig}0"},
                {"platform":"c","proof_sig":5},{"platform":"d"},"x",{"platform":"e","proof_sig":"${"g".repeat(128)}"}]""",
        ).jsonArray
        val kept = RestorePolicy.validatedPlatformProofs(entries)
        assertEquals(listOf("a"), kept.map { it.jsonObject["platform"]!!.jsonPrimitive.content })
        assertTrue(RestorePolicy.isValidProofSig(sig))
        assertFalse(RestorePolicy.isValidProofSig(sig.drop(1)))
    }

    // ---- N3 (stage_a_security_test)

    @Test
    fun `N3 key consistency`() {
        val k = KeyPair.generate()
        val other = KeyPair.generate()
        assertTrue(RestorePolicy.backupKeyConsistent(k.nsec, k.npub, k.privateKeyHex))
        assertFalse(RestorePolicy.backupKeyConsistent(k.nsec, other.npub, k.privateKeyHex))
        assertFalse(RestorePolicy.backupKeyConsistent(k.nsec, k.npub, other.privateKeyHex))
        assertFalse(RestorePolicy.backupKeyConsistent("zz-not-hex", k.npub, k.privateKeyHex))
    }

    @Test
    fun `shortenNpub like NostrService`() {
        assertEquals("npub1aaaaaaa...aaaaaaaa", RestorePolicy.shortenNpub(a))
        assertEquals("npub1short", RestorePolicy.shortenNpub("npub1short"))
        assertEquals("?", RestoreConfirmation.Replace(a, "").backupShort)
    }
}
