package space.einundzwanzig.meetup.data.trust.score

import space.einundzwanzig.meetup.data.trust.level.TrustLevel

/**
 * Value of one badge in the score (Dart `BadgeValue`). A badge over the weekly cap has every
 * numeric field `0`.
 */
data class BadgeValue(
    val badgeId: String,
    val meetupName: String,
    val baseValue: Double,
    val coAttestorBonus: Double,
    val signerBonus: Double,
    val decayFactor: Double,
    val totalValue: Double,
    val coAttestorCount: Int,
    val veteranCount: Int,
)

/** Progress of one promotion criterion (Dart `PromotionProgress`). */
data class PromotionProgress(
    val label: String,
    val current: Int,
    val required: Int,
) {
    /** `current >= required`. */
    val met: Boolean get() = current >= required

    /** `current / required` clamped to 0..1; `1.0` when nothing is required. */
    val percentage: Double
        get() = if (required > 0) (current.toDouble() / required).coerceIn(0.0, 1.0) else 1.0
}

/**
 * Co-attendance data of one meetup event (Dart `CoAttestorData`). Every production caller
 * passes no map today; it stays for parity and tests.
 */
data class CoAttestorData(
    val meetupEventId: String,
    val attendeeCount: Int,
    val veteranCount: Int,
    val attendeeNpubs: List<String>,
)

/**
 * The locally computed Trust Score (Dart `TrustScore`, spec 04 §3.4). In memory only, never
 * serialized.
 *
 * @property progress criteria in the insertion order [TrustScoreCalculator.PROGRESS_KEYS]
 *   (`badges, meetups, signers, age, score`); [promotionReason] names the first unmet one.
 */
data class TrustScore(
    val totalScore: Double,
    val maturityScore: Double,
    val diversityScore: Double,
    val qualityScore: Double,
    val activityScore: Double,
    val totalBadges: Int,
    val uniqueMeetups: Int,
    val uniqueSigners: Int,
    val uniqueCities: Int,
    val accountAgeDays: Int,
    val meetsPromotionThreshold: Boolean,
    val promotionReason: String,
    val badgeValues: List<BadgeValue>,
    val currentPhase: BootstrapPhase,
    val activeThresholds: PhaseThresholds,
    val progress: Map<String, PromotionProgress>,
) {
    /** Compact 0..10 display value: `totalScore / 5`, clamped. */
    val displayScore: Double get() = (totalScore / 5.0).coerceIn(0.0, 10.0)

    /** Local level (3/10/20/40 scale) as the internal wire value, e.g. `"AKTIV"`. Never translate it in payloads. */
    val level: String get() = trustLevel.wire

    /** Local level as an enum. */
    val trustLevel: TrustLevel get() = TrustLevel.forLocalScore(totalScore)

    /** Glyph of the level (`★ ● ◆ ▲ ○`). Screens use icons instead (see [TrustLevel]). */
    val levelEmoji: String
        get() = when {
            totalScore >= 40 -> "★"
            totalScore >= 20 -> "●"
            totalScore >= 10 -> "◆"
            totalScore >= 3 -> "▲"
            else -> "○"
        }

    /** 1.0 when promoted, 0.0 without criteria, else the mean of all [PromotionProgress.percentage]s. */
    val promotionProgress: Double
        get() {
            if (meetsPromotionThreshold) return 1.0
            if (progress.isEmpty()) return 0.0
            var sum = 0.0
            for (p in progress.values) sum += p.percentage
            return sum / progress.size
        }
}
