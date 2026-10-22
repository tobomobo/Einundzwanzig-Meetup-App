package space.einundzwanzig.meetup.feature.reputation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.data.badges.security.QrSignResult
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrCodec
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Result
import space.einundzwanzig.meetup.data.proofs.platform.ProofVerifyResult
import space.einundzwanzig.meetup.data.proofs.platform.VerifyLevel
import space.einundzwanzig.meetup.data.proofs.platform.VerifyMessage
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.reputation.event.ReputationEvent
import space.einundzwanzig.meetup.data.reputation.publish.PublishResult
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQr
import space.einundzwanzig.meetup.data.socialgraph.coattendance.PresenceCheck
import space.einundzwanzig.meetup.data.socialgraph.social.SocialAnalysis
import space.einundzwanzig.meetup.data.trust.score.TrustScore
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import space.einundzwanzig.meetup.feature.reputation.data.ReputationCardData
import space.einundzwanzig.meetup.feature.reputation.data.ReputationDataSource
import java.io.File

/** Fixed clock for score construction. */
internal object FixedTime : TimeSource {
    override fun nowMillis(): Long = 1_760_961_600_000
}

/** A Trust Score with the given figures (built from the calculator's empty score). */
internal fun trustScore(total: Double = 12.3, badges: Int = 4, meetups: Int = 3, signers: Int = 2, cities: Int = 3, ageDays: Int = 45): TrustScore =
    TrustScoreCalculator.create(FixedTime).calculate(emptyList()).copy(
        totalScore = total,
        totalBadges = badges,
        uniqueMeetups = meetups,
        uniqueSigners = signers,
        uniqueCities = cities,
        accountAgeDays = ageDays,
    )

internal fun qrContent(
    user: UserProfile = UserProfile(nickname = "Satoshi", nostrNpub = Vectors.signerNpub),
    signError: Throwable? = null,
    isSigned: Boolean = signError == null,
) = ReputationQr.Content(
    user = user,
    platformProofCount = 2,
    qrData = Vectors.qrString,
    isSigned = isSigned,
    signError = signError,
    score = trustScore(),
    verifiedBadgeCount = 4,
    boundBadgeCount = 2,
    countedBadgeCount = 4,
    organizerMeetupCount = 1,
)

/** Vectors of `spec/fixtures/04-trust-reputation-vectors.json`. */
internal object Vectors {
    private val qr by lazy {
        DartJson.decode(File("../../spec/fixtures/04-trust-reputation-vectors.json").readText()).jsonObject["reputationQrV3"]!!.jsonObject
    }
    val qrString: String get() = qr["qr_string"]!!.jsonPrimitive.content
    val tamperedQrString: String get() = qr["tampered_qr_string"]!!.jsonPrimitive.content
    val unsignedQr: String get() = qr["unsigned_qr_example"]!!.jsonPrimitive.content
    const val signerNpub = "npub10xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpkge6d"
}

internal const val OTHER_NPUB = "npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m"

internal class FakeReputationDataSource : ReputationDataSource {
    var qr: suspend () -> ReputationQr = { qrContent() }
    var unreadable = false
    var publishResult: suspend () -> PublishResult = { PublishResult.Sent(3, "e".repeat(64)) }
    var publishCalls = 0
    var card: suspend () -> ReputationCardData = { ReputationCardData(UserProfile(nickname = "Satoshi"), trustScore()) }
    var social: SocialAnalysis? = SocialAnalysis("hex", 10, 10, iFollow = true, followsMe = true, isMutual = true, commonContactCount = 4, orgFollowerCount = 1, hops = 1)
    var zaps: ZapStats? = ZapStats(6, 2, 4, 2, 3, true)
    var nip05: Nip05Result? = Nip05Result(valid = true, nip05 = "satoshi@einundzwanzig.space")
    var layerGate = CompletableDeferred<Unit>().apply { complete(Unit) }
    val layerHexes = mutableListOf<String>()
    var verify: suspend (String) -> ProofVerifyResult<ReputationEvent> = { input ->
        ProofVerifyResult(VerifyLevel.PARTIAL, VerifyMessage.NpubWithBindings, npub = input, reputation = reputationEvent())
    }
    val verifyInputs = mutableListOf<String>()
    var myNpub = Vectors.signerNpub
    var presence: suspend (String, String) -> PresenceCheck = { _, target ->
        PresenceCheck(target, 1, listOf(Vectors.signerNpub, target), setOf("m1@x", "m2@x"), true, 2)
    }
    val presenceCalls = mutableListOf<Pair<String, String>>()

    override suspend fun generateQr(): ReputationQr = qr()
    override fun badgesUnreadable(): Boolean = unreadable
    override suspend fun publishForced(): PublishResult {
        publishCalls++
        return publishResult()
    }
    override suspend fun loadCard(): ReputationCardData = card()
    override suspend fun analyzeSocial(pubkeyHex: String): SocialAnalysis {
        layerHexes += pubkeyHex
        layerGate.await()
        return social ?: error("social failed")
    }
    override suspend fun analyzeZaps(pubkeyHex: String): ZapStats {
        layerGate.await()
        return zaps ?: error("zaps failed")
    }
    override suspend fun checkNip05(pubkeyHex: String): Nip05Result? {
        layerGate.await()
        return nip05
    }
    override suspend fun verifyProofString(input: String): ProofVerifyResult<ReputationEvent> {
        verifyInputs += input
        return verify(input)
    }
    override suspend fun myNpub(): String = myNpub
    override suspend fun verifyPerson(myNpub: String, targetNpub: String): PresenceCheck {
        presenceCalls += myNpub to targetNpub
        return presence(myNpub, targetNpub)
    }
    override fun npubToHex(npub: String): String? = if (npub.startsWith("npub1") && npub.length > 50) "h:$npub" else null
    override fun isValidNpub(npub: String): Boolean = npub == Vectors.signerNpub || npub == OTHER_NPUB
}

internal fun reputationEvent(total: Int = 8, bound: Int = 8) = ReputationEvent(
    pubkeyHex = "a".repeat(64),
    npub = OTHER_NPUB,
    eventId = "b".repeat(64),
    signature = "c".repeat(128),
    createdAt = 1_760_000_000,
    nickname = "Hal",
    totalBadges = total,
    boundBadges = bound,
    meetupCount = 4,
    signerCount = 3,
    accountAgeDays = 120,
    since = "2025-03",
)

/**
 * Spec 04 S-8: a valid 21v3 code signed by key 2 that claims key 1's npub. Returns the code and
 * the signer's npub.
 */
internal fun mismatchQr(): Pair<String, String> {
    val keyA = KeyPair.fromPrivateKeyHex("0".repeat(63) + "1")
    val keyB = KeyPair.fromPrivateKeyHex("0".repeat(63) + "2")
    val json = DartJson.encode(jsonObjectOf("v" to 6, "id" to jsonObjectOf("n" to "Mallory", "np" to keyA.npub)))
    val ev = EventIds.sign(keyB, 1_760_961_600L, 21001, emptyList(), json, ByteArray(32))
    return ReputationQrCodec.encodeSigned(json, QrSignResult(ev.sig, ev.id, ev.createdAt, ev.pubkey, true)) to keyB.npub
}
