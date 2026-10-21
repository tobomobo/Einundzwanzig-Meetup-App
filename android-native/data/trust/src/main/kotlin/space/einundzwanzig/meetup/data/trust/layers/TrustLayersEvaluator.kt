package space.einundzwanzig.meetup.data.trust.layers

/** The trust "traffic light" (spec 04 §8.9). Each value names its Flutter colour, icon and l10n keys. */
enum class TrustSignalKind(
    val labelKey: String,
    val explanationKey: String,
    val adviceKey: String,
) {
    /** red400, `warning_amber_rounded`. */
    WEAK("rlWeakLabel", "rlWeakExpl", "rlWeakAdvice"),

    /** orange, `info_outline`. */
    LIMITED("rlLimitedLabel", "rlLimitedExpl", "rlLimitedAdvice"),

    /** amber, `shield_outlined`. */
    BUILDING("rlBuildingLabel", "rlBuildingExpl", "rlBuildingAdvice"),

    /** green, `verified_user`. */
    CONNECTED("rlConnectedLabel", "rlConnectedExpl", "rlConnectedAdvice"),

    /** green300, `shield`. */
    SOLID("rlSolidLabel", "rlSolidExpl", "rlSolidAdvice"),

    /** amber, `shield_outlined`; the label is the BUILDING one. */
    DEFAULT("rlBuildingLabel", "rlDefaultExpl", "rlDefaultAdvice"),
}

/** Meaning line of the Meetup layer card. */
enum class MeetupMeaning {
    /** `rlMeetupGood` */
    GOOD_DIVERSITY,

    /** `rlPhysGoodDiversity(signerCount ?? 1)` */
    SINGLE_ORGANIZER,

    /** `rlMeetupNone` (no data) */
    NONE,

    /**
     * Data present but no meaning line at all (Flutter passes `null`): no signer diversity and
     * `badgeCount` not > 0, reachable with a negative remote `stats.total_badges`.
     */
    NO_TEXT,
}

/** Meaning line of the Lightning layer card. */
enum class LightningMeaning {
    /** `rlLnBoth` */
    BOTH,

    /** `rlLnPaid` */
    PAID,

    /** `rlLnActiveOnly` */
    ACTIVE_ONLY,

    /** `rlLnNone` (no data) */
    NONE,
}

/** Meaning line of the Social layer card (first match wins). */
enum class SocialMeaning {
    /** `rlSocMutualMany` */
    MUTUAL_MANY,

    /** `rlSocMutual` */
    MUTUAL,

    /** `rlSocCommon` */
    COMMON,

    /** `rlSocOneSided` */
    ONE_SIDED,

    /** `rlSocOrgFollow` */
    ORG_FOLLOW,

    /** `rlSocDefault` */
    DEFAULT,

    /** `rlSocNone` (no data) */
    NONE,
}

/** Meaning line of the Identity layer card. */
enum class IdentityMeaning {
    /** `rlIdNip05Plat` */
    NIP05_AND_PLATFORMS,

    /** `rlIdNip05Only` */
    NIP05_ONLY,

    /** `rlIdPlatOnly` */
    PLATFORMS_ONLY,

    /** `rlIdNone` (no data) */
    NONE,
}

/**
 * Result of [TrustLayersEvaluator.evaluate]. The `has*Data` flags are what each card uses for
 * "present"/"missing"; note that [hasIdentityData] is not the same as `identityScore > 0`
 * (account age alone makes the identity score positive, PARITY spec 04 R-20).
 */
data class LayersEvaluation(
    val physicalScore: Double?,
    val lightningScore: Double,
    val socialScore: Double,
    val identityScore: Double?,
    val activeLayerCount: Int,
    val combinedScore: Double,
    val signal: TrustSignalKind,
    val hasMeetupData: Boolean,
    val hasLightningData: Boolean,
    val hasSocialData: Boolean,
    val hasIdentityData: Boolean,
    val meetupMeaning: MeetupMeaning,
    val lightningMeaning: LightningMeaning,
    val socialMeaning: SocialMeaning,
    val identityMeaning: IdentityMeaning,
    /** `signerCount >= 2`. */
    val hasSignerDiversity: Boolean,
    /** `boundBadges == badgeCount` (both non-null). */
    val allBadgesBound: Boolean,
)

/**
 * Pure part of Flutter's `ReputationLayersWidget` (spec 04 §8.9): layer scores, active-layer
 * count, combined score, traffic-light signal and the meaning of each layer card. The detail
 * rows are simple enough for the UI to build from [LayersInput] directly, using
 * [ZapLayerInput.activityLabel], [Nip05LayerInput.domainLabel] and [platformLabel].
 */
object TrustLayersEvaluator {

    /**
     * Dart `_platformLabel`: display name of a `platform_proofs` key (`telegram` → `Telegram`,
     * `satoshikleinanzeigen` → `Satoshi-Kleinanzeigen`, `robosats` → `RoboSats`, `nostr` →
     * `Nostr`, `other` → `Andere`), else the raw key. Not localized in Flutter either.
     */
    fun platformLabel(platform: String): String = when (platform) {
        "telegram" -> "Telegram"
        "satoshikleinanzeigen" -> "Satoshi-Kleinanzeigen"
        "robosats" -> "RoboSats"
        "nostr" -> "Nostr"
        "other" -> "Andere"
        else -> platform
    }

    /** Meetup layer score 0..8, `null` without badges. */
    fun physicalScore(badgeCount: Int?, meetupCount: Int?, signerCount: Int?, boundBadges: Int?): Double? {
        if (badgeCount == null || badgeCount == 0) return null
        var score = 0.0
        score += (badgeCount.toDouble() / (badgeCount + 5)) * 3.0
        if (meetupCount != null) score += (meetupCount.toDouble() / (meetupCount + 3)) * 2.0
        if (signerCount != null) score += (signerCount.toDouble() / (signerCount + 3)) * 2.0
        if (boundBadges != null && badgeCount > 0) score += (boundBadges.toDouble() / badgeCount) * 1.0
        return score.coerceIn(0.0, 8.0)
    }

    /** Identity layer score 0..2, `null` when 0. Account age > 30 days counts (PARITY). */
    fun identityScore(nip05: Nip05LayerInput?, platformProofCount: Int?, accountAgeDays: Int?): Double? {
        var score = 0.0
        if (nip05 != null && nip05.valid) score += nip05.score
        if (platformProofCount != null && platformProofCount > 0) {
            score += (platformProofCount * 0.3).coerceIn(0.0, 0.5)
        }
        if (accountAgeDays != null && accountAgeDays > 30) score += 0.5
        return if (score > 0) score.coerceIn(0.0, 2.0) else null
    }

    /** Number of active layers (0..4). */
    fun activeLayerCount(input: LayersInput): Int {
        var count = 0
        val physical = physicalScore(input)
        if (physical != null && physical > 0) count++
        if ((input.zaps != null && input.zaps.totalCount > 0) || input.humanityVerified) count++
        if (input.social != null && input.social.socialScore > 0) count++
        val identity = identityScore(input)
        if (identity != null && identity > 0) count++
        return count
    }

    /** Weighted sum 0.4/0.25/0.25/0.1, clamped to 0..10. */
    fun combinedScore(input: LayersInput): Double {
        val physical = (physicalScore(input) ?: 0.0) * 0.4
        val lightning = (input.zaps?.lightningScore ?: 0.0) * 0.25
        val social = (input.social?.socialScore ?: 0.0) * 0.25
        val identity = (identityScore(input) ?: 0.0) * 0.1
        return (physical + lightning + social + identity).coerceIn(0.0, 10.0)
    }

    /** Traffic-light selection, first match wins. */
    fun signal(input: LayersInput): TrustSignalKind = signalFor(activeLayerCount(input), combinedScore(input))

    /** [signal] for given layer count and combined score. */
    fun signalFor(layers: Int, score: Double): TrustSignalKind = when {
        layers <= 1 && score < 3.0 -> TrustSignalKind.WEAK
        layers <= 1 -> TrustSignalKind.LIMITED
        layers == 2 && score < 4.0 -> TrustSignalKind.BUILDING
        layers >= 3 && score >= 4.0 -> TrustSignalKind.CONNECTED
        layers >= 3 || score >= 5.0 -> TrustSignalKind.SOLID
        else -> TrustSignalKind.DEFAULT
    }

    /** Everything the layers widget derives from [input]. */
    fun evaluate(input: LayersInput): LayersEvaluation {
        val physical = physicalScore(input)
        val identity = identityScore(input)
        val layers = activeLayerCount(input)
        val combined = combinedScore(input)

        val hasMeetup = physical != null && physical > 0
        val hasDiversity = (input.signerCount ?: 0) >= 2
        val allBound = input.boundBadges != null && input.badgeCount != null && input.boundBadges == input.badgeCount
        val meetupMeaning = when {
            !hasMeetup -> MeetupMeaning.NONE
            hasDiversity -> MeetupMeaning.GOOD_DIVERSITY
            // Not implied by hasMeetup: a negative remote badge count can still score > 0.
            (input.badgeCount ?: 0) > 0 -> MeetupMeaning.SINGLE_ORGANIZER
            else -> MeetupMeaning.NO_TEXT
        }

        val hasZaps = input.zaps != null && input.zaps.totalCount > 0
        val hasLightning = hasZaps || input.humanityVerified
        val lightningMeaning = when {
            !hasLightning -> LightningMeaning.NONE
            input.humanityVerified && hasZaps -> LightningMeaning.BOTH
            input.humanityVerified -> LightningMeaning.PAID
            else -> LightningMeaning.ACTIVE_ONLY
        }

        val sa = input.social
        val hasSocial = sa != null && sa.socialScore > 0
        val socialMeaning = when {
            !hasSocial || sa == null -> SocialMeaning.NONE
            sa.isMutual && sa.commonContactCount > 3 -> SocialMeaning.MUTUAL_MANY
            sa.isMutual -> SocialMeaning.MUTUAL
            sa.commonContactCount > 5 -> SocialMeaning.COMMON
            sa.iFollow || sa.followsMe -> SocialMeaning.ONE_SIDED
            sa.orgFollowerCount > 0 -> SocialMeaning.ORG_FOLLOW
            else -> SocialMeaning.DEFAULT
        }

        val hasNip05 = input.nip05 != null && input.nip05.valid
        val hasPlatforms = (input.platformProofCount ?: 0) > 0 || !input.platformProofs.isNullOrEmpty()
        val hasIdentity = hasNip05 || hasPlatforms
        val identityMeaning = when {
            !hasIdentity -> IdentityMeaning.NONE
            hasNip05 && hasPlatforms -> IdentityMeaning.NIP05_AND_PLATFORMS
            hasNip05 -> IdentityMeaning.NIP05_ONLY
            else -> IdentityMeaning.PLATFORMS_ONLY
        }

        return LayersEvaluation(
            physicalScore = physical,
            lightningScore = input.zaps?.lightningScore ?: 0.0,
            socialScore = sa?.socialScore ?: 0.0,
            identityScore = identity,
            activeLayerCount = layers,
            combinedScore = combined,
            signal = signalFor(layers, combined),
            hasMeetupData = hasMeetup,
            hasLightningData = hasLightning,
            hasSocialData = hasSocial,
            hasIdentityData = hasIdentity,
            meetupMeaning = meetupMeaning,
            lightningMeaning = lightningMeaning,
            socialMeaning = socialMeaning,
            identityMeaning = identityMeaning,
            hasSignerDiversity = hasDiversity,
            allBadgesBound = allBound,
        )
    }

    private fun physicalScore(input: LayersInput): Double? =
        physicalScore(input.badgeCount, input.meetupCount, input.signerCount, input.boundBadges)

    private fun identityScore(input: LayersInput): Double? =
        identityScore(input.nip05, input.platformProofCount, input.accountAgeDays)
}
