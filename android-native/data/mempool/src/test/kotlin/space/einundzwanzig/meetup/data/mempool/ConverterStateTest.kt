package space.einundzwanzig.meetup.data.mempool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.mempool.converter.ConverterState

/** Spec 13 §10.2 converter, premium and slider vectors (price EUR = 95000). */
class ConverterStateTest {

    private val loaded = ConverterState(currency = "EUR").onPricesLoaded(mapOf("EUR" to 95000.0, "CHF" to 84000.0), 1L)
    private val btc = loaded.copy(cryptoIsBtc = true)

    @Test
    fun fiatToCrypto() {
        assertEquals("10526", loaded.onFiatChanged("10").cryptoText)
        assertEquals("11053", loaded.onFiatChanged("10,5").cryptoText)
        assertEquals("11", loaded.onFiatChanged("0.01").cryptoText)
        assertEquals("1053", loaded.onFiatChanged("1.").cryptoText)
        assertEquals("10.52631579", btc.onFiatChanged("1000000").cryptoText)
    }

    @Test
    fun cryptoToFiat() {
        assertEquals("0.95", loaded.onCryptoChanged("1000").fiatText)
        assertEquals("19.95", loaded.onCryptoChanged("21000").fiatText)
        assertEquals("0.00", loaded.onCryptoChanged("1").fiatText)
        assertEquals("11.73", loaded.onCryptoChanged("12345,6").fiatText)
        assertEquals("95000.00", btc.onCryptoChanged("1").fiatText)
        assertEquals("19.95", btc.onCryptoChanged("0.00021").fiatText)
        assertEquals("47500.00", btc.onCryptoChanged("0,5").fiatText)
    }

    @Test
    fun unparseableInputClearsTheOtherSide() {
        val s = loaded.onFiatChanged("10")
        assertEquals("", s.onFiatChanged("").cryptoText)
        assertEquals("", s.onFiatChanged("1,2,3").cryptoText)
        assertEquals("", loaded.onCryptoChanged("21000").onCryptoChanged("").fiatText)
    }

    @Test
    fun noPriceNoConversion() {
        val s = ConverterState(currency = "EUR").onFiatChanged("10")
        assertEquals("", s.cryptoText)
        // Converted once the prices arrive.
        assertEquals("10526", s.onPricesLoaded(mapOf("EUR" to 95000.0), 1L).cryptoText)
    }

    @Test
    fun failedLoadKeepsOldPrices() {
        val s = loaded.onLoading().onPricesLoaded(emptyMap(), 2L)
        assertTrue(s.error)
        assertFalse(s.loading)
        assertEquals(95000.0, s.btcPrice, 0.0)
        assertEquals(1L, s.updatedAtMillis)
    }

    @Test
    fun swapUnit() {
        assertEquals("0.00021000", loaded.onCryptoChanged("21000").swapUnit().cryptoText)
        assertEquals("0.00000001", loaded.onCryptoChanged("1").swapUnit().cryptoText)
        assertEquals("1.23456789", loaded.onCryptoChanged("123456789").swapUnit().cryptoText)
        assertEquals("21000", btc.onCryptoChanged("0.00021").swapUnit().cryptoText)
        assertEquals("50000000", btc.onCryptoChanged("0,5").swapUnit().cryptoText)
        val swapped = loaded.onFiatChanged("10").swapUnit()
        assertTrue(swapped.cryptoIsBtc)
        assertTrue(swapped.fiatIsInput)
        assertEquals("10", swapped.fiatText)
    }

    @Test
    fun currencyPickKeepsDirection() {
        val s = loaded.onFiatChanged("84").onCurrencyPicked("CHF")
        assertEquals("84", s.fiatText)
        assertEquals("100000", s.cryptoText)
    }

    @Test
    fun premium() {
        val base = loaded.onFiatChanged("100")
        val cases = listOf(
            "0" to ("100.00 EUR" to "105.263 sats"),
            "2.5" to ("102.50 EUR" to "107.895 sats"),
            "-3" to ("97.00 EUR" to "102.105 sats"),
            "20" to ("120.00 EUR" to "126.316 sats"),
            "-150" to ("-50.00 EUR" to "-52.632 sats"),
        )
        for ((p, expected) in cases) {
            val s = base.onPremiumText(p)
            assertEquals(expected.first, s.premiumResultText)
            assertEquals(expected.second, s.premiumSatsText)
            assertEquals("100.00 EUR", s.premiumBaseText)
        }
        assertEquals(2.5, base.onPremiumText("2,5").premiumPercent, 0.0)
        assertEquals(0.0, base.onPremiumText("abc").premiumPercent, 0.0)
        assertEquals(-20.0, base.onPremiumText("-150").sliderValue, 0.0)
    }

    @Test
    fun sliderRounding() {
        data class Case(val v: Float, val p: Double, val text: String, val label: String)
        val cases = listOf(
            Case(0.24f, 0.0, "", "0.0%"),
            Case(0.25f, 0.5, "0.5", "+0.5%"),
            Case(-0.25f, -0.5, "-0.5", "-0.5%"),
            Case(0.74f, 0.5, "0.5", "+0.5%"),
            Case(0.75f, 1.0, "1.0", "+1.0%"),
            Case(19.99f, 20.0, "20.0", "+20.0%"),
            Case(-19.75f, -20.0, "-20.0", "-20.0%"),
        )
        for (c in cases) {
            val s = loaded.onSlider(c.v)
            assertEquals("p(${c.v})", c.p, s.premiumPercent, 0.0)
            assertEquals("text(${c.v})", c.text, s.premiumText)
            assertEquals("label(${c.v})", c.label, s.premiumLabel)
        }
    }

    @Test
    fun currentFiatFromCryptoSide() {
        val s = loaded.copy(fiatText = "").onCryptoChanged("21000").copy(fiatText = "")
        assertEquals(19.95, s.currentFiat, 1e-9)
    }
}
