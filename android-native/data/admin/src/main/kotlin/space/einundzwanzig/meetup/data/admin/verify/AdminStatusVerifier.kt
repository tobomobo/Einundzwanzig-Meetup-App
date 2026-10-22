package space.einundzwanzig.meetup.data.admin.verify

import space.einundzwanzig.meetup.data.admin.model.AdminVerification
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.identity.profile.UserProfile

/**
 * Cryptographic self verification of the organizer status (Dart `AdminStatusVerifier`,
 * Security Audit C2, spec 05 §8.3). The stored `is_admin` flags are only a UI cache.
 */
interface AdminStatusVerifier {

    /**
     * Dart `verifyAdminStatus(badges)`:
     * 1. no signer → `no_key`;
     * 2. trust score of the badges meets the promotion threshold → `trust_score`;
     * 3. the own npub is the super admin (bootstrap phase) → `seed_admin`;
     * 4. otherwise `not_admin`.
     *
     * Registry `local_cache`/`nostr_relay` hits are deliberately ignored (they would be
     * self-published, circular). Registry errors fail closed.
     */
    suspend fun verifyAdminStatus(badges: List<MeetupBadge>): AdminVerification

    /** Dart `isVerifiedAdmin(badges)`: `verifyAdminStatus(badges).isAdmin` (for guards). */
    suspend fun isVerifiedAdmin(badges: List<MeetupBadge>): Boolean = verifyAdminStatus(badges).isAdmin

    /**
     * Dart `UserProfile.reVerifyAdmin(badges)`: verifies, then updates and saves the profile
     * (`seed_admin` → `adminViaSeed = isAdmin`; otherwise `adminViaSeed = false`,
     * `adminViaVouch = isAdmin`; `isAdminVerified = isAdmin`; the portal flag is untouched).
     *
     * Home then calls `PromotionClaimService.publishAdminClaim` when
     * [AdminVerification.shouldPublishClaim] is true.
     */
    suspend fun reVerifyAdmin(badges: List<MeetupBadge>): AdminReVerification
}

/**
 * Result of [AdminStatusVerifier.reVerifyAdmin].
 *
 * @property profile the saved profile; keep it, its `adminCryptoVerified` (`true`) lives in memory only.
 */
data class AdminReVerification(
    val verification: AdminVerification,
    val profile: UserProfile,
)
