package space.einundzwanzig.meetup.feature.bitcoin.converter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.feature.bitcoin.FakeCurrencyRepository
import space.einundzwanzig.meetup.feature.bitcoin.FakeMempoolService
import space.einundzwanzig.meetup.feature.bitcoin.FixedTime
import space.einundzwanzig.meetup.feature.bitcoin.MainDispatcherRule

class ConverterViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val service = FakeMempoolService()
    private val currency = FakeCurrencyRepository()
    private val time = FixedTime()

    private fun vm() = ConverterViewModel(service, currency, time, CoroutineScope(main.dispatcher))

    @Test
    fun `loads prices on create`() {
        val vm = vm()
        val c = vm.uiState.value.converter
        assertEquals(1, service.priceCalls)
        assertFalse(c.loading)
        assertFalse(c.error)
        assertEquals(100_000.0, c.btcPrice, 0.0)
        assertEquals(time.now, c.updatedAtMillis)
    }

    @Test
    fun `text typed before prices arrive is converted once they load`() {
        val gate = CompletableDeferred<Unit>()
        service.pricesGate = gate
        val vm = vm()
        assertTrue(vm.uiState.value.converter.loading)
        vm.onAction(ConverterAction.FiatChanged("10"))
        assertEquals("", vm.uiState.value.converter.cryptoText)
        gate.complete(Unit)
        assertEquals("10000", vm.uiState.value.converter.cryptoText)
    }

    @Test
    fun `fiat input recomputes sats and bumps only the crypto revision`() {
        val vm = vm()
        vm.onAction(ConverterAction.FiatChanged("100"))
        val s = vm.uiState.value
        assertEquals("100000", s.converter.cryptoText)
        assertEquals(0, s.fiatRevision)
        assertEquals(1, s.cryptoRevision)
    }

    @Test
    fun `crypto input recomputes fiat with comma decimals`() {
        val vm = vm()
        vm.onAction(ConverterAction.CryptoChanged("21000"))
        assertEquals("21.00", vm.uiState.value.converter.fiatText)
        assertEquals(1, vm.uiState.value.fiatRevision)
        assertEquals(0, vm.uiState.value.cryptoRevision)
    }

    @Test
    fun `unit swap converts the crypto text`() {
        val vm = vm()
        vm.onAction(ConverterAction.CryptoChanged("21000"))
        vm.onAction(ConverterAction.SwapUnit)
        val c = vm.uiState.value.converter
        assertTrue(c.cryptoIsBtc)
        assertEquals("0.00021000", c.cryptoText)
        assertEquals("21.00", c.fiatText)
    }

    @Test
    fun `empty price answer shows the offline state and keeps old prices`() {
        val vm = vm()
        service.prices = emptyMap()
        vm.onAction(ConverterAction.Refresh)
        val c = vm.uiState.value.converter
        assertTrue(c.error)
        assertFalse(c.loading)
        assertEquals(100_000.0, c.btcPrice, 0.0)
    }

    @Test
    fun `retry after an offline start succeeds`() {
        service.prices = emptyMap()
        val vm = vm()
        assertTrue(vm.uiState.value.converter.error)
        assertEquals(0.0, vm.uiState.value.converter.btcPrice, 0.0)
        service.prices = mapOf("EUR" to 50_000.0)
        vm.onAction(ConverterAction.Refresh)
        assertFalse(vm.uiState.value.converter.error)
        assertEquals(50_000.0, vm.uiState.value.converter.btcPrice, 0.0)
        assertEquals(2, service.priceCalls)
    }

    @Test
    fun `picking a currency persists it and recomputes the other side`() {
        val vm = vm()
        vm.onAction(ConverterAction.FiatChanged("90"))
        vm.onAction(ConverterAction.PickCurrency("CHF"))
        val c = vm.uiState.value.converter
        assertEquals("CHF", c.currency)
        assertEquals("100000", c.cryptoText)
        assertEquals(listOf("CHF"), currency.setCalls)
    }

    @Test
    fun `currency is read once at creation`() {
        currency.current.value = "USD"
        val vm = vm()
        currency.current.value = "GBP"
        assertEquals("USD", vm.uiState.value.converter.currency)
    }

    @Test
    fun `slider rounds to half percent and rewrites the premium field`() {
        val vm = vm()
        vm.onAction(ConverterAction.FiatChanged("100"))
        vm.onAction(ConverterAction.SliderChanged(2.37f))
        val s = vm.uiState.value
        assertEquals(2.5, s.converter.premiumPercent, 0.0)
        assertEquals("2.5", s.converter.premiumText)
        assertEquals(1, s.premiumRevision)
        assertEquals("102.50 EUR", s.converter.premiumResultText)
    }

    @Test
    fun `premium text is typed freely without a revision bump`() {
        val vm = vm()
        vm.onAction(ConverterAction.PremiumChanged("-3,5"))
        val s = vm.uiState.value
        assertEquals(-3.5, s.converter.premiumPercent, 0.0)
        assertEquals(0, s.premiumRevision)
    }

    @Test
    fun `sign toggle negates the premium and rewrites the field`() {
        val vm = vm()
        vm.onAction(ConverterAction.FiatChanged("100"))
        vm.onAction(ConverterAction.PremiumChanged("2,5"))
        vm.onAction(ConverterAction.TogglePremiumSign)
        var s = vm.uiState.value
        assertEquals("-2,5", s.converter.premiumText)
        assertEquals(-2.5, s.converter.premiumPercent, 0.0)
        assertEquals(1, s.premiumRevision)
        assertEquals("97.50 EUR", s.converter.premiumResultText)
        vm.onAction(ConverterAction.TogglePremiumSign)
        s = vm.uiState.value
        assertEquals("2,5", s.converter.premiumText)
        assertEquals(2.5, s.converter.premiumPercent, 0.0)
    }

    @Test
    fun `sign toggle on an empty field starts a negative entry`() {
        val vm = vm()
        vm.onAction(ConverterAction.TogglePremiumSign)
        assertEquals("-", vm.uiState.value.converter.premiumText)
        assertEquals(0.0, vm.uiState.value.converter.premiumPercent, 0.0)
        vm.onAction(ConverterAction.PremiumChanged("-25"))
        assertEquals(-25.0, vm.uiState.value.converter.premiumPercent, 0.0)
        assertEquals(-20.0, vm.uiState.value.converter.sliderValue, 0.0)
    }

    @Test
    fun `refresh is ignored while a load runs`() {
        val gate = CompletableDeferred<Unit>()
        service.pricesGate = gate
        val vm = vm()
        vm.onAction(ConverterAction.Refresh)
        assertEquals(1, service.priceCalls)
        gate.complete(Unit)
    }

    @Test
    fun `amount filter keeps digits and separators`() {
        assertEquals("12,5.0", filterAmount("1a2,5-.0 "))
    }
}
