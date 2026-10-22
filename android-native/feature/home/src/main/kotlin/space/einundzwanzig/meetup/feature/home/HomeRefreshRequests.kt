package space.einundzwanzig.meetup.feature.home

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Flutter `HomeScreen.refreshAfterScan()` for the app shell: call [request] when the Home tab is
 * tapped (also when it is already selected) and when the badge scanner opened from the scan sheet
 * returns (spec 12 §2.4). The dashboard reloads badges, favorites, the organizer status and
 * "Meine Termine".
 */
@Singleton
class HomeRefreshRequests @Inject constructor() {
    private val mutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Requests seen by the dashboard ViewModel. */
    val requests: SharedFlow<Unit> = mutable.asSharedFlow()

    /** Asks the dashboard to refresh (no-op while Home is not composed). */
    fun request() {
        mutable.tryEmit(Unit)
    }
}
