package space.einundzwanzig.meetup.data.mempool.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.data.mempool.MempoolConfig
import space.einundzwanzig.meetup.data.mempool.MempoolHosts
import space.einundzwanzig.meetup.data.mempool.MempoolMode
import space.einundzwanzig.meetup.data.mempool.MempoolPrefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MempoolConfig] on the shared [KeyValueStore].
 *
 * The persisted host is read lazily on [ensureLoaded] (like Dart) and afterwards follows every
 * change of `mempool_host` in the store (migration, app reset), re-normalized on read.
 *
 * Every mutation of [persisted] runs under [loadMutex]; local writes hold it until the store
 * write has returned, and the store observer re-reads the store under the same lock instead of
 * trusting the (possibly stale) emitted value. A late emission of an older local write can
 * therefore never flip the host back.
 */
@Singleton
internal class MempoolConfigImpl @Inject constructor(
    private val store: KeyValueStore,
    private val log: DiagnosticLog,
    @ApplicationScope scope: CoroutineScope,
) : MempoolConfig {

    @Volatile
    private var persisted: String = MempoolHosts.CLEARNET

    @Volatile
    private var sessionOverride: String? = null

    @Volatile
    private var loaded = false
    private val loadMutex = Mutex()

    private val _hostFlow = MutableStateFlow(MempoolHosts.CLEARNET)
    override val hostFlow: StateFlow<String> = _hostFlow.asStateFlow()

    override val host: String get() = sessionOverride ?: persisted
    override val persistedHost: String get() = persisted

    /**
     * Bumped after every successful local write. The observer re-subscribes on each bump, so its
     * distinct-baseline is the post-write value: a quick `X → local Y → external X` sequence
     * (e.g. app reset right after a settings change) is still seen as a change.
     */
    private val localWrites = MutableStateFlow(0)

    init {
        scope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            localWrites.flatMapLatest { store.observeString(MempoolPrefs.HOST) }.collect { onStoreChanged() }
        }
    }

    /** Applies an external change of `mempool_host` (migration, app reset) from the store's current state. */
    private suspend fun onStoreChanged() {
        loadMutex.withLock {
            val next = try {
                fromStored(store.getString(MempoolPrefs.HOST))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(TAG, "mempool_host nicht lesbar — Standard bleibt", e)
                return
            }
            if (!loaded || next != persisted) {
                if (loaded) sessionOverride = null
                persisted = next
                loaded = true
                publish()
            }
        }
    }

    override suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            try {
                persisted = fromStored(store.getString(MempoolPrefs.HOST))
            } catch (e: CancellationException) {
                // The caller went away: stay unloaded so the next caller reads the store again.
                throw e
            } catch (e: Exception) {
                // Unreadable prefs: keep the default, no hard failure (Dart behaviour).
                log.warn(TAG, "mempool_host nicht lesbar — Standard bleibt", e)
            }
            loaded = true
            publish()
        }
    }

    override suspend fun setHost(raw: String) {
        val normalized = MempoolHosts.normalize(raw)
        loadMutex.withLock {
            persisted = normalized
            sessionOverride = null
            loaded = true
            publish()
            if (store.setString(MempoolPrefs.HOST, normalized)) {
                localWrites.update { it + 1 }
            } else {
                log.warn(TAG, "mempool_host konnte nicht gespeichert werden — gilt nur fuer diese Sitzung")
            }
        }
    }

    override fun setSessionOverride(host: String?) {
        sessionOverride = if (host == null || host.dartTrim().isEmpty()) null else MempoolHosts.normalize(host)
        publish()
    }

    override suspend fun resetToDefaults() {
        loadMutex.withLock {
            persisted = MempoolHosts.CLEARNET
            sessionOverride = null
            loaded = true
            publish()
            if (store.remove(MempoolPrefs.HOST)) localWrites.update { it + 1 }
        }
    }

    /**
     * Sets the mirror [fallback] as session override, but only if the persisted host is still
     * [expectedPersisted] and the mode is clearnet; atomic with [setHost]/[resetToDefaults], so
     * a switch to Tor or an own node during a fallback run can never be overridden by a
     * clearnet mirror (spec 13 §9.1). Returns whether the override was set.
     */
    suspend fun trySetFallbackOverride(fallback: String, expectedPersisted: String): Boolean =
        loadMutex.withLock {
            if (persisted != expectedPersisted || mode != MempoolMode.CLEARNET) return@withLock false
            setSessionOverride(fallback)
            true
        }

    private fun publish() {
        _hostFlow.value = host
    }

    private fun fromStored(saved: String?): String =
        if (saved != null && saved.dartTrim().isNotEmpty()) MempoolHosts.normalize(saved) else MempoolHosts.CLEARNET

    private companion object {
        const val TAG = "Mempool"
    }
}
