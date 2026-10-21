package space.einundzwanzig.meetup.core.ui.format

import space.einundzwanzig.meetup.core.common.dart.dartRound
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class FormatsTest {

    @Test
    fun `groupInt vectors (spec 13 §10, spec 12 §10)`() {
        val cases = mapOf(
            0L to "0", 12L to "12", 123L to "123", 999L to "999", 1000L to "1.000", 1234L to "1.234",
            12345L to "12.345", 95000L to "95.000", 123456L to "123.456", 1234567L to "1.234.567",
            1000000L to "1.000.000", -1L to "-1", -1234L to "-1.234", -123456L to "-123.456",
        )
        cases.forEach { (value, expected) -> assertEquals("$value", expected, Formats.groupInt(value)) }
        assertEquals("-9.223.372.036.854.775.808", Formats.groupInt(Long.MIN_VALUE))
    }

    @Test
    fun `converter line`() {
        // price 56200 EUR -> sats per unit round(1e8 / 56200) = 1779
        val sats = (100_000_000.0 / 56_200).dartRound()
        assertEquals(1779L, sats)
        assertEquals("1 € = 1.779 sats", "1 € = ${Formats.sats(sats)}")
    }

    @Test
    fun `block height for wallet cards (spec 03 §10_3)`() {
        val cases = mapOf(-5L to "---", 0L to "---", 7L to "7", 999L to "999", 1000L to "1.000", 875000L to "875.000", 1234567L to "1.234.567")
        cases.forEach { (h, expected) -> assertEquals("$h", expected, Formats.blockHeight(h)) }
    }

    @Test
    fun `dates are locale independent`() {
        val date = LocalDate.of(2025, 3, 5)
        assertEquals("5.3.2025", Formats.dateShort(date))
        assertEquals("05.03.2025", Formats.datePadded(date))
        assertEquals("15.12.2025", Formats.dateShort(LocalDate.of(2025, 12, 15)))
        assertEquals("05.03.2025  07:08", Formats.dateTimePadded(LocalDateTime.of(2025, 3, 5, 7, 8, 9)))
        assertEquals("23:59", Formats.time(LocalDateTime.of(2025, 3, 5, 23, 59)))
    }

    @Test
    fun `dartRound rounds ties away from zero`() {
        assertEquals(1L, (0.5).dartRound())
        assertEquals(-1L, (-0.5).dartRound())
        assertEquals(3L, (2.5).dartRound())
        assertEquals(-3L, (-2.5).dartRound())
        assertEquals(125L, (124.5).dartRound())
        assertEquals(813L, (812.5).dartRound())
        assertEquals(1235L, (1234.5).dartRound())
        assertEquals(0L, (0.49999999999999994).dartRound())
        assertEquals(0L, (-0.04).dartRound())
        assertEquals(2L, (2.4999).dartRound())
    }
}
