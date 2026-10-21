package space.einundzwanzig.meetup.data.trust.score

import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.util.DartSort
import space.einundzwanzig.meetup.data.trust.ZoneIdProvider
import space.einundzwanzig.meetup.data.trust.internal.DefaultTrustScoreCalculator
import java.time.ZoneId

/**
 * Local Trust Score v3 (Dart `TrustScoreService`, spec 04 §8.1–§8.3). Pure computation: the
 * only inputs besides the arguments are the injected clock and device zone.
 *
 * Inject it (`@Singleton`), or build one with [create] in tests and non-Hilt code.
 */
interface TrustScoreCalculator {

    /**
     * Dart `calculateScore`. Counts only `isNostrSigned && !isOrganizer` badges, de-duplicated
     * per `BadgeIdentity.identityKey` (oldest wins); applies the 2-per-week cap, the 26-week
     * decay and the presence factor; checks the phase thresholds.
     *
     * @param firstBadgeDate start of the account age. Callers pass [earliestDate] over **all**
     *   badges (organizer markers and legacy included), like every Flutter caller.
     * @param coAttestors keyed by [coAttestorKey] (`lower(meetupName).replace(' ', '-') + '-' +
     *   yyyy-MM-dd` in the device zone); `null` in production.
     *
     * Never throws for extreme badge dates: where Dart's `round()` would throw on a non-finite
     * `totalScore` (badges dated centuries ahead), the `"score"` progress saturates to
     * `Int.MAX_VALUE`/`Int.MIN_VALUE` (NaN → 0); finite values beyond the `Int` range are
     * clamped, never wrapped.
     */
    fun calculate(
        badges: List<MeetupBadge>,
        firstBadgeDate: DartDateTime?,
        coAttestors: Map<String, CoAttestorData>? = null,
    ): TrustScore

    /**
     * [calculate] with `firstBadgeDate = earliestDate(badges)`: the call every Flutter screen
     * makes (home, QR, card, admin verifier). Never throws, see the other overload.
     */
    fun calculate(badges: List<MeetupBadge>): TrustScore = calculate(badges, earliestDate(badges), null)

    /** Dart `calculateBridgeScore` (unused in production): distinct non-event meetups × max(distinct signers, 1). */
    fun bridgeScore(badges: List<MeetupBadge>): Double

    /**
     * Dart `detectSuspiciousPatterns` (unused in production): German warnings
     * (`Alle Badges von nur einem Ersteller`, `N Badges an einem Tag`, `Kein Badge hat Co-Attestors`).
     */
    fun suspiciousPatterns(badges: List<MeetupBadge>, coAttestors: Map<String, CoAttestorData>?): List<String>

    companion object {
        /** Progress keys in their significant order. */
        val PROGRESS_KEYS: List<String> = listOf("badges", "meetups", "signers", "age", "score")

        /** Reason when every criterion is met. */
        const val REASON_ALL_MET: String = "Alle Bedingungen erfüllt! Du kannst jetzt Meetup-Tags erstellen."

        /** Reason of the empty score. */
        const val REASON_EMPTY: String = "Besuche Meetups und sammle Badges!"

        /**
         * The date the Flutter callers pass as `firstBadgeDate`: the first element after a Dart
         * `List.sort` by date over all badges, or `null` for an empty list.
         */
        fun earliestDate(badges: List<MeetupBadge>): DartDateTime? {
            if (badges.isEmpty()) return null
            return DartSort.sorted(badges) { a, b -> a.date.compareTo(b.date) }.first().date
        }

        /**
         * Key of a badge in the `coAttestors` map (Dart `_badgeToEventId`):
         * `DartString.lower(meetupName).replace(' ', '-') + '-' + yyyy-MM-dd`, the date taken in
         * [zone] (pass the same zone the calculator uses, i.e. `ZoneIdProvider.zone()`).
         */
        fun coAttestorKey(badge: MeetupBadge, zone: ZoneId): String =
            DefaultTrustScoreCalculator.badgeToEventId(badge, zone)

        /** A calculator for [time] and [zone] (tests, workers). */
        fun create(
            time: TimeSource,
            zone: ZoneIdProvider = ZoneIdProvider.SYSTEM,
            log: DiagnosticLog = AppLogger,
        ): TrustScoreCalculator = DefaultTrustScoreCalculator(time, zone, log)
    }
}
