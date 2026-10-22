package space.einundzwanzig.meetup.data.admin.internal

import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.data.admin.model.AdminCheckResult
import space.einundzwanzig.meetup.data.admin.model.AdminVerification
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistry
import space.einundzwanzig.meetup.data.admin.signing.AdminSigner
import space.einundzwanzig.meetup.data.admin.verify.AdminReVerification
import space.einundzwanzig.meetup.data.admin.verify.AdminStatusVerifier
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import javax.inject.Inject
import javax.inject.Singleton

/** Port of `lib/services/admin_status_verifier.dart` and `UserProfile.reVerifyAdmin`. */
@Singleton
internal class DefaultAdminStatusVerifier @Inject constructor(
    private val signer: AdminSigner,
    private val trustScore: TrustScoreCalculator,
    private val registry: AdminRegistry,
    private val profiles: UserProfileRepository,
) : AdminStatusVerifier {

    override suspend fun verifyAdminStatus(badges: List<MeetupBadge>): AdminVerification {
        if (!signer.canSign()) return AdminVerification.NO_KEY

        if (badges.isNotEmpty()) {
            // calculate(badges) uses the earliest date over ALL badges, as Dart's sorted.first.
            val score = trustScore.calculate(badges)
            if (score.meetsPromotionThreshold) {
                return AdminVerification(
                    isAdmin = true,
                    source = AdminVerification.SOURCE_TRUST_SCORE,
                    reason = "Trust Score ${score.totalScore.toStringAsFixed(1)} " +
                        "erfüllt Schwellenwert (${score.activeThresholds.promotionScore.toDartString()}).",
                )
            }
        }

        // Only the real (hard-coded) super admin counts for the SELF check; registry cache and
        // relay hits are self-published claims and would be circular.
        val npub = signer.npub()
        if (!npub.isNullOrEmpty()) {
            try {
                val result = registry.checkAdmin(npub)
                if (result.isAdmin && result.source == AdminCheckResult.SOURCE_SUPER_ADMIN) {
                    return AdminVerification(
                        isAdmin = true,
                        source = AdminVerification.SOURCE_SEED_ADMIN,
                        reason = "Super-Admin (${result.source}).",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fail closed: registry unreachable → no admin status.
            }
        }
        return AdminVerification.NOT_ADMIN
    }

    override suspend fun reVerifyAdmin(badges: List<MeetupBadge>): AdminReVerification {
        val verification = verifyAdminStatus(badges)
        val profile = profiles.update { it.withAdminVerification(verification.isAdmin, verification.source) }
        return AdminReVerification(verification, profile)
    }
}
