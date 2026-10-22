package space.einundzwanzig.meetup.data.trust.internal

import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.DartDuration
import space.einundzwanzig.meetup.core.common.dart.dartRound
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.badges.util.DartSort
import space.einundzwanzig.meetup.data.trust.ZoneIdProvider
import space.einundzwanzig.meetup.data.trust.score.BadgeValue
import space.einundzwanzig.meetup.data.trust.score.BootstrapPhase
import space.einundzwanzig.meetup.data.trust.score.CoAttestorData
import space.einundzwanzig.meetup.data.trust.score.PhaseThresholds
import space.einundzwanzig.meetup.data.trust.score.PromotionProgress
import space.einundzwanzig.meetup.data.trust.score.TrustConfig
import space.einundzwanzig.meetup.data.trust.score.TrustScore
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Line-by-line port of `lib/services/trust_score_service.dart`. */
@Singleton
internal class DefaultTrustScoreCalculator @Inject constructor(
    private val time: TimeSource,
    private val zoneProvider: ZoneIdProvider,
    private val log: DiagnosticLog,
) : TrustScoreCalculator {

    override fun calculate(
        badges: List<MeetupBadge>,
        firstBadgeDate: DartDateTime?,
        coAttestors: Map<String, CoAttestorData>?,
    ): TrustScore {
        val zone = zoneProvider.zone()
        // Phase from the trusted but NOT de-duplicated list (PARITY).
        val trustedForPhase = badges.filter { it.isNostrSigned && !it.isOrganizer }
        val phase = determinePhase(trustedForPhase)
        val thresholds = TrustConfig.thresholds(phase)

        if (badges.isEmpty()) return emptyScore(phase, thresholds)

        // Only Schnorr-signed participation badges; de-duplicated per event, oldest wins.
        val trusted = badges.filter { it.isNostrSigned && !it.isOrganizer }.toMutableList()
        DartSort.sort(trusted) { a, b -> a.date.compareTo(b.date) }
        val seen = HashSet<String>()
        // identityKey's date fallback reads the JVM default zone, not [zoneProvider] (see ZoneIdProvider).
        val trustedBadges = trusted.filter { seen.add(BadgeIdentity.identityKey(it)) }
        if (trustedBadges.size != trusted.size) {
            log.diag(TAG, "${trusted.size - trustedBadges.size} doppelte Badge(s) fuer die Reputation ignoriert.")
        }
        if (trustedBadges.isEmpty()) return emptyScore(phase, thresholds)

        // Event badges are no meetups, but they count as badges and for signer diversity.
        val uniqueMeetups = trustedBadges.filter { !it.isEvent }.mapTo(LinkedHashSet()) { it.meetupName }
        val uniqueSigners = trustedBadges.map { it.signerNpub }.filterTo(LinkedHashSet()) { it.isNotEmpty() }
        val uniqueCities = uniqueMeetups

        val now = DartDateTime.now(time)
        val accountAge = if (firstBadgeDate != null) now.difference(firstBadgeDate) else DartDuration.ZERO
        val accountAgeDays = accountAge.inDays.toInt()

        // Frequency cap over the newest first: the oldest badges of a week are zeroed.
        val badgeValues = ArrayList<BadgeValue>(trustedBadges.size)
        val weeklyCount = HashMap<Long, Int>()
        val sortedBadges = trustedBadges.toMutableList()
        DartSort.sort(sortedBadges) { a, b -> b.date.compareTo(a.date) }
        val weekAnchor = weekAnchor(zone)
        for (badge in sortedBadges) {
            val weekNumber = weekNumber(badge.date, weekAnchor)
            val count = (weeklyCount[weekNumber] ?: 0) + 1
            weeklyCount[weekNumber] = count
            if (count > TrustConfig.MAX_BADGES_PER_WEEK) {
                badgeValues += BadgeValue(badge.id, badge.meetupName, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0)
                continue
            }
            val coData = coAttestors?.get(badgeToEventId(badge, zone))
            val attendeeCount = coData?.attendeeCount ?: 1
            val veteranCount = coData?.veteranCount ?: 0
            // Double addition: Dart adds in int64, an Int sum would wrap at Int.MAX_VALUE (NaN).
            val coAttestorBonus = ln(attendeeCount.toDouble() + 1.0) / LN2
            val signerBonus = 1.0 + (veteranCount * 0.3)
            val ageWeeks = now.difference(badge.date).inDays / 7.0
            val decayFactor = 0.5.pow(ageWeeks / TrustConfig.HALF_LIFE_WEEKS)
            val presenceFactor = if (badge.presenceVerified) 1.0 else 0.7
            val totalValue = 1.0 * coAttestorBonus * signerBonus * decayFactor * presenceFactor
            badgeValues += BadgeValue(
                badgeId = badge.id,
                meetupName = badge.meetupName,
                baseValue = 1.0,
                coAttestorBonus = coAttestorBonus,
                signerBonus = signerBonus,
                decayFactor = decayFactor,
                totalValue = totalValue,
                coAttestorCount = attendeeCount,
                veteranCount = veteranCount,
            )
        }

        val maturityScore = min(1.0, accountAge.inDays / 180.0) * TrustConfig.WEIGHT_MATURITY

        // Verified presence counts fully for reach, unverified-only meetups half. Event badges
        // are included in verifiedMeetups (PARITY, spec 04 R-20).
        val verifiedMeetups = trustedBadges.filter { it.presenceVerified }.mapTo(LinkedHashSet()) { it.meetupName }
        val unverifiedOnly = uniqueMeetups - verifiedMeetups
        val effectiveDiversity = verifiedMeetups.size + 0.5 * unverifiedOnly.size
        if (unverifiedOnly.isNotEmpty()) {
            log.diag(
                TAG,
                "${unverifiedOnly.size} Meetup(s) nur ungeprueft belegt — zaehlen halb fuer die Reichweite.",
            )
        }
        val diversityScore = (ln(effectiveDiversity + 1) / LN2) *
            (ln((max(uniqueSigners.size, 1) + 1).toDouble()) / LN2) *
            TrustConfig.WEIGHT_DIVERSITY

        var qualitySum = 0.0
        for (bv in badgeValues) qualitySum += bv.signerBonus
        val qualityScore =
            if (badgeValues.isNotEmpty()) (qualitySum / badgeValues.size) * TrustConfig.WEIGHT_QUALITY else 0.0

        var activityScore = 0.0
        for (bv in badgeValues) activityScore += bv.totalValue

        val totalScore = activityScore * (1 + maturityScore) * (1 + diversityScore * 0.1)

        val progress = linkedMapOf(
            "badges" to PromotionProgress("Badges (Nostr-verifiziert)", trustedBadges.size, thresholds.minBadges),
            "meetups" to PromotionProgress("Verschiedene Meetups", uniqueMeetups.size, thresholds.minUniqueMeetups),
            "signers" to PromotionProgress("Verschiedene Ersteller", uniqueSigners.size, thresholds.minUniqueSigners),
            "age" to PromotionProgress("Account-Alter (Tage)", accountAgeDays, thresholds.minAccountAgeDays),
            "score" to PromotionProgress(
                "Trust Score",
                // Dart `round()` throws for NaN/±Infinity (far-future badge dates), so Flutter never
                // promotes such a profile; native fails closed (0, not met) instead of throwing.
                // Finite values saturate like Dart's int64 round.
                roundForProgress(totalScore),
                thresholds.promotionScore.dartRound().toInt(),
            ),
        )

        val firstUnmet = progress.entries.firstOrNull { !it.value.met }
        val meets = firstUnmet == null
        val reason = if (firstUnmet == null) {
            TrustScoreCalculator.REASON_ALL_MET
        } else {
            buildReason(firstUnmet.key, firstUnmet.value)
        }

        return TrustScore(
            totalScore = totalScore,
            maturityScore = maturityScore,
            diversityScore = diversityScore,
            qualityScore = qualityScore,
            activityScore = activityScore,
            totalBadges = trustedBadges.size,
            uniqueMeetups = uniqueMeetups.size,
            uniqueSigners = uniqueSigners.size,
            uniqueCities = uniqueCities.size,
            accountAgeDays = accountAgeDays,
            meetsPromotionThreshold = meets,
            promotionReason = reason,
            badgeValues = badgeValues,
            currentPhase = phase,
            activeThresholds = thresholds,
            progress = progress,
        )
    }

    override fun bridgeScore(badges: List<MeetupBadge>): Double {
        if (badges.size < 3) return 0.0
        val cities = badges.filter { !it.isEvent }.mapTo(HashSet()) { it.meetupName }
        if (cities.size <= 1) return 0.0
        val signers = badges.map { it.signerNpub }.filterTo(HashSet()) { it.isNotEmpty() }
        return cities.size.toDouble() * max(signers.size.toDouble(), 1.0)
    }

    override fun suspiciousPatterns(
        badges: List<MeetupBadge>,
        coAttestors: Map<String, CoAttestorData>?,
    ): List<String> {
        val warnings = ArrayList<String>()
        if (badges.isEmpty()) return warnings
        val zone = zoneProvider.zone()

        val signers = badges.map { it.signerNpub }.filterTo(HashSet()) { it.isNotEmpty() }
        if (signers.size == 1 && badges.size > 3) warnings += "Alle Badges von nur einem Ersteller"

        val perDay = HashMap<String, Int>()
        for (b in badges) {
            val day = b.date.toIso8601String(zone).substring(0, 10)
            perDay[day] = (perDay[day] ?: 0) + 1
        }
        val maxPerDay = perDay.values.fold(0, ::max)
        if (maxPerDay > 3) warnings += "$maxPerDay Badges an einem Tag"

        if (coAttestors != null) {
            val lonely = badges.count { b ->
                val coData = coAttestors[badgeToEventId(b, zone)]
                coData == null || coData.attendeeCount <= 1
            }
            if (lonely == badges.size && badges.size > 2) warnings += "Kein Badge hat Co-Attestors"
        }
        return warnings
    }

    private fun determinePhase(badges: List<MeetupBadge>): BootstrapPhase {
        val signers = badges.map { it.signerNpub }.filterTo(HashSet()) { it.isNotEmpty() }
        return when {
            signers.size >= 6 -> BootstrapPhase.STABIL
            signers.size >= 2 -> BootstrapPhase.WACHSTUM
            else -> BootstrapPhase.KEIMPHASE
        }
    }

    private fun emptyScore(phase: BootstrapPhase, t: PhaseThresholds) = TrustScore(
        totalScore = 0.0,
        maturityScore = 0.0,
        diversityScore = 0.0,
        qualityScore = 0.0,
        activityScore = 0.0,
        totalBadges = 0,
        uniqueMeetups = 0,
        uniqueSigners = 0,
        uniqueCities = 0,
        accountAgeDays = 0,
        meetsPromotionThreshold = false,
        promotionReason = TrustScoreCalculator.REASON_EMPTY,
        badgeValues = emptyList(),
        currentPhase = phase,
        activeThresholds = t,
        progress = linkedMapOf(
            "badges" to PromotionProgress("Badges", 0, t.minBadges),
            "meetups" to PromotionProgress("Meetups", 0, t.minUniqueMeetups),
            "signers" to PromotionProgress("Ersteller", 0, t.minUniqueSigners),
            "age" to PromotionProgress("Tage", 0, t.minAccountAgeDays),
            "score" to PromotionProgress("Score", 0, t.promotionScore.dartRound().toInt()),
        ),
    )

    private fun buildReason(key: String, p: PromotionProgress): String {
        val c = p.current
        val r = p.required
        return when (key) {
            "badges" -> "Noch ${r - c} Badges sammeln ($c/$r)"
            "meetups" -> "Noch ${r - c} verschiedene Meetups besuchen ($c/$r)"
            "signers" -> "Badges von ${r - c} weiteren Erstellern nötig ($c/$r)"
            "age" -> "Noch ${r - c} Tage warten ($c/$r Tage)"
            "score" -> "Trust Score $c / $r"
            else -> "${p.label}: $c/$r"
        }
    }

    companion object {
        private const val TAG = "TrustScore"
        private val LN2 = ln(2.0)

        /** Dart `_badgeToEventId`: key into the co-attestor map (public as [TrustScoreCalculator.coAttestorKey]). */
        fun badgeToEventId(badge: MeetupBadge, zone: ZoneId): String {
            val day = badge.date.toIso8601String(zone).substring(0, 10)
            return DartString.lower(badge.meetupName).replace(" ", "-") + "-" + day
        }

        /** Dart `DateTime(2024)`: LOCAL midnight of 2024-01-01 in [zone], the week-cap anchor. */
        internal fun weekAnchor(zone: ZoneId): DartDateTime = DartDateTime.local(2024, zone = zone)

        /** Week bucket of the frequency cap: `date.difference(anchor).inDays ~/ 7`. */
        internal fun weekNumber(date: DartDateTime, anchor: DartDateTime): Long = date.difference(anchor).inDays / 7

        /**
         * Dart `round()` narrowed to `Int`, failing closed: NaN and ±Infinity → 0 (Dart throws
         * there, so the score criterion is never met in Flutter); finite values rounded half
         * away from zero and clamped to the `Int` range (Dart's int64 result never wraps, so
         * `met` keeps the Dart sign).
         */
        internal fun roundForProgress(x: Double): Int = when {
            x.isNaN() || x.isInfinite() -> 0
            else -> x.dartRound().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }
    }
}
