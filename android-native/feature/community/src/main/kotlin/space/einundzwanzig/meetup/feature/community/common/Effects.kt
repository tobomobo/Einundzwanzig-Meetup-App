package space.einundzwanzig.meetup.feature.community.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/** Collects one-shot [events] while the screen is at least STARTED (visible). */
@Composable
internal fun <T> ObserveAsEvents(events: Flow<T>, onEvent: suspend (T) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val handler by rememberUpdatedState(onEvent)
    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            events.collect { handler(it) }
        }
    }
}
