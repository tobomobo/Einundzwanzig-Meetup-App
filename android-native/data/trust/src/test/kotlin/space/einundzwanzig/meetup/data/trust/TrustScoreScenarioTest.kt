package space.einundzwanzig.meetup.data.trust

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.trust.internal.DefaultTrustScoreCalculator
import space.einundzwanzig.meetup.data.trust.level.TrustLevel
import space.einundzwanzig.meetup.data.trust.score.BootstrapPhase
import space.einundzwanzig.meetup.data.trust.score.TrustConfig
import space.einundzwanzig.meetup.data.trust.score.TrustScore
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import java.time.ZoneId
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** `trustScoreScenarios`, `trustScoreEmpty` and `levelThresholdsLocal` of the spec 04 fixture (§10.4). */
class TrustScoreScenarioTest {

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private fun badgeOf(o: JsonObject) = MeetupBadge(
        id = o.str("id"),
        meetupName = o.str("meetupName"),
        date = DartDateTime.parse(o.str("date")),
        iconPath = "",
        signerNpub = o.str("signerNpub"),
        meetupEventId = o.str("meetupEventId"),
        sigVersion = o.getValue("sigVersion").jsonPrimitive.long,
        sig = o.str("sig"),
        adminPubkey = o.str("adminPubkey"),
        presenceVerified = o.getValue("presenceVerified").jsonPrimitive.boolean,
        isOrganizer = o.getValue("isOrganizer").jsonPrimitive.boolean,
        isEvent = o.getValue("isEvent").jsonPrimitive.boolean,
    )

    @Test
    fun scenarios() {
        val scenarios = Fixtures["trustScoreScenarios"].jsonArray
        assertEquals(3, scenarios.size)
        for (s in scenarios) runScenario(s.jsonObject)
    }

    private fun runScenario(s: JsonObject) {
        val name = s.str("name")
        val zone = ZoneId.of(s.str("timezone"))
        withDefaultZone(zone) {
            val time = FakeTime(DartDateTime.parse(s.str("now")).microsecondsSinceEpoch)
            val calc = TrustScoreCalculator.create(time, ZoneIdProvider { zone })
            val badges = s.getValue("badges").jsonArray.map { badgeOf(it.jsonObject) }
            val first = DartDateTime.parse(s.str("firstBadgeDate"))
            assertEquals(name, first, TrustScoreCalculator.earliestDate(badges))
            val tol = s.getValue("tolerance").jsonPrimitive.double
            val e = s.getValue("expected").jsonObject

            val score = calc.calculate(badges, first, null)
            assertEquals(score, calc.calculate(badges))

            assertEquals(name, BootstrapPhase.valueOf(e.str("phase").uppercase()), score.currentPhase)
            assertEquals(name, e.int("totalBadges"), score.totalBadges)
            assertEquals(name, e.int("uniqueMeetups"), score.uniqueMeetups)
            assertEquals(name, e.int("uniqueSigners"), score.uniqueSigners)
            assertEquals(name, e.int("uniqueCities"), score.uniqueCities)
            assertEquals(name, e.int("accountAgeDays"), score.accountAgeDays)
            assertEquals(name, e.dbl("activity"), score.activityScore, tol)
            assertEquals(name, e.dbl("maturity"), score.maturityScore, tol)
            assertEquals(name, e.dbl("diversity"), score.diversityScore, tol)
            // effectiveDiversity is internal; invert diversity = log2(eff+1)*log2(max(signers,1)+1)*W.
            val signerFactor = ln(max(score.uniqueSigners, 1) + 1.0) / ln(2.0)
            val effDiversity = 2.0.pow(score.diversityScore / (signerFactor * TrustConfig.WEIGHT_DIVERSITY)) - 1.0
            assertEquals("$name effDiversity", e.dbl("effDiversity"), effDiversity, 1e-9)
            assertEquals(name, e.dbl("quality"), score.qualityScore, tol)
            assertEquals(name, e.dbl("total"), score.totalScore, tol)
            assertEquals(name, e.str("level"), score.level)
            assertEquals(name, e.dbl("display"), score.displayScore, tol)
            assertEquals(name, e.getValue("meets").jsonPrimitive.boolean, score.meetsPromotionThreshold)
            assertEquals(name, e.str("reason"), score.promotionReason)
            assertEquals(name, e.dbl("promotionProgress"), score.promotionProgress, tol)

            val progress = e.getValue("progress").jsonArray.map { it.jsonObject }
            assertEquals(name, progress.map { it.str("key") }, score.progress.keys.toList())
            assertEquals(TrustScoreCalculator.PROGRESS_KEYS, score.progress.keys.toList())
            for (p in progress) {
                val actual = score.progress.getValue(p.str("key"))
                assertEquals("$name ${p.str("key")}", p.int("current"), actual.current)
                assertEquals("$name ${p.str("key")}", p.int("required"), actual.required)
                assertEquals("$name ${p.str("key")}", NON_EMPTY_LABELS.getValue(p.str("key")), actual.label)
            }

            val anchor = DefaultTrustScoreCalculator.weekAnchor(zone)
            val byId = badges.associateBy { it.id }
            val values = e.getValue("badgeValues_in_processing_order_date_desc").jsonArray.map { it.jsonObject }
            assertEquals(name, values.map { it.str("badgeId") }, score.badgeValues.map { it.badgeId })
            values.zip(score.badgeValues).forEach { (ev, bv) ->
                val week = DefaultTrustScoreCalculator.weekNumber(byId.getValue(bv.badgeId).date, anchor)
                assertEquals("$name ${bv.badgeId}", ev.getValue("weekNumber").jsonPrimitive.long, week)
                assertEquals("$name ${bv.badgeId}", ev.dbl("decayFactor"), bv.decayFactor, tol)
                assertEquals("$name ${bv.badgeId}", ev.dbl("totalValue"), bv.totalValue, tol)
                assertEquals("$name ${bv.badgeId}", ev.dbl("signerBonus"), bv.signerBonus, tol)
                assertEquals("$name ${bv.badgeId}", ev.dbl("coAttestorBonus"), bv.coAttestorBonus, tol)
            }
        }
    }

    @Test
    fun emptyScore() {
        val e = Fixtures["trustScoreEmpty"].jsonObject.getValue("expected").jsonObject
        val calc = TrustScoreCalculator.create(FakeTime(0), UTC_ZONE)
        val score = calc.calculate(emptyList(), null)
        assertEquals(BootstrapPhase.valueOf(e.str("phase").uppercase()), score.currentPhase)
        assertEquals(e.dbl("totalScore"), score.totalScore, 0.0)
        assertEquals(e.int("totalBadges"), score.totalBadges)
        assertEquals(e.str("level"), score.level)
        assertEquals(e.getValue("meetsPromotionThreshold").jsonPrimitive.boolean, score.meetsPromotionThreshold)
        assertEquals(e.str("promotionReason"), score.promotionReason)
        assertEquals(e.dbl("promotionProgress"), score.promotionProgress, 0.0)
        assertEquals(TrustConfig.thresholds(BootstrapPhase.KEIMPHASE), score.activeThresholds)
        val progress = e.getValue("progress").jsonArray.map { it.jsonObject }
        assertEquals(progress.map { it.str("key") }, score.progress.keys.toList())
        for (p in progress) {
            val actual = score.progress.getValue(p.str("key"))
            assertEquals(p.str("label"), actual.label)
            assertEquals(p.int("current"), actual.current)
            assertEquals(p.int("required"), actual.required)
        }
    }

    @Test
    fun onlyUntrustedBadges_emptyScoreKeepsPhase() {
        val calc = TrustScoreCalculator.create(FakeTime(DartDateTime.utc(2026, 10, 20).microsecondsSinceEpoch), UTC_ZONE)
        val marker = MeetupBadge("m", "Linz", DartDateTime.utc(2026, 10, 1), "", isOrganizer = true)
        val score = calc.calculate(listOf(marker))
        assertEquals(0.0, score.totalScore, 0.0)
        assertFalse(score.meetsPromotionThreshold)
        assertEquals(TrustScoreCalculator.REASON_EMPTY, score.promotionReason)
        assertEquals("Badges", score.progress.getValue("badges").label)
    }

    @Test
    fun levelThresholdsLocal() {
        for (row in Fixtures["levelThresholdsLocal"].jsonArray.map { it.jsonObject }) {
            val total = row.dbl("totalScore")
            val score = scoreWithTotal(total)
            assertEquals("$total", row.str("level"), score.level)
            assertEquals("$total", TrustLevel.fromWire(row.str("level")), score.trustLevel)
            assertEquals("$total", row.dbl("displayScore"), score.displayScore, 1e-12)
        }
    }

    private fun scoreWithTotal(total: Double) = TrustScore(
        totalScore = total, maturityScore = 0.0, diversityScore = 0.0, qualityScore = 0.0, activityScore = 0.0,
        totalBadges = 0, uniqueMeetups = 0, uniqueSigners = 0, uniqueCities = 0, accountAgeDays = 0,
        meetsPromotionThreshold = false, promotionReason = "", badgeValues = emptyList(),
        currentPhase = BootstrapPhase.KEIMPHASE,
        activeThresholds = TrustConfig.thresholds(BootstrapPhase.KEIMPHASE),
        progress = emptyMap(),
    )

    private companion object {
        /** Dart `trust_score_service.dart` progress labels of a non-empty score. */
        val NON_EMPTY_LABELS = mapOf(
            "badges" to "Badges (Nostr-verifiziert)",
            "meetups" to "Verschiedene Meetups",
            "signers" to "Verschiedene Ersteller",
            "age" to "Account-Alter (Tage)",
            "score" to "Trust Score",
        )
    }

    private fun JsonObject.int(k: String) = getValue(k).jsonPrimitive.int
    private fun JsonObject.dbl(k: String) = (getValue(k) as JsonPrimitive).double
}
