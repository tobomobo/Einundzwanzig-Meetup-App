package space.einundzwanzig.meetup.data.mempool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 13 §10.2 Moscow time, circulating supply, `priceIn` and status vectors. */
class DashboardMathTest {

    @Test
    fun moscowTime() {
        val cases = listOf(
            0.0 to "--:--", -1.0 to "--:--", 100000.0 to "10:00", 54733.0 to "18:27", 54735.0 to "18:27",
            103000.0 to "09:71", 120000.0 to "08:33", 66666.0 to "15:00", 9999.0 to "100:01",
            1000.0 to "1000:00", 100.0 to "10000:00", 1e8 to "00:01", 2e8 to "00:01", 3e8 to "00:00",
        )
        for ((usd, expected) in cases) assertEquals("moscow($usd)", expected, BitcoinDashboardData.moscowTime(usd))
    }

    @Test
    fun circulatingSupply() {
        val cases = listOf(
            0 to 0L, 1 to 50L, 209999 to 10499950L, 210000 to 10500000L, 210001 to 10500025L,
            420000 to 15750000L, 630000 to 18375000L, 840000 to 19687500L, 870000 to 19781250L,
            900000 to 19875000L, 945000 to 20015625L, 1050000 to 20343750L, 6929999 to 21000000L,
            6930000 to 21000000L, 7000000 to 21000000L, 13440000 to 21000000L,
        )
        for ((h, expected) in cases) assertEquals("supply($h)", expected, BitcoinDashboardData.circulatingSupply(h))
    }

    @Test
    fun priceInFallsBackToEurUsdFields() {
        val d = data(prices = mapOf("CHF" to 84000.0, "GBP" to 0.0), priceEur = 95000.0, priceUsd = 103000.0)
        assertEquals(84000.0, d.priceIn("CHF"), 0.0)
        assertEquals(95000.0, d.priceIn("EUR"), 0.0)
        assertEquals(103000.0, d.priceIn("USD"), 0.0)
        assertEquals(0.0, d.priceIn("GBP"), 0.0)
        assertEquals(0.0, d.priceIn("JPY"), 0.0)
    }

    @Test
    fun liveAndDead() {
        assertTrue(data(ok = 6).isLive)
        assertFalse(data(ok = 5).isLive)
        assertTrue(data(ok = 0).isDead)
        assertFalse(data(ok = 1).isDead)
    }

    companion object {
        fun data(
            ok: Int = 6,
            height: Int = 900000,
            prices: Map<String, Double> = mapOf("EUR" to 95000.0, "USD" to 103000.0),
            priceEur: Double = 95000.0,
            priceUsd: Double = 103000.0,
            remaining: Int = 1234,
            pct: Double = 3.71,
        ) = BitcoinDashboardData(
            blockHeight = height, feeLow = 3, feeMedium = 8, feeHigh = 12, priceEur = priceEur, priceUsd = priceUsd,
            prices = prices, supply = if (height > 0) BitcoinDashboardData.circulatingSupply(height) else 0,
            hashrateEhs = 812.3456789, difficultyChangePct = pct, difficultyRemainingBlocks = remaining,
            lnCapacityBtc = 5123.45678901, lnNodeCount = 15000, lnChannelCount = 50000,
            updatedAtMillis = 0, sourcesOk = ok,
        )
    }
}
