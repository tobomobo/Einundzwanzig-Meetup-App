package space.einundzwanzig.meetup.data.reputation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.DartBase64
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.data.badges.model.BadgeProofs
import space.einundzwanzig.meetup.data.badges.security.QrSignResult
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.reputation.qr.QrParseResult
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrCodec
import space.einundzwanzig.meetup.data.trust.score.BootstrapPhase
import space.einundzwanzig.meetup.data.trust.score.TrustConfig
import space.einundzwanzig.meetup.data.trust.score.TrustScore

/** Spec 04 §10.4 `ReputationQrCodecTest` on the `reputationQrV3` fixture. */
class ReputationQrCodecTest {

    private val qr = Fixtures["reputationQrV3"]
    private val payloadJson = qr.getValue("payload_json_exact").jsonPrimitive.content
    private val legacyMessage = "Legacy-QR (v1) wird nicht mehr akzeptiert. Bitte Organisator um neuen v2-Tag."

    @Test
    fun payloadJsonIsByteExactAndBase64Matches() {
        val decoded = DartJson.decode(payloadJson) as JsonObject
        assertEquals(payloadJson, ReputationQrCodec.payloadJson(decoded))
        assertEquals(qr.getValue("base64_standard_padded").jsonPrimitive.content, ReputationQrCodec.base64(payloadJson))
    }

    @Test
    fun signedStringMatchesFixtureWithZeroAux() {
        val ev = signed(KEY1, 21001, payloadJson, emptyList(), qr.getValue("created_at").jsonPrimitive.content.toLong())
        assertEquals(qr.getValue("event_id").jsonPrimitive.content, ev.id)
        assertEquals(qr.getValue("sig_aux0").jsonPrimitive.content, ev.sig)
        val code = ReputationQrCodec.encode(payloadJson, QrSignResult(ev.sig, ev.id, ev.createdAt, ev.pubkey, true))
        assertEquals(qr.getValue("qr_string").jsonPrimitive.content, code)
        assertEquals(qr.getValue("qr_string_length").jsonPrimitive.int, code.length)
    }

    @Test
    fun unsignedFallbackIsTheRejectedV1Form() {
        val code = ReputationQrCodec.encode(payloadJson, QrSignResult("", "", 0, "", false))
        assertEquals(qr.getValue("unsigned_qr_example").jsonPrimitive.content, code)
        assertEquals(code, ReputationQrCodec.encodeUnsigned(payloadJson))
        val result = ReputationQrCodec.parse(code)
        assertEquals(QrParseResult.SignatureInvalid(1, legacyMessage), result)
    }

    @Test
    fun verifiesFixtureQrAndMapsScanFields() {
        val result = ReputationQrCodec.parse(qr.getValue("qr_string").jsonPrimitive.content)
        result as QrParseResult.Verified
        val expected = qr.getValue("expected_verify").jsonObject
        assertEquals(expected.getValue("signerNpub").jsonPrimitive.content, result.signerNpub)
        assertEquals(expected.getValue("message").jsonPrimitive.content, result.message)
        assertFalse(result.identityMismatch)
        assertTrue(result.hasTrustedIdentity)
        assertEquals(result.signerNpub, result.layersNpub)

        val f = qr.getValue("expected_scan_result_fields").jsonObject
        val d = result.data
        assertEquals(f.getValue("trustLevel").jsonPrimitive.content, d.trustLevel)
        assertEquals(f.getValue("trustScore").jsonPrimitive.double, d.trustScore, 0.0)
        assertEquals(f.getValue("badgeCount").jsonPrimitive.int, d.badgeCount)
        assertEquals(f.getValue("verifiedBadgeCount").jsonPrimitive.int, d.verifiedBadgeCount)
        assertEquals(f.getValue("boundBadgeCount").jsonPrimitive.int, d.boundBadgeCount)
        assertEquals(f.getValue("meetupCount").jsonPrimitive.int, d.meetupCount)
        assertEquals(f.getValue("signerCount").jsonPrimitive.int, d.signerCount)
        assertEquals(f.getValue("accountAgeDays").jsonPrimitive.int, d.accountAgeDays)
        assertEquals(f.getValue("meetupList").jsonArray.map { it.jsonPrimitive.content }, d.meetupList)
        assertEquals(f.getValue("badgeProof").jsonPrimitive.content, d.badgeProof)
        assertEquals(f.getValue("proofTotalCount").jsonPrimitive.int, d.proofTotalCount)
        assertEquals(f.getValue("proofVerifiedCount").jsonPrimitive.int, d.proofVerifiedCount)
        assertEquals(f.getValue("hasIdentity").jsonPrimitive.content.toBoolean(), d.hasIdentity)
        assertEquals("Satoshi", d.nickname)
        assertEquals("satoshi", d.telegram)
        assertNull(d.twitter)
        assertEquals(mapOf("telegram" to "satoshi"), d.platformUsernames)
        assertEquals(6, d.payloadVersion)
        assertEquals(1_760_961_600_000L, d.timestampMillis)
    }

    @Test
    fun tamperedQrIsSignatureInvalid() {
        val result = ReputationQrCodec.parse(qr.getValue("tampered_qr_string").jsonPrimitive.content)
        assertEquals(QrParseResult.SignatureInvalid(3, "Fehler: Invalid event"), result)
    }

    @Test
    fun legacyV2IsAlwaysRejected() {
        val b64 = ReputationQrCodec.base64("""{"c":3,"m":2}""")
        assertEquals(QrParseResult.SignatureInvalid(2, legacyMessage), ReputationQrCodec.parse("21v2:$b64.sig.pub"))
    }

    @Test
    fun unknownFormatsAndReadErrors() {
        assertEquals(QrParseResult.UnknownFormat, ReputationQrCodec.parse("21:garbage"))
        assertEquals(QrParseResult.UnknownFormat, ReputationQrCodec.parse("21v2:a.b"))
        assertEquals(QrParseResult.UnknownFormat, ReputationQrCodec.parse("21v3:a.b.c.d"))
        assertTrue(ReputationQrCodec.parse("21v3:###.a.b.c.d") is QrParseResult.ReadError)
        assertTrue(ReputationQrCodec.parse("21:###.x") is QrParseResult.ReadError)
        assertTrue(ReputationQrCodec.parse("x") is QrParseResult.ReadError)
    }

    @Test
    fun isReputationCode() {
        assertTrue(ReputationQrCodec.isReputationCode("21v3:x"))
        assertTrue(ReputationQrCodec.isReputationCode("21v2:x"))
        assertTrue(ReputationQrCodec.isReputationCode("21:x"))
        assertFalse(ReputationQrCodec.isReputationCode("21review:einundzwanzig-meetup-demo"))
        assertFalse(ReputationQrCodec.isReputationCode("npub1abc"))
    }

    @Test
    fun buildPayloadMatchesFixtureLayout() {
        val date = DartDateTime.utc(2025, 9, 1)
        val badges = listOf(
            signedBadge("b1", "Aschaffenburg", date, signerNpub = "npub1s1", bound = true),
            signedBadge("b2", "Darmstadt", date, signerNpub = "npub1s2", bound = true),
            signedBadge("b3", "Frankfurt", date, signerNpub = "npub1s1", bound = true),
            signedBadge("b4", "Aschaffenburg", date, signerNpub = "npub1s2", bound = true),
            // Organizer marker: not counted in bc/mc/si/tc, but in vc/bb only if crypto/bound (it is neither).
            markerBadge("m1", "Wien", date),
        )
        val user = UserProfile(nickname = "Satoshi", nostrNpub = KEY1.npub, telegramHandle = "satoshi")
        val proofs = listOf(PlatformProof("telegram", "satoshi", "f".repeat(128), 1_760_000_000L))
        val payload = ReputationQrCodec.buildPayload(user, badges, score(12.25, 45), proofs, 1_760_961_600_000L)

        val expected = (DartJson.decode(payloadJson) as JsonObject).let { root ->
            val pf = root.getValue("pf").jsonObject
            JsonObject(root + ("pf" to JsonObject(pf + ("bp" to JsonPrimitive(BadgeProofs.generateBadgeProofV2(badges))))))
        }
        assertEquals(DartJson.encode(expected), ReputationQrCodec.payloadJson(payload))
    }

    @Test
    fun intFieldsHaveNoDecimalPointAndScoreIsADouble() {
        val date = DartDateTime.utc(2025, 9, 1)
        val payload = ReputationQrCodec.buildPayload(
            UserProfile(nickname = ""),
            listOf(signedBadge("b1", "Wien", date)),
            score(7.0, 3),
            emptyList(),
            5L,
        )
        val json = ReputationQrCodec.payloadJson(payload)
        assertTrue(json, json.startsWith("""{"v":6,"id":{"n":"Anon"},"rp":{"sc":7.0,"lv":"STARTER","bc":1,"vc":1,"mc":1,"si":1,"ad":3,"ml":["""))
        assertTrue(json, json.contains(""""pf":{"bp":""""))
        assertTrue(json, json.endsWith(""""pv":1,"vc":1,"tc":1,"bb":0},"t":5}"""))
        assertFalse(json.contains("\"pp\""))
    }

    @Test
    fun roundedScoreUsesDartToStringAsFixed() {
        assertEquals(2.8, ReputationQrCodec.roundedScore(2.75), 0.0)
        assertEquals(0.3, ReputationQrCodec.roundedScore(0.35), 0.0)
        assertEquals(0.0, ReputationQrCodec.roundedScore(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test
    fun claimedNpubOfAnotherKeyIsFlagged() {
        // Signed by KEY2 while claiming KEY1's npub (spec 04 S-8).
        val json = DartJson.encode(jsonObjectOf("v" to 6, "id" to jsonObjectOf("n" to "Mallory", "np" to KEY1.npub)))
        val ev = signed(KEY2, 21001, json)
        val code = ReputationQrCodec.encodeSigned(json, QrSignResult(ev.sig, ev.id, ev.createdAt, ev.pubkey, true))
        val result = ReputationQrCodec.parse(code) as QrParseResult.Verified
        assertTrue(result.identityMismatch)
        assertTrue(result.data.hasIdentity)
        assertFalse(result.hasTrustedIdentity)
        assertEquals(KEY2.npub, result.signerNpub)
        assertEquals(KEY2.npub, result.layersNpub)
    }

    @Test
    fun nonStringClaimedNpubIsAMismatch() {
        // `np` present but not a string: hasIdentity is true, so it must not pass as trusted.
        for (np in listOf<Any>(123, listOf("x"), mapOf("a" to 1))) {
            val json = DartJson.encode(jsonObjectOf("v" to 6, "id" to jsonObjectOf("np" to np)))
            val ev = signed(KEY1, 21001, json)
            val result = ReputationQrCodec.parse(
                ReputationQrCodec.encodeSigned(json, QrSignResult(ev.sig, ev.id, ev.createdAt, ev.pubkey, true)),
            ) as QrParseResult.Verified
            assertNull(result.data.claimedNpub)
            assertTrue(result.data.hasIdentity)
            assertTrue(result.identityMismatch)
            assertFalse(result.hasTrustedIdentity)
            assertEquals(KEY1.npub, result.layersNpub)
        }
    }

    @Test
    fun emptyOrMissingClaimedNpubIsNoMismatch() {
        for (id in listOf(jsonObjectOf("np" to ""), jsonObjectOf("tg" to "satoshi"))) {
            val json = DartJson.encode(jsonObjectOf("v" to 6, "id" to id))
            val ev = signed(KEY1, 21001, json)
            val result = ReputationQrCodec.parse(
                ReputationQrCodec.encodeSigned(json, QrSignResult(ev.sig, ev.id, ev.createdAt, ev.pubkey, true)),
            ) as QrParseResult.Verified
            assertFalse(result.identityMismatch)
        }
    }

    @Test
    fun upperCaseClaimedNpubIsNoMismatch() {
        val json = DartJson.encode(jsonObjectOf("v" to 6, "id" to jsonObjectOf("np" to KEY1.npub.uppercase())))
        val ev = signed(KEY1, 21001, json)
        val result = ReputationQrCodec.parse(
            ReputationQrCodec.encodeSigned(json, QrSignResult(ev.sig, ev.id, ev.createdAt, ev.pubkey, true)),
        ) as QrParseResult.Verified
        assertFalse(result.identityMismatch)
    }

    @Test
    fun scalarsAreReadLenientlyAndFallbacksApply() {
        // rp.vc / rp.bb missing → pf.vc / pf.bb; bc 3.0 → 3; mc "x" → 0; ml with a number → skipped.
        val json = """{"rp":{"bc":3.0,"mc":"x","ml":["a",1]},"pf":{"vc":5,"bb":2}}"""
        val ev = signed(KEY1, 21001, json)
        val result = ReputationQrCodec.parse(
            ReputationQrCodec.encodeSigned(json, QrSignResult(ev.sig, ev.id, ev.createdAt, ev.pubkey, true)),
        ) as QrParseResult.Verified
        assertEquals(3, result.data.badgeCount)
        assertEquals(0, result.data.meetupCount)
        assertEquals(listOf("a"), result.data.meetupList)
        assertEquals(5, result.data.verifiedBadgeCount)
        assertEquals(2, result.data.boundBadgeCount)
        assertFalse(result.data.hasIdentity)
        assertNull(result.layersNpub)
        assertEquals("", result.data.trustLevel)
    }

    @Test
    fun nonObjectSectionsAreReadErrors() {
        for (json in listOf("""[1,2]""", """{"rp":[1]}""")) {
            val ev = signed(KEY1, 21001, json)
            val code = ReputationQrCodec.encodeSigned(json, QrSignResult(ev.sig, ev.id, ev.createdAt, ev.pubkey, true))
            assertTrue(json, ReputationQrCodec.parse(code) is QrParseResult.ReadError)
        }
    }

    @Test
    fun urlSafeBase64IsAccepted() {
        // Dart base64.decode accepts the URL-safe alphabet too.
        val json = """{"id":{"n":"?>?>"}}"""
        val b64 = DartBase64.encodeUrl(DartUtf8.encode(json))
        val ev = signed(KEY1, 21001, json)
        val code = "21v3:$b64.${ev.sig}.${ev.id}.${ev.createdAt}.${ev.pubkey}"
        assertTrue(ReputationQrCodec.parse(code) is QrParseResult.Verified)
    }

    private fun score(total: Double, ageDays: Int) = TrustScore(
        totalScore = total,
        maturityScore = 0.0,
        diversityScore = 0.0,
        qualityScore = 0.0,
        activityScore = 0.0,
        totalBadges = 0,
        uniqueMeetups = 0,
        uniqueSigners = 0,
        uniqueCities = 0,
        accountAgeDays = ageDays,
        meetsPromotionThreshold = false,
        promotionReason = "",
        badgeValues = emptyList(),
        currentPhase = BootstrapPhase.KEIMPHASE,
        activeThresholds = TrustConfig.thresholds(BootstrapPhase.KEIMPHASE),
        progress = emptyMap(),
    )
}
