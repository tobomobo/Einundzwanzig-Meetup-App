package space.einundzwanzig.meetup.data.admin.claims

import space.einundzwanzig.meetup.data.admin.model.RawClaim
import space.einundzwanzig.meetup.data.admin.model.VerifiedClaim
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge

/**
 * "Proof of reputation" admin claims (Dart `PromotionClaimService`, spec 05 §8.5): kind 30021
 * `d=einundzwanzig-admin-claim` events carrying the claimer's own fully bound badge proofs.
 *
 * Verified claims are stored as **organic** information only (`organic_admin_claims`); they
 * never enter the admin registry and never count as known admins (Audit K1).
 */
interface PromotionClaimService {

    /**
     * Dart `publishAdminClaim(badges, meetupName)`: signs and publishes the own claim if at least
     * three badges are fully bound by the own key. Sequential fire-and-linger publish to the four
     * hard-coded relays (connect 5 s, send, wait 2 s, close). `true` if any relay took it.
     * Signing errors propagate (Home swallows them).
     */
    suspend fun publishAdminClaim(badges: List<MeetupBadge>, meetupName: String): Boolean

    /**
     * Dart `syncOrganicAdmins()` (Home start, background): fetches claims (first relay with a
     * non-empty answer wins), verifies them against the super admin + registry and stores the
     * newest valid claim per claimer. Nothing is overwritten when no claims were fetched or an
     * error occurred. Never throws (except cancellation).
     */
    suspend fun syncOrganicAdmins(): List<VerifiedClaim>

    /** Dart `getOrganicAdmins()`: the stored organic claims, `[]` if missing or unreadable. */
    suspend fun getOrganicAdmins(): List<VerifiedClaim>

    /** [PromotionClaims.verifyClaim] with the injected clock, on the default (CPU) dispatcher. */
    suspend fun verifyClaim(claim: RawClaim, knownAdminPubkeys: Set<String>): VerifiedClaim?
}
