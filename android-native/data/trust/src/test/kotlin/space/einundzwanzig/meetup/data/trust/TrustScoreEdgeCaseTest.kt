package space.einundzwanzig.meetup.data.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.DartDuration
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.trust.internal.DefaultTrustScoreCalculator
import space.einundzwanzig.meetup.data.trust.score.CoAttestorData
import space.einundzwanzig.meetup.data.trust.score.TrustConfig
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Paths the Dart suite does not reach: non-finite and > Int scores from future-dated badges,
 * the zone-dependent week cap and co-attestor keys, and the co-attestor bonus path.
 */
class TrustScoreEdgeCaseTest {

    private val nowDate = DartDateTime.parse("2026-10-20T12:00:00.000Z")
    private val time = FakeTime(nowDate.microsecondsSinceEpoch)
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    private fun badge(
        id: String,
        meetupName: String = "Aschaffenburg",
        date: DartDateTime,
        signerNpub: String = "npub1testsigner",
    ) = MeetupBadge(
        id = id,
        meetupName = meetupName,
        date = date,
        iconPath = "",
        signerNpub = signerNpub,
        sigVersion = 2,
        sig = "a".repeat(128),
        adminPubkey = "b".repeat(64),
    )

    private fun daysAgo(d: Long) = nowDate.subtract(DartDuration.of(days = d))

    // ---- Non-finite / huge totals (Dart round() would throw or saturate at int64)

    @Test
    fun farFutureBadge_doesNotThrow_negativeMaturity() = withDefaultZone(ZoneOffset.UTC) {
        val calc = TrustScoreCalculator.create(time, UTC_ZONE)
        val score = calc.calculate(listOf(badge("f", date = DartDateTime.utc(2600))))
        // firstBadgeDate = 2600 → maturity hugely negative → Infinity × negative.
        assertEquals(Double.NEGATIVE_INFINITY, score.totalScore, 0.0)
        assertEquals("NEU", score.level)
        assertEquals(Int.MIN_VALUE, score.progress.getValue("score").current)
        assertFalse(score.meetsPromotionThreshold)
    }

    @Test
    fun farFutureBadge_withPastAccountStart_isVeteran() = withDefaultZone(ZoneOffset.UTC) {
        val calc = TrustScoreCalculator.create(time, UTC_ZONE)
        val score = calc.calculate(listOf(badge("f", date = DartDateTime.utc(2600))), daysAgo(365), null)
        assertEquals(Double.POSITIVE_INFINITY, score.totalScore, 0.0)
        assertEquals("VETERAN", score.level)
        assertEquals(Int.MAX_VALUE, score.progress.getValue("score").current)
        assertTrue(score.progress.getValue("score").met)
    }

    @Test
    fun scoreAboveIntRange_clampsInsteadOfWrapping() = withDefaultZone(ZoneOffset.UTC) {
        val calc = TrustScoreCalculator.create(time, UTC_ZONE)
        // ~16 years ahead: decay ≈ 2^32, total ≈ 1e10 > 2^31.
        val score = calc.calculate(listOf(badge("f", date = DartDateTime.utc(2042, 10, 20))), daysAgo(365), null)
        assertTrue(score.totalScore.isFinite())
        assertTrue(score.totalScore > Int.MAX_VALUE.toDouble())
        val p = score.progress.getValue("score")
        assertEquals(Int.MAX_VALUE, p.current)
        assertTrue(p.met)
    }

    @Test
    fun roundForProgress() {
        assertEquals(0, DefaultTrustScoreCalculator.roundForProgress(Double.NaN))
        assertEquals(Int.MAX_VALUE, DefaultTrustScoreCalculator.roundForProgress(Double.POSITIVE_INFINITY))
        assertEquals(Int.MIN_VALUE, DefaultTrustScoreCalculator.roundForProgress(Double.NEGATIVE_INFINITY))
        assertEquals(Int.MAX_VALUE, DefaultTrustScoreCalculator.roundForProgress(3e9))
        assertEquals(Int.MIN_VALUE, DefaultTrustScoreCalculator.roundForProgress(-3e9))
        assertEquals(3, DefaultTrustScoreCalculator.roundForProgress(2.5))
        assertEquals(-3, DefaultTrustScoreCalculator.roundForProgress(-2.5))
        assertEquals(2, DefaultTrustScoreCalculator.roundForProgress(2.49))
    }

    // ---- Zone-dependent week cap

    private fun zeroedIds(zone: ZoneId): Set<String> = withDefaultZone(zone) {
        val t = FakeTime(DartDateTime.parse("2024-01-20T12:00:00.000Z").microsecondsSinceEpoch)
        val calc = TrustScoreCalculator.create(t, ZoneIdProvider { zone })
        val badges = listOf(
            badge("b1", meetupName = "Berlin", date = DartDateTime.parse("2024-01-07T23:30:00.000Z")),
            badge("b2", meetupName = "Potsdam", date = DartDateTime.parse("2024-01-08T00:30:00.000Z")),
            badge("b3", meetupName = "Leipzig", date = DartDateTime.parse("2024-01-10T12:00:00.000Z")),
        )
        val score = calc.calculate(badges, DartDateTime.parse("2023-06-01T00:00:00.000Z"), null)
        assertEquals(3, score.badgeValues.size)
        score.badgeValues.filter { it.totalValue == 0.0 }.mapTo(HashSet()) { it.badgeId }
    }

    @Test
    fun weekCap_usesLocalAnchorOfInjectedZone() {
        // Berlin anchor 2023-12-31T23:00Z: all three badges in week 1 → the oldest (b1) is capped.
        assertEquals(setOf("b1"), zeroedIds(berlin))
        // UTC anchor 2024-01-01T00:00Z: b1 falls in week 0 → nothing capped.
        assertEquals(emptySet<String>(), zeroedIds(ZoneOffset.UTC))
        assertEquals(TrustConfig.MAX_BADGES_PER_WEEK, 2)
    }

    @Test
    fun weekNumber_berlinVsUtc() {
        val d = DartDateTime.parse("2024-01-07T23:30:00.000Z")
        assertEquals(1L, DefaultTrustScoreCalculator.weekNumber(d, DefaultTrustScoreCalculator.weekAnchor(berlin)))
        assertEquals(0L, DefaultTrustScoreCalculator.weekNumber(d, DefaultTrustScoreCalculator.weekAnchor(ZoneOffset.UTC)))
    }

    // ---- Co-attestors

    @Test
    fun coAttestorKey_lowercasesReplacesSpacesAndUsesLocalDate() {
        // Stored badge dates are local values: the key's day depends on the zone.
        val b = badge("k", meetupName = "Bad Ischl", date = DartDateTime.parse("2026-10-13T23:30:00.000Z").toLocal())
        assertEquals("bad-ischl-2026-10-13", TrustScoreCalculator.coAttestorKey(b, ZoneOffset.UTC))
        assertEquals("bad-ischl-2026-10-14", TrustScoreCalculator.coAttestorKey(b, berlin))
        // A UTC value prints its UTC day in every zone (Dart toIso8601String).
        val u = badge("k", meetupName = "Bad Ischl", date = DartDateTime.parse("2026-10-13T23:30:00.000Z"))
        assertEquals("bad-ischl-2026-10-13", TrustScoreCalculator.coAttestorKey(u, berlin))
        val umlaut = badge("u", meetupName = "ÖSTERREICH Süd", date = DartDateTime.parse("2026-10-13T12:00:00.000Z"))
        assertEquals("österreich-süd-2026-10-13", TrustScoreCalculator.coAttestorKey(umlaut, ZoneOffset.UTC))
    }

    @Test
    fun coAttestors_feedBonusesAndQuality() = withDefaultZone(ZoneOffset.UTC) {
        val calc = TrustScoreCalculator.create(time, UTC_ZONE)
        val b = badge("c", meetupName = "Bad Ischl", date = DartDateTime.parse("2026-10-13T12:00:00.000Z"))
        val co = mapOf("bad-ischl-2026-10-13" to CoAttestorData("x", 3, 2, emptyList()))
        val score = calc.calculate(listOf(b), b.date, co)
        val bv = score.badgeValues.single()
        assertEquals(2.0, bv.coAttestorBonus, 1e-12)
        assertEquals(1.6, bv.signerBonus, 1e-12)
        assertEquals(3, bv.coAttestorCount)
        assertEquals(2, bv.veteranCount)
        assertEquals(1.92, score.qualityScore, 1e-12)
        val expectedTotal = 2.0 * 1.6 * bv.decayFactor
        assertEquals(expectedTotal, bv.totalValue, 1e-12)

        // A key in the wrong zone/format yields no bonus.
        val miss = calc.calculate(listOf(b), b.date, mapOf("Bad Ischl-2026-10-13" to CoAttestorData("x", 3, 2, emptyList())))
        assertEquals(1.0, miss.badgeValues.single().coAttestorBonus, 1e-12)
        assertEquals(1.2, miss.qualityScore, 1e-12)
    }

    @Test
    fun suspiciousPatterns_noCoAttestors() = withDefaultZone(ZoneOffset.UTC) {
        val calc = TrustScoreCalculator.create(time, UTC_ZONE)
        val badges = listOf(
            badge("1", meetupName = "Linz", date = daysAgo(30), signerNpub = "npub1a"),
            badge("2", meetupName = "Wien", date = daysAgo(20), signerNpub = "npub1b"),
            badge("3", meetupName = "Graz", date = daysAgo(10), signerNpub = "npub1c"),
        )
        assertTrue(calc.suspiciousPatterns(badges, emptyMap()).contains("Kein Badge hat Co-Attestors"))
        assertFalse(calc.suspiciousPatterns(badges, null).contains("Kein Badge hat Co-Attestors"))
        val oneShared = mapOf(TrustScoreCalculator.coAttestorKey(badges[1], ZoneOffset.UTC) to CoAttestorData("w", 2, 0, emptyList()))
        assertFalse(calc.suspiciousPatterns(badges, oneShared).contains("Kein Badge hat Co-Attestors"))
        // Two badges are not enough for the warning.
        assertFalse(calc.suspiciousPatterns(badges.take(2), emptyMap()).contains("Kein Badge hat Co-Attestors"))
    }
}
