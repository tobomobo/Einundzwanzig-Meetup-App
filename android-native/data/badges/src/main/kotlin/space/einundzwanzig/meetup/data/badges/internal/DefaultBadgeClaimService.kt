package space.einundzwanzig.meetup.data.badges.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaimService
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaims
import space.einundzwanzig.meetup.data.badges.claim.ClaimBatchResult
import space.einundzwanzig.meetup.data.badges.claim.ClaimResult
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
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

    /** Single flight: app start and the reputation card may both trigger a claim batch. */
    private val batchMutex = Mutex()

    override suspend fun createClaim(orgSig: String, orgEventId: String, orgPubkey: String, blockHeight: Long): ClaimResult {
        try {
            if (!signer.canSign()) {
                return ClaimResult(success = false, message = "Kein Schlüssel vorhanden — Claim nicht möglich")
            }
            // The signer is asked for created_at == claimed_at (spec 02 §7.4). Signers that cannot
            // honour it yet pick their own; the equality check stays as the guard, and only then
            // one more request is made.
            repeat(MAX_ATTEMPTS) { attempt ->
                val now = timeSource.nowSeconds()
                val content = BadgeClaims.claimContent(orgSig, orgEventId, orgPubkey, blockHeight, now)
                val signed = signer.signEvent(
                    BadgeClaims.KIND_CLAIM,
                    BadgeClaims.claimTags(orgPubkey, blockHeight),
                    content,
                    createdAt = now,
                )
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

    override suspend fun claimUnboundBadges(badges: List<MeetupBadge>): ClaimBatchResult =
        batchMutex.withLock { claimBatch(badges) }

    override suspend fun ensureBadgesClaimed(badges: List<MeetupBadge>): ClaimBatchResult {
        if (badges.none(::needsClaim)) return ClaimBatchResult(badges, 0)
        return batchMutex.withLock {
            // A batch that ran while we waited may already have claimed them.
            if (badges.none(::needsClaim)) ClaimBatchResult(badges, 0) else claimBatch(badges)
        }
    }

    /**
     * Signs claims outside the repository lock (one signer prompt per badge with Amber/bunker),
     * then applies them atomically to the collection as it is **now**: badges scanned meanwhile
     * stay, badges removed meanwhile are not brought back, a badge claimed meanwhile keeps its
     * claim. Must run under [batchMutex].
     */
    private suspend fun claimBatch(badges: List<MeetupBadge>): ClaimBatchResult {
        // Already claimed in the stored collection (e.g. by a batch that finished while we waited).
        val storedClaimed = repository.badges.value.filter { it.isClaimed }.mapTo(HashSet(), BadgeIdentity::unionKey)
        val claims = LinkedHashMap<String, MeetupBadge>()
        for (badge in badges) {
            if (!needsClaim(badge)) continue
            val key = BadgeIdentity.unionKey(badge)
            if (key in storedClaimed || key in claims) continue
            val result = createClaim(badge.sig, badge.sigId, badge.adminPubkey, badge.blockHeight)
            if (result.success) {
                claims[key] = badge.withClaim(
                    claimSig = result.claimSig,
                    claimEventId = result.claimEventId,
                    claimPubkey = result.claimPubkey,
                    claimTimestamp = result.claimTimestamp,
                    isRetroactive = true,
                    // Dart resets this to true; keeping it preserves the presence evidence (spec 02 §14 item 5).
                    presenceVerified = badge.presenceVerified,
                )
            }
        }
        if (claims.isEmpty()) return ClaimBatchResult(badges, 0)

        var applied = 0
        val saved = repository.update { current ->
            applied = 0
            val next = current.map { stored ->
                val claimed = claims[BadgeIdentity.unionKey(stored)]
                if (claimed == null || stored.isClaimed) {
                    stored
                } else {
                    applied++
                    claimed
                }
            }
            if (applied == 0) null else next
        }
        if (!saved) {
            AppLogger.warn(TAG, "${claims.size} Claims nicht gespeichert.")
            return ClaimBatchResult(badges, 0)
        }
        if (applied < claims.size) {
            AppLogger.debug(TAG, "${claims.size - applied} Claims verworfen, Badge nicht mehr in der Sammlung")
        }
        return if (applied == 0) ClaimBatchResult(badges, 0) else ClaimBatchResult(repository.badges.value, applied)
    }

    private fun needsClaim(badge: MeetupBadge): Boolean = badge.hasCryptoProof && !badge.isClaimed

    private companion object {
        const val TAG = "BadgeClaim"
        const val MAX_ATTEMPTS = 2
    }
}
