package space.einundzwanzig.meetup.data.badges

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.AesGcmBlob
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.core.crypto.hexToBytes
import space.einundzwanzig.meetup.data.badges.model.BadgeFormatException
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.BadgeProofs
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge

/** Port of `test/badge_model_test.dart` plus the model vectors of spec 02/03. */
class BadgeModelTest {

    private val sig128 = "a".repeat(128)
    private val pub64 = "b".repeat(64)
    private val sigId64 = "c".repeat(64)
    private val claimSig128 = "d".repeat(128)
    private val claimPub64 = "e".repeat(64)
    private val xSig128 = "x".repeat(128)
    private val yId64 = "y".repeat(64)
    private val zPub64 = "z".repeat(64)
    private val now = DartDateTime.now(FakeTime())

    private fun fullBadge(
        id: String = "badge-001",
        meetupName: String = "Aschaffenburg",
        sigVersion: Long = 2,
        sig: String = sig128,
        adminPubkey: String = pub64,
        claimSig: String = "",
        claimPubkey: String = "",
        claimTimestamp: Long = 0,
        isRetroactive: Boolean = false,
    ) = MeetupBadge(
        id = id,
        meetupName = meetupName,
        date = DartDateTime.utc(2025, 3, 15),
        iconPath = "assets/badge.png",
        blockHeight = 875000,
        signerNpub = "npub1testadmin",
        meetupEventId = "aschaffenburg-2025-03-15",
        delivery = "nfc",
        sig = sig,
        sigId = sigId64,
        adminPubkey = adminPubkey,
        sigVersion = sigVersion,
        sigContent = """{"v":2,"t":"B","m":"aschaffenburg-de"}""",
        claimSig = claimSig,
        claimPubkey = claimPubkey,
        claimTimestamp = claimTimestamp,
        isRetroactive = isRetroactive,
    )

    private fun claimedBadge(id: String = "badge-001", meetupName: String = "Aschaffenburg") =
        fullBadge(id = id, meetupName = meetupName, claimSig = claimSig128, claimPubkey = claimPub64, claimTimestamp = 1700000000)

    // ---- toJson / fromJson Roundtrip

    @Test
    fun `full badge survives round trip`() {
        val original = claimedBadge()
        val restored = MeetupBadge.fromJson(original.toJson())
        assertEquals(original.id, restored.id)
        assertEquals(original.meetupName, restored.meetupName)
        assertEquals(original.date, restored.date)
        assertEquals(original.blockHeight, restored.blockHeight)
        assertEquals(original.sig, restored.sig)
        assertEquals(original.sigId, restored.sigId)
        assertEquals(original.adminPubkey, restored.adminPubkey)
        assertEquals(original.sigVersion, restored.sigVersion)
        assertEquals(original.delivery, restored.delivery)
        assertEquals(original.claimSig, restored.claimSig)
        assertEquals(original.claimPubkey, restored.claimPubkey)
        assertEquals(original.claimTimestamp, restored.claimTimestamp)
        assertEquals(original.toJsonString(), restored.toJsonString())
    }

    @Test
    fun `minimal badge is backward compatible`() {
        val badge = MeetupBadge.fromJsonString("""{"id":"old-badge","meetupName":"Test","date":"2025-01-01T00:00:00.000Z","iconPath":""}""")
        assertEquals("old-badge", badge.id)
        assertEquals("", badge.sig)
        assertEquals(0L, badge.sigVersion)
        assertEquals("", badge.claimSig)
        assertEquals("nfc", badge.delivery)
        assertTrue("presenceVerified defaults to true", badge.presenceVerified)
        assertEquals(0.0, badge.lat, 0.0)
    }

    @Test
    fun `json encode produces valid json`() {
        val decoded = DartJson.decode(fullBadge().toJsonString()).jsonObject
        assertEquals("badge-001", decoded["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `toJson has Dart key order and types`() {
        val json = MeetupBadge(id = "i", meetupName = "n", date = DartDateTime.utc(2025, 3, 15, 19), iconPath = "p", lat = 48.0).toJsonString()
        assertEquals(
            """{"id":"i","meetupName":"n","date":"2025-03-15T19:00:00.000Z","iconPath":"p","coverUrl":"","blockHeight":0,""" +
                """"signerNpub":"","meetupEventId":"","delivery":"nfc","sig":"","sigId":"","adminPubkey":"","sigVersion":0,""" +
                """"sigContent":"","claimSig":"","claimEventId":"","claimPubkey":"","claimTimestamp":0,"isRetroactive":false,""" +
                """"presenceVerified":true,"isOrganizer":false,"isEvent":false,"lat":48.0,"lng":0.0}""",
            json,
        )
    }

    @Test
    fun `fromJson is strict on types like Dart casts`() {
        val base = """"id":"a","meetupName":"n","date":"2025-01-01T00:00:00.000Z","iconPath":"""""
        MeetupBadge.fromJsonString("{$base,\"lat\":49,\"blockHeight\":null}").also {
            assertEquals(49.0, it.lat, 0.0)
            assertEquals(0L, it.blockHeight)
        }
        assertThrows(BadgeFormatException::class.java) { MeetupBadge.fromJsonString("{$base,\"blockHeight\":1.0}") }
        assertThrows(BadgeFormatException::class.java) { MeetupBadge.fromJsonString("{$base,\"isEvent\":\"true\"}") }
        assertThrows(BadgeFormatException::class.java) { MeetupBadge.fromJsonString("{$base,\"sig\":5}") }
        assertThrows(BadgeFormatException::class.java) { MeetupBadge.fromJsonString("""{"id":"a","meetupName":"n","iconPath":""}""") }
        assertThrows(BadgeFormatException::class.java) { MeetupBadge.fromJsonString("""{"id":"a","meetupName":"n","date":"kein","iconPath":""}""") }
        assertThrows(BadgeFormatException::class.java) { MeetupBadge.listFromJsonString("{}") }
    }

    @Test
    fun `equality is identity like Dart`() {
        val a = fullBadge()
        val b = MeetupBadge.fromJson(a.toJson())
        assertFalse(a == b)
        assertEquals(a.toJsonString(), b.toJsonString())
    }

    @Test
    fun `copyWith keeps isEvent and withClaim resets presence like Dart`() {
        val event = MeetupBadge(id = "e", meetupName = "E", date = now, iconPath = "", isEvent = true, presenceVerified = false)
        assertTrue(event.copyWith(id = "f").isEvent)
        assertFalse(event.copyWith(id = "f").presenceVerified)
        val claimed = event.withClaim("s", "i", "p", 1)
        assertTrue(claimed.isEvent)
        assertTrue(claimed.presenceVerified)
        assertFalse(event.withClaim("s", "i", "p", 1, presenceVerified = false).presenceVerified)
    }

    // ---- Kryptographische Properties

    @Test fun `hasCryptoProof v2 with signature`() = assertTrue(fullBadge(sigVersion = 2).hasCryptoProof)

    @Test fun `hasCryptoProof v0 without signature`() = assertFalse(fullBadge(sigVersion = 0, sig = "").hasCryptoProof)

    @Test fun `hasCryptoProof v1 legacy has sig`() = assertTrue(fullBadge(sigVersion = 1).hasCryptoProof)

    @Test fun `isNostrSigned v2 128 sig pubkey`() = assertTrue(fullBadge(sigVersion = 2).isNostrSigned)

    @Test fun `isNostrSigned v1 false`() = assertFalse(fullBadge(sigVersion = 1).isNostrSigned)

    @Test fun `isNostrSigned empty sig false`() = assertFalse(fullBadge(sigVersion = 2, sig = "").isNostrSigned)

    @Test fun `isNostrSigned short sig false`() = assertFalse(fullBadge(sigVersion = 2, sig = pub64).isNostrSigned)

    @Test fun `isNostrSigned zero sig false`() = assertFalse(fullBadge(sigVersion = 2, sig = "0".repeat(128)).isNostrSigned)

    // ---- Claim-Binding

    @Test fun `isClaimed with claim`() = assertTrue(claimedBadge().isClaimed)

    @Test fun `isClaimed without claim`() = assertFalse(fullBadge().isClaimed)

    @Test fun `isFullyBound signature and claim`() = assertTrue(claimedBadge().isFullyBound)

    @Test fun `isFullyBound signature without claim`() = assertFalse(fullBadge().isFullyBound)

    @Test
    fun `isFullyBound claim without signature`() {
        val badge = MeetupBadge(
            id = "test", meetupName = "Test", date = now, iconPath = "",
            sigVersion = 0, sig = "", adminPubkey = "", claimSig = claimSig128, claimPubkey = claimPub64,
        )
        assertFalse(badge.isFullyBound)
    }

    // ---- withClaim

    @Test
    fun `withClaim creates new badge with claim data`() {
        val original = fullBadge()
        assertFalse(original.isClaimed)
        val claimed = original.withClaim(xSig128, yId64, zPub64, 1700000000)
        assertTrue(claimed.isClaimed)
        assertEquals(xSig128, claimed.claimSig)
        assertEquals(zPub64, claimed.claimPubkey)
        assertFalse(original.isClaimed)
        assertNotSame(original, claimed)
    }

    @Test
    fun `withClaim keeps original data`() {
        val original = fullBadge()
        val claimed = original.withClaim(xSig128, yId64, zPub64, 1700000000)
        assertEquals(original.id, claimed.id)
        assertEquals(original.meetupName, claimed.meetupName)
        assertEquals(original.sig, claimed.sig)
        assertEquals(original.sigVersion, claimed.sigVersion)
    }

    @Test
    fun `withClaim sets retroactive flag`() {
        assertTrue(fullBadge().withClaim(xSig128, yId64, zPub64, 1700000000, isRetroactive = true).isRetroactive)
    }

    // ---- proofId

    @Test fun `proofId uses sigId`() = assertEquals(sigId64, fullBadge().proofId)

    @Test
    fun `proofId falls back to sig prefix`() {
        val badge = MeetupBadge(id = "test", meetupName = "Test", date = now, iconPath = "", sig = sig128, sigId = "", sigVersion = 2, adminPubkey = pub64)
        assertEquals(sig128.substring(0, 64), badge.proofId)
    }

    @Test
    fun `proofId hash fallback`() {
        val badge = MeetupBadge(id = "test", meetupName = "Test", date = DartDateTime.utc(2025), iconPath = "", sig = "", sigId = "", sigVersion = 0)
        assertEquals(64, badge.proofId.length)
        assertTrue(Regex("^[0-9a-f]+$").matches(badge.proofId))
    }

    @Test
    fun `proofId does not crash on a short sig`() {
        val badge = MeetupBadge(id = "t", meetupName = "T", date = now, iconPath = "", sig = "abc", sigVersion = 1)
        assertEquals("abc", badge.proofId)
    }

    // ---- claimProofId

    @Test
    fun `claimed badge has claimProofId`() {
        assertEquals(64, claimedBadge().claimProofId.length)
    }

    @Test fun `unclaimed badge has empty claimProofId`() = assertEquals("", fullBadge().claimProofId)

    @Test fun `claimProofId is deterministic`() = assertEquals(claimedBadge().claimProofId, claimedBadge().claimProofId)

    // ---- Badge-Proof Hashes

    @Test
    fun `generateBadgeProof deterministic`() {
        val badges = listOf(fullBadge(id = "1"), fullBadge(id = "2"))
        val a = BadgeProofs.generateBadgeProof(badges)
        assertEquals(a, BadgeProofs.generateBadgeProof(badges))
        assertEquals(64, a.length)
    }

    @Test fun `generateBadgeProof empty list`() = assertEquals("", BadgeProofs.generateBadgeProof(emptyList()))

    @Test
    fun `generateBadgeProof order independent`() {
        val b1 = fullBadge(id = "1")
        val b2 = fullBadge(id = "2")
        assertEquals(BadgeProofs.generateBadgeProof(listOf(b1, b2)), BadgeProofs.generateBadgeProof(listOf(b2, b1)))
    }

    @Test
    fun `verifyBadgeProof correct`() {
        val badges = listOf(fullBadge(id = "1"), fullBadge(id = "2"))
        assertTrue(BadgeProofs.verifyBadgeProof(badges, BadgeProofs.generateBadgeProof(badges)))
    }

    @Test fun `verifyBadgeProof tampered`() = assertFalse(BadgeProofs.verifyBadgeProof(listOf(fullBadge(id = "1")), "fake"))

    @Test
    fun `generateBadgeProofV2 only bound badges`() {
        val proof = BadgeProofs.generateBadgeProofV2(listOf(claimedBadge(id = "1"), fullBadge(id = "2"), claimedBadge(id = "3")))
        assertEquals(64, proof.length)
    }

    @Test fun `generateBadgeProofV2 no bound badges`() = assertEquals("", BadgeProofs.generateBadgeProofV2(listOf(fullBadge(id = "1"))))

    // ---- Reputations-Statistiken

    @Test
    fun `getReputationStats counts`() {
        val badges = listOf(
            claimedBadge(id = "1"), fullBadge(id = "2"), claimedBadge(id = "3"),
            MeetupBadge(id = "4", meetupName = "X", date = now, iconPath = "", sigVersion = 0),
        )
        val stats = BadgeProofs.getReputationStats(badges).toMap()
        assertEquals(4, stats["total"])
        assertEquals(3, stats["crypto_proof"])
        assertEquals(2, stats["claimed"])
        assertEquals(2, stats["fully_trusted"])
        assertEquals(0, stats["retroactive"])
        assertEquals(listOf("total", "crypto_proof", "claimed", "retroactive", "fully_trusted"), stats.keys.toList())
    }

    @Test
    fun countVerifiedBadges() {
        val badges = listOf(fullBadge(id = "1", sigVersion = 2), fullBadge(id = "2", sigVersion = 0, sig = ""), fullBadge(id = "3", sigVersion = 1))
        assertEquals(2, BadgeProofs.countVerifiedBadges(badges))
    }

    @Test
    fun `retroactive badges counted separately`() {
        val badges = listOf(
            fullBadge(id = "1", claimSig = claimSig128, claimPubkey = claimPub64, claimTimestamp = 1700000000),
            MeetupBadge(
                id = "2", meetupName = "Test", date = now, iconPath = "",
                sig = sig128, sigId = sigId64, adminPubkey = pub64, sigVersion = 2,
                claimSig = claimSig128, claimPubkey = claimPub64, claimTimestamp = 1700000000, isRetroactive = true,
            ),
        )
        val stats = BadgeProofs.getReputationStats(badges)
        assertEquals(1, stats.retroactive)
        assertEquals(1, stats.fullyTrusted)
    }

    // ---- spec fixtures

    @Test
    fun `fixture dartIso8601`() {
        for (v in Fixtures["dartIso8601"].jsonArray) {
            val o = v.jsonObject
            fun i(k: String) = o[k]!!.jsonPrimitive.int
            val dt = if (o["utc"]!!.jsonPrimitive.boolean) {
                DartDateTime.utc(i("y"), i("mo"), i("d"), i("h"), i("mi"), i("s"), i("ms"), i("us"))
            } else {
                DartDateTime.local(i("y"), i("mo"), i("d"), i("h"), i("mi"), i("s"), i("ms"), i("us"))
            }
            assertEquals(o["expected"]!!.jsonPrimitive.content, dt.toIso8601String())
        }
    }

    @Test
    fun `fixture proofs`() {
        val p = Fixtures["proofs"].jsonObject
        val claim = Fixtures["claim"].jsonObject
        val compact = Fixtures["compactBadge"].jsonObject
        val keys = Fixtures["keys"].jsonObject
        fun s(o: JsonObject, k: String) = o[k]!!.jsonPrimitive.content
        val badge = MeetupBadge(
            id = "bad-neuenahr", meetupName = "Bad Neuenahr", date = DartDateTime.utc(2025, 10, 9), iconPath = "",
            sig = s(compact, "sig"), sigVersion = 2, adminPubkey = s(keys["organizer"]!!.jsonObject, "pub"),
            claimSig = s(claim, "sig"), claimEventId = s(claim, "id"), claimPubkey = s(keys["attendee"]!!.jsonObject, "pub"),
            claimTimestamp = 1760000123,
        )
        assertEquals(s(p, "claimProofId_input"), "${badge.sig}|${badge.claimSig}|${badge.claimPubkey}")
        assertEquals(s(p, "claimProofId"), badge.claimProofId)
        assertEquals(s(p, "badgeProofV2_single_bound_badge"), BadgeProofs.generateBadgeProofV2(listOf(badge)))
        assertEquals(s(p, "proofId_when_sigId_empty"), badge.proofId)
        assertEquals(s(p, "badgeProofV1_single_badge_sigId_empty"), BadgeProofs.generateBadgeProof(listOf(badge)))

        val wien = MeetupBadge(id = "wien", meetupName = "Wien, AT", date = DartDateTime.utc(2025, 3, 15, 19), iconPath = "", blockHeight = 875000)
        assertEquals(s(p, "proofId_fallback_input"), "wien-Wien, AT-${wien.date.toIso8601String()}-875000")
        assertEquals(s(p, "proofId_fallback"), wien.proofId)
        assertEquals(s(p, "verificationHash"), wien.verificationHash())
        assertEquals(s(p, "reputationQr_meetupList_hash12"), BadgeProofs.meetupListHash("Wien, AT"))
    }

    @Test
    fun `fixture identityKey`() {
        for (v in Fixtures["identityKey"].jsonArray) {
            val o = v.jsonObject
            fun s(k: String) = o[k]!!.jsonPrimitive.content
            val b = MeetupBadge(
                id = s("id"), meetupName = s("meetupName"), date = DartDateTime.parse(s("date")), iconPath = "",
                meetupEventId = s("meetupEventId"), sigId = s("sigId"), isOrganizer = o["isOrganizer"]!!.jsonPrimitive.boolean,
            )
            assertEquals(s("case"), s("identityKey"), BadgeIdentity.identityKey(b))
            assertEquals(s("case"), s("unionKey"), BadgeIdentity.unionKey(b))
        }
    }

    @Test
    fun `fixture unionByIdentity`() {
        val defs = Fixtures["unionByIdentity_fixtures"].jsonObject
        fun badge(name: String): MeetupBadge {
            val o = defs[name]!!.jsonObject
            return MeetupBadge(
                id = o["id"]!!.jsonPrimitive.content,
                meetupName = "Koblenz",
                date = DartDateTime.parse(o["date"]!!.jsonPrimitive.content),
                iconPath = "",
                meetupEventId = o["meetupEventId"]!!.jsonPrimitive.content,
                claimSig = o["claimSig"]?.jsonPrimitive?.content ?: "",
                isOrganizer = o["isOrganizer"]?.jsonPrimitive?.boolean ?: false,
            )
        }
        for (v in Fixtures["unionByIdentity"].jsonArray) {
            val o = v.jsonObject
            val base = o["base"]!!.jsonArray.map { badge(it.jsonPrimitive.content) }
            val extra = o["extra"]!!.jsonArray.map { badge(it.jsonPrimitive.content) }
            val ids = BadgeIdentity.unionByIdentity(base, extra).map { it.id }
            assertEquals(o["case"]!!.jsonPrimitive.content, o["expectedIds"]!!.jsonArray.map { it.jsonPrimitive.content }, ids)
        }
    }

    @Test
    fun `unionByIdentity returns the chosen objects and keeps position`() {
        val a = MeetupBadge(id = "a", meetupName = "A", date = DartDateTime.utc(2026, 1, 1), iconPath = "", meetupEventId = "e1")
        val b = MeetupBadge(id = "b", meetupName = "B", date = DartDateTime.utc(2026, 1, 2), iconPath = "", meetupEventId = "e2")
        val aClaimed = MeetupBadge(id = "a2", meetupName = "A", date = DartDateTime.utc(2026, 1, 3), iconPath = "", meetupEventId = "e1", claimSig = "x")
        val result = BadgeIdentity.unionByIdentity(listOf(a, b), listOf(aClaimed))
        assertSame(aClaimed, result[0])
        assertSame(b, result[1])
        // Structurally identical copies: base wins (identity).
        val copy = MeetupBadge.fromJson(a.toJson())
        assertSame(a, BadgeIdentity.unionByIdentity(listOf(a, copy), emptyList()).single())
    }

    @Test
    fun `sameBadges ignores order`() {
        val a = fullBadge(id = "1")
        val b = fullBadge(id = "2")
        assertTrue(BadgeIdentity.sameBadges(listOf(a, b), listOf(MeetupBadge.fromJson(b.toJson()), a)))
        assertFalse(BadgeIdentity.sameBadges(listOf(a, b), listOf(a)))
        assertFalse(BadgeIdentity.sameBadges(listOf(a), listOf(b)))
    }

    @Test
    fun `fixture aesGcmBlob`() {
        val o = Fixtures["aesGcmBlob"].jsonObject
        val key = o["key_hex"]!!.jsonPrimitive.content.hexToBytes()
        val iv = o["iv_hex"]!!.jsonPrimitive.content.hexToBytes()
        val plaintext = o["plaintext"]!!.jsonPrimitive.content
        val blob = o["blob"]!!.jsonPrimitive.content
        assertEquals(plaintext, AesGcmBlob.open(blob, key))
        assertEquals(blob, AesGcmBlob.seal(key, plaintext, iv))
        val badges = MeetupBadge.listFromJsonString(plaintext)
        assertEquals("wien", badges.single().id)
        assertEquals("2025-03-15T19:00:00.000Z", badges.single().date.toIso8601String())
    }

    // ---- spec 03 §10.2 vectors

    private val badgeA = MeetupBadge(
        id = "badge-001", meetupName = "Aschaffenburg", date = DartDateTime.utc(2025, 3, 15), iconPath = "",
        blockHeight = 875000, sig = "a".repeat(128), sigId = "c".repeat(64), adminPubkey = "b".repeat(64), sigVersion = 2,
        claimSig = "d".repeat(128), claimPubkey = "e".repeat(64), claimEventId = "", claimTimestamp = 1700000000,
        signerNpub = "npub1testadmin",
    )
    private val badgeB = MeetupBadge(
        id = "old-badge", meetupName = "München, DE", date = DartDateTime.local(2026, 10, 11, 19, 3, 12, 345, 678), iconPath = "",
    )

    @Test
    fun `verification hash vectors`() {
        assertEquals("48dd2de552fae3a0", badgeA.verificationHash())
        assertEquals("f38c551862271ffe", badgeB.verificationHash())
        assertEquals("f38c551862271ffe70e896d95e9adfd9a4bb18b32400da422b838a006c3a7471", badgeB.proofId)
        assertEquals("e8570ebcc5503c00ab0f3308646e1f1b0c163b72cd99b26aca97f60e4cb880be", badgeA.claimProofId)
        assertEquals("a1b78251c23903f98a7cd1ef4ddebc405b656aa90b8fa4e84fff2d94600e2f07", BadgeProofs.generateBadgeProof(listOf(badgeA, badgeB)))
        assertEquals("75f4c3ecb24216b8d7673b76601895da7f170a7e879520e43024ad5294692827", BadgeProofs.generateBadgeProofV2(listOf(badgeA, badgeB)))
    }

    @Test
    fun `reputation export vector`() {
        val out = BadgeProofs.exportBadgesForReputation(
            listOf(badgeA, badgeB), "npub1xyz", exportedAt = DartDateTime.local(2026, 10, 20, 12), nickname = "Anon",
        )
        val a128 = "a".repeat(128)
        val expectedCompact = """{"version":"4.0","identity":{"nickname":"Anon","nostr_npub":"npub1xyz"},"total_badges":2,""" +
            """"verified_badges":1,"bound_badges":1,"meetups_visited":2,"unique_signers":1,""" +
            """"badge_proof":"a1b78251c23903f98a7cd1ef4ddebc405b656aa90b8fa4e84fff2d94600e2f07",""" +
            """"badge_proof_v2":"75f4c3ecb24216b8d7673b76601895da7f170a7e879520e43024ad5294692827",""" +
            """"stats":{"total":2,"crypto_proof":1,"claimed":1,"retroactive":0,"fully_trusted":1},""" +
            """"badges":[{"meetup":"Aschaffenburg","date":"2025-03-15T00:00:00.000Z","block":875000,"signer_npub":"npub1testadmin",""" +
            """"sig_version":2,"is_bound":true,"is_retroactive":false,"presence_verified":true,"sig":"$a128",""" +
            """"sig_id":"${"c".repeat(64)}","admin_pubkey":"${"b".repeat(64)}","claim_sig":"${"d".repeat(128)}",""" +
            """"claim_event_id":"","claim_pubkey":"${"e".repeat(64)}","hash":"48dd2de552fae3a0"},""" +
            """{"meetup":"München, DE","date":"2026-10-11T19:03:12.345678","block":0,"signer_npub":"","sig_version":0,""" +
            """"is_bound":false,"is_retroactive":false,"presence_verified":true,"hash":"f38c551862271ffe"}],""" +
            """"exported_at":"2026-10-20T12:00:00.000"}"""
        assertEquals("745487f0", Sha256.hexOfUtf8(expectedCompact).substring(0, 8))
        val parsed = DartJson.decode(out).jsonObject
        assertEquals("745487f0", parsed["checksum"]!!.jsonPrimitive.content)
        assertTrue(out.endsWith("  \"exported_at\": \"2026-10-20T12:00:00.000\",\n  \"checksum\": \"745487f0\"\n}"))
        val withoutChecksum = JsonObject(parsed.filterKeys { it != "checksum" })
        assertEquals(expectedCompact, DartJson.encode(withoutChecksum))
        assertEquals(875000L, parsed["badges"]!!.jsonArray[0].jsonObject["block"]!!.jsonPrimitive.long)
    }

    @Test
    fun `anonymous export identity`() {
        val out = BadgeProofs.exportBadgesForReputation(emptyList(), "", DartDateTime.utc(2026, 1, 1))
        val identity = DartJson.decode(out).jsonObject["identity"]!!.jsonObject
        assertEquals(JsonPrimitive("anonymous"), identity["status"])
    }

    @Test
    fun `toReputationString`() {
        assertEquals(
            "Badge #badge-001\nMeetup: Aschaffenburg\nDatum: 15.3.2025\nBlock: 875000\n🔐 Nostr-verifiziert bei Einundzwanzig\n🔗 Gebunden",
            badgeA.toReputationString(),
        )
    }
}
