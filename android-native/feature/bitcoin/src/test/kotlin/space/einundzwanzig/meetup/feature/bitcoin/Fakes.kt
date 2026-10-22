package space.einundzwanzig.meetup.feature.bitcoin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.StateFlow
import androidx.navigation3.runtime.NavKey
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.LogEntry
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData
import space.einundzwanzig.meetup.data.mempool.MempoolConfig
import space.einundzwanzig.meetup.data.mempool.MempoolHosts
import space.einundzwanzig.meetup.data.mempool.MempoolService
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepository

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Clock that follows the virtual time of [scheduler]. */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerTime(private val scheduler: TestCoroutineScheduler, private val base: Long = 1_760_000_000_000) : TimeSource {
    override fun nowMillis(): Long = base + scheduler.currentTime
}

class FixedTime(var now: Long = 1_760_000_000_000) : TimeSource {
    override fun nowMillis(): Long = now
}

fun dashboard(
    height: Int = 912_345,
    sourcesOk: Int = 6,
    blocked: Boolean = false,
    lastError: String = "",
) = BitcoinDashboardData(
    blockHeight = height,
    feeLow = 3,
    feeMedium = 8,
    feeHigh = 12,
    priceEur = 95_000.0,
    priceUsd = 105_000.0,
    prices = mapOf("EUR" to 95_000.0, "USD" to 105_000.0, "CHF" to 89_000.0),
    supply = 19_912_345,
    hashrateEhs = 812.4,
    difficultyChangePct = 3.7,
    difficultyRemainingBlocks = 1234,
    lnCapacityBtc = 5123.4,
    lnNodeCount = 15_234,
    lnChannelCount = 52_001,
    updatedAtMillis = 1_760_000_000_000,
    sourcesOk = sourcesOk,
    lastError = lastError,
    blocked = blocked,
)

class FakeMempoolService : MempoolService {
    override val lastDashboard = MutableStateFlow<BitcoinDashboardData?>(null)
    var nextDashboard: BitcoinDashboardData = dashboard()
    var dashboardGate: CompletableDeferred<Unit>? = null
    var dashboardCalls = 0
    var prices: Map<String, Double> = mapOf("EUR" to 100_000.0, "USD" to 110_000.0, "CHF" to 90_000.0)
    var pricesGate: CompletableDeferred<Unit>? = null
    var priceCalls = 0
    var testHostResult: (String) -> Int = { 912_345 }
    val testedHosts = mutableListOf<String>()
    var clearCalls = 0

    override suspend fun getBlockHeight(): Int = nextDashboard.blockHeight

    override suspend fun getPrices(): Map<String, Double> {
        priceCalls++
        pricesGate?.await()
        return prices
    }

    override suspend fun testHost(rawHost: String): Int {
        testedHosts += rawHost
        return testHostResult(rawHost)
    }

    override suspend fun getDashboardData(): BitcoinDashboardData {
        dashboardCalls++
        dashboardGate?.await()
        val d = nextDashboard
        if (!d.isDead) lastDashboard.value = d
        return d
    }

    override fun clearLastDashboard() {
        clearCalls++
        lastDashboard.value = null
    }

    override fun resetClient() = Unit
    override fun clearState() = Unit
}

class FakeMempoolConfig(initial: String = MempoolHosts.CLEARNET) : MempoolConfig {
    private var persisted = initial
    private var override: String? = null
    override val hostFlow = MutableStateFlow(initial)
    override val host: String get() = hostFlow.value
    override val persistedHost: String get() = persisted
    val setHostCalls = mutableListOf<String>()

    /** When set, [setHost] throws it (simulates a failing store). */
    var setHostError: Exception? = null

    /** When set, [ensureLoaded] suspends until it completes (simulates a slow first load). */
    var loadGate: CompletableDeferred<Unit>? = null

    override suspend fun ensureLoaded() {
        loadGate?.await()
    }

    override suspend fun setHost(raw: String) {
        setHostCalls += raw
        setHostError?.let { throw it }
        persisted = MempoolHosts.normalize(raw)
        override = null
        hostFlow.value = persisted
    }

    override fun setSessionOverride(host: String?) {
        override = host?.takeIf { it.isNotBlank() }?.let(MempoolHosts::normalize)
        hostFlow.value = override ?: persisted
    }

    override suspend fun resetToDefaults() = setHost(MempoolHosts.CLEARNET)
}

class FakeCurrencyRepository(initial: String = "EUR") : CurrencyRepository {
    override val current = MutableStateFlow(initial)
    val setCalls = mutableListOf<String>()
    override suspend fun load() = Unit
    override suspend fun read(): String = current.value
    override suspend fun set(code: String) {
        setCalls += code
        current.value = code
    }

    override suspend fun reset() {
        current.value = "EUR"
    }
}

/** Swallows log output; counts warnings. */
class FakeLog : DiagnosticLog {
    var warnings = 0
    override val entries: StateFlow<List<LogEntry>> = MutableStateFlow(emptyList())
    override val verbose: StateFlow<Boolean> = MutableStateFlow(false)
    override val isVerbose: Boolean = false
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun diag(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, error: Any?) {
        warnings++
    }
    override fun error(tag: String, message: String, error: Any?, stack: Array<StackTraceElement>?) = Unit
    override fun security(tag: String, message: String) = Unit
    override fun section(title: String) = Unit
    override fun setVerbose(on: Boolean) = Unit
    override suspend fun clear() = Unit
}

/** Records navigation calls. */
class FakeNavigator : Navigator {
    val navigated = mutableListOf<NavKey>()
    var backCalls = 0
    override fun navigate(key: NavKey) {
        navigated += key
    }
    override fun replaceTop(key: NavKey) = Unit
    override fun back() {
        backCalls++
    }
    override fun backTo(key: NavKey, inclusive: Boolean) = Unit
    override fun resetTo(key: NavKey) = Unit
    override fun navigateFromRoot(key: NavKey) = Unit
    override fun openUrl(url: String) = Unit
}
