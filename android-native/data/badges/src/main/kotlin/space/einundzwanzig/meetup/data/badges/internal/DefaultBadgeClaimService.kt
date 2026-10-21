package space.einundzwanzig.meetup.data.badges.internal

import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaimService
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaims
import space.einundzwanzig.meetup.data.badges.claim.ClaimBatchResult
import space.einundzwanzig.meetup.data.badges.claim.ClaimResult
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.BadgeSigner
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultBadgeClaimService @Inject constructor(
    private val signer: BadgeSigner,
    private val repository: BadgeRepository,
    private val timeSource: TimeSource,
) : BadgeClaimService {

    override suspend fun createClaim(orgSig: String, orgEventId: String, orgPubkey: String, blockHeight: Long): ClaimResult {
        try {
            if (!signer.canSign()) {
                return ClaimResult(success = false, message = "Kein Schlüssel vorhanden — Claim nicht möglich")
            }
            repeat(MAX_ATTEMPTS) { attempt ->
                val now = timeSource.nowSeconds()
                val content = BadgeClaims.claimContent(orgSig, orgEventId, orgPubkey, blockHeight, now)
                val signed = signer.signEvent(BadgeClaims.KIND_CLAIM, BadgeClaims.claimTags(orgPubkey, blockHeight), content)
                if (signed.createdAt == now) {
                    return ClaimResult(
                        success = true,
                        claimSig = signed.sig,
                        claimEventId = signed.id,
                        claimPubkey = signed.pubkey,
                        claimTimestamp = now,
                        message = "Badge gebunden ✓",
                    )
                }
                AppLogger.warn(
                    TAG,
                    "Claim-Zeitstempel weicht ab (Versuch ${attempt + 1}): signiert ${signed.createdAt}, erwartet $now",
                )
            }
            return ClaimResult(success = false, message = "Claim-Fehler: Zeitstempel des Signers weicht ab")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ClaimResult(success = false, message = "Claim-Fehler: ${e.message ?: e}")
        }
    }

    override suspend fun claimUnboundBadges(badges: List<MeetupBadge>): ClaimBatchResult {
        val list = badges.toMutableList()
        var claimed = 0
        for (i in list.indices) {
            val badge = list[i]
            if (!badge.hasCryptoProof || badge.isClaimed) continue
            val result = createClaim(badge.sig, badge.sigId, badge.adminPubkey, badge.blockHeight)
            if (result.success) {
                list[i] = badge.withClaim(
                    claimSig = result.claimSig,
                    claimEventId = result.claimEventId,
                    claimPubkey = result.claimPubkey,
                    claimTimestamp = result.claimTimestamp,
                    isRetroactive = true,
                    // Dart resets this to true; keeping it preserves the presence evidence (spec 02 §14 item 5).
                    presenceVerified = badge.presenceVerified,
                )
                claimed++
            }
        }
        if (claimed > 0 && !repository.saveBadges(list)) {
            AppLogger.warn(TAG, "$claimed Claims nicht gespeichert.")
            return ClaimBatchResult(badges, 0)
        }
        return ClaimBatchResult(list, claimed)
    }

    override suspend fun ensureBadgesClaimed(badges: List<MeetupBadge>): ClaimBatchResult {
        if (badges.none { it.hasCryptoProof && !it.isClaimed }) return ClaimBatchResult(badges, 0)
        return claimUnboundBadges(badges)
    }

    private companion object {
        const val TAG = "BadgeClaim"
        const val MAX_ATTEMPTS = 2
    }
}
