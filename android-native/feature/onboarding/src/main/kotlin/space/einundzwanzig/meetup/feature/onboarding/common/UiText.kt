package space.einundzwanzig.meetup.feature.onboarding.common

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar

/** Text a ViewModel hands to the UI without touching resources itself. */
@Immutable
sealed interface UiText {
    /** A string resource with positional [args]. */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** Raw text: service messages that are already user facing (German service texts, as in Flutter). */
    data class Raw(val text: String) : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

@Composable
@ReadOnlyComposable
fun UiText.resolve(): String {
    LocalConfiguration.current
    return resolveWith(LocalContext.current)
}

fun UiText.resolveWith(context: Context): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> context.getString(id, *args.toTypedArray())
}

/** One snackbar request (Flutter `ScaffoldMessenger.showSnackBar`). */
@Immutable
data class SnackbarMessage(
    val text: UiText,
    val tone: Tone = Tone.Neutral,
    val durationMillis: Long = AppSnackbarDurations.Medium,
)

/**
 * Shows every message of [messages] in [hostState]; a new message replaces the visible one.
 *
 * `showSnackbar` removes its snackbar when the calling coroutine is cancelled. For the app-level
 * host ([LocalAppSnackbarHostState]) the snackbar must outlive this nav entry (e.g. Intro's
 * `introSetIdentity` while the setup is pushed, or "key saved" right before the reset to the
 * shell), so it runs in the Activity's lifecycle scope, which owns that host. A local host lives
 * and dies with the entry and uses the composition scope.
 */
@Composable
fun SnackbarEffect(messages: Flow<SnackbarMessage>, hostState: SnackbarHostState) {
    val localScope = rememberCoroutineScope()
    val appHost = LocalAppSnackbarHostState.current
    val activityScope = (LocalActivity.current as? LifecycleOwner)?.lifecycleScope
    val scope = if (hostState === appHost && activityScope != null) activityScope else localScope
    val currentScope = rememberUpdatedState(scope)
    val context = rememberUpdatedState(LocalContext.current)
    LaunchedEffect(messages, hostState) {
        messages.collect { message ->
            val text = message.text.resolveWith(context.value)
            hostState.currentSnackbarData?.dismiss()
            currentScope.value.launch { hostState.showAppSnackbar(text, message.tone, message.durationMillis) }
        }
    }
}
