package space.einundzwanzig.meetup.data.proofs.zap

import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Zap statistics for the Lightning layer (Dart `ZapVerificationService`, spec 06 §8.5).
 * Only receipts that pass [ZapReceiptValidator.checkLocal] **and** come from the recipient's
 * LNURL provider ([ZapProviderResolver]) count.
 */
interface ZapVerificationService {

    /**
     * Stats of [pubkeyHex] over the last 180 days. With [useCache] a cache entry younger than
     * [CACHE_TTL] is returned. Otherwise received (`#p`) and sent (`#P`) receipts are fetched
     * concurrently from the first 3 active relays (each sequential, stopping at ≥ 50), filtered
     * to provider-issued receipts, and the result is written to the cache (always, for any
     * pubkey — Dart parity, spec 06 §14 R18). Other users: pass `useCache = false`.
     */
    suspend fun analyzeZapActivity(pubkeyHex: String, useCache: Boolean = true): ZapStats

    /** Dart `getMyStats()`: stats of the active identity (cached), [ZapStats.EMPTY] without one. */
    suspend fun getMyStats(): ZapStats

    companion object {
        /** Pref key prefix of the cached stats JSON: `zap_stats_cache_v2_<pubkey>`. */
        const val CACHE_STATS_PREFIX: String = "zap_stats_cache_v2_"

        /** Pref key prefix of the cache time (epoch ms): `zap_cache_ts_v2_<pubkey>`. */
        const val CACHE_TS_PREFIX: String = "zap_cache_ts_v2_"

        /** Cache lifetime. */
        val CACHE_TTL: Duration = 6.hours

        /** Analysis window. */
        val ANALYSIS_WINDOW: Duration = 180.days

        /** Relays queried (first N active relays). */
        const val MAX_RELAYS: Int = 3

        /** Stop querying further relays once this many receipts are collected. */
        const val ENOUGH_RECEIPTS: Int = 50

        /** At most this many recipient groups get a provider lookup; later groups are dropped. */
        const val MAX_PROVIDER_LOOKUPS: Int = 15

        /**
         * Dart `receiptFilter`: `{"kinds":[9735],"since":since,"limit":100,"#P"|"#p":[pubkeyHex]}`
         * in this key order. Sent receipts are found via `#P` (the sender), received via `#p`.
         * Never both in one filter (`answersFilter` would require both tags).
         */
        fun receiptFilter(pubkeyHex: String, since: Long, sent: Boolean): JsonObject = jsonObjectOf(
            "kinds" to listOf(ZapReceiptValidator.KIND_ZAP_RECEIPT),
            "since" to since,
            "limit" to 100,
            (if (sent) "#P" else "#p") to listOf(pubkeyHex),
        )
    }
}
