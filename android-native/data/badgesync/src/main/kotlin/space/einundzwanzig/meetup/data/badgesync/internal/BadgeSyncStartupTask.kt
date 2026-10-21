package space.einundzwanzig.meetup.data.badgesync.internal

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.data.badgesync.BadgeSync
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Starts the badge sync (Dart `main`: `BadgeSync.init()` + `BadgeSync.retryIfDirty()`; the retry
 * is dropped here because `init()` already triggers a full run that sets dirty on failure) and
 * replaces the Dart home-screen `resumed` hook: every later return of the app to the
 * foreground ([ProcessLifecycleOwner] `ON_START`) runs [BadgeSync.retryIfDirty]. Only the
 * catch-up `ON_START` that [androidx.lifecycle.Lifecycle.addObserver] delivers while the app is
 * already in the foreground (the launch itself) is skipped, it is covered by the start-up call. A
 * process started in the background (widget, worker, push) retries on the first real return.
 * [run] returns at once; the relay work runs in [ApplicationScope].
 */
internal class BadgeSyncStartupTask @Inject constructor(
    private val sync: BadgeSync,
    @param:ApplicationScope private val scope: CoroutineScope,
) : AppStartupTask {

    override val name: String = "BadgeSync"

    private val firstStart = AtomicBoolean(false)

    private val resumeObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (firstStart.getAndSet(false)) return
            scope.launch { sync.retryIfDirty() }
        }
    }

    override suspend fun run() {
        sync.init()
        withContext(Dispatchers.Main.immediate) {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            firstStart.set(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            lifecycle.addObserver(resumeObserver)
        }
    }
}
