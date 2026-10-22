package space.einundzwanzig.meetup.data.reputation.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.badges.model.BadgeProofs
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.badges.security.BadgeSigner
import space.einundzwanzig.meetup.data.badges.security.QrSignResult
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofRepository
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQr
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrCodec
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrGenerator
import space.einundzwanzig.meetup.data.trust.score.TrustScore
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import javax.inject.Inject

/** [ReputationQrGenerator] (Dart `_generateQRData`). */
internal class DefaultReputationQrGenerator @Inject constructor(
    private val badgeRepository: BadgeRepository,
    private val profiles: UserProfileRepository,
    private val platformProofs: PlatformProofRepository,
    private val calculator: TrustScoreCalculator,
    private val signer: BadgeSigner,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : ReputationQrGenerator {

    override suspend fun generateForCurrentBadges(): ReputationQr {
        // Cold start (deep link/shortcut) before anything loaded the wallet: an empty value would
        // show "no badges" to a user who has some. loadBadges() is the repository's own loader.
        val badges = if (badgeRepository.loaded.value) badgeRepository.badges.value else badgeRepository.loadBadges()
        return generate(badges)
    }

    override suspend fun generate(badges: List<MeetupBadge>): ReputationQr {
        val user = profiles.load()
        val proofs = platformProofs.getSavedProofs()
        if (badges.isEmpty()) return ReputationQr.Empty(user, proofs.size)

        val prepared = withContext(cpu) {
            val score = calculator.calculate(badges)
            val payload = ReputationQrCodec.buildPayload(user, badges, score, proofs, time.nowMillis())
            Prepared(
                json = ReputationQrCodec.payloadJson(payload),
                score = score,
                verified = BadgeProofs.countVerifiedBadges(badges),
                bound = BadgeProofs.countBoundBadges(badges),
                counted = badges.count { it.isNostrSigned && !it.isOrganizer },
                organizers = ReputationQr.organizerMeetupCount(badges),
            )
        }

        var signError: Throwable? = null
        val sign = try {
            BadgeSecurity.signQrV3(signer, prepared.json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "QR-Signatur fehlgeschlagen: $e")
            signError = e
            QrSignResult("", "", 0, "", false)
        }

        return ReputationQr.Content(
            user = user,
            platformProofCount = proofs.size,
            qrData = ReputationQrCodec.encode(prepared.json, sign),
            isSigned = sign.isNostr,
            signError = signError,
            score = prepared.score,
            verifiedBadgeCount = prepared.verified,
            boundBadgeCount = prepared.bound,
            countedBadgeCount = prepared.counted,
            organizerMeetupCount = prepared.organizers,
        )
    }

    private class Prepared(
        val json: String,
        val score: TrustScore,
        val verified: Int,
        val bound: Int,
        val counted: Int,
        val organizers: Int,
    )

    private companion object {
        const val TAG = "ReputationQR"
    }
}
