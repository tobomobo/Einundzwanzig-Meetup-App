package space.einundzwanzig.meetup.data.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.data.admin.claims.PromotionClaims
import space.einundzwanzig.meetup.data.admin.model.RawClaim
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaims
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity

/**
 * Port of `test/promotion_claim_k1_test.dart`: the self-promotion attacks of the security audit
 * (copied proof, unbound proofs, foreign binding, mislabelled signer, one session) and the
 * positive case (three sessions bound by the claimer).
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

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    /** Compact organizer badge signed by [org]; [createdAt] determines the session. */
    private fun organizerBadge(
        org: KeyPair,
        createdAt: Long,
        meetupId: String = "aschaffenburg-de",
        block: Long = 875000,
    ): Map<String, Any?> {
        val content = linkedMapOf<String, Any?>("v" to 2, "t" to "B", "m" to meetupId, "b" to block, "x" to createdAt + 4 * 3600)
        val contentJson = BadgeSecurity.canonicalJsonEncode(content)
        val tags = listOf(listOf("t", "badge"), listOf("m", meetupId))
        val event = EventIds.sign(org, createdAt, 21000, tags, contentJson)
        return content + linkedMapOf("c" to event.createdAt, "p" to event.pubkey, "s" to event.sig)
    }

    /** Participant binding (kind 21002) as `BadgeClaimService.createClaim`. */
    private fun bindBadge(who: KeyPair, badge: Map<String, Any?>, sigId: String, claimedAt: Long): Map<String, Any?> {
        val orgPubkey = badge["p"] as String
        val block = (badge["b"] as Number).toLong()
        val contentJson = BadgeClaims.claimContent(badge["s"] as String, sigId, orgPubkey, block, claimedAt)
        val ev = EventIds.sign(who, claimedAt, 21002, BadgeClaims.claimTags(orgPubkey, block), contentJson)
        return linkedMapOf("claim_sig" to ev.sig, "claim_event_id" to ev.id, "claim_timestamp" to claimedAt)
    }

    private fun proofFor(badge: Map<String, Any?>, boundBy: KeyPair? = null, adminPubkeyLabel: String? = null): Map<String, Any?> {
        val sigId = Sha256.hexOfUtf8(badge["s"] as String)
        val proof = linkedMapOf<String, Any?>(
            "meetup" to badge["m"],
            "date" to "2026-10-21T12:00:00.000",
            "block" to badge["b"],
            "sig" to badge["s"],
            "sig_id" to sigId,
            "admin_pubkey" to (adminPubkeyLabel ?: badge["p"]),
            "sig_version" to 2,
            "sig_content" to DartJson.encode(badge),
        )
        if (boundBy != null) proof += bindBadge(boundBy, badge, sigId, (badge["c"] as Long) + 60)
        return proof
    }

    private fun claimWith(proofs: List<Map<String, Any?>>): RawClaim = RawClaim(
        pubkey = claimer.publicKeyHex,
        createdAt = nowSeconds(),
        content = DartJson.encode(
            linkedMapOf("type" to "admin_claim", "version" to 1, "meetup" to "Aschaffenburg", "proofs" to proofs),
        ),
    )

    private fun day(n: Int): Long = nowSeconds() - n * 86400L

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
}
