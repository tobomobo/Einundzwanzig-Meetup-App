package space.einundzwanzig.meetup.data.trust.reputation

import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.data.badges.model.BadgeProofs
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.trust.level.TrustLevel
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import java.time.ZoneId

/**
 * Pure parts of Flutter's `ReputationPublisher` and `_generateQRData` (spec 04 §7.3, §7.4,
 * §8.4, §8.5): the simplified relay basic score in kind 30078 `stats.score`, its level, the
 * `account_age_days`/`since` inputs, the publish change hash and the QR meetup list hash.
 * `:data:reputation` builds the event content and the QR payload around these; the choice of
 * the badge basis (spec 04 §8.6 counting, §8.8 R-4) stays there.
 */
object RelayReputationMath {

    /**
     * Dart `_calculateBasicScore`, 0..10:
     * bound×0.3 (≤3) + meetups×0.5 (≤2) + signers×0.7 (≤2) + (ageDays/30)×0.3 (≤2)
     * + 1.0 when every badge is bound.
     *
     * [accountAgeDays] is `Long` like Dart's int64 and like [accountAgeDays]'s result (it is
     * also published as `stats.account_age_days`), so callers pass it through unnarrowed.
     */
    fun basicScore(totalBadges: Int, boundBadges: Int, uniqueMeetups: Int, uniqueSigners: Int, accountAgeDays: Long): Double {
        var score = 0.0
        score += (boundBadges * 0.3).coerceIn(0.0, 3.0)
        score += (uniqueMeetups * 0.5).coerceIn(0.0, 2.0)
        score += (uniqueSigners * 0.7).coerceIn(0.0, 2.0)
        score += ((accountAgeDays / 30.0) * 0.3).coerceIn(0.0, 2.0)
        if (totalBadges > 0 && boundBadges == totalBadges) score += 1.0
        return score.coerceIn(0.0, 10.0)
    }

    /** [basicScore] with an `Int` account age (widened to `Long`). */
    fun basicScore(totalBadges: Int, boundBadges: Int, uniqueMeetups: Int, uniqueSigners: Int, accountAgeDays: Int): Double =
        basicScore(totalBadges, boundBadges, uniqueMeetups, uniqueSigners, accountAgeDays.toLong())

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

    /**
     * Dart publisher `accountAgeDays = DateTime.now().difference(firstBadge).inDays`, where
     * `firstBadge` is the first date after a Dart `List.sort` by date over [badges]
     * ([TrustScoreCalculator.earliestDate]). `0` for an empty list (Flutter never publishes
     * one). `Long` like Dart's int64; negative for badges dated in the future.
     */
    fun accountAgeDays(badges: List<MeetupBadge>, time: TimeSource): Long {
        val first = TrustScoreCalculator.earliestDate(badges) ?: return 0
        return DartDateTime.now(time).difference(first).inDays
    }

    /**
     * Dart publisher `since`: `'${firstBadge.year}-${firstBadge.month.toString().padLeft(2, '0')}'`
     * of [firstBadgeDate] as stored (UTC fields for a UTC value, else local fields in [zone]).
     * The year is not padded. Pass [TrustScoreCalculator.earliestDate] of the publish basis.
     *
     * [zone] is required on purpose: pass `zoneIdProvider.zone()`, the same zone the trust
     * calculator uses, so the published month is deterministic under test.
     */
    fun since(firstBadgeDate: DartDateTime, zone: ZoneId): String {
        val f = firstBadgeDate.fields(zone)
        return "${f.year}-${f.monthValue.toString().padStart(2, '0')}"
    }

    /**
     * One QR `rp.ml` entry (spec 04 §7.3): `sha256(utf8("21meetup:" + meetupName))[0:12]`, the
     * name exact (case-sensitive, not trimmed). `"Aschaffenburg"` → `d4ca521ba896`. Delegates
     * to [BadgeProofs.meetupListHash], the single source of truth for this wire hash.
     */
    fun meetupListHash(meetupName: String): String = BadgeProofs.meetupListHash(meetupName)

    /**
     * QR `rp.ml` (spec 04 §7.3, §8.6): first-seen distinct `meetupName`s of
     * `badges.filter { isNostrSigned && !isOrganizer }` (events included), the first 10,
     * each hashed with [meetupListHash]. The caller omits `ml` when the list is empty (`mc == 0`).
     */
    fun meetupListHashes(badges: List<MeetupBadge>): List<String> =
        badges.asSequence()
            .filter { it.isNostrSigned && !it.isOrganizer }
            .mapTo(LinkedHashSet()) { it.meetupName }
            .take(MAX_MEETUP_LIST_HASHES)
            .map(::meetupListHash)

    /** Dart `uniqueMeetups.take(10)`: maximum number of `ml` entries. */
    const val MAX_MEETUP_LIST_HASHES: Int = 10
}
