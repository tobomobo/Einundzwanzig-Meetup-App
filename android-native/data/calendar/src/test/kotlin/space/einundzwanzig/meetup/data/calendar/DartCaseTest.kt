package space.einundzwanzig.meetup.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import space.einundzwanzig.meetup.data.calendar.internal.dartLower
import space.einundzwanzig.meetup.data.calendar.internal.dartUpper

/**
 * Pins the spec 00 item 12 case-mapping vectors for the module-local `dartLower`/`dartUpper`
 * until the canonical `DartString.lower/upper` moves to `:core:common` and replaces them.
 */
class DartCaseTest {

    @Test
    fun `dotted capital I lowers to plain i`() {
        assertEquals("i", "İ".dartLower())
    }

    @Test
    fun `no final sigma rule`() {
        assertEquals("οδοσ", "ΟΔΟΣ".dartLower())
    }

    @Test
    fun `sharp s stays on upper`() {
        assertEquals("ß", "ß".dartUpper())
        assertEquals("STRAßE", "straße".dartUpper())
    }

    @Test
    fun `ascii hex and address input`() {
        assertEquals("31923:abcdef0123:meetup-x", "31923:ABCDEF0123:Meetup-X".dartLower())
    }
}
