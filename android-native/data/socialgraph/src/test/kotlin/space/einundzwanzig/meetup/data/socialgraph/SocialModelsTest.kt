package space.einundzwanzig.meetup.data.socialgraph

import org.junit.Assert.assertEquals
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.data.socialgraph.internal.DefaultSocialGraphService
import space.einundzwanzig.meetup.data.socialgraph.social.SocialAnalysis
import space.einundzwanzig.meetup.data.socialgraph.social.SocialStats

/** Spec 05 §10.2 socialScore / getMyStats vectors and the `social` JSON. */
class SocialModelsTest {

    private fun analysis(mutual: Boolean = false, iFollow: Boolean = false, followsMe: Boolean = false, common: Int = 0, org: Int = 0) =
        SocialAnalysis("t", 0, 0, iFollow || mutual, followsMe || mutual, mutual, common, org, -1)

    @Test
    fun `socialScore vectors`() {
        assertEquals(2.0, analysis(mutual = true, common = 5, org = 1).socialScore, 1e-12)
        assertEquals(1.3, analysis(iFollow = true, common = 0, org = 3).socialScore, 1e-12)
        assertEquals(1.0 / 6.0, analysis(common = 1).socialScore, 1e-12)
        assertEquals(1 + 1000.0 / 1005.0 + 1, analysis(mutual = true, common = 1000, org = 10).socialScore, 1e-12)
        assertEquals(0.0, SocialAnalysis.empty().socialScore, 0.0)
        assertEquals(0.3, analysis(followsMe = true).socialScore, 1e-12)
    }

    @Test
    fun `connectionLabel order`() {
        assertEquals("Gegenseitig verbunden", analysis(mutual = true, common = 3).connectionLabel)
        assertEquals("Du folgst", analysis(iFollow = true).connectionLabel)
        assertEquals("Folgt dir", analysis(followsMe = true).connectionLabel)
        assertEquals("4 gemeinsame Kontakte", analysis(common = 4).connectionLabel)
        assertEquals("Keine Verbindung", analysis().connectionLabel)
    }

    @Test
    fun `empty analysis`() {
        val e = SocialAnalysis.empty()
        assertEquals("", e.targetPubkey)
        assertEquals(-1, e.hops)
    }

    @Test
    fun `mutual estimate`() {
        assertEquals(105, DefaultSocialGraphService.estimateMutuals(7, 20, 300))
        assertEquals(0, DefaultSocialGraphService.estimateMutuals(0, 0, 0))
        // Half away from zero: 1/2 * 5 = 2.5 → 3.
        assertEquals(3, DefaultSocialGraphService.estimateMutuals(1, 2, 5))
    }

    @Test
    fun `SocialStats JSON keys and lenient parsing`() {
        val s = SocialStats(12, 5, 2)
        assertEquals("""{"follows":12,"mutuals":5,"org_followers":2}""", DartJson.encode(s.toJson()))
        assertEquals(s, SocialStats.fromJson(s.toJson()))
        assertEquals(SocialStats(0, 3, 0), SocialStats.fromJson(DartJson.decode("""{"follows":"x","mutuals":3,"org_followers":1.0}""")))
        assertEquals(SocialStats.empty(), SocialStats.fromJson(DartJson.decode("[]")))
    }
}
