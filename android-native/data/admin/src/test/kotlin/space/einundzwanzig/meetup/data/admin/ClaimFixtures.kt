package space.einundzwanzig.meetup.data.admin

import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity

/**
 * Helpers of `test/promotion_claim_k1_test.dart`, spelled out independently of the production
 * builders (BadgeClaims/PromotionClaims), so a key-name or tag error there cannot hide itself.
 */
object ClaimFixtures {

    fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    fun day(n: Int): Long = nowSeconds() - n * 86400L

    /** Compact organizer badge signed by [org]; [createdAt] determines the session. */
    fun organizerBadge(
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

    /** Participant binding (kind 21002) as `BadgeClaimService.createClaim` — the Dart helper verbatim. */
    fun bindBadge(who: KeyPair, badge: Map<String, Any?>, sigId: String, claimedAt: Long): Map<String, Any?> {
        val claimContent = linkedMapOf(
            "action" to "claim_badge",
            "org_sig" to badge["s"],
            "org_event_id" to sigId,
            "org_pubkey" to badge["p"],
            "block_height" to badge["b"],
            "claimed_at" to claimedAt,
        )
        val contentJson = BadgeSecurity.canonicalJsonEncode(claimContent)
        val tags = listOf(
            listOf("t", "badge_claim"),
            listOf("p", badge["p"] as String),
            listOf("block", badge["b"].toString()),
        )
        val ev = EventIds.sign(who, claimedAt, 21002, tags, contentJson)
        return linkedMapOf("claim_sig" to ev.sig, "claim_event_id" to ev.id, "claim_timestamp" to claimedAt)
    }

    fun proofFor(badge: Map<String, Any?>, boundBy: KeyPair? = null, adminPubkeyLabel: String? = null): Map<String, Any?> {
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

    fun claimContent(proofs: List<Map<String, Any?>>, meetup: String = "Aschaffenburg"): String = DartJson.encode(
        linkedMapOf("type" to "admin_claim", "version" to 1, "meetup" to meetup, "proofs" to proofs),
    )

    /** Three sessions of [admin], bound by [claimer]: a claim that verifies. */
    fun validProofs(admin: KeyPair, claimer: KeyPair): List<Map<String, Any?>> = listOf(
        proofFor(organizerBadge(admin, day(30)), boundBy = claimer),
        proofFor(organizerBadge(admin, day(20)), boundBy = claimer),
        proofFor(organizerBadge(admin, day(10)), boundBy = claimer),
    )

    /** A signed kind-30021 claim event of [claimer] (as relays deliver it). */
    fun claimEvent(admin: KeyPair, claimer: KeyPair, createdAt: Long = nowSeconds(), meetup: String = "Aschaffenburg"): NostrEvent =
        EventIds.sign(
            claimer,
            createdAt,
            AdminConstants.CLAIM_KIND,
            listOf(listOf("d", AdminConstants.CLAIM_D_TAG), listOf("meetup", meetup)),
            claimContent(validProofs(admin, claimer), meetup),
        )
}
