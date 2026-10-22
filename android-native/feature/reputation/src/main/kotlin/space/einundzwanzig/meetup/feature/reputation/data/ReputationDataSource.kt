package space.einundzwanzig.meetup.feature.reputation.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaimService
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Result
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Service
import space.einundzwanzig.meetup.data.proofs.platform.ProofVerifyResult
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService
import space.einundzwanzig.meetup.data.reputation.event.ReputationEvent
import space.einundzwanzig.meetup.data.reputation.publish.PublishResult
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQr
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrGenerator
import space.einundzwanzig.meetup.data.reputation.verify.ReputationProofVerifier
import space.einundzwanzig.meetup.data.socialgraph.coattendance.CoAttendanceRepository
import space.einundzwanzig.meetup.data.socialgraph.coattendance.PresenceCheck
import space.einundzwanzig.meetup.data.socialgraph.social.SocialAnalysis
import space.einundzwanzig.meetup.data.socialgraph.social.SocialGraphService
import space.einundzwanzig.meetup.data.trust.score.TrustScore
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import javax.inject.Inject

/** What the "Reputations-Profil" card shows (spec 04 §2.2 load steps 1–3). */
internal data class ReputationCardData(val user: UserProfile, val score: TrustScore)

/**
 * The calls the reputation screens make into the data layer (spec 04 §2), bundled behind one
 * seam so the ViewModels can be tested with a fake. Pure delegation: no logic of its own.
 */
internal interface ReputationDataSource {
    /** [ReputationQrGenerator.generateForCurrentBadges] (REPUTATION screen, spec 04 §2.1). */
    suspend fun generateQr(): ReputationQr

    /** `BadgeRepository.badgesUnreadable` (empty view text, share action disabled). */
    fun badgesUnreadable(): Boolean

    /** Forced publish of the in-memory wallet with the saved platform proofs (spec 04 §2.1.2). */
    suspend fun publishForced(): PublishResult

    /**
     * Card load (spec 04 §2.2): profile, stored badges, silent `ensureBadgesClaimed` (the
     * repository emits the updated list, R-22), Trust Score over the claimed collection.
     */
    suspend fun loadCard(): ReputationCardData

    /** [SocialGraphService.analyze] for the layers. */
    suspend fun analyzeSocial(pubkeyHex: String): SocialAnalysis

    /** [ZapVerificationService.analyzeZapActivity] with `useCache = false` (spec 04 §2.3). */
    suspend fun analyzeZaps(pubkeyHex: String): ZapStats

    /**
     * NIP-05 of [pubkeyHex]: `fetchNip05FromProfile(hex, activeRelays)`, then `verify` if
     * non-empty; `null` when the profile has no NIP-05.
     */
    suspend fun checkNip05(pubkeyHex: String): Nip05Result?

    /** [ReputationProofVerifier.verifyProofString] (spec 04 §8.10). */
    suspend fun verifyProofString(input: String): ProofVerifyResult<ReputationEvent>

    /** Profile npub (`UserProfile.nostrNpub`), as Flutter's VerifyPerson uses it. */
    suspend fun myNpub(): String

    /** [CoAttendanceRepository.verifyPerson] (default max depth 6). */
    suspend fun verifyPerson(myNpub: String, targetNpub: String): PresenceCheck

    /** Dart `npubToHex`; `null` if [npub] does not decode to a pubkey. */
    fun npubToHex(npub: String): String?

    /** `NostrService.isValidNpub`: `npub1` prefix and a 32-byte bech32 payload. */
    fun isValidNpub(npub: String): Boolean
}

internal class DefaultReputationDataSource @Inject constructor(
    private val qrGenerator: ReputationQrGenerator,
    private val publisher: ReputationPublisher,
    private val badges: BadgeRepository,
    private val claims: BadgeClaimService,
    private val calculator: TrustScoreCalculator,
    private val profiles: UserProfileRepository,
    private val social: SocialGraphService,
    private val zaps: ZapVerificationService,
    private val nip05: Nip05Service,
    private val relays: RelayConfig,
    private val verifier: ReputationProofVerifier,
    private val coAttendance: CoAttendanceRepository,
) : ReputationDataSource {

    override suspend fun generateQr(): ReputationQr = qrGenerator.generateForCurrentBadges()

    override fun badgesUnreadable(): Boolean = badges.badgesUnreadable.value

    override suspend fun publishForced(): PublishResult {
        if (!badges.loaded.value) badges.loadBadges()
        return publisher.publish(badges.badges.value, platformProofs = null, force = true)
    }

    override suspend fun loadCard(): ReputationCardData {
        val user = profiles.load()
        val stored = badges.loadBadges()
        val claimed = claims.ensureBadgesClaimed(stored).badges
        return ReputationCardData(user, calculator.calculate(claimed))
    }

    override suspend fun analyzeSocial(pubkeyHex: String): SocialAnalysis = social.analyze(pubkeyHex)

    override suspend fun analyzeZaps(pubkeyHex: String): ZapStats = zaps.analyzeZapActivity(pubkeyHex, useCache = false)

    override suspend fun checkNip05(pubkeyHex: String): Nip05Result? {
        val found = nip05.fetchNip05FromProfile(pubkeyHex, relays.getActiveRelays())
        if (found.isNullOrEmpty()) return null
        return nip05.verify(found, pubkeyHex)
    }

    override suspend fun verifyProofString(input: String): ProofVerifyResult<ReputationEvent> =
        verifier.verifyProofString(input)

    override suspend fun myNpub(): String = profiles.load().nostrNpub

    override suspend fun verifyPerson(myNpub: String, targetNpub: String): PresenceCheck =
        coAttendance.verifyPerson(myNpub, targetNpub)

    override fun npubToHex(npub: String): String? =
        runCatching { Nip19.decodeNpub(npub) }.getOrNull()?.takeIf { it.length == 64 }

    override fun isValidNpub(npub: String): Boolean = Nip19.isValidNpub(npub) && npubToHex(npub) != null
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ReputationDataModule {
    @Binds
    abstract fun bindReputationDataSource(impl: DefaultReputationDataSource): ReputationDataSource
}
