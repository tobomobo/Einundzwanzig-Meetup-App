package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.einundzwanzig.meetup.data.meetups.location.LocationPermission
import space.einundzwanzig.meetup.data.meetups.location.LocationPermissionBridge
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Single-request permission handshake between the location service and the Activity. */
@Singleton
internal class LocationPermissionBridgeImpl @Inject constructor() : LocationPermissionBridge {

    private val state = MutableStateFlow<LocationPermissionBridge.Request?>(null)
    private val ids = AtomicLong()
    private val lock = Any()
    private val requestMutex = Mutex()
    private var waiting: Pair<Long, CompletableDeferred<LocationPermission>>? = null

    /**
     * `true` after the user denied with "don't ask again"; reset by a grant. Android cannot tell
     * this without an Activity, so it is remembered from the last prompt (process lifetime).
     */
    @Volatile
    var deniedForever: Boolean = false
        private set

    override val pendingRequest: StateFlow<LocationPermissionBridge.Request?> = state.asStateFlow()

    /** Asks the Activity for the permission and suspends until it answers. */
    suspend fun request(): LocationPermission = requestMutex.withLock {
        if (state.subscriptionCount.value == 0) return LocationPermission.DENIED
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<LocationPermission>()
        synchronized(lock) {
            waiting = id to deferred
            state.value = LocationPermissionBridge.Request(id)
        }
        try {
            deferred.await()
        } finally {
            synchronized(lock) {
                if (waiting?.first == id) waiting = null
                if (state.value?.id == id) state.value = null
            }
        }
    }

    /** Clears [deniedForever] once the permission is granted (e.g. via the app settings). */
    fun noteGranted() {
        deniedForever = false
    }

    override fun onLaunched(id: Long): Boolean = synchronized(lock) {
        val current = state.value
        if (current == null || current.id != id || current.launched) return false
        state.value = current.copy(launched = true)
        true
    }

    override fun onResult(id: Long, granted: Boolean, permanentlyDenied: Boolean) {
        val result = when {
            granted -> LocationPermission.GRANTED
            permanentlyDenied -> LocationPermission.DENIED_FOREVER
            else -> LocationPermission.DENIED
        }
        deniedForever = result == LocationPermission.DENIED_FOREVER
        complete(id, result)
    }

    override fun onLaunchFailed(id: Long) = complete(id, LocationPermission.DENIED)

    private fun complete(id: Long, result: LocationPermission) {
        val deferred = synchronized(lock) {
            val w = waiting?.takeIf { it.first == id } ?: return
            waiting = null
            if (state.value?.id == id) state.value = null
            w.second
        }
        deferred.complete(result)
    }
}
