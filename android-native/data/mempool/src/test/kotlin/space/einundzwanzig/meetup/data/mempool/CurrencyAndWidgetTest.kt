package space.einundzwanzig.meetup.data.mempool

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepository
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepositoryImpl
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetBridge
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetBridgeImpl
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetSink
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetValues
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** Spec 13 §10.3 test 12 (currency repository), §4.3 widget values and §8.12 widget rules. */
class CurrencyAndWidgetTest {

    private var env = TestEnv()
    private val pushed = CopyOnWriteArrayList<BitcoinWidgetValues>()
    private val sink = object : BitcoinWidgetSink {
        override suspend fun updateBitcoin(values: BitcoinWidgetValues) {
            pushed += values
        }
    }
    private lateinit var currency: CurrencyRepository
    private lateinit var bridge: BitcoinWidgetBridge

    private fun wire() {
        var bridgeRef: BitcoinWidgetBridge? = null
        currency = CurrencyRepositoryImpl(env.store, env.service, { bridgeRef!! }, env.log, env.scope)
        bridge = BitcoinWidgetBridgeImpl(setOf(sink), currency, env.service, env.time, env.log)
        bridgeRef = bridge
    }

    init {
        wire()
    }

    @After
    fun tearDown() = env.close()

    private fun withPrefs(prefs: Map<String, PrefValue>) {
        env.close()
        env = TestEnv(prefs)
        wire()
    }

    @Test
    fun unknownStoredCodeReadsAsEur() = runBlocking {
        withPrefs(mapOf(MempoolPrefs.FIAT_CURRENCY to PrefValue.StringValue("XYZ")))
        assertEquals("EUR", currency.read())
        currency.load()
        assertEquals("EUR", currency.current.value)
    }

    @Test
    fun storedCodeIsLoaded() = runBlocking {
        withPrefs(mapOf(MempoolPrefs.FIAT_CURRENCY to PrefValue.StringValue("CHF")))
        currency.load()
        assertEquals("CHF", currency.current.value)
    }

    @Test
    fun setIgnoresUnsupported() = runBlocking {
        currency.set("XYZ")
        assertEquals("EUR", currency.current.value)
        assertEquals(null, env.store.getString(MempoolPrefs.FIAT_CURRENCY))
    }

    @Test
    fun setPersistsAndPushesTheWidget() = runBlocking {
        env.fake.respond = FakeMempool.healthy()
        env.service.getDashboardData()
        currency.set("CHF")
        assertEquals("CHF", currency.current.value)
        assertEquals("CHF", env.store.getString(MempoolPrefs.FIAT_CURRENCY))
        withTimeout(5_000) { while (pushed.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals("84.000", pushed.single().priceFiat)
        assertEquals("CHF", pushed.single().priceSymbol)
    }

    @Test
    fun resetAndStoreWipeGoBackToEur() = runBlocking {
        currency.set("USD")
        env.store.clear()
        withTimeout(5_000) { currency.current.first { it == "EUR" } }
        currency.set("GBP")
        currency.reset()
        assertEquals("EUR", currency.current.value)
    }

    @Test
    fun widgetValues() {
        val d = DashboardMathTest.data().copy(supply = 19_875_000)
        val v = BitcoinWidgetValues.of(d, "CHF", 13 * 3_600_000L + 7 * 60_000L, ZoneOffset.UTC)
        assertEquals("900.000", v.block)
        assertEquals("––", v.priceFiat) // no CHF price in this dataset
        assertEquals("CHF", v.priceSymbol)
        assertEquals("95.000", v.priceEur)
        assertEquals("09:71", v.moscow)
        assertEquals("3·8·12", v.fees)
        assertEquals("812 EH/s", v.hashrate)
        assertEquals("19.875.000", v.supply)
        assertEquals("+3.7% / 1234", v.difficulty)
        assertEquals("13:07", v.updated)
        assertEquals(
            listOf("block", "priceFiat", "priceSymbol", "priceEur", "moscow", "fees", "hashrate", "supply", "difficulty", "updated"),
            v.toMap().keys.toList(),
        )
        val eur = BitcoinWidgetValues.of(d, "EUR", 0, ZoneOffset.UTC)
        assertEquals("95.000", eur.priceFiat)
        assertEquals("€", eur.priceSymbol)
    }

    @Test
    fun emptyDashboardWidgetValues() {
        val d = DashboardMathTest.data(height = 0, prices = emptyMap(), priceEur = 0.0, priceUsd = 0.0, remaining = 0)
            .copy(hashrateEhs = 0.0, feeLow = 0, feeMedium = 0, feeHigh = 0)
        val v = BitcoinWidgetValues.of(d, "EUR", 0, ZoneOffset.UTC)
        assertEquals("––", v.block)
        assertEquals("––", v.priceFiat)
        assertEquals("––", v.priceEur)
        assertEquals("--:--", v.moscow)
        assertEquals("0·0·0", v.fees)
        assertEquals("––", v.hashrate)
        assertEquals("––", v.supply)
        assertEquals("––", v.difficulty)
    }

    @Test
    fun refreshSkipsDeadData() = runBlocking {
        env.config.setMode(MempoolMode.CUSTOM, "node.example.com")
        env.fake.respond = { _, _ -> FakeMempool.status(500, "") }
        assertFalse(bridge.refreshBitcoin())
        assertTrue(pushed.isEmpty())
        assertTrue(
            env.logMessages().any {
                it == "Bitcoin-Widget nicht aktualisiert — keine Quelle erreichbar (HTTP 500). Alte Werte bleiben stehen."
            },
        )
        env.fake.respond = FakeMempool.healthy()
        assertTrue(bridge.refreshBitcoin())
        assertEquals("95.000", pushed.single().priceFiat)
    }
}
