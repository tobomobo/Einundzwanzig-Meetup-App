package space.einundzwanzig.meetup.data.trust.reputation

import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.data.badges.model.BadgeProofs
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.trust.level.TrustLevel

/**
 * Pure parts of Flutter's `ReputationPublisher` (spec 04 §7.4, §8.4, §8.5): the simplified
 * relay basic score in kind 30078 `stats.score`, its level, and the publish change hash.
 * `:data:reputation` builds the event content around these.
 */
object RelayReputationMath {

    /**
     * Dart `_calculateBasicScore`, 0..10:
     * bound×0.3 (≤3) + meetups×0.5 (≤2) + signers×0.7 (≤2) + (ageDays/30)×0.3 (≤2)
     * + 1.0 when every badge is bound.
     */
    fun basicScore(totalBadges: Int, boundBadges: Int, uniqueMeetups: Int, uniqueSigners: Int, accountAgeDays: Int): Double {
        var score = 0.0
        score += (boundBadges * 0.3).coerceIn(0.0, 3.0)
        score += (uniqueMeetups * 0.5).coerceIn(0.0, 2.0)
        score += (uniqueSigners * 0.7).coerceIn(0.0, 2.0)
        score += ((accountAgeDays / 30.0) * 0.3).coerceIn(0.0, 2.0)
        if (totalBadges > 0 && boundBadges == totalBadges) score += 1.0
        return score.coerceIn(0.0, 10.0)
    }

    /** Value written to `stats.score`: Dart `double.parse(score.toStringAsFixed(1))`. */
    fun publishedScore(basicScore: Double): Double = basicScore.toStringAsFixed(1).toDouble()

    /** Dart `_scoreToLevel` (relay scale, ≥8/≥5/≥2/>0); the wire value for `stats.level`. */
    fun levelForBasic(score: Double): String = TrustLevel.forBasicScore(score).wire

    /**
     * Dart `_computeContentHash` (publish throttle, prefs `reputation_last_hash`):
     * `sha256("$total|$bound|$distinctMeetupNames|$distinctNonEmptyAdminPubkeys")[0:16]`.
     */
    fun changeHash(badges: List<MeetupBadge>): String = changeHash(
        total = badges.size,
        bound = BadgeProofs.countBoundBadges(badges),
        meetups = badges.mapTo(HashSet()) { it.meetupName }.size,
        signers = badges.map { it.adminPubkey }.filterTo(HashSet()) { it.isNotEmpty() }.size,
    )

    /** [changeHash] from the four counts. */
    fun changeHash(total: Int, bound: Int, meetups: Int, signers: Int): String =
        Sha256.hexOfUtf8("$total|$bound|$meetups|$signers").substring(0, 16)
}
