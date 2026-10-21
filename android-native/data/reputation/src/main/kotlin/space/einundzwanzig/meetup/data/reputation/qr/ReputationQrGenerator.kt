package space.einundzwanzig.meetup.data.reputation.qr

import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.trust.score.TrustScore

/**
 * Produces the signed reputation QR of the "REPUTATION" screen (Dart
 * `ReputationQRScreen._generateQRData`, spec 04 §2.1, §7.5, §7.6). Inject the `@Singleton`.
 *
 * Rendering is the feature's job: draw [ReputationQr.Content.qrData] with `:core:ui`
 * `QrCode(content, size = 220.dp, errorCorrection = L)` (byte mode, white background), which
 * encodes the same bytes as Flutter's `qr_flutter`.
 */
interface ReputationQrGenerator {

    /**
     * Builds and signs the QR for [badges] (the in-memory wallet, `BadgeRepository.badges.value`).
     * Loads the profile and the saved platform proofs. Signs kind 21001 (tags `[]`) through the
     * SigningService — with Amber/NIP-46 this can prompt the user, so call it from a ViewModel.
     *
     * Signer failure FIX (spec 04 §2.1 step 4): a signer failure never throws; instead of
     * Flutter's endless spinner the result is the unsigned `21:` code with
     * [ReputationQr.Content.signError] set (show a snackbar / sign-in hint, R-8). Storage errors
     * while loading the profile or the saved proofs propagate (show the generic error state).
     */
    suspend fun generate(badges: List<MeetupBadge>): ReputationQr

    /**
     * [generate] for the repository's current `badges` value; loads the wallet first if nothing
     * has loaded it in this process yet. An empty result while `BadgeRepository.badgesUnreadable`
     * is set is NOT an empty wallet: show `badgesUnreadableBody`.
     */
    suspend fun generateForCurrentBadges(): ReputationQr
}

/** Everything the REPUTATION screen shows. */
sealed interface ReputationQr {
    val user: UserProfile

    /** `PlatformProofRepository.getSavedProofs().size` (`repPlatformLinksActive`). */
    val platformProofCount: Int

    /**
     * Dart `hasIdentity`: npub, telegram or twitter set. Drives the warning banner
     * (`reputationNoIdentity`) when `false`.
     */
    val hasIdentity: Boolean
        get() = user.nostrNpub.isNotEmpty() || user.telegramHandle.isNotEmpty() || user.twitterHandle.isNotEmpty()

    /** No badges: the empty view (`reputationNoBadges`, or `badgesUnreadableBody` if unreadable). */
    data class Empty(override val user: UserProfile, override val platformProofCount: Int) : ReputationQr

    /**
     * A generated QR.
     *
     * @property qrData the string to render (`21v3:…`, or the rejected `21:…` when unsigned).
     * @property isSigned the QR carries a Nostr signature. Use it for the card label
     *   (`reputationSchnorrSigned` / `reputationUnsigned`) — FIX of R-8 (Flutter used [hasIdentity]).
     * @property signError why signing failed (`SigningException.error` gives a localizable reason);
     *   `null` when signed or when no signer is configured.
     * @property score the local Trust Score over all badges (card: `totalScore`, `level`,
     *   `uniqueMeetups`, `uniqueSigners`; stats: `totalBadges`).
     * @property verifiedBadgeCount `countVerifiedBadges(all)` (card badge count, proof block).
     * @property boundBadgeCount `countBoundBadges(all)` (stats tile "Gebunden", proof block).
     * @property countedBadgeCount `isNostrSigned && !isOrganizer` count, the proof block's `total`.
     * @property organizerMeetupCount distinct organizer markers (`reputationOrganizerNote`, shown if > 0).
     */
    data class Content(
        override val user: UserProfile,
        override val platformProofCount: Int,
        val qrData: String,
        val isSigned: Boolean,
        val signError: Throwable?,
        val score: TrustScore,
        val verifiedBadgeCount: Int,
        val boundBadgeCount: Int,
        val countedBadgeCount: Int,
        val organizerMeetupCount: Int,
    ) : ReputationQr {
        /** Proof-status block state (spec 04 §8.7), first match wins. */
        val proofStatus: ProofStatus
            get() {
                val total = countedBadgeCount
                return when {
                    total > 0 && boundBadgeCount == total -> ProofStatus.ALL_BOUND
                    boundBadgeCount > 0 -> ProofStatus.PARTLY_BOUND
                    total > 0 && verifiedBadgeCount == total -> ProofStatus.ALL_VERIFIED
                    verifiedBadgeCount > 0 -> ProofStatus.SOME_VERIFIED
                    else -> ProofStatus.NONE
                }
            }
    }

    /**
     * The five proof-status states. Strings (pass `(total, bound)` / `(total, verified)`, R-17):
     * [ALL_BOUND] `repAllBound(total)`; [PARTLY_BOUND] `repBoundOf(total, bound)` +
     * (`verified > bound` ? `repBoundExtra(verified)` : ""); [ALL_VERIFIED] `repAllVerified(total)`;
     * [SOME_VERIFIED] `repVerifiedSchnorr(total, verified)`; [NONE] `reputationNoProofs`.
     */
    enum class ProofStatus { ALL_BOUND, PARTLY_BOUND, ALL_VERIFIED, SOME_VERIFIED, NONE }

    companion object {
        /** Spec 04 §8.11: distinct `meetupEventId` (or `id`) over organizer markers. */
        fun organizerMeetupCount(badges: List<MeetupBadge>): Int =
            badges.filter { it.isOrganizer }.mapTo(HashSet()) { it.meetupEventId.ifEmpty { it.id } }.size
    }
}
