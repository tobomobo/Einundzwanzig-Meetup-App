package space.einundzwanzig.meetup.feature.profile.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar

/**
 * Shows every [SnackbarMessage] of [messages] in [hostState]. Like Flutter's
 * `hideCurrentSnackBar()` + `showSnackBar()`, a message with [SnackbarMessage.replaceCurrent]
 * replaces the visible one and drops every message still queued behind it (a queued
 * `showSnackbar` that is cancelled while waiting for the host never appears).
 */
@Composable
fun SnackbarEffect(messages: Flow<SnackbarMessage>, hostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resolver = rememberUpdatedState(context)
    LaunchedEffect(messages, hostState) {
        val jobs = mutableListOf<Job>()
        messages.collect { message ->
            val text = message.text.resolveWith(resolver.value)
            if (message.replaceCurrent) {
                jobs.toList().forEach { it.cancel() }
                jobs.clear()
                hostState.currentSnackbarData?.dismiss()
            }
            val job = scope.launch {
                hostState.showAppSnackbar(
                    message = text,
                    tone = message.tone,
                    durationMillis = message.durationMillis,
                    withDismissAction = message.dismissable,
                )
            }
            jobs += job
            job.invokeOnCompletion { jobs.remove(job) }
        }
    }
}

/** Non-composable resolution (inside effects). */
fun UiText.resolveWith(context: android.content.Context): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> {
        val resolved = args.map { if (it is UiText) it.resolveWith(context) else it }
        context.getString(id, *resolved.toTypedArray())
    }
    is UiText.Concat -> parts.joinToString("") { it.resolveWith(context) }
}
