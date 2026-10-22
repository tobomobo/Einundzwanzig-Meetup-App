package space.einundzwanzig.meetup.data.mempool

/**
 * Data source mode of the mempool client (Dart `MempoolMode`).
 *
 * It is never stored: [MempoolConfig.mode] derives it from the persisted host
 * (`mempool_host`). A session fallback mirror does not change the mode.
 */
enum class MempoolMode {
    /** `https://mempool.space` (the default). Only this mode may fall back to public mirrors. */
    CLEARNET,

    /** The official mempool.space onion host, reached through Orbot in VPN mode. */
    TOR,

    /** Any other normalized URL (own node, Umbrel, a mirror chosen by the user, ...). */
    CUSTOM,
}
