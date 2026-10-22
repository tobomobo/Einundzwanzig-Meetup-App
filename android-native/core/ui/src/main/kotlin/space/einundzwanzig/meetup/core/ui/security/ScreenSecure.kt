package space.einundzwanzig.meetup.core.ui.security

import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import space.einundzwanzig.meetup.core.ui.util.UiLog
import space.einundzwanzig.meetup.core.ui.util.findActivity
import java.util.WeakHashMap

/**
 * Blocks screenshots, screen recording and the recents thumbnail (`FLAG_SECURE`) while this
 * composable is in the composition and [enabled] is true (Security Audit M3, spec 01 §9).
 *
 * Use it inside every surface that shows an nsec or ncryptsec: the nsec dialog, the export
 * sheet. Compose dialogs and bottom sheets are separate windows, so the flag is set on the
 * activity window **and** on the window that hosts this composable. Nested or overlapping
 * secure surfaces are reference counted; the flag is cleared when the last one leaves.
 */
@Composable
fun SecureScreenEffect(enabled: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val release = ScreenSecure.acquire(view)
        onDispose { release() }
    }
}

/** Reference-counted `FLAG_SECURE` per window. Main thread only. */
object ScreenSecure {
    private val holders = WeakHashMap<Window, Int>()
    private val rootHolders = WeakHashMap<View, Int>()

    /**
     * Marks the windows of [view] secure until the returned function is invoked (once).
     */
    fun acquire(view: View): () -> Unit {
        val windows = buildSet {
            view.context.findActivity()?.window?.let(::add)
            view.dialogWindow()?.let(::add)
        }
        // A window that is neither the activity's nor a Compose dialog (e.g. a popup):
        // flip the flag on its layout params directly.
        val foreignRoot = view.rootView.takeIf { root -> windows.none { it.decorView === root } }
        windows.forEach { window -> holders.increment(window) { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
        foreignRoot?.let { root -> rootHolders.increment(root) { root.setSecureLayoutFlag(true) } }

        var released = false
        return {
            if (!released) {
                released = true
                windows.forEach { window ->
                    holders.decrement(window) { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                }
                foreignRoot?.let { root -> rootHolders.decrement(root) { root.setSecureLayoutFlag(false) } }
            }
        }
    }

    /** Number of active holders for [window] (for tests). */
    internal fun holdCount(window: Window): Int = holders[window] ?: 0

    private inline fun <K> WeakHashMap<K, Int>.increment(key: K, onFirst: () -> Unit) {
        val count = (this[key] ?: 0) + 1
        this[key] = count
        if (count == 1) onFirst()
    }

    private inline fun <K> WeakHashMap<K, Int>.decrement(key: K, onLast: () -> Unit) {
        val count = (this[key] ?: return) - 1
        if (count <= 0) {
            remove(key)
            onLast()
        } else {
            this[key] = count
        }
    }

    private fun View.dialogWindow(): Window? {
        var parent: ViewParent? = parent
        while (parent != null) {
            if (parent is DialogWindowProvider) return parent.window
            parent = parent.parent
        }
        return null
    }

    private fun View.setSecureLayoutFlag(secure: Boolean) {
        val params = layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = if (secure) {
            params.flags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
        if (isAttachedToWindow) {
            val windowManager = context.getSystemService(WindowManager::class.java) ?: return
            try {
                windowManager.updateViewLayout(this, params)
            } catch (e: IllegalArgumentException) {
                // The window was removed between the attach check and the update.
                UiLog.warn("ScreenSecure", "FLAG_SECURE konnte nicht gesetzt werden", e)
            }
        }
    }
}
