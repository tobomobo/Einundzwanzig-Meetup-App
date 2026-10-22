package space.einundzwanzig.meetup.core.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Spherical Web Mercator (EPSG:3857), the projection of OSM raster tiles, flutter_map and osmdroid.
 *
 * Normalised coordinates run from 0 to 1 across the world (x west → east, y north → south). At zoom
 * `z` the world is `tileSizePx · 2^z` pixels wide. With osmdroid's tiles scaled to the display
 * density (256 dp per tile, as in flutter_map), zoom levels match the Flutter screens.
 */
object WebMercator {
    /** Latitude limit of the square Web Mercator world. */
    const val MAX_LATITUDE = 85.05112877980658

    /** Tile edge in dp (flutter_map logical pixels). */
    const val TILE_SIZE_DP = 256.0

    /** Equatorial radius used by EPSG:3857. */
    const val EARTH_RADIUS_M = 6378137.0

    /** Highest zoom with OSM standard tiles. */
    const val OSM_MAX_ZOOM = 19.0

    /**
     * Lowest zoom the map allows. flutter_map has no minimum; at zoom 1 the world is 512 dp wide,
     * still wider than a phone, and a fit across continents (spec 03 §2.4, padding 60 dp on a
     * 360 dp screen) can go below 2. Zoom 0 would leave empty bands on phones.
     */
    const val MIN_ZOOM = 1.0

    fun clampLatitude(latitude: Double): Double = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)

    fun x(longitude: Double): Double = (longitude + 180.0) / 360.0

    fun y(latitude: Double): Double {
        val s = sin(clampLatitude(latitude) * PI / 180.0)
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    fun longitude(x: Double): Double = x * 360.0 - 180.0

    fun latitude(y: Double): Double = 90.0 - 360.0 * atan(exp((y - 0.5) * 2 * PI)) / PI

    /** World width in pixels at [zoom]. */
    fun worldSize(zoom: Double, tileSizePx: Double): Double = tileSizePx * 2.0.pow(zoom)

    /** Pixel position of [point] inside a [widthPx] × [heightPx] viewport centred on [camera]. */
    fun toScreen(
        point: LatLng,
        camera: CameraPosition,
        widthPx: Int,
        heightPx: Int,
        tileSizePx: Double,
    ): PixelPoint {
        val world = worldSize(camera.zoom, tileSizePx)
        return PixelPoint(
            x = (x(point.longitude) - x(camera.target.longitude)) * world + widthPx / 2.0,
            y = (y(point.latitude) - y(camera.target.latitude)) * world + heightPx / 2.0,
        )
    }

    /** Inverse of [toScreen]. */
    fun fromScreen(
        pixel: PixelPoint,
        camera: CameraPosition,
        widthPx: Int,
        heightPx: Int,
        tileSizePx: Double,
    ): LatLng {
        val world = worldSize(camera.zoom, tileSizePx)
        val nx = x(camera.target.longitude) + (pixel.x - widthPx / 2.0) / world
        val ny = y(camera.target.latitude) + (pixel.y - heightPx / 2.0) / world
        return LatLng(latitude(ny), longitude(nx))
    }

    /**
     * Camera that shows all [points] with [paddingPx] on every side, like flutter_map
     * `CameraFit.coordinates(padding: EdgeInsets.all(p), maxZoom: m)`: the largest zoom at which the
     * projected bounds fit the padded viewport, capped at [maxZoom] (and at least [minZoom]), centred
     * on the middle of the projected bounds. A single point (or identical points) gets [maxZoom].
     *
     * @return null when [points] is empty.
     */
    fun fitCamera(
        points: Collection<LatLng>,
        widthPx: Int,
        heightPx: Int,
        paddingPx: Double,
        tileSizePx: Double,
        maxZoom: Double = OSM_MAX_ZOOM,
        minZoom: Double = MIN_ZOOM,
    ): CameraPosition? {
        if (points.isEmpty()) return null
        val xs = points.map { x(it.longitude) }
        val ys = points.map { y(it.latitude) }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        val center = LatLng(latitude((minY + maxY) / 2), longitude((minX + maxX) / 2))
        val availableW = max(widthPx - 2 * paddingPx, 1.0)
        val availableH = max(heightPx - 2 * paddingPx, 1.0)
        val dx = maxX - minX
        val dy = maxY - minY
        val zoomX = if (dx > 0) log2(availableW / (dx * tileSizePx)) else Double.POSITIVE_INFINITY
        val zoomY = if (dy > 0) log2(availableH / (dy * tileSizePx)) else Double.POSITIVE_INFINITY
        val zoom = min(min(zoomX, zoomY), maxZoom).coerceAtLeast(min(minZoom, maxZoom))
        return CameraPosition(center, zoom)
    }

    /** Pixels covered by [meters] on the ground at [latitude] and [zoom] (circle radii). */
    fun metersToPixels(meters: Double, latitude: Double, zoom: Double, tileSizePx: Double): Double {
        val metersPerPixel =
            cos(clampLatitude(latitude) * PI / 180.0) * 2 * PI * EARTH_RADIUS_M / worldSize(zoom, tileSizePx)
        return meters / metersPerPixel
    }

    /** osmdroid's density-scaled tile edge: `(int)(256 · density)`. */
    fun tileSizePx(density: Float): Double = (TILE_SIZE_DP * density).toInt().toDouble()
}
