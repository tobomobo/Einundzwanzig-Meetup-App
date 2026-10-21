package space.einundzwanzig.meetup.data.mempool.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.data.mempool.MempoolPrefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monotonic "highest block height ever seen" (`mempool_last_height`, spec 13 §8.3 step 3).
 * Kept in RAM and in the shared store; the effective value is the max of both, and it never
 * decreases.
 */
@Singleton
internal class BlockHeightStore @Inject constructor(
    private val store: KeyValueStore,
    private val log: DiagnosticLog,
) {
    private val mutex = Mutex()

    @Volatile
    private var ramHeight: Long = 0

    /** `max(RAM, stored)`; store failures fall back to the RAM value. */
    suspend fun load(): Long = mutex.withLock {
        try {
            val stored = store.getInt(MempoolPrefs.LAST_HEIGHT) ?: 0L
            if (stored > ramHeight) ramHeight = stored
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "mempool_last_height nicht lesbar: ${e::class.simpleName}")
        }
        ramHeight
    }

    /** Raises the known height to [height]; ignored unless strictly greater. */
    suspend fun save(height: Long) = mutex.withLock {
        if (height <= ramHeight) return@withLock
        ramHeight = height
        try {
            store.setInt(MempoolPrefs.LAST_HEIGHT, height)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "mempool_last_height nicht gespeichert: ${e::class.simpleName}")
        }
    }

    /** App reset: forgets the RAM value (the store itself is wiped by the reset). */
    fun clear() {
        ramHeight = 0
    }

    private companion object {
        const val TAG = "Mempool"
    }
}
