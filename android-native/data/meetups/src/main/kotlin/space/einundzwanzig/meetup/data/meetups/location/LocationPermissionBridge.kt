package space.einundzwanzig.meetup.data.meetups.location

import kotlinx.coroutines.flow.StateFlow

/**
 * Host side of the location permission prompt. The data layer holds no Activity, so
 * [MeetupLocationService.getCurrentLocation] posts a request here and suspends until the Activity
 * answers (the Dart code called `Geolocator.requestPermission()` inline).
 *
 * Wiring in `MainActivity` (collect while STARTED):
 * ```kotlin
 * private val locationLauncher = registerForActivityResult(RequestMultiplePermissions()) { r ->
 *     val granted = r.values.any { it }
 *     val forever = !granted && !shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION) &&
 *         !shouldShowRequestPermissionRationale(ACCESS_COARSE_LOCATION)
 *     bridge.onResult(granted, permanentlyDenied = forever)
 * }
 * bridge.pendingRequest.collect { req ->
 *     if (req != null && bridge.onLaunched(req.id)) {
 *         locationLauncher.launch(arrayOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION))
 *     }
 * }
 * ```
 * Use the id-less [onResult]: the Activity Result API re-delivers the answer to the launcher of a
 * recreated Activity (rotation, configuration change), and an id kept in an Activity field would be
 * lost at that point. If no answer arrives at all, the request resolves to
 * [LocationPermission.DENIED] after a safety timeout (120 s), so later requests are never blocked.
 * Request both permissions; approximate-only is sufficient (spec 09 §14 item 21).
 *
 * If nothing collects [pendingRequest] (no Activity in the foreground), the request resolves to
 * [LocationPermission.DENIED] immediately instead of hanging.
 */
interface LocationPermissionBridge {

    /** A permission request waiting for the Activity. */
    data class Request(val id: Long, val launched: Boolean = false)

    /** The current request, or `null`. */
    val pendingRequest: StateFlow<Request?>

    /** Marks request [id] as launched; `false` if it is no longer pending or already launched. */
    fun onLaunched(id: Long): Boolean

    /**
     * Delivers the result of request [id]. [permanentlyDenied] = denied and the system will no
     * longer show the dialog (`shouldShowRequestPermissionRationale` is `false` after a denial).
     */
    fun onResult(id: Long, granted: Boolean, permanentlyDenied: Boolean)

    /**
     * Delivers the result of whichever request is currently launched (preferred: survives
     * Activity recreation). Ignored if no launched request is waiting.
     */
    fun onResult(granted: Boolean, permanentlyDenied: Boolean)

    /** The prompt could not be shown; resolves request [id] as denied. */
    fun onLaunchFailed(id: Long)
}
