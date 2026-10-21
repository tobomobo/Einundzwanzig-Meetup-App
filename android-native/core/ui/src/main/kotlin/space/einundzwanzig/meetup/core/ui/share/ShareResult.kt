package space.einundzwanzig.meetup.core.ui.share

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Outcome of a share sheet (share_plus `ShareResultStatus`, spec 14 §2, spec 08 §2 step 12).
 * "Chosen" is not "sent": [Success] only means the user picked a target.
 */
sealed interface ShareResult {
    /** The user picked [chosenComponent] in the chooser (null if the system did not say which). */
    data class Success(val chosenComponent: ComponentName?) : ShareResult

    /** The chooser closed without a choice. The backup flow treats this as "not shared". */
    data object Dismissed : ShareResult

    /** The outcome cannot be observed (no lifecycle-aware activity, or the chooser did not cover it). */
    data object Unavailable : ShareResult

    /** Dart `status != ShareResultStatus.dismissed`: success and unavailable count as shared. */
    val isNotDismissed: Boolean get() = this != Dismissed
}

/**
 * Receives the chooser callback (`EXTRA_CHOSEN_COMPONENT`) of [ShareHelper]'s
 * `…ForResult` functions. Declared non-exported in the library manifest; the chooser reaches it
 * through an explicit `PendingIntent` (replacement for share_plus's `SharePlusPendingIntent`).
 */
class ShareChooserReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getIntExtra(EXTRA_TOKEN, -1)
        val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        }
        ShareChoices.onChosen(token, component)
    }

    internal companion object {
        const val EXTRA_TOKEN = "space.einundzwanzig.meetup.share.TOKEN"
    }
}

/** Pending chooser callbacks by token (one per open share sheet). */
internal object ShareChoices {
    private val counter = AtomicInteger(0)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<ComponentName?>>()

    fun register(): Pair<Int, CompletableDeferred<ComponentName?>> {
        val token = counter.incrementAndGet() and Int.MAX_VALUE
        val deferred = CompletableDeferred<ComponentName?>()
        waiting[token] = deferred
        return token to deferred
    }

    fun onChosen(token: Int, component: ComponentName?) {
        waiting[token]?.complete(component)
    }

    fun unregister(token: Int) {
        waiting.remove(token)
    }

    /**
     * Explicit, mutable broadcast `PendingIntent` for `Intent.createChooser(…, intentSender)`:
     * the system fills in `EXTRA_CHOSEN_COMPONENT`, which requires `FLAG_MUTABLE` on API 31+.
     * The explicit component keeps it safe.
     */
    fun callback(context: Context, token: Int): PendingIntent {
        val intent = Intent(context, ShareChooserReceiver::class.java)
            .setPackage(context.packageName)
            .putExtra(ShareChooserReceiver.EXTRA_TOKEN, token)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, token, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    /** Max time for the chooser to cover the activity after it was started. */
    const val LEAVE_TIMEOUT_MS = 2_000L

    /** Wait after the activity is resumed again for a late chooser broadcast. */
    const val GRACE_MS = 500L

    /**
     * Waits for the share sheet to close: a choice ([chosen]) wins immediately; otherwise the
     * activity has to leave and re-enter `RESUMED` ([lifecycle]) and, after [graceMillis]
     * without a broadcast, the result is [ShareResult.Dismissed]. If the activity never leaves
     * `RESUMED` within [leaveTimeoutMillis], the outcome is [ShareResult.Unavailable].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitChoice(
        lifecycle: Lifecycle,
        chosen: CompletableDeferred<ComponentName?>,
        leaveTimeoutMillis: Long = LEAVE_TIMEOUT_MS,
        graceMillis: Long = GRACE_MS,
    ): ShareResult = coroutineScope {
        val closed = async {
            val left = withTimeoutOrNull(leaveTimeoutMillis) {
                lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.RESUMED) }
            }
            if (left == null) return@async ShareResult.Unavailable
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            delay(graceMillis)
            ShareResult.Dismissed
        }
        val result = select<ShareResult> {
            chosen.onAwait { ShareResult.Success(it) }
            closed.onAwait { if (chosen.isCompleted) ShareResult.Success(chosen.getCompleted()) else it }
        }
        closed.cancel()
        result
    }
}
