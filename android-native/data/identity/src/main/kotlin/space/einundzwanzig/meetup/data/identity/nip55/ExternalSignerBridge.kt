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
 *         externalSignerBridge.pendingLaunch.collect { launch ->
 *             if (launch != null && !launch.launched && externalSignerBridge.onLaunched(launch.id)) {
 *                 launchedSignerId = launch.id
 *                 try { signerLauncher.launch(launch.intent) }
 *                 catch (e: ActivityNotFoundException) { externalSignerBridge.onLaunchFailed(launch.id, e) }
 *             }
 *         }
 *     }
 * }
 * ```
 * Only one foreground request runs at a time (others fail with `busy`, like the Flutter bridge).
 * A launched request stays reserved until its result arrives, even if the caller gave up; results
 * whose id is not the current request are dropped. A request published while no host is
 * `STARTED` waits until the app comes to the foreground (Flutter launched it from the Activity
 * as soon as it could). After process death the waiting coroutine is gone; a late result is ignored.
 */
interface ExternalSignerBridge {
    /** The request the host must launch, or `null`. Launch only while [ExternalSignerLaunch.launched] is `false`. */
    val pendingLaunch: StateFlow<ExternalSignerLaunch?>

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
    /** Request id (monotonic). */
    val id: Long,
    /** The explicit `nostrsigner:` intent to start for result. */
    val intent: Intent,
    /** `true` once the host reported [ExternalSignerBridge.onLaunched]. */
    val launched: Boolean = false,
)
