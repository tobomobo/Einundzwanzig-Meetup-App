package space.einundzwanzig.meetup.core.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.log.AppLogger
import kotlin.math.roundToInt

/**
 * Captures an on-screen map area as a bitmap (Flutter `RepaintBoundary.toImage(pixelRatio: 2.5)`
 * of the world map, spec 03 §2.4 `shareImage()`).
 *
 * Mark the area with [Modifier.mapSnapshotArea]; everything drawn inside it (tiles, markers, a stats
 * overlay, the attribution) is captured. The window is drawn into a software canvas and cropped, so
 * the output is `width dp · pixelRatio` × `height dp · pixelRatio` independent of the screen density.
 * Content holding hardware bitmaps (Coil defaults) cannot be drawn in software; inside the area,
 * load images with `allowHardware(false)`.
 */
@Stable
class MapSnapshotState internal constructor() {
    internal var view: View? = null
    internal var density: Float = 1f
    internal var bounds: Rect? = null

    /** The captured area in root px, or null before layout. */
    val areaBounds: Rect? get() = bounds

    /**
     * Waits [settleMillis] (the world map waits 300 ms for tiles) and a frame, then draws the area.
     *
     * @return the bitmap, or null if the area is not laid out or drawing failed (logged).
     */
    suspend fun capture(pixelRatio: Float = WORLD_MAP_PIXEL_RATIO, settleMillis: Long = 0): Bitmap? {
        if (view == null) return null
        return withContext(Dispatchers.Main) {
            if (settleMillis > 0) delay(settleMillis)
            awaitFrame()
            val target = view ?: return@withContext null
            val area = bounds?.takeIf { it.width > 0f && it.height > 0f } ?: return@withContext null
            val scale = pixelRatio / density
            val (width, height) = outputSize(area, scale)
            try {
                val bitmap = createBitmap(width, height)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                canvas.translate(-area.left, -area.top)
                target.draw(canvas)
                bitmap
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Karten-Schnappschuss fehlgeschlagen", e)
                null
            }
        }
    }

    companion object {
        private const val TAG = "OsmMap"

        /** Spec 03 §2.4: capture at 2.5 px per dp. */
        const val WORLD_MAP_PIXEL_RATIO = 2.5f

        /** Spec 03 §2.4: wait so tiles can be drawn. */
        const val WORLD_MAP_SETTLE_MS = 300L

        internal fun outputSize(area: Rect, scale: Float): Pair<Int, Int> =
            (area.width * scale).roundToInt().coerceAtLeast(1) to (area.height * scale).roundToInt().coerceAtLeast(1)
    }
}

/** A [MapSnapshotState] bound to the current window. */
@Composable
fun rememberMapSnapshotState(): MapSnapshotState {
    val view = LocalView.current
    val density = LocalDensity.current.density
    val state = remember { MapSnapshotState() }
    SideEffect {
        state.view = view
        state.density = density
    }
    return state
}

/** Marks the area that [MapSnapshotState.capture] draws. */
fun Modifier.mapSnapshotArea(state: MapSnapshotState): Modifier =
    onGloballyPositioned { state.bounds = it.boundsInRoot() }
