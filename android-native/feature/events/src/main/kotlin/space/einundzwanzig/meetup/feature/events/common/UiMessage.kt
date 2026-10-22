package space.einundzwanzig.meetup.feature.events.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar

/** Text that is resolved in the UI (ViewModels never touch resources). */
@Immutable
sealed interface UiText {
    /** A string resource; [args] may themselves be [UiText]s. */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A literal (relay/portal error text). */
    data class Raw(val text: String) : UiText

    /** Resolves the text with [context]'s resources. */
    fun resolve(context: Context): String = when (this) {
        is Raw -> text
        is Res -> if (args.isEmpty()) {
            context.getString(id)
        } else {
            context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
        }
    }

    companion object {
        /** Shorthand for [Res]. */
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

/** What a snackbar action does; handled by the screen (it needs a Context or the navigator). */
enum class MessageAction { OPEN_LOCATION_SETTINGS, OPEN_APP_SETTINGS }

/** One snackbar (Flutter `ScaffoldMessenger.showSnackBar`). */
@Immutable
data class UiMessage(
    val text: UiText,
    val tone: Tone = Tone.Neutral,
    val durationMillis: Long = AppSnackbarDurations.Medium,
    @param:StringRes val actionLabel: Int? = null,
    val action: MessageAction? = null,
) {
    companion object {
        /** Only hides the visible snackbar (Flutter `hideCurrentSnackBar`). */
        val Dismiss: UiMessage = UiMessage(UiText.Raw(""), durationMillis = 0)
    }
}

/**
 * Collects [messages] into [hostState]. Every new message replaces the visible one (Flutter's
 * `hideCurrentSnackBar` + `showSnackBar` pattern used throughout these screens).
 */
@Composable
fun MessageEffect(
    messages: Flow<UiMessage>,
    hostState: SnackbarHostState,
    onAction: (MessageAction) -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(messages, hostState) {
        messages.collect { m ->
            hostState.currentSnackbarData?.dismiss()
            if (m == UiMessage.Dismiss) return@collect
            launch {
                val result = hostState.showAppSnackbar(
                    message = m.text.resolve(context),
                    tone = m.tone,
                    durationMillis = m.durationMillis,
                    actionLabel = m.actionLabel?.let(context::getString),
                )
                if (result == SnackbarResult.ActionPerformed) m.action?.let(onAction)
            }
        }
    }
}
