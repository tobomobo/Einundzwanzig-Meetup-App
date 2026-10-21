package space.einundzwanzig.meetup.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import space.einundzwanzig.meetup.core.ui.R
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors

/** Snackbar display times used by the Flutter app (milliseconds). */
object AppSnackbarDurations {
    /** 1 s — e.g. "npub kopiert" on the profile. */
    const val Brief: Long = 1_000

    /** 2 s — Flutter's short confirmations (`npubCopied`). */
    const val Short: Long = 2_000

    /** 3 s — success / failure results (link saved, publish result). */
    const val Medium: Long = 3_000

    /** 6 s — event-session errors. */
    const val Long: Long = 6_000

    /** 8 s — "badges unreadable" notice on home. */
    const val ExtraLong: Long = 8_000

    /** 10 s — long, dismissable error messages (spec 06: platform proof failures). */
    const val VeryLong: Long = 10_000
}

/**
 * [SnackbarVisuals] with a semantic [tone] and an exact display time. Only [AppSnackbarHost]
 * honours [durationMillis]; to Material it reports [SnackbarDuration.Indefinite].
 */
@Immutable
data class AppSnackbarVisuals(
    override val message: String,
    val tone: Tone = Tone.Neutral,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    val durationMillis: Long = AppSnackbarDurations.Medium,
) : SnackbarVisuals {
    override val duration: SnackbarDuration get() = SnackbarDuration.Indefinite
}

/**
 * Shows an app snackbar and suspends until it is dismissed or its action is pressed.
 * The display time starts when the snackbar becomes visible (queued snackbars wait).
 */
suspend fun SnackbarHostState.showAppSnackbar(
    message: String,
    tone: Tone = Tone.Neutral,
    durationMillis: Long = AppSnackbarDurations.Medium,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
): SnackbarResult = showSnackbar(
    AppSnackbarVisuals(message, tone, actionLabel, withDismissAction, durationMillis),
)

/**
 * The long, dismissable variant: 10 s with a close icon. For long error texts such as
 * "Fehler: Der Signer hat abgelehnt: Permission denied for sign_event kind:21003".
 */
suspend fun SnackbarHostState.showLongDismissable(
    message: String,
    tone: Tone = Tone.Error,
    actionLabel: String? = null,
): SnackbarResult = showAppSnackbar(
    message = message,
    tone = tone,
    durationMillis = AppSnackbarDurations.VeryLong,
    actionLabel = actionLabel,
    withDismissAction = true,
)

/**
 * The app-wide snackbar host state, provided by the app shell (Flutter `rootMessengerKey`).
 * Shared components (e.g. NpubChip) use it for confirmations; `null` means no host.
 */
val LocalAppSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }

/** Colours of one snackbar tone. */
@Immutable
data class SnackbarToneColors(val container: Color, val content: Color, val action: Color)

/** Brand snackbar styling (Flutter `SnackBarTheme`: card colour, white text, radius 10, flat). */
object AppSnackbarDefaults {
    /** Snackbar shape (radius 10, floating). */
    val Shape = RoundedCornerShape(10.dp)

    /** Colours for [tone]; neutral text is `onSurface` on the card colour (contrast >= 4.5). */
    @Composable
    @ReadOnlyComposable
    fun colors(tone: Tone): SnackbarToneColors {
        val scheme = MaterialTheme.colorScheme
        return when (tone) {
            Tone.Neutral, Tone.Info -> SnackbarToneColors(scheme.surfaceContainer, scheme.onSurface, scheme.primary)
            Tone.Success -> MaterialTheme.extendedColors.let {
                SnackbarToneColors(it.successContainer, it.onSuccessContainer, it.onSuccessContainer)
            }
            Tone.Warning -> SnackbarToneColors(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.onPrimaryContainer)
            Tone.Error -> SnackbarToneColors(scheme.errorContainer, scheme.onErrorContainer, scheme.onErrorContainer)
        }
    }

    /**
     * Display time for [visuals] adjusted by the accessibility settings (users who need
     * longer time to read get it, as with Material's own durations).
     */
    fun timeoutMillis(visuals: AppSnackbarVisuals, accessibilityManager: AccessibilityManager?): Long =
        accessibilityManager?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = visuals.durationMillis,
            containsIcons = visuals.withDismissAction,
            containsText = true,
            containsControls = visuals.actionLabel != null,
        ) ?: visuals.durationMillis
}

/**
 * [SnackbarHost] that renders [AppSnackbarVisuals] with their tone and dismisses them after
 * their exact duration. Plain `showSnackbar(...)` calls keep Material's behaviour.
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val accessibilityManager = LocalAccessibilityManager.current
    val current = hostState.currentSnackbarData
    LaunchedEffect(current) {
        val visuals = current?.visuals as? AppSnackbarVisuals ?: return@LaunchedEffect
        delay(AppSnackbarDefaults.timeoutMillis(visuals, accessibilityManager))
        current.dismiss()
    }
    SnackbarHost(hostState, modifier) { data -> AppSnackbar(data) }
}

/** One brand snackbar for [data]; tone from [AppSnackbarVisuals], neutral otherwise. */
@Composable
fun AppSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val visuals = data.visuals
    val tone = (visuals as? AppSnackbarVisuals)?.tone ?: Tone.Neutral
    val colors = AppSnackbarDefaults.colors(tone)
    Snackbar(
        modifier = modifier.padding(12.dp),
        action = visuals.actionLabel?.let { label ->
            {
                TextButton(onClick = { data.performAction() }) {
                    Text(label, color = colors.action, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissAction = if (visuals.withDismissAction) {
            {
                IconButton(onClick = { data.dismiss() }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.native_snackbar_dismiss),
                        tint = colors.content,
                    )
                }
            }
        } else {
            null
        },
        shape = AppSnackbarDefaults.Shape,
        containerColor = colors.container,
        contentColor = colors.content,
    ) {
        Text(
            visuals.message,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.5.sp),
        )
    }
}

@Preview(name = "AppSnackbar", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun AppSnackbarPreview() {
    EinundzwanzigTheme {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Tone.entries.forEach { tone ->
                AppSnackbar(PreviewSnackbarData(AppSnackbarVisuals("Chatraum wird gesucht … ($tone)", tone, withDismissAction = tone == Tone.Error)))
            }
        }
    }
}

private class PreviewSnackbarData(override val visuals: SnackbarVisuals) : SnackbarData {
    override fun performAction() = Unit
    override fun dismiss() = Unit
}
