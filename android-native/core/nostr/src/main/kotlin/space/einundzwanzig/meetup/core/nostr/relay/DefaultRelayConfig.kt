package space.einundzwanzig.meetup.core.nostr.relay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig.Companion.KEY_CUSTOM_RELAYS
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig.Companion.KEY_DISABLED_DEFAULTS
import javax.inject.Inject
import kotlin.time.Duration

/**
 * Creates a [RelayConfig] on [store] and [connector] (tests; production code injects it).
 */
fun RelayConfig(store: KeyValueStore, connector: RelayConnector): RelayConfig = DefaultRelayConfig(store, connector)

internal class DefaultRelayConfig @Inject constructor(
    private val store: KeyValueStore,
    private val connector: RelayConnector,
) : RelayConfig {

    override suspend fun getActiveRelays(): List<String> =
        RelayConfig.activeRelaysOf(getDisabledDefaults(), getCustomRelays())

    override fun observeActiveRelays(): Flow<List<String>> =
        combine(disabledFlow(), customFlow()) { disabled, custom -> RelayConfig.activeRelaysOf(disabled, custom) }
            .distinctUntilChanged()

    override suspend fun getCustomRelays(): List<String> = store.getStringList(KEY_CUSTOM_RELAYS) ?: emptyList()

    override suspend fun getDisabledDefaults(): List<String> = store.getStringList(KEY_DISABLED_DEFAULTS) ?: emptyList()

    override suspend fun getRelayStatus(): Map<String, Boolean> =
        RelayConfig.relayStatusOf(getDisabledDefaults(), getCustomRelays())

    override fun observeRelayStatus(): Flow<Map<String, Boolean>> =
        combine(disabledFlow(), customFlow()) { disabled, custom -> RelayConfig.relayStatusOf(disabled, custom) }
            .distinctUntilChanged()

    override suspend fun addCustomRelay(url: String): RelayAddResult {
        val trimmed = url.dartTrim()
        if (!RelayUrls.isWellFormedRelayUrl(trimmed)) return RelayAddResult.INVALID_URL
        val current = getCustomRelays()
        if (trimmed in current || trimmed in RelayConfig.DEFAULT_RELAYS) return RelayAddResult.ALREADY_PRESENT
        if (!probeRelay(trimmed)) return RelayAddResult.UNREACHABLE
        // Re-read inside the transaction so a concurrent change is not lost, and check again:
        // a concurrent add of the same URL (double tap) may have finished during the probe.
        var alreadyPresent = false
        store.edit {
            val latest = getStringList(KEY_CUSTOM_RELAYS) ?: emptyList()
            alreadyPresent = trimmed in latest
            if (!alreadyPresent) setStringList(KEY_CUSTOM_RELAYS, latest + trimmed)
        }
        return if (alreadyPresent) RelayAddResult.ALREADY_PRESENT else RelayAddResult.ADDED
    }

    override suspend fun removeCustomRelay(url: String) {
        store.edit {
            val current = (getStringList(KEY_CUSTOM_RELAYS) ?: emptyList()).toMutableList()
            current.remove(url)
            setStringList(KEY_CUSTOM_RELAYS, current)
        }
    }

    override suspend fun setDefaultRelayEnabled(url: String, enabled: Boolean) {
        store.edit {
            val disabled = (getStringList(KEY_DISABLED_DEFAULTS) ?: emptyList()).toMutableList()
            if (enabled) disabled.remove(url) else if (url !in disabled) disabled.add(url)
            setStringList(KEY_DISABLED_DEFAULTS, disabled)
        }
    }

    override suspend fun probeRelay(url: String, timeout: Duration): Boolean {
        // Assigned inside the block: withTimeout may throw after connect() already returned a
        // socket (asynchronous timeout); `finally` closes it then too.
        var socket: RelaySocket? = null
        return try {
            withTimeout(timeout) { socket = connector.connect(url.dartTrim()) }
            true
        } catch (e: CancellationException) {
            // Only our own withTimeout means "unreachable". A real cancel, or the caller's outer
            // timeout firing during the connect (it arrives as the outer
            // TimeoutCancellationException, kotlinx.coroutines #1374), propagates.
            if (e !is TimeoutCancellationException) throw e
            currentCoroutineContext().ensureActive()
            false
        } catch (_: Exception) {
            false
        } finally {
            socket?.close()
        }
    }

    private fun customFlow(): Flow<List<String>> = store.observeStringList(KEY_CUSTOM_RELAYS).map { it ?: emptyList() }

    private fun disabledFlow(): Flow<List<String>> = store.observeStringList(KEY_DISABLED_DEFAULTS).map { it ?: emptyList() }
}
