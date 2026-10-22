package space.einundzwanzig.meetup.core.nostr.relay

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Result of [RelayConfig.addCustomRelay] (Dart `RelayAddResult`). */
enum class RelayAddResult { ADDED, INVALID_URL, UNREACHABLE, ALREADY_PRESENT }

/**
 * The user's relay list (Dart `RelayConfig`): four default relays that can be switched off,
 * plus custom relays that were validated and probed when added. Every domain asks
 * [getActiveRelays] where to read and publish.
 *
 * Stored in the shared `KeyValueStore` under the Flutter keys [KEY_CUSTOM_RELAYS] and
 * [KEY_DISABLED_DEFAULTS] (string lists, insertion order, strings exactly as typed).
 */
interface RelayConfig {

    /** [DEFAULT_RELAYS]. */
    val defaultRelays: List<String> get() = DEFAULT_RELAYS

    /** Enabled defaults (in default order) followed by the custom relays, duplicates removed (first wins). */
    suspend fun getActiveRelays(): List<String>

    /** [getActiveRelays] now and after every change of the two keys. */
    fun observeActiveRelays(): Flow<List<String>>

    /** The custom relays in insertion order. */
    suspend fun getCustomRelays(): List<String>

    /** The default relays the user switched off. */
    suspend fun getDisabledDefaults(): List<String>

    /** Defaults → enabled flag (in default order), then custom relays → `true` (ordered map). */
    suspend fun getRelayStatus(): Map<String, Boolean>

    /** [getRelayStatus] now and after every change. */
    fun observeRelayStatus(): Flow<Map<String, Boolean>>

    /**
     * Validates ([isWellFormedRelayUrl]) the trimmed [url], rejects duplicates of a custom or
     * default relay (exact string compare: `wss://nos.lol/` is not a duplicate of
     * `wss://nos.lol`), probes it ([probeRelay], 5 s) and appends it as typed.
     */
    suspend fun addCustomRelay(url: String): RelayAddResult

    /** Removes the first entry equal to [url]. */
    suspend fun removeCustomRelay(url: String)

    /** Removes [url] from / adds it (once) to the disabled-defaults list. */
    suspend fun setDefaultRelayEnabled(url: String, enabled: Boolean)

    /**
     * `true` if a WebSocket handshake to the trimmed [url] completes within [timeout]. The
     * socket is always closed afterwards. No NIP-11, no REQ: any upgrade counts as reachable.
     */
    suspend fun probeRelay(url: String, timeout: Duration = PROBE_TIMEOUT): Boolean

    companion object {
        /** Default relays, exact strings, exact order. */
        val DEFAULT_RELAYS: List<String> = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band",
            "wss://nostr.einundzwanzig.space",
        )

        /** Pref key (string list): custom relays. */
        const val KEY_CUSTOM_RELAYS: String = "custom_relays"

        /** Pref key (string list): disabled default relays. */
        const val KEY_DISABLED_DEFAULTS: String = "disabled_default_relays"

        /** Dart `RelayConfig.relayTimeout`: connect and answer budget for relay reads. */
        val RELAY_TIMEOUT: Duration = 8.seconds

        /** Dart `RelayConfig.publishTimeout`: connect budget for publishing. */
        val PUBLISH_TIMEOUT: Duration = 5.seconds

        /** Default timeout of [probeRelay]. */
        val PROBE_TIMEOUT: Duration = 5.seconds

        /** `active = defaults − disabled + custom`, de-duplicated keeping the first occurrence. */
        fun activeRelaysOf(disabled: List<String>, custom: List<String>): List<String> =
            LinkedHashSet<String>().apply {
                DEFAULT_RELAYS.filterTo(this) { it !in disabled }
                addAll(custom)
            }.toList()

        /** Defaults → `!disabled`, then custom → `true`, as an ordered map. */
        fun relayStatusOf(disabled: List<String>, custom: List<String>): Map<String, Boolean> =
            LinkedHashMap<String, Boolean>().apply {
                for (r in DEFAULT_RELAYS) this[r] = r !in disabled
                for (r in custom) this[r] = true
            }

        /** See [RelayUrls.isWellFormedRelayUrl]. */
        fun isWellFormedRelayUrl(url: String): Boolean = RelayUrls.isWellFormedRelayUrl(url)
    }
}
