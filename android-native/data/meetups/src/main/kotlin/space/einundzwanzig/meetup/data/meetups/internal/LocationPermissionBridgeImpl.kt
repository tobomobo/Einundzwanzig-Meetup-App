package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import space.einundzwanzig.meetup.data.meetups.location.LocationPermission
import space.einundzwanzig.meetup.data.meetups.location.LocationPermissionBridge
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Single-request permission handshake between the location service and the Activity. */
@Singleton
internal class LocationPermissionBridgeImpl @Inject constructor() : LocationPermissionBridge {

    private val state = MutableStateFlow<LocationPermissionBridge.Request?>(null)
    private val ids = AtomicLong()
    private val lock = Any()
    private val requestMutex = Mutex()
    private var waiting: Pair<Long, CompletableDeferred<LocationPermission>>? = null

    /**
     * `true` after the user denied with "don't ask again"; reset by a grant or by any later prompt
     * answer. Android cannot tell this without an Activity, so it is remembered from the last
     * prompt (process lifetime). It is never trusted on its own: `getCurrentLocation` still posts
     * one request while it is set (the system answers instantly if the permission really is denied
     * forever, and shows the dialog if the user reset it to "Ask every time" in the settings), so a
     * stale flag cannot lock the user out. It only decides the answer when no Activity can prompt
     * (no collector, unanswered request): then [LocationPermission.DENIED_FOREVER] instead of
     * [LocationPermission.DENIED].
     */
    @Volatile
    var deniedForever: Boolean = false
        private set

    /**
     * Safety net: a request that gets no answer within this time (e.g. the result was lost
     * across an Activity recreation) resolves to [LocationPermission.DENIED], so [requestMutex]
     * is always released. Mutable for tests only.
     */
    @Volatile
    internal var answerTimeout: Duration = DEFAULT_ANSWER_TIMEOUT

    override val pendingRequest: StateFlow<LocationPermissionBridge.Request?> = state.asStateFlow()

    /**
     * Asks the Activity for the permission and suspends until it answers, at most
     * [answerTimeout]. Without an answer (no collector, timeout) the result is
     * [LocationPermission.DENIED], or [LocationPermission.DENIED_FOREVER] while [deniedForever]
     * is set.
     */
    suspend fun request(): LocationPermission = requestMutex.withLock {
        if (state.subscriptionCount.value == 0) return unanswered()
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<LocationPermission>()
        synchronized(lock) {
            waiting = id to deferred
            state.value = LocationPermissionBridge.Request(id)
        }
        try {
            withTimeoutOrNull(answerTimeout) { deferred.await() } ?: unanswered()
        } finally {
            synchronized(lock) {
                if (waiting?.first == id) waiting = null
                if (state.value?.id == id) state.value = null
            }
        }
    }

    private fun unanswered(): LocationPermission =
        if (deniedForever) LocationPermission.DENIED_FOREVER else LocationPermission.DENIED

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
        answer(id, resultOf(granted, permanentlyDenied))
    }

    override fun onResult(granted: Boolean, permanentlyDenied: Boolean) {
        val id = synchronized(lock) {
            state.value?.takeIf { it.launched && waiting?.first == it.id }?.id
        }
        val result = resultOf(granted, permanentlyDenied)
        if (id == null) {
            // Late answer (request timed out): still remember the "don't ask again" state.
            deniedForever = result == LocationPermission.DENIED_FOREVER
            return
        }
        answer(id, result)
    }

    private fun resultOf(granted: Boolean, permanentlyDenied: Boolean): LocationPermission = when {
        granted -> LocationPermission.GRANTED
        permanentlyDenied -> LocationPermission.DENIED_FOREVER
        else -> LocationPermission.DENIED
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

    /** Remembers the system's answer and resolves request [id] with it. */
    private fun answer(id: Long, result: LocationPermission) {
        deniedForever = result == LocationPermission.DENIED_FOREVER
        complete(id, result)
    }

    private companion object {
        val DEFAULT_ANSWER_TIMEOUT: Duration = 120.seconds
    }
}
