package space.einundzwanzig.meetup.core.camera

import android.content.Context
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Seam between the [QrScanner] composable and CameraX/ML Kit, replaced by a fake in tests. */
internal interface QrCameraBackend {
    /**
     * Binds preview + QR analysis to [lifecycleOwner] (main thread). [onSurfaceRequest] gets
     * the preview surface request, [onCodes] the raw values of each analyzed frame that
     * contained QR codes (main thread). Throws when no camera can be opened.
     */
    suspend fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        onSurfaceRequest: (SurfaceRequest) -> Unit,
        onCodes: (List<String>) -> Unit,
    ): BoundQrCamera
}

/** A running camera session. */
internal interface BoundQrCamera {
    val hasTorch: Boolean

    /** Pauses or resumes the (power-hungry) frame analysis; the preview keeps running. */
    var analysisEnabled: Boolean

    fun setTorch(on: Boolean)

    /** Observes the real torch state (it turns off when the app goes to the background). */
    fun observeTorch(onChange: (Boolean) -> Unit)

    /**
     * Observes the camera device after binding. [onUnavailable] gets `false` once the camera is
     * open and frames flow, `true` when it failed after binding (in use by another app, too many
     * cameras open, disabled by policy, fatal error). A recoverable error may later be followed by
     * `false` when CameraX reopens the camera by itself.
     */
    fun observeState(onUnavailable: (Boolean) -> Unit)

    /** Unbinds the use cases and frees the analyzer (main thread). */
    fun release()
}

/**
 * Maps a CameraX [CameraState] to the scanner's view: `true` = unavailable, `false` = open and
 * running, `null` = in between (opening, closing in the background), keep the current status.
 */
internal fun CameraState.toUnavailable(): Boolean? {
    val error = error
    return when {
        error != null && (error.type == CameraState.ErrorType.CRITICAL || error.code in UnavailableErrorCodes) -> true
        type == CameraState.Type.OPEN && error == null -> false
        else -> null
    }
}

private val UnavailableErrorCodes = setOf(
    CameraState.ERROR_CAMERA_IN_USE,
    CameraState.ERROR_MAX_CAMERAS_IN_USE,
    CameraState.ERROR_CAMERA_DISABLED,
    CameraState.ERROR_CAMERA_FATAL_ERROR,
    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED,
)

internal class NoCameraException : IllegalStateException("No camera available")

internal val LocalQrCameraBackend = staticCompositionLocalOf<QrCameraBackend> { CameraXQrBackend }

/** Builds a QR-only ML Kit client (bundled model). */
internal fun newQrBarcodeScanner(): BarcodeScanner = BarcodeScanning.getClient(
    BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
)

internal object CameraXQrBackend : QrCameraBackend {

    override suspend fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        onSurfaceRequest: (SurfaceRequest) -> Unit,
        onCodes: (List<String>) -> Unit,
    ): BoundQrCamera {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val selector = when {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> throw NoCameraException()
        }
        val preview = Preview.Builder().build().apply { setSurfaceProvider(onSurfaceRequest) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()
        val session = CameraXSession(provider, preview, analysis, onCodes)
        try {
            analysis.setAnalyzer(session.executor, session::analyze)
            session.camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } catch (e: Exception) {
            session.release()
            throw e
        }
        return session
    }
}

private class CameraXSession(
    private val provider: ProcessCameraProvider,
    private val preview: Preview,
    private val analysis: ImageAnalysis,
    private val onCodes: (List<String>) -> Unit,
) : BoundQrCamera {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanner = newQrBarcodeScanner()
    var camera: Camera? = null

    @Volatile
    private var released = false
    private var torchObserver: Observer<Int>? = null
    private var stateObserver: Observer<CameraState>? = null

    @Volatile
    override var analysisEnabled: Boolean = true

    override val hasTorch: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    override fun setTorch(on: Boolean) {
        if (hasTorch) camera?.cameraControl?.enableTorch(on)
    }

    override fun observeTorch(onChange: (Boolean) -> Unit) {
        val live = camera?.cameraInfo?.torchState ?: return
        val observer = Observer<Int> { onChange(it == TorchState.ON) }
        torchObserver?.let { live.removeObserver(it) }
        torchObserver = observer
        live.observeForever(observer)
    }

    override fun observeState(onUnavailable: (Boolean) -> Unit) {
        val live = camera?.cameraInfo?.cameraState ?: return
        val observer = Observer<CameraState> { state -> state.toUnavailable()?.let(onUnavailable) }
        stateObserver?.let { live.removeObserver(it) }
        stateObserver = observer
        live.observeForever(observer)
    }

    @OptIn(ExperimentalGetImage::class)
    fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (released || !analysisEnabled || media == null) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                if (released) return@addOnSuccessListener
                val values = barcodes.mapNotNull { it.rawValue }.filter { it.isNotEmpty() }
                if (values.isNotEmpty()) onCodes(values)
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun release() {
        if (released) return
        released = true
        torchObserver?.let { camera?.cameraInfo?.torchState?.removeObserver(it) }
        torchObserver = null
        stateObserver?.let { camera?.cameraInfo?.cameraState?.removeObserver(it) }
        stateObserver = null
        analysis.clearAnalyzer()
        runCatching { provider.unbind(preview, analysis) }
        scanner.close()
        executor.shutdown()
    }
}
