package space.einundzwanzig.meetup.feature.bitcoin.dashboard

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData
import space.einundzwanzig.meetup.data.mempool.MempoolConfig
import space.einundzwanzig.meetup.data.mempool.MempoolHosts
import space.einundzwanzig.meetup.data.mempool.MempoolService
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepository
import javax.inject.Inject

/** Colour of the dashboard status dot (spec 13 §8.7). */
enum class DashboardStatus {
    /** No data object yet (grey). */
    UNKNOWN,

    /** 0 of 6 sources (red). */
    DEAD,

    /** 1–5 of 6 sources (orange). */
    PARTIAL,

    /** 6 of 6 sources (green). */
    LIVE,
    ;

    companion object {
        /** Status of [d]. */
        fun of(d: BitcoinDashboardData?): DashboardStatus = when {
            d == null -> UNKNOWN
            d.isDead -> DEAD
            d.isLive -> LIVE
            else -> PARTIAL
        }
    }
}

/**
 * State of the Bitcoin dashboard (spec 13 §2.1).
 *
 * @property data last loaded run (starts with the cached `lastDashboard`).
 * @property loading a load is running (initially true, as in Flutter).
 * @property refreshing a pull-to-refresh is waiting for its load.
 * @property fresh the 2 s glow of the status dot after every completed load.
 * @property currency display currency of the price row.
 * @property hostLabel footer host (`MempoolHosts.label` of the effective host).
 */
@Immutable
data class BitcoinDashboardUiState(
    val data: BitcoinDashboardData? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val fresh: Boolean = false,
    val currency: String = "EUR",
    val hostLabel: String = "",
) {
    /** Status dot colour. */
    val status: DashboardStatus get() = DashboardStatus.of(data)

    /** Full-screen loading indicator: no data at all yet and a load is running. */
    val showFullScreenLoading: Boolean get() = data == null && loading
}

/** User actions of the dashboard. */
sealed interface BitcoinDashboardAction {
    /** App-bar refresh button. */
    data object Refresh : BitcoinDashboardAction

    /** Pull-to-refresh gesture. */
    data object PullRefresh : BitcoinDashboardAction

    /** "Datenquelle ändern" in the error banner (opens the Mempool settings). */
    data object ChangeServer : BitcoinDashboardAction

    /** Back arrow. */
    data object Back : BitcoinDashboardAction
}

/**
 * Bitcoin dashboard (Flutter `_BitcoinDashboardScreenState`). Loads via the single-flight
 * [MempoolService.getDashboardData] (never throws). The 60 s auto-refresh only ticks while the
 * screen is visible ([autoRefreshWhileVisible]); returning from the Mempool settings reloads.
 */
@HiltViewModel
class BitcoinDashboardViewModel @Inject constructor(
    private val mempool: MempoolService,
    config: MempoolConfig,
    currency: CurrencyRepository,
    private val time: TimeSource,
) : ViewModel() {

    private val local = MutableStateFlow(BitcoinDashboardUiState(data = mempool.lastDashboard.value))

    /** UI state. The currency and the footer host follow their repositories. */
    val uiState: StateFlow<BitcoinDashboardUiState> =
        combine(local, currency.current, config.hostFlow) { s, cur, host ->
            s.copy(currency = cur, hostLabel = MempoolHosts.label(host))
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            local.value.copy(currency = currency.current.value, hostLabel = MempoolHosts.label(config.host)),
        )

    private var loadJob: Job? = null
    private var freshJob: Job? = null
    private var reloadWhenVisible = true
    private var reloadAfterCurrent = false
    private var lastLoadMillis = 0L

    /** Handles [action]; navigation actions are handled by the route. */
    fun onAction(action: BitcoinDashboardAction) {
        when (action) {
            BitcoinDashboardAction.Refresh -> load()
            BitcoinDashboardAction.PullRefresh -> {
                local.update { it.copy(refreshing = true) }
                load()
            }
            BitcoinDashboardAction.ChangeServer -> reloadWhenVisible = true
            BitcoinDashboardAction.Back -> Unit
        }
    }

    /**
     * Runs while the screen is at least STARTED: loads at once on the first start, after the
     * settings screen returned, or when the last load is older than a minute; then every 60 s.
     */
    suspend fun autoRefreshWhileVisible() {
        if (reloadWhenVisible || time.nowMillis() - lastLoadMillis >= REFRESH_MILLIS) {
            val force = reloadWhenVisible
            reloadWhenVisible = false
            load(force = force)
        }
        while (true) {
            delay(REFRESH_MILLIS)
            load()
        }
    }

    private fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) {
            // A source change must not be answered by a run that started against the old host.
            if (force) reloadAfterCurrent = true
            return
        }
        loadJob = viewModelScope.launch {
            do {
                reloadAfterCurrent = false
                local.update { it.copy(loading = true) }
                val d = mempool.getDashboardData()
                lastLoadMillis = time.nowMillis()
                local.update { it.copy(data = d, loading = false, refreshing = false, fresh = true) }
                freshJob?.cancel()
                freshJob = viewModelScope.launch {
                    delay(FRESH_MILLIS)
                    local.update { it.copy(fresh = false) }
                }
            } while (reloadAfterCurrent)
        }
    }

    companion object {
        /** Auto-refresh period. */
        const val REFRESH_MILLIS: Long = 60_000

        /** Duration of the "fresh" glow. */
        const val FRESH_MILLIS: Long = 2_000
    }
}
