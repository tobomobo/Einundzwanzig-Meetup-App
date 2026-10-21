package space.einundzwanzig.meetup.core.ui.motion

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Overrides the system "remove animations" detection (previews, screenshot tests).
 * `null` (default) means: read the system setting.
 */
val LocalReducedMotion = compositionLocalOf<Boolean?> { null }

/**
 * True when the user turned animations off (developer option / accessibility
 * "Remove animations", i.e. `ANIMATOR_DURATION_SCALE == 0`). Mirrors Flutter's
 * `MediaQuery.disableAnimations`, which decorative animations (pulsing empty-state icon,
 * scanner line, press scale) respect by standing still.
 */
fun isReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Composable accessor for [isReducedMotion], honouring [LocalReducedMotion]. */
@Composable
fun rememberReducedMotion(): Boolean {
    LocalReducedMotion.current?.let { return it }
    val context = LocalContext.current
    return remember(context) { isReducedMotion(context) }
}

/**
 * Spring specs matching the Material 3 Expressive motion tokens. Usable outside of a
 * composition (modifier nodes), where `MaterialTheme.motionScheme` is not reachable.
 */
object ExpressiveSprings {
    /** Expressive "fast spatial": small elements, press feedback. Slight overshoot. */
    fun <T> fastSpatial(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** Expressive "default spatial". */
    fun <T> defaultSpatial(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Expressive "default effects" (alpha/colour, no overshoot). */
    fun <T> defaultEffects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
}
