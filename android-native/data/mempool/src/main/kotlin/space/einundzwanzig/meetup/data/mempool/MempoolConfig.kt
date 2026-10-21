package space.einundzwanzig.meetup.data.mempool

import kotlinx.coroutines.flow.StateFlow

/**
 * Which mempool instance every call goes to (Dart `MempoolConfig`, spec 13 §3.1, §5.1).
 *
 * The persisted host (`mempool_host`) is the user's choice. A **session override** (a public
 * mirror the dashboard fell back to, clearnet mode only) takes precedence for the rest of the
 * process but is never persisted. The persisted host follows the store, so the Flutter
 * migration and the app reset (store cleared → mempool.space) are picked up automatically.
 */
interface MempoolConfig {

    /** Effective host: session override, else the persisted host. No `/api`, no trailing slash. */
    val host: String

    /** [host] as a flow (changes on settings changes, fallback switches and resets). */
    val hostFlow: StateFlow<String>

    /** The persisted host, ignoring the session override. */
    val persistedHost: String

    /** `"$host/api"`. */
    val apiBase: String get() = "$host/api"

    /** Mode derived from the **persisted** host only (the session override is ignored). */
    val mode: MempoolMode
        get() = when (persistedHost) {
            MempoolHosts.CLEARNET -> MempoolMode.CLEARNET
            MempoolHosts.TOR -> MempoolMode.TOR
            else -> MempoolMode.CUSTOM
        }

    /** Whether the effective host is an onion host. */
    val isOnion: Boolean get() = MempoolHosts.isOnion(host)

    /** Loads the persisted host once (idempotent). Every network call awaits it first. */
    suspend fun ensureLoaded()

    /**
     * Normalizes [raw] and persists it (an empty value becomes mempool.space). Also clears the
     * session override, so a new choice takes effect immediately (fix of spec 13 §14 item 7).
     * A failed write still applies for this session.
     */
    suspend fun setHost(raw: String)

    /** [setHost] with the mode's host; [custom] is the raw text for [MempoolMode.CUSTOM]. */
    suspend fun setMode(mode: MempoolMode, custom: String = "") {
        when (mode) {
            MempoolMode.CLEARNET -> setHost(MempoolHosts.CLEARNET)
            MempoolMode.TOR -> setHost(MempoolHosts.TOR)
            MempoolMode.CUSTOM -> setHost(custom)
        }
    }

    /** Sets (normalized) or clears (`null`/blank) the in-memory session override. */
    fun setSessionOverride(host: String?)

    /** App reset: mempool.space, no override, `mempool_host` removed. */
    suspend fun resetToDefaults()
}
