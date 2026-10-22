package space.einundzwanzig.meetup.core.map

import android.graphics.Bitmap
import android.os.Looper
import java.time.Duration
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.robolectric.Shadows.shadowOf

/**
 * Runs [MapSnapshotState.capture] on the paused Robolectric main looper. `capture` hops to
 * `Dispatchers.Main` and awaits a frame, so it cannot run inside `runTest`/`runBlocking` on the
 * test (= main) thread; the scope is cancelled afterwards, so nothing outlives the test.
 */
internal fun captureOnMainLooper(snapshot: MapSnapshotState, pixelRatio: Float = 2.5f): Bitmap? {
    val scope = MainScope()
    try {
        val result = scope.async { snapshot.capture(pixelRatio = pixelRatio) }
        repeat(200) { if (!result.isCompleted) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }
        check(result.isCompleted) { "capture did not finish" }
        @Suppress("OPT_IN_USAGE")
        return result.getCompleted()
    } finally {
        scope.cancel()
    }
}
