package space.einundzwanzig.meetup.core.camera

import android.os.SystemClock
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import space.einundzwanzig.meetup.core.ui.component.AppContainedLoadingIndicator
import space.einundzwanzig.meetup.core.ui.qr.ScannerOverlay
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/** Camera lifecycle as seen by the UI. */
enum class CameraStatus { Starting, Running, Unavailable }

/**
 * Hoisted state of a [QrScanner]: torch, camera status and the "stopped after a hit" flag.
 * Screens may render their own torch control (for example in the top bar) with
 * [hasTorch], [torchOn] and [toggleTorch], and pass `showTorchButton = false`.
 */
@Stable
class QrScannerState internal constructor(
    internal val debouncer: ScanDebouncer,
) {
    var cameraStatus: CameraStatus by mutableStateOf(CameraStatus.Starting)
        internal set

    /** True when the active camera has a flash unit. */
    var hasTorch: Boolean by mutableStateOf(false)
        internal set

    /** Real torch state as reported by the camera. */
    var torchOn: Boolean by mutableStateOf(false)
        internal set

    /** True after `onResult` returned true or [stop]; nothing is delivered until [resume]. */
    var isStopped: Boolean by mutableStateOf(false)
        private set

    internal var camera: BoundQrCamera? = null
        set(value) {
            field = value
            value?.analysisEnabled = !isStopped
        }

    internal var retryCount by mutableIntStateOf(0)
        private set

    fun toggleTorch() = setTorch(!torchOn)

    fun setTorch(on: Boolean) {
        if (hasTorch) camera?.setTorch(on)
    }

    /** Re-arms scanning after a hit (spec 04 §2.3: reset after the demo dialog). */
    fun resume() {
        debouncer.reset()
        isStopped = false
        camera?.analysisEnabled = true
    }

    /** Retries opening the camera after [CameraStatus.Unavailable]. */
    fun retry() {
        cameraStatus = CameraStatus.Starting
        retryCount++
    }

    /**
     * Stops delivering codes until [resume], for example after a gallery hit (spec 04 §2.3: the
     * camera ignores further codes once `isScanned` is set). The preview and the torch stay as they are.
     */
    fun stop() {
        debouncer.stop()
        isStopped = true
        camera?.analysisEnabled = false
    }

    /** Runs the debouncer and the consumer; true when the consumer took the value. */
    internal fun deliver(values: List<String>, nowMillis: Long, onResult: (String) -> Boolean): Boolean {
        for (value in values) {
            if (isStopped) return false
            if (!debouncer.accept(value, nowMillis)) continue
            if (onResult(value)) {
                stop()
                return true
            }
        }
        return false
    }
}

@Composable
fun rememberQrScannerState(
    sameValueWindowMillis: Long = ScanDebouncer.DEFAULT_SAME_VALUE_WINDOW_MILLIS,
    minIntervalMillis: Long = ScanDebouncer.DEFAULT_MIN_INTERVAL_MILLIS,
): QrScannerState {
    fun create() = QrScannerState(ScanDebouncer(sameValueWindowMillis, minIntervalMillis))
    // Survives recreation (rotation, multi-window resize): a stopped scanner stays stopped.
    val saver = remember(sameValueWindowMillis, minIntervalMillis) {
        Saver<QrScannerState, Boolean>(
            save = { it.isStopped },
            restore = { stopped -> create().also { if (stopped) it.stop() } },
        )
    }
    return rememberSaveable(saver = saver) { create() }
}

/**
 * Full-bleed QR scanner (mobile_scanner replacement, spec 14 §2): CameraX preview, ML Kit QR
 * analysis (bundled model), torch toggle, camera permission flow and an overlay
 * ([ScannerOverlay], spec 02 §2.7, by default).
 *
 * [onResult] gets every new raw value (debounced: the same value at most every 1.5 s) and
 * returns true to stop scanning ("ignore after first hit"); call [QrScannerState.resume] to scan
 * again. Values are delivered on the main thread. While [enabled] is false nothing is analyzed
 * or delivered (for example while a dialog is open).
 *
 * The camera binds to the current lifecycle: it pauses in the background and is released when
 * the composable leaves the composition.
 */
@Composable
fun QrScanner(
    onResult: (String) -> Boolean,
    modifier: Modifier = Modifier,
    state: QrScannerState = rememberQrScannerState(),
    permissionState: CameraPermissionState = rememberCameraPermissionState(),
    enabled: Boolean = true,
    showTorchButton: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = { ScannerOverlay() },
) {
    Box(
        modifier
            .fillMaxSize()
            // Camera letterbox: black in both themes (scrim is black in the brand scheme).
            .background(MaterialTheme.colorScheme.scrim),
    ) {
        if (permissionState.isGranted) {
            CameraContent(
                state = state,
                onResult = onResult,
                enabled = enabled,
                showTorchButton = showTorchButton,
                overlay = overlay,
            )
        } else {
            CameraPermissionContent(
                status = permissionState.status,
                onRequestPermission = permissionState::launchPermissionRequest,
                onOpenSettings = permissionState::openAppSettings,
            )
        }
    }
}

@Composable
private fun BoxScope.CameraContent(
    state: QrScannerState,
    onResult: (String) -> Boolean,
    enabled: Boolean,
    showTorchButton: Boolean,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val backend = LocalQrCameraBackend.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentEnabled by rememberUpdatedState(enabled)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

    LaunchedEffect(backend, lifecycleOwner, state.retryCount) {
        state.cameraStatus = CameraStatus.Starting
        val camera = try {
            backend.bind(
                context = context,
                lifecycleOwner = lifecycleOwner,
                onSurfaceRequest = { surfaceRequest = it },
                onCodes = { values ->
                    if (currentEnabled) state.deliver(values, SystemClock.elapsedRealtime(), currentOnResult)
                },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            state.cameraStatus = CameraStatus.Unavailable
            return@LaunchedEffect
        }
        state.camera = camera
        state.hasTorch = camera.hasTorch
        camera.observeTorch { state.torchOn = it }
        // Running only once the device is open (frames flow); errors after binding (camera in
        // use, disabled by policy, fatal error) show the unavailable panel instead of a black view.
        camera.observeState { unavailable ->
            state.cameraStatus = if (unavailable) CameraStatus.Unavailable else CameraStatus.Running
        }
        try {
            awaitCancellation()
        } finally {
            state.camera = null
            state.hasTorch = false
            state.torchOn = false
            surfaceRequest = null
            camera.release()
        }
    }
    LaunchedEffect(enabled, state.isStopped, state.cameraStatus) {
        state.camera?.analysisEnabled = enabled && !state.isStopped
    }

    val previewDescription = stringResource(R.string.native_camera_preview_description)
    Box(
        Modifier
            .fillMaxSize()
            .testTag(QrScannerTestTags.Preview)
            .semantics { contentDescription = previewDescription },
    ) {
        surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
    }
    if (state.cameraStatus == CameraStatus.Unavailable) {
        // Drawn over the (kept) viewfinder so a camera that CameraX reopens by itself, for
        // example after another app released it, shows its frames again.
        CameraUnavailableContent(onRetry = state::retry)
        return
    }
    overlay()
    AnimatedVisibility(
        visible = state.cameraStatus == CameraStatus.Starting || surfaceRequest == null,
        modifier = Modifier.align(Alignment.Center),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        AppContainedLoadingIndicator(Modifier.testTag(QrScannerTestTags.Loading))
    }
    if (showTorchButton) {
        BoxWithConstraints(Modifier.matchParentSize()) {
            val topInset = with(LocalDensity.current) { WindowInsets.safeDrawing.getTop(this).toDp() }
            val offset = torchOffsetFromCenter(maxHeight, topInset)
            AnimatedVisibility(
                visible = state.hasTorch && state.cameraStatus == CameraStatus.Running,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = -offset),
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                TorchButton(on = state.torchOn, onToggle = state::toggleTorch)
            }
        }
    }
}

/** Above the 260 dp scan window, clear of the bottom hint boxes of the scanner screens. */
private val TorchOffsetFromCenter: Dp = 130.dp + 52.dp
private val TorchSize: Dp = 56.dp

/** Space a feature's (transparent) top app bar takes below the status bar. */
private val TopBarReserve: Dp = 64.dp

/**
 * Distance of the torch centre above the scanner centre: [TorchOffsetFromCenter], but in short
 * windows (split screen, freeform) never under the top bar or off screen.
 */
internal fun torchOffsetFromCenter(height: Dp, topInset: Dp): Dp {
    val maxOffset = height / 2 - TorchSize / 2 - topInset - TopBarReserve
    return min(TorchOffsetFromCenter, maxOffset).coerceAtLeast(0.dp)
}

/** Expressive shape-morphing torch toggle (56 dp). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TorchButton(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    // One stable label; the toggleable semantics announce on/off.
    val label = stringResource(R.string.native_torch)
    val stateText = stringResource(if (on) R.string.native_torch_state_on else R.string.native_torch_state_off)
    FilledIconToggleButton(
        checked = on,
        onCheckedChange = { onToggle() },
        modifier = modifier
            .size(TorchSize)
            .testTag(QrScannerTestTags.Torch)
            .semantics { stateDescription = stateText },
        shapes = IconButtonDefaults.toggleableShapes(),
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(
            imageVector = if (on) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
            contentDescription = label,
        )
    }
}

object QrScannerTestTags {
    const val Preview = "qrScanner.preview"
    const val Loading = "qrScanner.loading"
    const val Torch = "qrScanner.torch"
    const val Permission = "qrScanner.permission"
    const val Unavailable = "qrScanner.unavailable"
}

@Preview(name = "QrScanner – torch", widthDp = 360, heightDp = 640)
@Composable
private fun TorchButtonPreview() {
    EinundzwanzigTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            ScannerOverlay()
            Box(Modifier.align(Alignment.Center).offset(y = -TorchOffsetFromCenter)) {
                TorchButton(on = false, onToggle = {})
            }
        }
    }
}

/** Preview backend: binds and never opens (Starting) or fails at once (Unavailable). */
private class PreviewBackend(private val fail: Boolean) : QrCameraBackend {
    override suspend fun bind(
        context: android.content.Context,
        lifecycleOwner: LifecycleOwner,
        onSurfaceRequest: (SurfaceRequest) -> Unit,
        onCodes: (List<String>) -> Unit,
    ): BoundQrCamera {
        if (fail) throw NoCameraException()
        return object : BoundQrCamera {
            override val hasTorch = true
            override var analysisEnabled = true
            override fun setTorch(on: Boolean) = Unit
            override fun observeTorch(onChange: (Boolean) -> Unit) = Unit
            override fun observeState(onUnavailable: (Boolean) -> Unit) = Unit
            override fun release() = Unit
        }
    }
}

@Composable
private fun QrScannerPreview(fail: Boolean) {
    EinundzwanzigTheme {
        androidx.compose.runtime.CompositionLocalProvider(LocalQrCameraBackend provides PreviewBackend(fail)) {
            QrScanner(
                onResult = { false },
                permissionState = remember { CameraPermissionState({ true }, { false }) },
            )
        }
    }
}

@Preview(name = "QrScanner – starting", widthDp = 360, heightDp = 640)
@Composable
private fun QrScannerStartingPreview() = QrScannerPreview(fail = false)

@Preview(name = "QrScanner – unavailable", widthDp = 360, heightDp = 640)
@Composable
private fun QrScannerUnavailablePreview() = QrScannerPreview(fail = true)
