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
    /** Serialises store access so a lower height never overwrites a higher one. */
    private val mutex = Mutex()

    /** Guards [ramHeight] and [clears]; never held across a suspension point. */
    private val ramLock = Any()

    private var ramHeight: Long = 0

    /** Bumped by [clear]; a [load] that read the store in an older generation adopts nothing. */
    private var clears = 0

    /** `max(RAM, stored)`; store failures fall back to the RAM value. */
    suspend fun load(): Long = mutex.withLock {
        val gen = synchronized(ramLock) { clears }
        try {
            val stored = store.getInt(MempoolPrefs.LAST_HEIGHT) ?: 0L
            synchronized(ramLock) {
                if (clears == gen && stored > ramHeight) ramHeight = stored
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "mempool_last_height nicht lesbar: ${e::class.simpleName}")
        }
        synchronized(ramLock) { ramHeight }
    }

    /** Raises the known height to [height]; ignored unless strictly greater. */
    suspend fun save(height: Long) = mutex.withLock {
        val raised = synchronized(ramLock) {
            if (height <= ramHeight) {
                false
            } else {
                ramHeight = height
                true
            }
        }
        if (!raised) return@withLock
        try {
            store.setInt(MempoolPrefs.LAST_HEIGHT, height)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "mempool_last_height nicht gespeichert: ${e::class.simpleName}")
        }
    }

    /**
     * App reset: forgets the RAM value; a [load] still reading the store when this runs does
     * not bring the old value back. The store itself is wiped by the reset, which must happen
     * **before** this call: a [load] after [clear] but before the wipe would re-read the old
     * height.
     */
    fun clear() {
        synchronized(ramLock) {
            clears++
            ramHeight = 0
        }
    }

    private companion object {
        const val TAG = "Mempool"
    }
}
