package space.einundzwanzig.meetup.core.ui.haptics

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

/**
 * The user's haptics setting (`haptic_enabled`, default true). The app shell provides the
 * persisted value from the settings repository; shared components read it implicitly.
 */
val LocalHapticsEnabled = compositionLocalOf { true }

/**
 * Central haptic patterns (port of `lib/services/haptic_service.dart`, spec 12 §8.11).
 * Each pattern maps to the same `HapticFeedbackConstants` the Flutter engine uses on Android,
 * so both apps feel identical:
 *
 * | pattern | Flutter | Android constant |
 * |---|---|---|
 * | [light] | `selectionClick` | `CLOCK_TICK` |
 * | [medium] | `mediumImpact` | `KEYBOARD_TAP` |
 * | [heavy] | `heavyImpact` | `CONTEXT_CLICK` |
 * | [success] | medium, 90 ms, `lightImpact` | `KEYBOARD_TAP`, 90 ms, `VIRTUAL_KEY` |
 * | [error] | heavy, 110 ms, heavy | `CONTEXT_CLICK`, 110 ms, `CONTEXT_CLICK` |
 *
 * Every call is a no-op while [enabled] returns false. The setting is read on every call
 * (no stale cache, unlike the Flutter service, see spec 12 §14.6).
 */
@Stable
class Haptics(
    private val view: View,
    private val enabled: () -> Boolean,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Tap on tiles, buttons, list rows, tabs. */
    fun light() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** State change: pinned, toggled, selected, copied. */
    fun medium() = perform(HapticFeedbackConstants.KEYBOARD_TAP)

    /** Weighty action: session started, tag written. */
    fun heavy() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    /** Alias of [light] (Dart `HapticService.lightImpact`, which also plays `selectionClick`). */
    fun lightImpact() = light()

    /** Alias of [medium] (Dart `HapticService.mediumImpact`). */
    fun mediumImpact() = medium()

    /** Alias of [heavy] (Dart `HapticService.heavyImpact`). */
    fun heavyImpact() = heavy()

    /** Success: two short pulses, distinguishable from a plain tap. */
    fun success() {
        if (!enabled()) return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        mainHandler.postDelayed({ if (enabled()) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }, SUCCESS_GAP_MS)
    }

    /** Error: two heavy pulses. */
    fun error() {
        if (!enabled()) return
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        mainHandler.postDelayed({ if (enabled()) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }, ERROR_GAP_MS)
    }

    private fun perform(constant: Int) {
        if (enabled()) view.performHapticFeedback(constant)
    }

    companion object {
        /** Pause between the two success pulses (Flutter: 90 ms). */
        const val SUCCESS_GAP_MS: Long = 90

        /** Pause between the two error pulses (Flutter: 110 ms). */
        const val ERROR_GAP_MS: Long = 110
    }
}

/**
 * [Haptics] bound to the current view.
 *
 * @param enabled the user setting; defaults to [LocalHapticsEnabled].
 */
@Composable
fun rememberHaptics(enabled: Boolean = LocalHapticsEnabled.current): Haptics {
    val view = LocalView.current
    val currentEnabled = rememberUpdatedState(enabled)
    return remember(view) { Haptics(view) { currentEnabled.value } }
}
