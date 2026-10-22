package space.einundzwanzig.meetup.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.FileNotFoundException

/** Outcome of decoding a gallery image (mobile_scanner `analyzeImage` replacement). */
sealed interface QrImageResult {
    /** Raw values of all QR codes found, in ML Kit order (non-empty). */
    data class Codes(val values: List<String>) : QrImageResult

    /** The image contains no readable QR code. */
    data object NoCode : QrImageResult

    /** The image could not be read or analyzed. */
    data class Failed(val error: Throwable) : QrImageResult
}

/** Decodes QR codes from images (spec 02 §2.2 / §2.6, spec 04 §2.3 "gallery"). */
object QrImageDecoder {

    /** Longest edge the picked image is downsampled to before analysis (avoids OOM on 100+ MP photos). */
    const val MAX_EDGE_PX = 2048

    /**
     * Reads [uri] (content/file), downsampled to about [MAX_EDGE_PX] on the long edge, and returns
     * the QR codes in it. Never throws except on cancellation.
     */
    suspend fun decode(
        context: Context,
        uri: Uri,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): QrImageResult = try {
        val bitmap = withContext(ioDispatcher) { loadBitmap(context, uri, MAX_EDGE_PX) }
        decode(InputImage.fromBitmap(bitmap, 0))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        QrImageResult.Failed(e)
    } catch (e: OutOfMemoryError) {
        QrImageResult.Failed(e)
    }

    /** Power-of-two sample size so that the long edge is at most [maxEdge] (BitmapFactory path). */
    internal fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxEdge) sample *= 2
        return sample
    }

    private fun loadBitmap(context: Context, uri: Uri, maxEdge: Int): Bitmap {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF rotation itself.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val long = maxOf(w, h)
                if (long > maxEdge) decoder.setTargetSize(w * maxEdge / long, h * maxEdge / long)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        // API 26-27: no EXIF rotation needed, ML Kit finds QR codes in any orientation.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString()))
            .use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Unreadable image: $uri")
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        return (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString()))
            .use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Unreadable image: $uri")
    }

    /** Analyzes an already loaded [image]. */
    suspend fun decode(image: InputImage): QrImageResult {
        val scanner = newQrBarcodeScanner()
        return try {
            val values = suspendCancellableCoroutine { cont ->
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        cont.resume(barcodes.mapNotNull { it.rawValue }.filter { it.isNotEmpty() })
                    }
                    .addOnFailureListener { cont.resumeWithException(it) }
                    .addOnCanceledListener { cont.cancel() }
            }
            if (values.isEmpty()) QrImageResult.NoCode else QrImageResult.Codes(values)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            QrImageResult.Failed(e)
        } finally {
            scanner.close()
        }
    }
}

/**
 * Gallery QR picker: [launch] opens the system Photo Picker (images only, no permission needed)
 * and decodes the picked image; [isDecoding] is true meanwhile, so callers can show progress and
 * disable the gallery action.
 */
@Stable
class QrImagePicker internal constructor(private val onLaunch: () -> Unit) {
    var isDecoding: Boolean by mutableStateOf(false)
        internal set

    /** Opens the Photo Picker; ignored while a picked image is still being decoded. */
    fun launch() {
        if (!isDecoding) onLaunch()
    }
}

/**
 * Remembers a [QrImagePicker]. [onResult] is not called when the user cancels the picker. The
 * picked image survives recreation (rotation) while it is being decoded: decoding restarts and
 * the result still arrives.
 */
@Composable
fun rememberQrImagePicker(onResult: (QrImageResult) -> Unit): QrImagePicker {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    var pending by rememberSaveable { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pending = uri
    }
    val picker = remember(launcher) {
        QrImagePicker {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    LaunchedEffect(pending) {
        val uri = pending ?: return@LaunchedEffect
        picker.isDecoding = true
        try {
            val result = QrImageDecoder.decode(context.applicationContext, uri)
            pending = null
            callback(result)
        } finally {
            picker.isDecoding = false
        }
    }
    return picker
}
