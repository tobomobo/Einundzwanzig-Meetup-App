package space.einundzwanzig.meetup.data.mempool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.dartRound
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyFormat
import space.einundzwanzig.meetup.data.mempool.format.BtcFormat
import space.einundzwanzig.meetup.data.mempool.format.dartTryParseDouble
import java.time.ZoneOffset

/** Spec 13 §10.2 formatting vectors (grouping, symbols, toStringAsFixed, round, labels). */
class FormatTest {

    @Test
    fun groupInt() {
        val cases = listOf(
            0L to "0", 12L to "12", 123L to "123", 1234L to "1.234", 12345L to "12.345", 123456L to "123.456",
            1234567L to "1.234.567", 95000L to "95.000", 1000000L to "1.000.000", -1L to "-1",
            -1234L to "-1.234", -123456L to "-123.456",
        )
        for ((v, expected) in cases) {
            assertEquals(expected, CurrencyFormat.groupInt(v))
            if (v >= 0) assertEquals(expected, BtcFormat.fmtInt(v))
        }
    }

    @Test
    fun symbols() {
        val cases = mapOf(
            "EUR" to "€", "USD" to "$", "GBP" to "£", "JPY" to "¥", "CAD" to "CA$", "AUD" to "A$", "CHF" to "CHF", "XYZ" to "XYZ",
        )
        for ((code, symbol) in cases) assertEquals(symbol, CurrencyFormat.symbol(code))
    }

    @Test
    fun formatPrice() {
        assertEquals("95.000 €", CurrencyFormat.formatPrice(95000.4, "EUR"))
        assertEquals("84.000 CHF", CurrencyFormat.formatPrice(84000.0, "CHF"))
    }

    @Test
    fun toStringAsFixedReference() {
        // The shared :core:common helper must give the Dart reference values used on these screens.
        val cases = listOf(
            Triple(0.125, 0, "0"), Triple(0.125, 1, "0.1"), Triple(0.125, 2, "0.13"), Triple(0.125, 8, "0.12500000"),
            Triple(1.005, 0, "1"), Triple(1.005, 1, "1.0"), Triple(1.005, 2, "1.00"), Triple(1.005, 8, "1.00500000"),
            Triple(2.5, 0, "3"), Triple(2.5, 1, "2.5"), Triple(2.5, 2, "2.50"),
            Triple(-2.5, 0, "-3"), Triple(-2.5, 1, "-2.5"), Triple(-2.5, 2, "-2.50"),
            Triple(-0.04, 0, "-0"), Triple(-0.04, 1, "-0.0"), Triple(-0.04, 2, "-0.04"),
            Triple(-0.05, 0, "-0"), Triple(-0.05, 1, "-0.1"), Triple(-0.05, 2, "-0.05"),
            Triple(812.5, 0, "813"),
            Triple(1234.5, 0, "1235"), Triple(1234.5, 1, "1234.5"), Triple(1234.5, 2, "1234.50"),
            Triple(0.005, 2, "0.01"), Triple(0.015, 2, "0.01"), Triple(0.025, 2, "0.03"),
            Triple(2.675, 1, "2.7"), Triple(2.675, 2, "2.67"),
            Triple(1e-9, 8, "0.00000000"), Triple(1.23456789012, 8, "1.23456789"),
            Triple(-0.0, 1, "-0.0"), Triple(-0.0, 2, "-0.00"),
        )
        for ((x, n, expected) in cases) assertEquals("$x.toStringAsFixed($n)", expected, x.toStringAsFixed(n))
    }

    @Test
    fun dartRoundReference() {
        val cases = listOf(2.5 to 3L, -2.5 to -3L, 0.5 to 1L, -0.5 to -1L, 3.5 to 4L, 1.4999999 to 1L)
        for ((x, expected) in cases) assertEquals("$x.round()", expected, x.dartRound())
    }

    @Test
    fun difficultyLabels() {
        fun row(pct: Double) = BtcFormat.difficultyRow(DashboardMathTest.data(pct = pct))
        assertEquals("+3.7% / 1234 blk", row(3.71))
        assertEquals("-3.8% / 1234 blk", row(-3.75))
        assertEquals("+0.0% / 1234 blk", row(0.0))
        assertEquals("-0.0% / 1234 blk", row(-0.04))
        assertEquals(BtcFormat.DASH, BtcFormat.difficultyRow(DashboardMathTest.data(height = 0)))
        assertEquals("+3.7% / 1234", BtcFormat.difficultyWidget(DashboardMathTest.data()))
        assertEquals(BtcFormat.DASH, BtcFormat.difficultyWidget(DashboardMathTest.data(remaining = 0)))
    }

    @Test
    fun hashrateAndLightning() {
        assertEquals("812 EH/s", BtcFormat.hashrate(812345678901234567890.0 / 1e18))
        assertEquals(BtcFormat.DASH, BtcFormat.hashrate(0.0))
        assertEquals("5.123 BTC", BtcFormat.lnCapacity(512345678901.0 / 1e8))
        assertEquals("––", BtcFormat.DASH)
    }

    @Test
    fun feesAndTimes() {
        val d = DashboardMathTest.data()
        assertEquals("3·8·12", BtcFormat.fees(d))
        assertEquals("3·8·12", BtcFormat.feesRow(d))
        assertEquals(BtcFormat.DASH, BtcFormat.feesRow(d.copy(feeHigh = 0)))
        assertEquals("0·0·0", BtcFormat.fees(d.copy(feeLow = 0, feeMedium = 0, feeHigh = 0)))
        assertEquals("09:05", BtcFormat.hhmm(9 * 3_600_000L + 5 * 60_000L, ZoneOffset.UTC))
        assertEquals("--:--", BtcFormat.updatedLabel(null))
        assertEquals("--:--", BtcFormat.updatedLabel(d.copy(sourcesOk = 0)))
    }

    @Test
    fun converterTile() {
        assertEquals(1053L, BtcFormat.satsPerUnit(95000.0))
        assertEquals("1 € = 1.053 sats", BtcFormat.converterTile(95000.0, "EUR"))
        assertEquals(1190L, BtcFormat.satsPerUnit(84000.0))
        assertEquals(7L, BtcFormat.satsPerUnit(15000000.0))
        assertNull(BtcFormat.converterTile(0.0, "EUR"))
    }

    @Test
    fun dartDoubleTryParse() {
        val accepted = mapOf(
            "1." to 1.0, ".5" to 0.5, "+5" to 5.0, "1e3" to 1000.0, " 2.5 " to 2.5, "-0.5" to -0.5, "1E-2" to 0.01,
            "Infinity" to Double.POSITIVE_INFINITY, "-Infinity" to Double.NEGATIVE_INFINITY,
        )
        for ((s, v) in accepted) assertEquals("tryParse('$s')", v, dartTryParseDouble(s)!!, 0.0)
        assertEquals(true, dartTryParseDouble("NaN")!!.isNaN())
        for (s in listOf("1.2.3", ".", "-", "0x10", "1d", "1_000", "", "1f", "abc", "1,5")) {
            assertNull("tryParse('$s')", dartTryParseDouble(s))
        }
    }
}
