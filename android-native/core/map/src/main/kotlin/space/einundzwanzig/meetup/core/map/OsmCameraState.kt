package space.einundzwanzig.meetup.core.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * The map view behind an [OsmCameraState] (osmdroid in [OsmMap]). Absent in previews and tests,
 * where the state moves the camera itself.
 */
internal interface MapDriver {
    fun move(position: CameraPosition, animate: Boolean)

    /** True when every visible tile is drawn from a loaded image. */
    fun tilesLoaded(): Boolean = true
}

/**
 * Camera of an [OsmMap] (flutter_map `MapController` + `MapCamera`). Create it with
 * [rememberOsmCameraState]; centre and zoom survive configuration changes and process death.
 *
 * [position] follows every pan, fling and pinch. All camera commands are safe before the map is
 * laid out; [fitPoints] waits for the map size.
 */
@Stable
class OsmCameraState(
    initial: CameraPosition,
    val minZoom: Double = WebMercator.MIN_ZOOM,
    val maxZoom: Double = WebMercator.OSM_MAX_ZOOM,
) {
    /** Current camera. */
    var position: CameraPosition by mutableStateOf(initial.clamped(minZoom, maxZoom))
        private set

    /** Map size in px; zero until the first layout. */
    var viewportSize: IntSize by mutableStateOf(IntSize.Zero)
        private set

    private var density by mutableFloatStateOf(1f)

    private var driver: MapDriver? = null

    /**
     * Where the last command sent the camera while a map view animates there. [position] only
     * follows the moves osmdroid reports during the animation, so quick consecutive commands
     * ([zoomBy] taps) build on this instead. Cleared once the reported camera arrives, or after
     * the animation time when a gesture interrupted it.
     */
    private var animationTarget: CameraPosition? by mutableStateOf(null)
    private var targetDeadlineNanos = 0L

    /** [animationTarget] while an animation towards it may still run, else [position]. */
    private val pendingPosition: CameraPosition
        get() {
            val t = animationTarget ?: return position
            return if (System.nanoTime() - targetDeadlineNanos < 0) t else position
        }

    /** Edge of one tile in px (256 dp, like flutter_map's logical pixels). */
    internal val tileSizePx: Double get() = WebMercator.tileSizePx(density)

    val canZoomIn: Boolean get() = pendingPosition.zoom < maxZoom
    val canZoomOut: Boolean get() = pendingPosition.zoom > minZoom

    /** Jumps to [target] (flutter_map `move`). */
    fun move(target: LatLng, zoom: Double = position.zoom) = apply(CameraPosition(target, zoom), animate = false)

    /** Animates to [target]; jumps when no map view is attached. */
    fun animateTo(target: LatLng, zoom: Double = position.zoom) = apply(CameraPosition(target, zoom), animate = true)

    /** Zooms around the centre by [delta] levels (zoom buttons). */
    fun zoomBy(delta: Double) {
        val from = pendingPosition
        apply(from.copy(zoom = from.zoom + delta), animate = true)
    }

    /**
     * flutter_map `fitCamera(CameraFit.coordinates(points, padding: EdgeInsets.all(padding),
     * maxZoom: maxZoom))`. One point is shown at [maxZoom]; the Flutter screens use [move] for a
     * single point instead (spec 03 §2.4: zoom 11, spec 09 §2.2: zoom 10). Waits until the map
     * has a size.
     *
     * @return false when [points] is empty.
     */
    suspend fun fitPoints(
        points: Collection<LatLng>,
        padding: Dp = 0.dp,
        maxZoom: Double = this.maxZoom,
        animate: Boolean = false,
    ): Boolean {
        if (points.isEmpty()) return false
        val size = snapshotFlow { viewportSize }.first { it.width > 0 && it.height > 0 }
        val camera = WebMercator.fitCamera(
            points = points,
            widthPx = size.width,
            heightPx = size.height,
            paddingPx = padding.value.toDouble() * density,
            tileSizePx = tileSizePx,
            maxZoom = maxZoom,
            minZoom = minZoom,
        ) ?: return false
        apply(camera, animate)
        return true
    }

    /**
     * Waits until the visible tiles are loaded, at most [timeoutMillis] (world map snapshot).
     * Returns whether they all loaded in time.
     */
    suspend fun awaitTilesLoaded(timeoutMillis: Long = 3_000L): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (driver?.tilesLoaded() == false) delay(TILE_POLL_MS)
            true
        } ?: false

    /** [point] in px relative to the map's top-left corner, or null before layout. */
    fun toScreen(point: LatLng): PixelPoint? {
        val size = viewportSize
        if (size.width == 0 || size.height == 0) return null
        return WebMercator.toScreen(point, position, size.width, size.height, tileSizePx)
    }

    /** The coordinate under [pixel], or null before layout. */
    fun fromScreen(pixel: PixelPoint): LatLng? {
        val size = viewportSize
        if (size.width == 0 || size.height == 0) return null
        return WebMercator.fromScreen(pixel, position, size.width, size.height, tileSizePx)
    }

    private fun apply(target: CameraPosition, animate: Boolean) {
        val clamped = target.clamped(minZoom, maxZoom)
        val d = driver
        if (d == null || !animate) {
            animationTarget = null
            position = clamped
        } else {
            animationTarget = clamped
            targetDeadlineNanos = System.nanoTime() + (CAMERA_ANIMATION_MS + TARGET_GRACE_MS) * 1_000_000
        }
        d?.move(clamped, animate)
    }

    internal fun attach(mapDriver: MapDriver) {
        driver = mapDriver
    }

    internal fun detach(mapDriver: MapDriver) {
        if (driver === mapDriver) driver = null
    }

    internal fun onViewport(size: IntSize, density: Float) {
        this.density = density
        viewportSize = size
    }

    /** Called by the map view on every pan and zoom. */
    internal fun onMapMoved(camera: CameraPosition) {
        if (camera != position) position = camera
        animationTarget?.let { if (camera.isCloseTo(it)) animationTarget = null }
    }

    companion object {
        private const val TILE_POLL_MS = 100L

        /** Length of animated camera moves on the map view. */
        internal const val CAMERA_ANIMATION_MS = 450L

        /** Extra time before an unreached animation target is dropped (gesture interrupted it). */
        private const val TARGET_GRACE_MS = 250L

        val Saver: Saver<OsmCameraState, *> = listSaver(
            save = {
                listOf(it.position.target.latitude, it.position.target.longitude, it.position.zoom, it.minZoom, it.maxZoom)
            },
            restore = { OsmCameraState(CameraPosition(LatLng(it[0], it[1]), it[2]), it[3], it[4]) },
        )
    }
}

private fun CameraPosition.clamped(minZoom: Double, maxZoom: Double): CameraPosition =
    CameraPosition(
        // The world does not wrap (OsmMap), so the centre stays within ±180°.
        LatLng(WebMercator.clampLatitude(target.latitude), target.longitude.coerceIn(-180.0, 180.0)),
        zoom.coerceIn(minZoom, maxZoom),
    )

private fun CameraPosition.isCloseTo(other: CameraPosition): Boolean =
    abs(zoom - other.zoom) < 1e-3 &&
        abs(target.latitude - other.target.latitude) < 1e-5 &&
        abs(target.longitude - other.target.longitude) < 1e-5

/**
 * Remembers an [OsmCameraState] starting at [initialTarget] / [initialZoom] (flutter_map
 * `MapOptions.initialCenter` / `initialZoom`) with the zoom limits [minZoom]..[maxZoom].
 */
@Composable
fun rememberOsmCameraState(
    initialTarget: LatLng,
    initialZoom: Double,
    minZoom: Double = WebMercator.MIN_ZOOM,
    maxZoom: Double = WebMercator.OSM_MAX_ZOOM,
): OsmCameraState =
    rememberSaveable(saver = OsmCameraState.Saver) {
        OsmCameraState(CameraPosition(initialTarget, initialZoom), minZoom, maxZoom)
    }
