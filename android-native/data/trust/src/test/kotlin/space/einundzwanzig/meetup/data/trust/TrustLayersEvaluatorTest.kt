package space.einundzwanzig.meetup.data.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.trust.layers.IdentityMeaning
import space.einundzwanzig.meetup.data.trust.layers.LayersInput
import space.einundzwanzig.meetup.data.trust.layers.LightningMeaning
import space.einundzwanzig.meetup.data.trust.layers.MeetupMeaning
import space.einundzwanzig.meetup.data.trust.layers.Nip05DomainKind
import space.einundzwanzig.meetup.data.trust.layers.Nip05LayerInput
import space.einundzwanzig.meetup.data.trust.layers.SocialLayerInput
import space.einundzwanzig.meetup.data.trust.layers.SocialMeaning
import space.einundzwanzig.meetup.data.trust.layers.TrustLayersEvaluator
import space.einundzwanzig.meetup.data.trust.layers.TrustSignalKind
import space.einundzwanzig.meetup.data.trust.layers.ZapLayerInput

/** `LayersEvaluatorTest` vectors of spec 04 §10.4 (Python reference) plus the §8.9 rules. */
class TrustLayersEvaluatorTest {

    private val tol = 1e-12

    private val l1 = LayersInput(badgeCount = 8, meetupCount = 4, signerCount = 3, boundBadges = 7, accountAgeDays = 120)

    private val l2 = l1.copy(
        zaps = ZapLayerInput(12, 5, 4, 3, 6, true),
        humanityVerified = true,
        social = SocialLayerInput(iFollow = true, followsMe = true, isMutual = true, commonContactCount = 8, orgFollowerCount = 2),
    )

    private val l3 = LayersInput(
        badgeCount = 10, meetupCount = 5, signerCount = 4, boundBadges = 10,
        zaps = ZapLayerInput(30, 20, 10, 8, 12, true),
        social = SocialLayerInput(iFollow = true, followsMe = true, isMutual = true, commonContactCount = 12, orgFollowerCount = 3),
        nip05 = Nip05LayerInput(true, "alice@einundzwanzig.space", Nip05DomainKind.COMMUNITY),
        platformProofCount = 2,
        platformProofs = linkedMapOf("telegram" to "alice", "x" to ""),
        accountAgeDays = 200,
    )

    @Test
    fun l1_badgesOnly() {
        val e = TrustLayersEvaluator.evaluate(l1)
        assertEquals(4.864010989010989, e.physicalScore!!, tol)
        assertEquals(0.5, e.identityScore!!, tol)
        assertEquals(2, e.activeLayerCount)
        assertEquals(1.9956043956043958, e.combinedScore, tol)
        assertEquals(TrustSignalKind.BUILDING, e.signal)
        assertEquals(TrustSignalKind.BUILDING, TrustLayersEvaluator.signal(l1))
        // Age alone makes the identity score positive, but the card says "missing" (PARITY R-20).
        assertFalse(e.hasIdentityData)
        assertEquals(IdentityMeaning.NONE, e.identityMeaning)
        assertEquals(MeetupMeaning.GOOD_DIVERSITY, e.meetupMeaning)
        assertFalse(e.allBadgesBound)
    }

    @Test
    fun l2_withZapsHumanitySocial() {
        val e = TrustLayersEvaluator.evaluate(l2)
        assertEquals(1.6590909090909092, e.lightningScore, tol)
        assertEquals(2.6153846153846154, e.socialScore, tol)
        assertEquals(4, e.activeLayerCount)
        assertEquals(3.064223276723277, e.combinedScore, tol)
        assertEquals(TrustSignalKind.SOLID, e.signal)
        assertEquals(LightningMeaning.BOTH, e.lightningMeaning)
        assertEquals(SocialMeaning.MUTUAL_MANY, e.socialMeaning)
    }

    @Test
    fun l3_allLayers() {
        val e = TrustLayersEvaluator.evaluate(l3)
        assertEquals(5.392857142857142, e.physicalScore!!, tol)
        assertEquals(2.0625, e.lightningScore, tol)
        assertEquals(2.7058823529411766, e.socialScore, tol)
        assertEquals(2.0, e.identityScore!!, tol)
        assertEquals(3.549238445378151, e.combinedScore, tol)
        assertEquals(TrustSignalKind.SOLID, e.signal)
        assertEquals(IdentityMeaning.NIP05_AND_PLATFORMS, e.identityMeaning)
        assertEquals(LightningMeaning.ACTIVE_ONLY, e.lightningMeaning)
        assertTrue(e.allBadgesBound)
    }

    @Test
    fun physicalScore_nullWithoutBadges() {
        assertNull(TrustLayersEvaluator.physicalScore(null, 3, 3, 3))
        assertNull(TrustLayersEvaluator.physicalScore(0, 3, 3, 3))
        assertEquals(MeetupMeaning.NONE, TrustLayersEvaluator.evaluate(LayersInput()).meetupMeaning)
    }

    @Test
    fun identityScore_nullWhenZero() {
        assertNull(TrustLayersEvaluator.identityScore(null, 0, 30))
        assertEquals(0.3, TrustLayersEvaluator.identityScore(null, 1, null)!!, tol)
        assertEquals(0.5, TrustLayersEvaluator.identityScore(null, 5, null)!!, tol)
        assertNull(TrustLayersEvaluator.identityScore(Nip05LayerInput(false, "x@y", Nip05DomainKind.COMMUNITY), null, null))
    }

    @Test
    fun nip05Scores() {
        assertEquals(1.0, Nip05LayerInput(true, "", Nip05DomainKind.COMMUNITY).score, 0.0)
        assertEquals(0.7, Nip05LayerInput(true, "", Nip05DomainKind.CUSTOM).score, 0.0)
        assertEquals(0.3, Nip05LayerInput(true, "", Nip05DomainKind.PUBLIC_PROVIDER).score, 0.0)
        assertEquals(0.2, Nip05LayerInput(true, "", Nip05DomainKind.UNKNOWN).score, 0.0)
        assertEquals(0.0, Nip05LayerInput(false, "", Nip05DomainKind.COMMUNITY).score, 0.0)
    }

    @Test
    fun signalTable() {
        assertEquals(TrustSignalKind.WEAK, TrustLayersEvaluator.signalFor(0, 0.0))
        assertEquals(TrustSignalKind.WEAK, TrustLayersEvaluator.signalFor(1, 2.99))
        assertEquals(TrustSignalKind.LIMITED, TrustLayersEvaluator.signalFor(1, 3.0))
        assertEquals(TrustSignalKind.BUILDING, TrustLayersEvaluator.signalFor(2, 3.99))
        assertEquals(TrustSignalKind.SOLID, TrustLayersEvaluator.signalFor(2, 5.0))
        assertEquals(TrustSignalKind.DEFAULT, TrustLayersEvaluator.signalFor(2, 4.5))
        assertEquals(TrustSignalKind.CONNECTED, TrustLayersEvaluator.signalFor(3, 4.0))
        assertEquals(TrustSignalKind.SOLID, TrustLayersEvaluator.signalFor(4, 3.0))
        assertEquals("rlBuildingLabel", TrustSignalKind.DEFAULT.labelKey)
        assertEquals("rlDefaultExpl", TrustSignalKind.DEFAULT.explanationKey)
    }

    @Test
    fun meaningsFirstMatch() {
        fun social(s: SocialLayerInput) = TrustLayersEvaluator.evaluate(LayersInput(social = s)).socialMeaning
        assertEquals(SocialMeaning.MUTUAL, social(SocialLayerInput(true, true, true, 3, 0)))
        assertEquals(SocialMeaning.COMMON, social(SocialLayerInput(false, false, false, 6, 0)))
        assertEquals(SocialMeaning.ONE_SIDED, social(SocialLayerInput(false, true, false, 0, 0)))
        assertEquals(SocialMeaning.ORG_FOLLOW, social(SocialLayerInput(false, false, false, 0, 1)))
        assertEquals(SocialMeaning.DEFAULT, social(SocialLayerInput(false, false, false, 2, 0)))
        assertEquals(SocialMeaning.NONE, social(SocialLayerInput(false, false, false, 0, 0)))

        val paid = TrustLayersEvaluator.evaluate(LayersInput(humanityVerified = true))
        assertEquals(LightningMeaning.PAID, paid.lightningMeaning)
        assertEquals(1, paid.activeLayerCount)

        val single = TrustLayersEvaluator.evaluate(LayersInput(badgeCount = 2, signerCount = 1))
        assertEquals(MeetupMeaning.SINGLE_ORGANIZER, single.meetupMeaning)
        assertFalse(single.hasSignerDiversity)

        val platOnly = TrustLayersEvaluator.evaluate(LayersInput(platformProofs = mapOf("telegram" to "a")))
        assertEquals(IdentityMeaning.PLATFORMS_ONLY, platOnly.identityMeaning)
        // A platform map without a count does not raise the identity score (PARITY).
        assertNull(platOnly.identityScore)
    }

    @Test
    fun negativeBadgeCount_withoutDiversity_hasNoMeaningText() {
        val ev = TrustLayersEvaluator.evaluate(LayersInput(badgeCount = -1, meetupCount = 1000, signerCount = 1))
        assertTrue(ev.hasMeetupData)
        assertEquals(MeetupMeaning.NO_TEXT, ev.meetupMeaning)
        val diverse = TrustLayersEvaluator.evaluate(LayersInput(badgeCount = -1, meetupCount = 100, signerCount = 100))
        assertEquals(MeetupMeaning.GOOD_DIVERSITY, diverse.meetupMeaning)
        val single = TrustLayersEvaluator.evaluate(LayersInput(badgeCount = 1, signerCount = 1))
        assertEquals(MeetupMeaning.SINGLE_ORGANIZER, single.meetupMeaning)
    }

    @Test
    fun hugeRemoteCounts_doNotWrap() {
        // Dart adds in int64: n / (n + 5) stays ~1 for counts at and beyond the Int limit.
        val big = Int.MAX_VALUE.toLong()
        val p = TrustLayersEvaluator.physicalScore(big, big, big, null)!!
        assertTrue("physicalScore $p", p > 6.99 && p <= 7.0)
        val pLong = TrustLayersEvaluator.physicalScore(Long.MAX_VALUE / 2, null, null, null)!!
        assertTrue("physicalScore $pLong", pLong > 2.99 && pLong <= 3.0)
        val e = TrustLayersEvaluator.evaluate(LayersInput(badgeCount = big, meetupCount = big, signerCount = big))
        assertTrue(e.hasMeetupData)
        assertEquals(MeetupMeaning.GOOD_DIVERSITY, e.meetupMeaning)

        val zaps = ZapLayerInput(Int.MAX_VALUE, Int.MAX_VALUE, 0, 0, 0, false)
        assertEquals(2L * Int.MAX_VALUE, zaps.totalCount)
        assertEquals("Sehr aktiv", zaps.activityLabel)
        assertEquals(1.5, zaps.lightningScore, 1e-6)
        val ez = TrustLayersEvaluator.evaluate(LayersInput(zaps = zaps))
        assertTrue(ez.hasLightningData)
        assertEquals(LightningMeaning.ACTIVE_ONLY, ez.lightningMeaning)

        val social = SocialLayerInput(false, false, false, Int.MAX_VALUE, 0)
        assertEquals(1.0, social.socialScore, 1e-6)
    }

    @Test
    fun activityLabel() {
        fun z(sent: Int, received: Int) = ZapLayerInput(sent, received, 0, 0, 0, false)
        assertEquals("Keine Zap-Aktivität", z(0, 0).activityLabel)
        assertEquals("Wenig Aktivität", z(1, 0).activityLabel)
        assertEquals("Wenig Aktivität", z(2, 2).activityLabel)
        assertEquals("Regelmäßig aktiv", z(3, 2).activityLabel)
        assertEquals("Regelmäßig aktiv", z(19, 0).activityLabel)
        assertEquals("Sehr aktiv", z(10, 10).activityLabel)
    }

    @Test
    fun domainLabel() {
        fun n(kind: Nip05DomainKind) = Nip05LayerInput(true, "a@b.c", kind)
        assertEquals("Community-verifiziert", n(Nip05DomainKind.COMMUNITY).domainLabel)
        assertEquals("Eigene Domain", n(Nip05DomainKind.CUSTOM).domainLabel)
        assertEquals("Public Provider", n(Nip05DomainKind.PUBLIC_PROVIDER).domainLabel)
        assertEquals("", n(Nip05DomainKind.UNKNOWN).domainLabel)
    }

    @Test
    fun platformLabel() {
        assertEquals("Telegram", TrustLayersEvaluator.platformLabel("telegram"))
        assertEquals("Satoshi-Kleinanzeigen", TrustLayersEvaluator.platformLabel("satoshikleinanzeigen"))
        assertEquals("RoboSats", TrustLayersEvaluator.platformLabel("robosats"))
        assertEquals("Nostr", TrustLayersEvaluator.platformLabel("nostr"))
        assertEquals("Andere", TrustLayersEvaluator.platformLabel("other"))
        assertEquals("x", TrustLayersEvaluator.platformLabel("x"))
    }
}
