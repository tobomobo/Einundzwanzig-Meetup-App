package space.einundzwanzig.meetup.data.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.einundzwanzig.meetup.data.trust.level.TrustLevel
import space.einundzwanzig.meetup.data.trust.level.TrustLevelColor
import space.einundzwanzig.meetup.data.trust.level.TrustLevelIcon
import space.einundzwanzig.meetup.data.trust.level.TrustLevelStyle

/** Level mapping of spec 04 §2.0. */
class TrustLevelTest {

    @Test
    fun styles() {
        assertEquals(TrustLevelStyle(TrustLevelIcon.BOLT, TrustLevelColor.AMBER, 0xFFFFC107), TrustLevelStyle.forWire("VETERAN"))
        assertEquals(TrustLevelStyle(TrustLevelIcon.SHIELD, TrustLevelColor.SUCCESS_GREEN, 0xFF4CAF50), TrustLevelStyle.forWire("ETABLIERT"))
        assertEquals(TrustLevelStyle(TrustLevelIcon.LOCAL_FIRE_DEPARTMENT, TrustLevelColor.COMMUNITY_GOLD, 0xFFD4A017), TrustLevelStyle.forWire("AKTIV"))
        assertEquals(TrustLevelStyle(TrustLevelIcon.ECO, TrustLevelColor.BITCOIN_ORANGE, 0xFFF7931A), TrustLevelStyle.forWire("STARTER"))
        assertEquals(TrustLevelStyle(TrustLevelIcon.FIBER_NEW, TrustLevelColor.NEUTRAL_GREY, 0xFF9E9E9E), TrustLevelStyle.forWire("NEU"))
        assertEquals(TrustLevelStyle.OTHER, TrustLevelStyle.forWire(""))
        assertEquals(TrustLevelStyle.OTHER, TrustLevelStyle.forWire("veteran"))
    }

    @Test
    fun labels() {
        assertEquals("levelNew", TrustLevelStyle.labelKeyForWire("NEU"))
        assertEquals("levelStarter", TrustLevelStyle.labelKeyForWire("STARTER"))
        assertEquals("levelActive", TrustLevelStyle.labelKeyForWire("AKTIV"))
        assertEquals("levelEstablished", TrustLevelStyle.labelKeyForWire("ETABLIERT"))
        assertEquals("levelVeteran", TrustLevelStyle.labelKeyForWire("VETERAN"))
        assertNull(TrustLevelStyle.labelKeyForWire("LEGENDE"))
    }

    @Test
    fun scales() {
        assertEquals(TrustLevel.NEU, TrustLevel.forLocalScore(2.99))
        assertEquals(TrustLevel.STARTER, TrustLevel.forLocalScore(3.0))
        assertEquals(TrustLevel.STARTER, TrustLevel.forBasicScore(0.01))
        assertEquals(TrustLevel.VETERAN, TrustLevel.forBasicScore(8.0))
        assertEquals(TrustLevel.ETABLIERT, TrustLevel.forLocalScore(39.99))
    }
}
