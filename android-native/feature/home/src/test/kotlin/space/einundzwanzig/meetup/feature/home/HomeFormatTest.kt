package space.einundzwanzig.meetup.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.data.mempool.format.BtcFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** Spec 12 §10 items 4, 6, 7, 8 and the score rounding of §7. */
class HomeFormatTest {

    private fun at(s: String): ZonedDateTime = LocalDateTime.parse(s).atZone(BERLIN)

    @Test
    fun `daysUntil counts calendar days across DST`() {
        assertEquals(1, HomeFormat.daysUntil(at("2026-10-25T19:00").toInstant(), at("2026-10-24T20:00")))
        assertEquals(1, HomeFormat.daysUntil(at("2026-03-30T09:00").toInstant(), at("2026-03-29T10:00")))
        assertEquals(2, HomeFormat.daysUntil(at("2026-03-30T08:00").toInstant(), at("2026-03-28T12:00")))
        assertEquals(0, HomeFormat.daysUntil(at("2026-10-24T08:00").toInstant(), at("2026-10-24T20:00")))
        assertEquals(-1, HomeFormat.daysUntil(at("2026-10-23T23:00").toInstant(), at("2026-10-24T00:30")))
    }

    @Test
    fun `fav label font size by length`() {
        assertEquals(13, HomeFormat.favLabelFontSize(21))
        assertEquals(15, HomeFormat.favLabelFontSize(16))
        assertEquals(17, HomeFormat.favLabelFontSize(11))
        assertEquals(22, HomeFormat.favLabelFontSize(10))
    }

    @Test
    fun `session time label`() {
        assertEquals("1h 02m", HomeFormat.sessionTimeLeft(3725))
        assertEquals("0h 00m", HomeFormat.sessionTimeLeft(59))
        assertEquals("0h 00m", HomeFormat.sessionTimeLeft(-5))
    }

    @Test
    fun `fav date is unpadded`() {
        assertEquals("5.3.2026", HomeFormat.favDate(LocalDate.of(2026, 3, 5)))
    }

    @Test
    fun `converter tile and grouping`() {
        assertEquals("1 € = 1.779 sats", BtcFormat.converterTile(56_200.0, "EUR"))
        assertEquals(null, BtcFormat.converterTile(0.0, "EUR"))
        assertEquals("95.000", BtcFormat.fmtInt(95_000))
    }

    @Test
    fun `score uses Dart toStringAsFixed`() {
        assertEquals("1.4", 1.45.toStringAsFixed(1))
        assertEquals("2.3", 2.25.toStringAsFixed(1))
    }

    @Test
    fun `midnight refresh fires two seconds after local midnight`() {
        assertEquals(2_000 + 60_000, HomeFormat.millisUntilMidnightRefresh(at("2026-10-24T23:59")))
        // 2026-10-25 is 25 h long in Berlin (DST end): from 00:00 to the next midnight.
        assertEquals(25 * 3_600_000L + 2_000, HomeFormat.millisUntilMidnightRefresh(at("2026-10-25T00:00")))
    }

    @Test
    fun `nostr dot after 24 h, badge cap, proximity`() {
        assertTrue(HomeFormat.isNostrNew(0, 100_000))
        assertFalse(HomeFormat.isNostrNew(100_000 - 86_400, 100_000))
        assertTrue(HomeFormat.isNostrNew(100_000 - 86_401, 100_000))
        assertEquals("99+", HomeFormat.badgeText(100))
        assertEquals("99", HomeFormat.badgeText(99))
        assertEquals(0, HomeFormat.proximity(0))
        assertEquals(1, HomeFormat.proximity(3))
        assertEquals(2, HomeFormat.proximity(4))
    }
}
