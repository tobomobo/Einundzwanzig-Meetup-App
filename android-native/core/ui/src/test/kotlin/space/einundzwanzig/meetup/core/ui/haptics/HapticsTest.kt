package space.einundzwanzig.meetup.core.ui.haptics

import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** Pattern mapping of spec 12 §8.11 (same constants as the Flutter engine on Android). */
@RunWith(AndroidJUnit4::class)
class HapticsTest {

    /** Records every constant passed to performHapticFeedback. */
    private class RecordingView : View(ApplicationProvider.getApplicationContext()) {
        val performed = mutableListOf<Int>()
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            performed += feedbackConstant
            return true
        }
    }

    private var enabled = true
    private val view = RecordingView()
    private val haptics = Haptics(view) { enabled }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    @Test
    fun `single patterns`() {
        haptics.light()
        haptics.medium()
        haptics.heavy()
        assertEquals(
            listOf(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.CONTEXT_CLICK),
            view.performed,
        )
    }

    @Test
    fun `impact aliases map like the Dart service`() {
        haptics.lightImpact()
        haptics.mediumImpact()
        haptics.heavyImpact()
        assertEquals(
            listOf(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.CONTEXT_CLICK),
            view.performed,
        )
    }

    @Test
    fun `success is medium, 90 ms, light impact`() {
        haptics.success()
        assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP), view.performed)
        idle(89)
        assertEquals(1, view.performed.size)
        idle(1)
        assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.VIRTUAL_KEY), view.performed)
    }

    @Test
    fun `error is heavy, 110 ms, heavy`() {
        haptics.error()
        idle(110)
        assertEquals(listOf(HapticFeedbackConstants.CONTEXT_CLICK, HapticFeedbackConstants.CONTEXT_CLICK), view.performed)
    }

    @Test
    fun `disabled setting suppresses every pattern`() {
        enabled = false
        haptics.light()
        haptics.medium()
        haptics.heavy()
        haptics.success()
        haptics.error()
        idle(200)
        assertEquals(emptyList<Int>(), view.performed)
    }

    @Test
    fun `switching off between the two success pulses drops the second`() {
        haptics.success()
        enabled = false
        idle(100)
        assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP), view.performed)
    }
}
