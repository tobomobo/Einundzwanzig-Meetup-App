package space.einundzwanzig.meetup.feature.profile.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.Tone

/** Text a ViewModel hands to the UI without touching resources itself. */
@Immutable
sealed interface UiText {
    /** A string resource with positional [args] (each arg may itself be a [UiText]). */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A raw text (service messages that are already user facing, e.g. German signer reasons). */
    data class Raw(val text: String) : UiText

    /** Several texts in a row (Flutter concatenated ARB fragments). */
    data class Concat(val parts: List<UiText>) : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

@Composable
@ReadOnlyComposable
fun UiText.resolve(): String {
    // Resources are read through the configuration-aware context, so locale changes recompose.
    LocalConfiguration.current
    return resolveWith(LocalContext.current)
}

/** One snackbar request of a screen (Flutter `ScaffoldMessenger.showSnackBar`). */
@Immutable
data class SnackbarMessage(
    val text: UiText,
    val tone: Tone = Tone.Neutral,
    val durationMillis: Long = AppSnackbarDurations.Medium,
    val dismissable: Boolean = false,
    /** Hide the visible snackbar first (Flutter `hideCurrentSnackBar`); otherwise messages queue. */
    val replaceCurrent: Boolean = false,
) {
    companion object {
        /** Spec 01 §2.7 `_showError`: red, 10 s, close icon. */
        fun error(text: UiText) = SnackbarMessage(text, Tone.Error, AppSnackbarDurations.VeryLong, dismissable = true)
    }
}
