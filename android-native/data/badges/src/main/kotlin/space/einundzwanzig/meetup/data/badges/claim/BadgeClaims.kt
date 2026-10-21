package space.einundzwanzig.meetup.data.badges.claim

import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity

/**
 * Result of a claim creation (Dart `ClaimResult`).
 *
 * @property claimTimestamp the claim's `claimed_at` == event `created_at` (unix seconds).
 * @property message German status text (`Badge gebunden ✓` / reason).
 */
data class ClaimResult(
    val success: Boolean,
    val claimSig: String = "",
    val claimEventId: String = "",
    val claimPubkey: String = "",
    val claimTimestamp: Long = 0,
    val message: String = "",
)

/** Result of a claim verification (Dart `ClaimVerifyResult`). */
data class ClaimVerifyResult(
    val isValid: Boolean,
    val claimerNpub: String = "",
    val message: String = "",
)

/**
 * Result of retroactive claiming.
 *
 * @property badges the collection with newly claimed badges replaced in place (same order).
 * @property claimedCount how many were claimed **and** saved (0 if the save failed).
 */
data class ClaimBatchResult(val badges: List<MeetupBadge>, val claimedCount: Int)

/**
 * The kind-21002 claim format (Dart `BadgeClaimService`, spec 02 §7.4): content, tags and the
 * pure verification. Byte-identical to Flutter; used by promotion verifiers in other domains.
 */
object BadgeClaims {

    /** Kind of the claim event. */
    const val KIND_CLAIM = 21002

    /**
     * Canonical claim content
     * `{"action":"claim_badge","block_height":…,"claimed_at":…,"org_event_id":"…","org_pubkey":"…","org_sig":"…"}`.
     */
    fun claimContent(orgSig: String, orgEventId: String, orgPubkey: String, blockHeight: Long, claimedAt: Long): String =
        BadgeSecurity.canonicalJsonEncode(
            linkedMapOf(
                "action" to "claim_badge",
                "org_sig" to orgSig,
                "org_event_id" to orgEventId,
                "org_pubkey" to orgPubkey,
                "block_height" to blockHeight,
                "claimed_at" to claimedAt,
            ),
        )

    /** Claim tags `[["t","badge_claim"],["p",orgPubkey],["block","<blockHeight>"]]`. */
    fun claimTags(orgPubkey: String, blockHeight: Long): List<List<String>> = listOf(
        listOf("t", "badge_claim"),
        listOf("p", orgPubkey),
        listOf("block", blockHeight.toString()),
    )

    /**
     * Dart `verifyClaim`: rebuilds the content with `claimed_at = claimTimestamp`, recomputes the
     * id over `[0, claimPubkey, claimTimestamp, 21002, tags, content]`, compares it with
     * [claimEventId], then checks the BIP-340 signature. Messages are Flutter's.
     */
    fun verifyClaim(
        claimSig: String,
        claimEventId: String,
        claimPubkey: String,
        claimTimestamp: Long,
        orgSig: String,
        orgEventId: String,
        orgPubkey: String,
        blockHeight: Long,
    ): ClaimVerifyResult {
        if (claimSig.isEmpty() || claimEventId.isEmpty() || claimPubkey.isEmpty()) {
            return ClaimVerifyResult(false, message = "Fehlende Claim-Daten")
        }
        val content = claimContent(orgSig, orgEventId, orgPubkey, blockHeight, claimTimestamp)
        val tags = claimTags(orgPubkey, blockHeight)
        // Dart hashes the pubkey as given (no lower-casing) for this comparison.
        val expected = if (claimPubkey == claimPubkey.lowercase()) {
            EventIds.compute(claimPubkey, claimTimestamp, KIND_CLAIM, tags, content)
        } else {
            null
        }
        if (expected != claimEventId) {
            return ClaimVerifyResult(false, message = "Claim Event-ID stimmt nicht überein")
        }
        if (!EventIds.isValid(claimEventId, claimPubkey, claimTimestamp, KIND_CLAIM, tags, content, claimSig)) {
            return ClaimVerifyResult(false, message = "Claim-Verifikation fehlgeschlagen: Invalid event")
        }
        val npub = BadgeSecurity.npubOrEmpty(claimPubkey)
        return ClaimVerifyResult(true, npub, "Claim verifiziert ✓ — Gebunden an $npub")
    }

    /** Dart `verifyBadgeClaim`: [verifyClaim] with the badge's own fields, `Badge hat keinen Claim` if unclaimed. */
    fun verifyBadgeClaim(badge: MeetupBadge): ClaimVerifyResult {
        if (!badge.isClaimed) return ClaimVerifyResult(false, message = "Badge hat keinen Claim")
        return verifyClaim(
            claimSig = badge.claimSig,
            claimEventId = badge.claimEventId,
            claimPubkey = badge.claimPubkey,
            claimTimestamp = badge.claimTimestamp,
            orgSig = badge.sig,
            orgEventId = badge.sigId,
            orgPubkey = badge.adminPubkey,
            blockHeight = badge.blockHeight,
        )
    }
}
