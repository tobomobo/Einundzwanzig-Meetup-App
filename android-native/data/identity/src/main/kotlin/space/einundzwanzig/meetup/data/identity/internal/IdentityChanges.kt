package space.einundzwanzig.meetup.data.identity.internal

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signals identity changes that are invisible in the prefs (secure-store key writes/deletes,
 * profile saves), so the profile flow re-reads them. Conflated: only "something changed" matters.
 */
@Singleton
internal class IdentityChanges @Inject constructor() {
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits after every relevant change. */
    val changes: SharedFlow<Unit> = flow.asSharedFlow()

    /** Records a change. */
    fun notifyChanged() {
        flow.tryEmit(Unit)
    }
}
