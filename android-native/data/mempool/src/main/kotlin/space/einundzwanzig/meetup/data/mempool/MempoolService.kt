package space.einundzwanzig.meetup.data.mempool

import kotlinx.coroutines.flow.StateFlow

/**
 * Bitcoin network data from the configured mempool.space-compatible instance
 * (Dart `MempoolService`, spec 13 §5, §8).
 *
 * Requests use the effective [MempoolConfig.host] (session override included), the headers
 * [USER_AGENT] / `Accept: application/json, text/plain, (any)` / `Cache-Control: no-cache`, no
 * HTTP cache, no retries and a whole-call timeout of 20 s (45 s for onion hosts). Only status
 * 200 counts as success.
 */
interface MempoolService {

    /**
     * Last dashboard run in which at least one source answered (Dart `lastDashboard`).
     * A dead run never replaces it. Shown while loading and used by the converter tile.
     */
    val lastDashboard: StateFlow<BitcoinDashboardData?>

    /**
     * Current tip height, **raw** from the server (no monotonic guard, nothing persisted).
     * Never throws: returns `0` (= unknown) on any failure. Timeout capped at 20 s, also for
     * onion hosts. Rolling QR, co-attendance and badge scans rely on this contract.
     */
    suspend fun getBlockHeight(): Int

    /**
     * BTC prices for [SUPPORTED_CURRENCIES] from `/v1/prices` (only keys with a JSON number).
     * Uses the effective host, no mirror fallback. Never throws: an empty map means failure.
     */
    suspend fun getPrices(): Map<String, Double>

    /**
     * Connection test against an arbitrary host ([MempoolHosts.normalize]d, not saved) with a
     * throw-away connection pool, `Accept: text/plain, (any)` and no `Cache-Control`.
     *
     * @return the tip height (> 0).
     * @throws MempoolHttpException for a non-200 status or a 200 body that is not a positive integer.
     * @throws java.io.IOException for network failures (use [HostTestFailure.of] to classify).
     *   Rethrow [kotlinx.coroutines.CancellationException] before classifying: a closed screen
     *   must not be reported as a failed connection test.
     */
    suspend fun testHost(rawHost: String): Int

    /**
     * Runs the six dashboard sources in parallel and aggregates them (single-flight: concurrent
     * callers share one run, which lives in the application scope and survives a cancelled
     * caller). In clearnet mode a dead run falls back to [MempoolHosts.FALLBACKS] for the
     * session. Never throws.
     */
    suspend fun getDashboardData(): BitcoinDashboardData

    /** Forgets [lastDashboard] (the source was changed in the settings). */
    fun clearLastDashboard()

    /** Drops pooled keep-alive connections (host change, total failure, Orbot toggled). */
    fun resetClient()

    /**
     * App reset: forgets [lastDashboard], the in-memory height, error state and connections.
     * Call it **after** `KeyValueStore.clear()`: a dashboard run between this call and the
     * store wipe would read the old `mempool_last_height` back into memory.
     */
    fun clearState()

    /** Constants of the domain. */
    companion object {
        /** Byte-exact User-Agent of every mempool request (the Dart default was WAF-blocked). */
        const val USER_AGENT: String = "21Meetup/1.3 (Einundzwanzig Meetup App)"

        /** Fiat currencies of `/v1/prices`, in UI order. Single source of truth. */
        val SUPPORTED_CURRENCIES: List<String> = listOf("EUR", "USD", "GBP", "CHF", "CAD", "AUD", "JPY")

        /** API path of the tip height. */
        const val PATH_TIP_HEIGHT: String = "/blocks/tip/height"

        /** API path of the prices. */
        const val PATH_PRICES: String = "/v1/prices"

        /** API path of the fee recommendation. */
        const val PATH_FEES: String = "/v1/fees/recommended"

        /** API path of the 3-day hash rate. */
        const val PATH_HASHRATE: String = "/v1/mining/hashrate/3d"

        /** API path of the difficulty adjustment. */
        const val PATH_DIFFICULTY: String = "/v1/difficulty-adjustment"

        /** API path of the Lightning statistics. */
        const val PATH_LIGHTNING: String = "/v1/lightning/statistics/latest"
    }
}
