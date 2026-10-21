package space.einundzwanzig.meetup.data.identity.nip55

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

/**
 * Host side of foreground NIP-55 (Amber) requests. The data layer never holds an Activity: it
 * publishes the intent it needs launched in [pendingLaunch]; the single `MainActivity`
 * (a `FragmentActivity`) launches it with an `ActivityResultLauncher` and reports back with the
 * id it launched. Keep that id across configuration changes (saved instance state), because the
 * activity result reaches the recreated Activity:
 * ```
 * private var launchedSignerId = 0L   // save/restore in onSaveInstanceState/onCreate
 * private val signerLauncher = registerForActivityResult(StartActivityForResult()) { r ->
 *     externalSignerBridge.onResult(launchedSignerId, r.resultCode, r.data)
 * }
 * lifecycleScope.launch {
 *     repeatOnLifecycle(Lifecycle.State.STARTED) {
 *         val host = externalSignerBridge.attachHost()
 *         try {
 *             externalSignerBridge.pendingLaunch.collect { launch ->
 *                 if (launch != null && !launch.launched && externalSignerBridge.onLaunched(launch.id)) {
 *                     launchedSignerId = launch.id
 *                     try { signerLauncher.launch(launch.intent) }
 *                     catch (e: ActivityNotFoundException) { externalSignerBridge.onLaunchFailed(launch.id, e) }
 *                 }
 *             }
 *         } finally {
 *             host.close()
 *         }
 *     }
 * }
 * ```
 * Only one foreground request runs at a time (others fail with `busy`, like the Flutter bridge).
 * A launched request stays reserved until its result arrives, even if the caller gave up; results
 * whose id is not the current, launched request are dropped. Ids are random per process, so a
 * result for a request of a dead process never matches. Without an attached host (no Activity
 * `STARTED`) a request waits briefly (configuration change) and then fails with `cancelled`
 * instead of blocking the single slot from the background.
 */
interface ExternalSignerBridge {
    /** The request the host must launch, or `null`. Launch only while [ExternalSignerLaunch.launched] is `false`. */
    val pendingLaunch: StateFlow<ExternalSignerLaunch?>

    /**
     * Registers a host that can launch requests right now (the Activity is `STARTED`). Close the
     * returned handle when it stops; closing twice is harmless.
     */
    fun attachHost(): AutoCloseable

    /**
     * The host is about to start the activity for request [id].
     *
     * @return `true` if [id] is still the current, not yet launched request; launch only then.
     */
    fun onLaunched(id: Long): Boolean

    /** The activity result of the request [id] (the id passed to [onLaunched]). Other ids are ignored. */
    fun onResult(id: Long, resultCode: Int, data: Intent?)

    /** Launching request [id] failed (no activity, security exception). */
    fun onLaunchFailed(id: Long, error: Throwable)
}

/** One foreground signer request. */
data class ExternalSignerLaunch(
    /** Request id (random start per process, then increasing; never 0). */
    val id: Long,
    /** The explicit `nostrsigner:` intent to start for result. */
    val intent: Intent,
    /** `true` once the host reported [ExternalSignerBridge.onLaunched]. */
    val launched: Boolean = false,
)
