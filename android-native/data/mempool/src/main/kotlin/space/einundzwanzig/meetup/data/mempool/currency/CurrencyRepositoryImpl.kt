package space.einundzwanzig.meetup.data.mempool.currency

import dagger.Lazy
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
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.data.mempool.MempoolPrefs
import space.einundzwanzig.meetup.data.mempool.MempoolService
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetBridge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CurrencyRepository] on the shared [KeyValueStore].
 *
 * Local writes hold [writeMutex] until the store write returned; the store observer re-reads the
 * store under the same lock, so a late emission of an older [set] cannot flip [current] back.
 */
@Singleton
internal class CurrencyRepositoryImpl @Inject constructor(
    private val store: KeyValueStore,
    private val mempool: MempoolService,
    private val widget: Lazy<BitcoinWidgetBridge>,
    private val log: DiagnosticLog,
    @ApplicationScope private val scope: CoroutineScope,
) : CurrencyRepository {

    private val _current = MutableStateFlow(CurrencyFormat.FALLBACK)
    override val current: StateFlow<String> = _current.asStateFlow()

    private val writeMutex = Mutex()

    /**
     * Bumped after every successful local write. The observer re-subscribes on each bump, so its
     * distinct-baseline is the post-write value: a quick `X → local Y → external X` sequence
     * (e.g. app reset right after a settings change) is still seen as a change.
     */
    private val localWrites = MutableStateFlow(0)

    init {
        scope.launch {
            // Every emission (including the first, which may already be a later change) is
            // applied from the store's current state, never from the possibly stale value.
            @OptIn(ExperimentalCoroutinesApi::class)
            localWrites.flatMapLatest { store.observeString(MempoolPrefs.FIAT_CURRENCY) }.collect {
                writeMutex.withLock { _current.value = read() }
            }
        }
    }

    override suspend fun load() {
        writeMutex.withLock { _current.value = read() }
    }

    override suspend fun read(): String = try {
        validated(store.getString(MempoolPrefs.FIAT_CURRENCY))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CurrencyFormat.FALLBACK
    }

    override suspend fun set(code: String) {
        if (!CurrencyFormat.isSupported(code)) return
        writeMutex.withLock {
            _current.value = code
            if (store.setString(MempoolPrefs.FIAT_CURRENCY, code)) {
                localWrites.update { it + 1 }
            } else {
                log.warn(TAG, "fiat_currency konnte nicht gespeichert werden")
            }
        }
        // Update the home-screen widget right away, not only on the next fetch.
        val d = mempool.lastDashboard.value
        if (d != null) scope.launch { widget.get().publish(d) }
    }

    override suspend fun reset() {
        writeMutex.withLock {
            _current.value = CurrencyFormat.FALLBACK
            if (store.remove(MempoolPrefs.FIAT_CURRENCY)) localWrites.update { it + 1 }
        }
    }

    private fun validated(code: String?): String =
        if (code != null && CurrencyFormat.isSupported(code)) code else CurrencyFormat.FALLBACK

    private companion object {
        const val TAG = "Currency"
    }
}
