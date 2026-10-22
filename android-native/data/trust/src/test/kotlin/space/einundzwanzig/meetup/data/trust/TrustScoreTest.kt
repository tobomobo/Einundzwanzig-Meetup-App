package space.einundzwanzig.meetup.data.trust

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.DartDuration
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.trust.score.BootstrapPhase
import space.einundzwanzig.meetup.data.trust.score.PromotionProgress
import space.einundzwanzig.meetup.data.trust.score.TrustConfig
import space.einundzwanzig.meetup.data.trust.score.TrustScore
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import java.util.TimeZone

/** 1:1 port of `test/trust_score_test.dart` (spec 04 §10.1) with a fixed clock. */
class TrustScoreTest {

    private val time = FakeTime(DartDateTime.parse("2026-10-20T12:00:00.000Z").microsecondsSinceEpoch)
    private val calc = TrustScoreCalculator.create(time, UTC_ZONE)
    private lateinit var previousZone: TimeZone

    @Before
    fun pinZone() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(previousZone)

    private fun now(): DartDateTime = DartDateTime.now(time)
    private fun daysAgo(d: Long): DartDateTime = now().subtract(DartDuration.of(days = d))

    /** The Dart helper: sig `'a'*128`, adminPubkey `'b'*64`, sigVersion 2, date now−7 d. */
    private fun badge(
        id: String = "test",
        meetupName: String = "Aschaffenburg",
        date: DartDateTime? = null,
        blockHeight: Long = 875000,
        signerNpub: String = "npub1testsigner",
        sigVersion: Long = 2,
        sig: String = "",
        adminPubkey: String = "",
    ) = MeetupBadge(
        id = id,
        meetupName = meetupName,
        date = date ?: daysAgo(7),
        iconPath = "",
        blockHeight = blockHeight,
        signerNpub = signerNpub,
        sigVersion = sigVersion,
        sig = sig.ifEmpty { "a".repeat(128) },
        adminPubkey = adminPubkey.ifEmpty { "b".repeat(64) },
    )

    // ---- Leere/Keine Badges

    @Test
    fun noBadges_scoreZero() {
        val score = calc.calculate(emptyList(), null)
        assertEquals(0.0, score.totalScore, 0.0)
        assertEquals(0, score.totalBadges)
        assertFalse(score.meetsPromotionThreshold)
        assertEquals("NEU", score.level)
    }

    @Test
    fun onlyLegacyBadges_scoreZero() {
        val legacy = listOf(
            badge(id = "1", sigVersion = 1, sig = "legacy1".repeat(18) + "ab"),
            badge(id = "2", sigVersion = 1, sig = "legacy2".repeat(18) + "ab"),
            // Dart passes sig '' which the helper replaces with 'a'*128.
            badge(id = "3", sigVersion = 0, sig = ""),
        )
        val score = calc.calculate(legacy, daysAgo(60))
        assertEquals(0.0, score.totalScore, 0.0)
        assertEquals(0, score.totalBadges)
    }

    // ---- Bootstrap-Phasen

    @Test
    fun keimphase_oneSigner() {
        val badges = listOf(badge(id = "1", signerNpub = "npub1signer_a"), badge(id = "2", signerNpub = "npub1signer_a"))
        assertEquals(BootstrapPhase.KEIMPHASE, calc.calculate(badges, daysAgo(7)).currentPhase)
    }

    @Test
    fun wachstum_twoToFiveSigners() {
        val badges = listOf(
            badge(id = "1", signerNpub = "npub1signer_a"),
            badge(id = "2", signerNpub = "npub1signer_b"),
            badge(id = "3", signerNpub = "npub1signer_c"),
        )
        assertEquals(BootstrapPhase.WACHSTUM, calc.calculate(badges, daysAgo(30)).currentPhase)
    }

    @Test
    fun stabil_sixSigners() {
        val badges = List(6) { i -> badge(id = "$i", signerNpub = "npub1signer_$i") }
        assertEquals(BootstrapPhase.STABIL, calc.calculate(badges, daysAgo(90)).currentPhase)
    }

    // ---- Score-Berechnung

    @Test
    fun moreBadges_higherScore() {
        val few = listOf(badge(id = "1"))
        val many = listOf(
            badge(id = "1", meetupName = "A", signerNpub = "npub1a"),
            badge(id = "2", meetupName = "B", signerNpub = "npub1b"),
            badge(id = "3", meetupName = "C", signerNpub = "npub1c"),
        )
        val scoreFew = calc.calculate(few, now())
        val scoreMany = calc.calculate(many, daysAgo(30))
        assertTrue(scoreMany.totalScore > scoreFew.totalScore)
    }

    @Test
    fun olderAccount_higherMaturity() {
        val badges = listOf(badge(id = "1"))
        val young = calc.calculate(badges, now())
        val old = calc.calculate(badges, daysAgo(180))
        assertTrue(old.maturityScore >= young.maturityScore)
    }

    @Test
    fun differentMeetups_higherDiversity() {
        val same = listOf(badge(id = "1", meetupName = "A"), badge(id = "2", meetupName = "A"))
        val diverse = listOf(
            badge(id = "1", meetupName = "A", signerNpub = "npub1a"),
            badge(id = "2", meetupName = "B", signerNpub = "npub1b"),
        )
        assertTrue(calc.calculate(diverse, now()).diversityScore > calc.calculate(same, now()).diversityScore)
    }

    @Test
    fun oldBadges_decay() {
        val recent = listOf(badge(id = "1", date = daysAgo(1)))
        val old = listOf(badge(id = "1", date = daysAgo(365)))
        assertTrue(calc.calculate(recent, now()).activityScore > calc.calculate(old, daysAgo(365)).activityScore)
    }

    // ---- Frequency Cap

    @Test
    fun frequencyCap_twoPerWeek() {
        val date = daysAgo(1)
        val badges = listOf(
            badge(id = "1", date = date, meetupName = "A"),
            badge(id = "2", date = date, meetupName = "B"),
            badge(id = "3", date = date, meetupName = "C"),
        )
        val score = calc.calculate(badges, date)
        assertEquals(1, score.badgeValues.count { it.totalValue == 0.0 })
    }

    // ---- Promotion

    @Test
    fun promotionProgressKeys() {
        val badges = listOf(
            badge(id = "1", meetupName = "A", date = daysAgo(20)),
            badge(id = "2", meetupName = "B", date = daysAgo(14)),
            badge(id = "3", meetupName = "C", date = daysAgo(7)),
        )
        val score = calc.calculate(badges, daysAgo(20))
        assertTrue(score.progress.isNotEmpty())
        listOf("badges", "meetups", "signers", "age").forEach { assertTrue(it, score.progress.containsKey(it)) }
    }

    @Test
    fun promotionProgressPercentage() {
        val p = PromotionProgress("Test", 2, 4)
        assertEquals(0.5, p.percentage, 0.0)
        assertFalse(p.met)
        val met = PromotionProgress("Test", 5, 4)
        assertEquals(1.0, met.percentage, 0.0)
        assertTrue(met.met)
    }

    // ---- Bridge

    @Test
    fun bridge_lessThanThree() {
        assertEquals(0.0, calc.bridgeScore(listOf(badge(id = "1"), badge(id = "2"))), 0.0)
    }

    @Test
    fun bridge_singleMeetup() {
        val badges = listOf(badge(id = "1", meetupName = "A"), badge(id = "2", meetupName = "A"), badge(id = "3", meetupName = "A"))
        assertEquals(0.0, calc.bridgeScore(badges), 0.0)
    }

    @Test
    fun bridge_differentMeetupsAndSigners() {
        val score = calc.bridgeScore(
            listOf(
                badge(id = "1", meetupName = "A", signerNpub = "npub1x"),
                badge(id = "2", meetupName = "B", signerNpub = "npub1y"),
                badge(id = "3", meetupName = "C", signerNpub = "npub1z"),
            ),
        )
        assertTrue(score > 0)
        assertEquals(9.0, score, 0.0) // 3 cities × 3 signers
    }

    // ---- Sybil

    @Test
    fun sybil_singleSigner() {
        val badges = List(4) { i -> badge(id = "$i", signerNpub = "npub1single") }
        assertTrue(calc.suspiciousPatterns(badges, null).any { it.contains("einem Ersteller") })
    }

    @Test
    fun sybil_manySameDay() {
        val date = now()
        val badges = List(4) { i -> badge(id = "$i", date = date, signerNpub = "npub1_$i") }
        assertTrue(calc.suspiciousPatterns(badges, null).any { it.contains("an einem Tag") })
    }

    @Test
    fun sybil_normalActivity() {
        val badges = listOf(
            badge(id = "1", signerNpub = "npub1a", date = daysAgo(30)),
            badge(id = "2", signerNpub = "npub1b", date = daysAgo(14)),
        )
        assertTrue(calc.suspiciousPatterns(badges, null).isEmpty())
    }

    @Test
    fun sybil_empty() {
        assertTrue(calc.suspiciousPatterns(emptyList(), null).isEmpty())
    }

    // ---- Level & Display

    private fun makeScore(total: Double, progress: Map<String, PromotionProgress> = emptyMap()) = TrustScore(
        totalScore = total, maturityScore = 0.0, diversityScore = 0.0, qualityScore = 0.0, activityScore = 0.0,
        totalBadges = 0, uniqueMeetups = 0, uniqueSigners = 0, uniqueCities = 0, accountAgeDays = 0,
        meetsPromotionThreshold = false, promotionReason = "", badgeValues = emptyList(),
        currentPhase = BootstrapPhase.KEIMPHASE,
        activeThresholds = TrustConfig.thresholds(BootstrapPhase.KEIMPHASE),
        progress = progress,
    )

    @Test
    fun levelMapping() {
        assertEquals("NEU", makeScore(0.0).level)
        assertEquals("STARTER", makeScore(3.0).level)
        assertEquals("AKTIV", makeScore(10.0).level)
        assertEquals("ETABLIERT", makeScore(20.0).level)
        assertEquals("VETERAN", makeScore(40.0).level)
    }

    @Test
    fun displayScore() {
        assertEquals(0.0, makeScore(0.0).displayScore, 0.0)
        assertEquals(5.0, makeScore(25.0).displayScore, 0.0)
        assertEquals(10.0, makeScore(50.0).displayScore, 0.0)
        assertEquals(10.0, makeScore(100.0).displayScore, 0.0)
    }

    @Test
    fun promotionProgressMean() {
        val half = makeScore(
            0.0,
            linkedMapOf("a" to PromotionProgress("A", 5, 10), "b" to PromotionProgress("B", 10, 10)),
        )
        assertEquals(0.75, half.promotionProgress, 0.0)
    }

    // ---- TrustConfig

    @Test
    fun config_allPhases() {
        BootstrapPhase.entries.forEach { assertTrue(TrustConfig.phases.containsKey(it)) }
    }

    @Test
    fun config_thresholdsRise() {
        val keim = TrustConfig.thresholds(BootstrapPhase.KEIMPHASE)
        val wachstum = TrustConfig.thresholds(BootstrapPhase.WACHSTUM)
        val stabil = TrustConfig.thresholds(BootstrapPhase.STABIL)
        assertTrue(wachstum.minBadges >= keim.minBadges)
        assertTrue(stabil.minBadges >= wachstum.minBadges)
        assertTrue(stabil.promotionScore > keim.promotionScore)
    }

    @Test
    fun config_frequencyCap() = assertEquals(2, TrustConfig.MAX_BADGES_PER_WEEK)

    @Test
    fun config_halfLife() = assertEquals(26.0, TrustConfig.HALF_LIFE_WEEKS, 0.0)
}
