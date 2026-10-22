package space.einundzwanzig.meetup.core.ui.component

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.channels.Channel

/**
 * App-lifetime snackbar queue (Flutter's root `ScaffoldMessenger`). Code that outlives a screen
 * (an application-scope publish, a message posted right before a `replaceTop`) calls [show]; the
 * app shell drains the queue into its current [SnackbarHostState] with [AppSnackbarControllerEffect].
 *
 * Unlike handing a [SnackbarHostState] to a long-lived coroutine, nothing here references the
 * activity composition: after a rotation the new host simply continues the queue. Holds no
 * Android types, so a ViewModel or an `@ApplicationScope` job may keep a reference.
 */
@Stable
class AppSnackbarController {
    private val queue = Channel<AppSnackbarVisuals>(Channel.UNLIMITED)

    /** The message that was showing when the host left composition; shown again first. */
    private var interrupted: AppSnackbarVisuals? = null

    /** Queues one message. Thread-safe and non-suspending. */
    fun show(message: String, tone: Tone = Tone.Neutral, durationMillis: Long = AppSnackbarDurations.Medium) {
        queue.trySend(AppSnackbarVisuals(message, tone, durationMillis = durationMillis))
    }

    /** Shows queued messages on [host] one after the other until cancelled. */
    suspend fun drainInto(host: SnackbarHostState) {
        while (true) {
            val next = interrupted ?: queue.receive()
            interrupted = next // stays set if the host leaves composition mid-display
            host.showSnackbar(next)
            interrupted = null
        }
    }
}

/**
 * The app's [AppSnackbarController]; `null` when the shell provides none (tests, previews).
 * Screens fall back to their own host then.
 */
val LocalAppSnackbarController = staticCompositionLocalOf<AppSnackbarController?> { null }

/** Drains [controller] into [host] while composed; use it next to the app's [AppSnackbarHost]. */
@Composable
fun AppSnackbarControllerEffect(controller: AppSnackbarController, host: SnackbarHostState) {
    LaunchedEffect(controller, host) { controller.drainInto(host) }
}
