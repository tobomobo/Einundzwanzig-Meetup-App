package space.einundzwanzig.meetup.data.mempool

/**
 * Exact Flutter key names of this domain in the shared `KeyValueStore` (spec 13 §4.1).
 * None of them is part of the encrypted backup; all are wiped by the app reset.
 */
object MempoolPrefs {
    /** String: normalized mempool base URL (no `/api`, no trailing slash). Re-normalized on read. */
    const val HOST: String = "mempool_host"

    /** String: display currency code, one of [MempoolService.SUPPORTED_CURRENCIES]; else EUR. */
    const val FIAT_CURRENCY: String = "fiat_currency"

    /** Int (64-bit): highest block height ever seen by the dashboard aggregation. Never decreases. */
    const val LAST_HEIGHT: String = "mempool_last_height"
}
