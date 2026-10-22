package space.einundzwanzig.meetup.core.camera

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import space.einundzwanzig.meetup.core.ui.util.findActivity

/** Where the camera permission stands, as far as the app can tell. */
enum class CameraPermissionStatus {
    /** Granted: the camera may run. */
    Granted,

    /** Never asked in this screen yet; the scanner asks automatically once. */
    NotRequested,

    /** Denied, but Android still shows the dialog: explain and offer to ask again. */
    Denied,

    /**
     * Denied and Android no longer shows the dialog ("don't ask again", or two denials since
     * Android 11): only the app settings can grant it.
     */
    PermanentlyDenied,
}

/**
 * Pure transition rules of the camera permission (Android does not report "denied forever"
 * directly: it is a denial after a request where no rationale may be shown any more).
 */
object CameraPermissionLogic {

    /** Status when a screen opens, before any request. */
    fun initial(granted: Boolean, shouldShowRationale: Boolean): CameraPermissionStatus = when {
        granted -> CameraPermissionStatus.Granted
        shouldShowRationale -> CameraPermissionStatus.Denied
        else -> CameraPermissionStatus.NotRequested
    }

    /** Status after the system dialog answered (or answered at once without a dialog). */
    fun afterRequest(granted: Boolean, shouldShowRationale: Boolean): CameraPermissionStatus = when {
        granted -> CameraPermissionStatus.Granted
        shouldShowRationale -> CameraPermissionStatus.Denied
        else -> CameraPermissionStatus.PermanentlyDenied
    }

    /**
     * Status after the screen resumes (for example back from the app settings). A permanent
     * denial stays permanent unless the user granted it or reset it so that Android shows a
     * rationale again; a revoked grant falls back to the initial rules.
     */
    fun onResume(
        current: CameraPermissionStatus,
        granted: Boolean,
        shouldShowRationale: Boolean,
    ): CameraPermissionStatus = when {
        granted -> CameraPermissionStatus.Granted
        shouldShowRationale -> CameraPermissionStatus.Denied
        current == CameraPermissionStatus.Granted -> CameraPermissionStatus.NotRequested
        else -> current
    }

    /** The scanner asks once by itself when nobody has asked yet. */
    fun shouldAutoRequest(status: CameraPermissionStatus, alreadyAutoRequested: Boolean): Boolean =
        status == CameraPermissionStatus.NotRequested && !alreadyAutoRequested
}

/**
 * Hoisted camera permission state. Create it with [rememberCameraPermissionState]; the
 * constructor takes the platform calls so tests can drive it without an activity.
 */
@Stable
class CameraPermissionState internal constructor(
    private val checkGranted: () -> Boolean,
    private val shouldShowRationale: () -> Boolean,
    initialStatus: CameraPermissionStatus? = null,
) {
    var status: CameraPermissionStatus by mutableStateOf(
        initialStatus ?: CameraPermissionLogic.initial(checkGranted(), shouldShowRationale()),
    )
        private set

    internal var launcher: (() -> Unit)? = null
    internal var settingsOpener: (() -> Unit)? = null

    val isGranted: Boolean get() = status == CameraPermissionStatus.Granted

    /** Shows the system permission dialog (Android answers at once if denied forever). */
    fun launchPermissionRequest() {
        launcher?.invoke()
    }

    /** Opens this app's system settings page, where a permanently denied permission can be granted. */
    fun openAppSettings() {
        settingsOpener?.invoke()
    }

    internal fun onRequestResult(granted: Boolean) {
        status = CameraPermissionLogic.afterRequest(granted, shouldShowRationale())
    }

    internal fun refresh() {
        status = CameraPermissionLogic.onResume(status, checkGranted(), shouldShowRationale())
    }
}

/**
 * Remembers the camera permission state of the current activity. It re-checks on every
 * `ON_RESUME` (returning from the settings) and, when [autoRequest] is true, shows the system
 * dialog once if the permission was never asked for in this screen.
 */
@Composable
fun rememberCameraPermissionState(autoRequest: Boolean = true): CameraPermissionState {
    val context = LocalContext.current
    // Saved so "denied forever" survives recreation (tablet rotation, multi-window resize,
    // process death): Android cannot tell it apart from "never asked" without a request.
    var saved by rememberSaveable { mutableStateOf<CameraPermissionStatus?>(null) }
    val state = remember(context) {
        val checkGranted = { context.hasCameraPermission() }
        val shouldShowRationale = {
            context.findActivity()?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
            } ?: false
        }
        CameraPermissionState(
            checkGranted = checkGranted,
            shouldShowRationale = shouldShowRationale,
            // Reconciled once so a permission granted meanwhile (settings) still wins.
            initialStatus = saved?.let { CameraPermissionLogic.onResume(it, checkGranted(), shouldShowRationale()) },
        )
    }
    LaunchedEffect(state) { snapshotFlow { state.status }.collect { saved = it } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state.onRequestResult(granted)
    }
    SideEffect {
        state.launcher = { launcher.launch(Manifest.permission.CAMERA) }
        state.settingsOpener = { context.openAppSettings() }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refresh() }

    var autoRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state, autoRequest) {
        if (autoRequest && CameraPermissionLogic.shouldAutoRequest(state.status, autoRequested)) {
            autoRequested = true
            state.launchPermissionRequest()
        }
    }
    return state
}

/** True when the CAMERA permission is granted. */
fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Opens the system "App info" page of this app. */
fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    if (findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some ROMs lack the details page: fall back to the general settings.
        runCatching {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
