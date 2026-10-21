package space.einundzwanzig.meetup.data.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.data.admin.ClaimFixtures.day
import space.einundzwanzig.meetup.data.admin.ClaimFixtures.nowSeconds
import space.einundzwanzig.meetup.data.admin.ClaimFixtures.organizerBadge
import space.einundzwanzig.meetup.data.admin.ClaimFixtures.proofFor
import space.einundzwanzig.meetup.data.admin.claims.PromotionClaims
import space.einundzwanzig.meetup.data.admin.model.RawClaim
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity

/**
 * Port of `test/promotion_claim_k1_test.dart`: the self-promotion attacks of the security audit
 * (copied proof, unbound proofs, foreign binding, mislabelled signer, one session) and the
 * positive case (three sessions bound by the claimer). The fixtures ([ClaimFixtures]) build the
 * kind-21002 binding by hand, exactly as the Dart helper, not with the production builders.
 */
class PromotionClaimK1Test {

    private lateinit var admin: KeyPair
    private lateinit var claimer: KeyPair
    private lateinit var stranger: KeyPair

    @Before
    fun setUp() {
        admin = KeyPair.generate()
        claimer = KeyPair.generate()
        stranger = KeyPair.generate()
    }

    private fun claimWith(proofs: List<Map<String, Any?>>): RawClaim = RawClaim(
        pubkey = claimer.publicKeyHex,
        createdAt = nowSeconds(),
        content = ClaimFixtures.claimContent(proofs),
    )

    private fun verify(proofs: List<Map<String, Any?>>) =
        PromotionClaims.verifyClaim(claimWith(proofs), setOf(admin.publicKeyHex), nowSeconds())

    @Test
    fun `Positivfall - drei verschiedene Sessions, vom Claimer gebunden`() {
        val result = verify(
            listOf(
                proofFor(organizerBadge(admin, day(30)), boundBy = claimer),
                proofFor(organizerBadge(admin, day(20)), boundBy = claimer),
                proofFor(organizerBadge(admin, day(10)), boundBy = claimer),
            ),
        )
        assertNotNull(result)
        assertEquals(3, result!!.verifiedBadgeCount)
        assertEquals(claimer.publicKeyHex, result.claimerPubkey)
    }

    @Test
    fun `Angriff - derselbe Beweis dreimal kopiert wird abgelehnt`() {
        val one = proofFor(organizerBadge(admin, day(10)), boundBy = claimer)
        assertNull(verify(listOf(one, LinkedHashMap(one), LinkedHashMap(one))))
    }

    @Test
    fun `Angriff - Beweise ohne Teilnehmer-Bindung zaehlen nicht`() {
        assertNull(
            verify(
                listOf(
                    proofFor(organizerBadge(admin, day(30))),
                    proofFor(organizerBadge(admin, day(20))),
                    proofFor(organizerBadge(admin, day(10))),
                ),
            ),
        )
    }

    @Test
    fun `Angriff - Bindung mit fremdem Schluessel zaehlt nicht`() {
        assertNull(
            verify(
                listOf(
                    proofFor(organizerBadge(admin, day(30)), boundBy = stranger),
                    proofFor(organizerBadge(admin, day(20)), boundBy = stranger),
                    proofFor(organizerBadge(admin, day(10)), boundBy = stranger),
                ),
            ),
        )
    }

    @Test
    fun `Angriff - admin_pubkey zeigt auf bekannten Admin, signiert hat ein Fremder`() {
        val label = admin.publicKeyHex
        assertNull(
            verify(
                listOf(
                    proofFor(organizerBadge(stranger, day(30)), boundBy = claimer, adminPubkeyLabel = label),
                    proofFor(organizerBadge(stranger, day(20)), boundBy = claimer, adminPubkeyLabel = label),
                    proofFor(organizerBadge(stranger, day(10)), boundBy = claimer, adminPubkeyLabel = label),
                ),
            ),
        )
    }

    @Test
    fun `Drei Badges aus EINER Session reichen nicht`() {
        // Same UTC day for all three: anchor at noon UTC of the day 10 days ago.
        val t = day(10) / 86400 * 86400 + 12 * 3600
        assertNull(
            verify(
                listOf(
                    proofFor(organizerBadge(admin, t, block = 1), boundBy = claimer),
                    proofFor(organizerBadge(admin, t + 600, block = 2), boundBy = claimer),
                    proofFor(organizerBadge(admin, t + 1200, block = 3), boundBy = claimer),
                ),
            ),
        )
    }

    // ---- Native additions (same helpers) ----

    @Test
    fun `Claim aelter als 90 Tage wird abgelehnt`() {
        val proofs = listOf(
            proofFor(organizerBadge(admin, day(120)), boundBy = claimer),
            proofFor(organizerBadge(admin, day(110)), boundBy = claimer),
            proofFor(organizerBadge(admin, day(100)), boundBy = claimer),
        )
        val old = claimWith(proofs).copy(createdAt = day(91))
        assertNull(PromotionClaims.verifyClaim(old, setOf(admin.publicKeyHex), nowSeconds()))
        val fresh = claimWith(proofs).copy(createdAt = day(89))
        assertNotNull(PromotionClaims.verifyClaim(fresh, setOf(admin.publicKeyHex), nowSeconds()))
    }

    @Test
    fun `unbekannter Signer zaehlt nicht, falscher Typ und leeres Meetup sind ungueltig`() {
        val proofs = listOf(
            proofFor(organizerBadge(admin, day(30)), boundBy = claimer),
            proofFor(organizerBadge(admin, day(20)), boundBy = claimer),
            proofFor(organizerBadge(admin, day(10)), boundBy = claimer),
        )
        assertNull(PromotionClaims.verifyClaim(claimWith(proofs), setOf(stranger.publicKeyHex), nowSeconds()))
        val wrongType = RawClaim(claimer.publicKeyHex, DartJson.encode(mapOf("type" to "x", "meetup" to "A", "proofs" to proofs)), nowSeconds())
        assertNull(PromotionClaims.verifyClaim(wrongType, setOf(admin.publicKeyHex), nowSeconds()))
        val noMeetup = RawClaim(claimer.publicKeyHex, DartJson.encode(mapOf("type" to "admin_claim", "meetup" to "", "proofs" to proofs)), nowSeconds())
        assertNull(PromotionClaims.verifyClaim(noMeetup, setOf(admin.publicKeyHex), nowSeconds()))
        assertNull(PromotionClaims.verifyClaim(RawClaim(claimer.publicKeyHex, "not json", nowSeconds()), setOf(admin.publicKeyHex), nowSeconds()))
    }

    /**
     * Legacy full-format organizer badge (`verifyNostr`) signed at [createdAt] (10 digits, as
     * `isValid` demands), with an extra organizer-signed `c` field: the session day key reads
     * `c` before `created_at`, so only this field can carry a time outside Dart's range.
     */
    private fun legacyProof(org: KeyPair, createdAt: Long, c: Long, block: Long): Map<String, Any?> {
        val content = linkedMapOf<String, Any?>(
            "v" to 2, "type" to "Badge", "meetup_id" to "aschaffenburg-de", "block_height" to block, "c" to c,
        )
        val tags = listOf(listOf("t", "badge"), listOf("meetup", "aschaffenburg-de"), listOf("block", block.toString()))
        val event = EventIds.sign(org, createdAt, BadgeSecurity.KIND_BADGE, tags, BadgeSecurity.canonicalJsonEncode(content))
        val data = content + linkedMapOf("sig" to event.sig, "sig_id" to event.id, "admin_pubkey" to event.pubkey, "created_at" to createdAt)
        val binding = ClaimFixtures.bindBadge(
            claimer,
            mapOf("s" to event.sig, "p" to event.pubkey, "b" to block),
            event.id,
            day(5),
        )
        return linkedMapOf<String, Any?>(
            "meetup" to "aschaffenburg-de",
            "date" to "2026-10-21T12:00:00.000",
            "block" to block,
            "sig" to event.sig,
            "sig_id" to event.id,
            "admin_pubkey" to event.pubkey,
            "sig_version" to 2,
            "sig_content" to DartJson.encode(data),
        ) + binding
    }

    @Test
    fun `Signierzeit ausserhalb des Dart-DateTime-Bereichs - Beweis wird uebersprungen`() {
        val compact = listOf(
            proofFor(organizerBadge(admin, day(30)), boundBy = claimer),
            proofFor(organizerBadge(admin, day(20)), boundBy = claimer),
        )
        // Control: the legacy proof counts with an in-range `c` (its own session).
        assertNotNull(verify(compact + legacyProof(admin, day(10), c = day(10), block = 876_000)))
        // Dart: DateTime.fromMillisecondsSinceEpoch(c * 1000, isUtc: true) throws for
        // |ms| > 8.64e15 inside the per-proof try → proof skipped → only two sessions.
        assertNull(verify(compact + legacyProof(admin, day(10), c = 8_640_000_000_001L, block = 876_001)))
        assertNull(verify(compact + legacyProof(admin, day(10), c = -8_640_000_000_001L, block = 876_002)))
        // The boundary itself is still valid in Dart.
        assertNotNull(verify(compact + legacyProof(admin, day(10), c = 8_640_000_000_000L, block = 876_003)))
    }
}
