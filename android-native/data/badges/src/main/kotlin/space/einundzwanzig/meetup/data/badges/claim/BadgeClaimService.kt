package space.einundzwanzig.meetup.data.badges.claim

import space.einundzwanzig.meetup.data.badges.model.MeetupBadge

/**
 * Binds badges to their collector with a kind-21002 claim (Dart `BadgeClaimService`, spec 02
 * §7.4/§8.11). Inject it (`@Singleton`).
 */
interface BadgeClaimService {

    /**
     * Signs a claim for a scanned badge with the active identity. `claimed_at` and the event's
     * `created_at` are guaranteed equal: if a (remote) signer returns another `created_at`, the
     * claim is requested once more and otherwise reported as failure (a mismatching claim would
     * be unverifiable; spec 02 §7.4).
     *
     * Never throws (except cancellation); failures come back as `success = false` with Flutter's
     * message (`Kein Schlüssel vorhanden — Claim nicht möglich`, `Claim-Fehler: …`).
     *
     * @param orgEventId the badge `sigId` (empty for compact badges).
     */
    suspend fun createClaim(orgSig: String, orgEventId: String, orgPubkey: String, blockHeight: Long): ClaimResult

    /** [BadgeClaims.verifyClaim]. */
    fun verifyClaim(
        claimSig: String,
        claimEventId: String,
        claimPubkey: String,
        claimTimestamp: Long,
        orgSig: String,
        orgEventId: String,
        orgPubkey: String,
        blockHeight: Long,
    ): ClaimVerifyResult = BadgeClaims.verifyClaim(
        claimSig, claimEventId, claimPubkey, claimTimestamp, orgSig, orgEventId, orgPubkey, blockHeight,
    )

    /** [BadgeClaims.verifyBadgeClaim]. */
    fun verifyBadgeClaim(badge: MeetupBadge): ClaimVerifyResult = BadgeClaims.verifyBadgeClaim(badge)

    /**
     * Dart `claimUnboundBadges`: claims every badge with an organizer proof but no claim, in
     * list order, marks it `isRetroactive` (keeping its `presenceVerified`), and saves the list
     * through the badge repository if anything was claimed. A failed save returns count 0
     * (claims are retried on the next start).
     */
    suspend fun claimUnboundBadges(badges: List<MeetupBadge>): ClaimBatchResult

    /**
     * Dart `ensureBadgesClaimed`: no-op (count 0, same list) if nothing needs a claim, else
     * [claimUnboundBadges]. Call it at app start (only when the wallet is readable) and when the
     * reputation card loads. With Amber/bunker this prompts once per unclaimed badge.
     */
    suspend fun ensureBadgesClaimed(badges: List<MeetupBadge>): ClaimBatchResult
}
