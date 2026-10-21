package space.einundzwanzig.meetup.data.reputation.content

import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.json.toJsonElement
import space.einundzwanzig.meetup.data.badges.model.BadgeProofs
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.reputation.ReputationProtocol
import space.einundzwanzig.meetup.data.socialgraph.social.SocialStats
import space.einundzwanzig.meetup.data.trust.reputation.RelayReputationMath
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import java.time.ZoneId

/**
 * Dart `ReputationPublisher._buildContent` (spec 04 §3.10): the kind-30078 content object with
 * the exact key order and JSON types (ints stay ints, only `stats.score` is a double).
 *
 * Pure: the caller chooses the badge basis ([publishBasis]) and loads the optional blocks.
 */
object ReputationContentBuilder {

    /**
     * The badges the publisher counts (spec 04 §8.8 R-4 FIX, wire-compatible): signed
     * (`isNostrSigned`) and not self-issued (`!isOrganizer`), the same rule as the Trust Score and
     * the QR. Flutter passed ALL badges, so unsigned organizer markers and legacy badges inflated
     * `total_badges`/`meetup_count` and broke `bound == total`.
     */
    fun publishBasis(badges: List<MeetupBadge>): List<MeetupBadge> =
        badges.filter { it.isNostrSigned && !it.isOrganizer }

    /**
     * Builds the content map.
     *
     * @param badges the publish basis, non-empty (Flutter never builds content for `[]`).
     * @param nickname `UserProfile.nickname`; `""` becomes `"Anon"`.
     * @param social included as `social` only if `followCount > 0`.
     * @param zaps included as `lightning` only if `totalCount > 0`.
     * @param humanity `HumanityProofRepository.getProofForPublishing()`, included verbatim if non-null.
     * @param platformProofs included as `platform_proofs` only if non-empty (key = platform id).
     * @param time `updated_at` and `account_age_days` clock.
     * @param zone local zone for `since` of local-dated badges.
     * @throws IllegalArgumentException if [badges] is empty.
     */
    fun build(
        badges: List<MeetupBadge>,
        nickname: String,
        social: SocialStats?,
        zaps: ZapStats?,
        humanity: JsonObject?,
        platformProofs: Map<String, PlatformProof>?,
        time: TimeSource,
        zone: ZoneId = ZoneId.systemDefault(),
    ): JsonObject {
        require(badges.isNotEmpty()) { "no badges" }
        val stats = BadgeProofs.getReputationStats(badges)
        val uniqueMeetups = badges.mapTo(HashSet()) { it.meetupName }.size
        val uniqueSigners = badges.map { it.adminPubkey }.filterTo(HashSet()) { it.isNotEmpty() }.size

        val firstBadge = TrustScoreCalculator.earliestDate(badges)!!
        val accountAgeDays = RelayReputationMath.accountAgeDays(badges, time)
        val since = RelayReputationMath.since(firstBadge, zone)

        val basic = RelayReputationMath.basicScore(
            totalBadges = badges.size,
            boundBadges = stats.claimed,
            uniqueMeetups = uniqueMeetups,
            uniqueSigners = uniqueSigners,
            accountAgeDays = accountAgeDays.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
        )

        val proofV2 = BadgeProofs.generateBadgeProofV2(badges)
        val proofHash = proofV2.ifEmpty { BadgeProofs.generateBadgeProof(badges) }

        val content = LinkedHashMap<String, Any?>()
        content["version"] = ReputationProtocol.PROTOCOL_VERSION
        content["identity"] = jsonObjectOf("nickname" to nickname.ifEmpty { "Anon" })
        content["stats"] = jsonObjectOf(
            "score" to RelayReputationMath.publishedScore(basic),
            "level" to RelayReputationMath.levelForBasic(basic),
            "total_badges" to badges.size,
            "verified_badges" to stats.cryptoProof,
            "bound_badges" to stats.claimed,
            "retroactive_badges" to stats.retroactive,
            "meetup_count" to uniqueMeetups,
            "signer_count" to uniqueSigners,
            "account_age_days" to accountAgeDays,
            "since" to since,
        )
        content["proof"] = jsonObjectOf(
            "badge_proof_hash" to proofHash,
            "proof_version" to if (proofV2.isNotEmpty()) 2 else 1,
        )
        content["updated_at"] = Math.floorDiv(time.nowMillis(), 1000L)
        if (social != null && social.followCount > 0) content["social"] = social.toJson()
        if (zaps != null && zaps.totalCount > 0) content["lightning"] = zaps.toJson()
        if (humanity != null) content["humanity_proof"] = humanity
        if (!platformProofs.isNullOrEmpty()) {
            content["platform_proofs"] = JsonObject(platformProofs.mapValues { it.value.toReputationJson() })
        }
        return content.toJsonElement() as JsonObject
    }

    /** Dart `jsonEncode(content)`: compact, Dart escaping and number formatting. */
    fun encode(content: JsonObject): String = DartJson.encode(content)

    /**
     * Dart `_computeContentHash` over [badges] (the publish basis): 16 hex of
     * `sha256("total|bound|meetups|signers")`, stored in `reputation_last_hash`.
     */
    fun changeHash(badges: List<MeetupBadge>): String = RelayReputationMath.changeHash(badges)
}
