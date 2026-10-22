package space.einundzwanzig.meetup.data.mempool

import space.einundzwanzig.meetup.core.common.dart.dartRound

/**
 * One aggregated dashboard run (Dart `BitcoinDashboardData`, spec 13 §3.3). In-memory only.
 *
 * Failed sources carry their previous value (last-good-value retention), so a partial or dead
 * result still shows the last known numbers; [sourcesOk] says how many sources answered in
 * this run.
 *
 * @property blockHeight tip height, monotonic across runs and processes (0 = never known).
 * @property feeLow `economyFee` (fallback `minimumFee`) in sat/vB.
 * @property feeMedium `halfHourFee` in sat/vB.
 * @property feeHigh `fastestFee` in sat/vB.
 * @property priceEur BTC price in EUR.
 * @property priceUsd BTC price in USD (Moscow time).
 * @property prices BTC price per supported currency code.
 * @property supply whole BTC in circulation, computed from the height.
 * @property hashrateEhs hash rate in EH/s.
 * @property difficultyChangePct expected difficulty change in percent, e.g. `-3.7`.
 * @property difficultyRemainingBlocks blocks until the next retarget.
 * @property lnCapacityBtc Lightning capacity in BTC.
 * @property updatedAtMillis wall clock (epoch millis) at the end of the fetch.
 * @property sourcesOk sources that succeeded in this run (0..[sourcesTotal]).
 * @property lastError short code of the last failure: `HTTP 403` or an exception type name.
 * @property blocked any failure looked like WAF/rate-limit blocking.
 */
data class BitcoinDashboardData(
    val blockHeight: Int,
    val feeLow: Int,
    val feeMedium: Int,
    val feeHigh: Int,
    val priceEur: Double,
    val priceUsd: Double,
    val prices: Map<String, Double> = emptyMap(),
    val supply: Long,
    val hashrateEhs: Double,
    val difficultyChangePct: Double,
    val difficultyRemainingBlocks: Int,
    val lnCapacityBtc: Double,
    val lnNodeCount: Int,
    val lnChannelCount: Int,
    val updatedAtMillis: Long,
    val sourcesOk: Int = 0,
    val sourcesTotal: Int = SOURCES_TOTAL,
    val lastError: String = "",
    val blocked: Boolean = false,
) {
    /** All sources answered. */
    val isLive: Boolean get() = sourcesOk == sourcesTotal

    /** No source answered (offline or blocked). */
    val isDead: Boolean get() = sourcesOk == 0

    /**
     * BTC price in [code]: the `prices` entry if positive, else [priceEur]/[priceUsd] for
     * EUR/USD, else 0 (unknown).
     */
    fun priceIn(code: String): Double {
        val p = prices[code]
        if (p != null && p > 0) return p
        if (code == "EUR") return priceEur
        if (code == "USD") return priceUsd
        return 0.0
    }

    /** Moscow time (sats per USD read like a clock, `18:27`), `--:--` without a USD price. */
    val moscowTime: String get() = moscowTime(priceUsd)

    /** Constants and pure helpers. */
    companion object {
        /** Number of dashboard sources (height, fees, prices, hashrate, difficulty, Lightning). */
        const val SOURCES_TOTAL: Int = 6

        /**
         * Dart `moscowTime`: `round(1e8 / priceUsd)` zero-padded to 4 digits and split before the
         * last two (`54733 → "18:27"`, `9999 → "100:01"`); `--:--` (ASCII) when `priceUsd <= 0`.
         */
        fun moscowTime(priceUsd: Double): String {
            if (priceUsd <= 0) return "--:--"
            val s = (100_000_000 / priceUsd).dartRound().toString().padStart(4, '0')
            return "${s.substring(0, s.length - 2)}:${s.substring(s.length - 2)}"
        }

        /**
         * Whole BTC in circulation after [height] blocks (Dart `_circulatingSupply`): 50 BTC
         * halving every 210,000 blocks while the reward is at least 1 sat, summed in doubles and
         * rounded half away from zero (`840000 → 19687500`).
         */
        fun circulatingSupply(height: Int): Long {
            var reward = 50.0
            var supply = 0.0
            var h = height
            while (h > 0 && reward >= 0.00000001) {
                val blocksInEra = if (h > 210_000) 210_000 else h
                supply += blocksInEra * reward
                h -= blocksInEra
                reward /= 2
            }
            return supply.dartRound()
        }
    }
}
