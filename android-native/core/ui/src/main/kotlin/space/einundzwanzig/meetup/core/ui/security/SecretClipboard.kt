package space.einundzwanzig.meetup.core.ui.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import space.einundzwanzig.meetup.core.ui.util.UiLog

/**
 * Clipboard helper for secrets (Security Audit M3, `ScreenSecure.copySecret`):
 *
 * - the clip is flagged sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`, honoured from
 *   Android 13 on: no content preview in the system clipboard UI, no keyboard suggestion);
 * - after [CLEAR_AFTER_MS] (45 s, as in Flutter) the clipboard is cleared, but only if it still
 *   holds exactly this secret. Android 10+ blocks clipboard reads from the background: if the
 *   timer fires while the app is in the background, the check is repeated on the next
 *   process `ON_RESUME` within [RETRY_WINDOW_MS] (10 min) after the due time (spec 14 §6).
 *   Flutter could not clear in the background at all.
 *
 * Plain copies (npub, pairing URIs, the setup sheet's ncryptsec) use [copyPlain] — parity with
 * the Flutter app, which copies them without auto-clear.
 */
object SecretClipboard {
    /** Delay until a copied secret is wiped (Flutter `ScreenSecure._hold`). */
    const val CLEAR_AFTER_MS: Long = 45_000

    /** How long after the due time a missed clear is still done on the next resume. */
    const val RETRY_WINDOW_MS: Long = 10 * 60_000

    /** `ClipDescription.EXTRA_IS_SENSITIVE`; the literal also works before API 33. */
    internal const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private const val TAG = "SecretClipboard"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** The secret waiting to be cleared (main thread only). */
    private class PendingClear(val context: Context, val secret: String, val dueAtMillis: Long)

    private var pending: PendingClear? = null
    private var observerInstalled = false
    private val timer = Runnable { onTimer() }

    /** Monotonic clock (uptime, advanced by Robolectric's looper in tests). */
    @VisibleForTesting
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }

    /**
     * True while the process is in the background (process lifecycle `CREATED`). Before the
     * process lifecycle is initialised the state is unknown and a clear is attempted.
     */
    @VisibleForTesting
    internal var isInBackground: () -> Boolean = {
        ProcessLifecycleOwner.get().lifecycle.currentState == Lifecycle.State.CREATED
    }

    /** Copies [secret], marks it sensitive and clears it after [clearAfterMillis] if unchanged. */
    fun copySecret(
        context: Context,
        secret: String,
        label: String = "",
        clearAfterMillis: Long = CLEAR_AFTER_MS,
    ) {
        val appContext = context.applicationContext
        copy(appContext, secret, label, sensitive = true)
        runOnMain {
            installResumeObserver()
            // A newer secret replaces the older pending one: the older timer could only have
            // cleared a clipboard that still held the older secret, which is no longer the case.
            pending = PendingClear(appContext, secret, clock() + clearAfterMillis)
            mainHandler.removeCallbacks(timer)
            mainHandler.postDelayed(timer, clearAfterMillis)
        }
    }

    /** Copies [text] without auto-clear. */
    fun copyPlain(context: Context, text: String, label: String = "") {
        copy(context.applicationContext, text, label, sensitive = false)
    }

    private fun copy(context: Context, text: String, label: String, sensitive: Boolean) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                EXTRA_IS_SENSITIVE
            }
            clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
        }
        clipboard.setPrimaryClip(clip)
    }

    @MainThread
    private fun onTimer() {
        val p = pending ?: return
        if (isInBackground()) return // clipboard unreadable now; retried on the next resume
        pending = null
        clearIfUnchanged(p.context, p.secret)
    }

    /** Process `ON_RESUME`: performs a clear that was due while the app was in the background. */
    @MainThread
    @VisibleForTesting
    internal fun onProcessResumed() {
        val p = pending ?: return
        val now = clock()
        if (now < p.dueAtMillis) return // the regular timer is still running
        pending = null
        if (now - p.dueAtMillis <= RETRY_WINDOW_MS) clearIfUnchanged(p.context, p.secret)
    }

    @MainThread
    private fun installResumeObserver() {
        if (observerInstalled) return
        observerInstalled = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = onProcessResumed()
            },
        )
    }

    /** Clears the clipboard if it still contains exactly [secret]. */
    internal fun clearIfUnchanged(context: Context, secret: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val current = try {
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        } catch (e: SecurityException) {
            // Background read blocked (Android 10+): nothing we may do.
            UiLog.warn(TAG, "Zwischenablage nicht lesbar, Geheimnis bleibt stehen", e)
            null
        }
        if (current != secret) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    /** Test hook: forgets a pending clear and its timer. */
    @VisibleForTesting
    internal fun resetForTest() {
        mainHandler.removeCallbacks(timer)
        pending = null
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }
}
