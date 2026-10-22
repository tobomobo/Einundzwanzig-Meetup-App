package space.einundzwanzig.meetup.core.map

import android.content.Context
import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Whether [OsmMap] renders real OSM tiles. Previews always draw a tile-grid placeholder; tests and
 * screenshot tooling can provide `false` to avoid osmdroid and network access. Markers, circles,
 * camera and attribution behave the same either way.
 */
val LocalOsmTileRendering = staticCompositionLocalOf { true }

/**
 * OpenStreetMap raster map (flutter_map + `TileLayer` + `MarkerLayer`/`CircleLayer` replacement,
 * spec 14 §2; used by spec 03 §2.4, spec 09 §2.2 and §2.6).
 *
 * - Tiles: OSM standard tiles at 256 dp per tile, so zoom levels match the Flutter screens.
 * - Gestures: drag, fling, pinch and double-tap zoom. Rotation is always off (all three Flutter maps
 *   disable it).
 * - [content] declares overlays in z-order with [OsmMapScope.Circle] and [OsmMapScope.Marker]. Markers
 *   are composables, so they get semantics, ripple and animation; tapping one does not reach
 *   [onMapClick]. There is no clustering (the Flutter maps have none).
 * - The OSM attribution ("© OpenStreetMap contributors") is shown unless [showAttribution] is
 *   false; only turn it off when the screen shows it elsewhere.
 * - The osmdroid view follows the lifecycle (`onResume`/`onPause`) and is detached on dispose.
 *
 * - Accessibility: the map surface offers custom actions to pan by a third of the viewport
 *   (north/south/east/west) and to zoom, so TalkBack and switch-access users can move the map.
 *   With [centerClickLabel] and [onMapClick], its click action reports the camera centre
 *   (the location picker's "pick map centre").
 *
 * @param contentDescription accessibility label of the map surface (default: "Karte").
 * @param centerClickLabel label of the accessibility click that calls [onMapClick] with the
 *   current camera centre; null for no click action.
 */
@Composable
fun OsmMap(
    cameraState: OsmCameraState,
    modifier: Modifier = Modifier,
    onMapClick: ((LatLng) -> Unit)? = null,
    onMapLongClick: ((LatLng) -> Unit)? = null,
    tileStyle: MapTileStyle = MapTileStyle.Standard,
    showAttribution: Boolean = true,
    attributionAlignment: Alignment = Alignment.BottomEnd,
    contentDescription: String? = null,
    centerClickLabel: String? = null,
    content: @Composable OsmMapScope.() -> Unit = {},
) {
    val density = LocalDensity.current.density
    val description = contentDescription ?: stringResource(R.string.native_map_description)
    val renderTiles = LocalOsmTileRendering.current && !LocalInspectionMode.current
    val actions = mapAccessibilityActions(cameraState)
    val currentClick by rememberUpdatedState(onMapClick)
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { cameraState.onViewport(it, density) }
            .semantics {
                this.contentDescription = description
                customActions = actions
                if (centerClickLabel != null && onMapClick != null) {
                    onClick(label = centerClickLabel) {
                        currentClick?.invoke(cameraState.position.target)
                        true
                    }
                }
            },
    ) {
        if (renderTiles) {
            OsmTileLayer(cameraState, onMapClick, onMapLongClick, tileStyle)
        } else {
            TileGridPlaceholder(cameraState, onMapClick, onMapLongClick)
        }
        OverlayLayer(cameraState, content)
        if (showAttribution) {
            MapAttribution(Modifier.align(attributionAlignment).padding(4.dp))
        }
    }
}

/** Pan (a third of the viewport) and zoom actions for assistive technology. */
@Composable
private fun mapAccessibilityActions(camera: OsmCameraState): List<CustomAccessibilityAction> {
    val north = stringResource(R.string.native_map_pan_north)
    val south = stringResource(R.string.native_map_pan_south)
    val east = stringResource(R.string.native_map_pan_east)
    val west = stringResource(R.string.native_map_pan_west)
    val zoomIn = stringResource(R.string.native_map_zoom_in)
    val zoomOut = stringResource(R.string.native_map_zoom_out)
    return remember(camera, north, south, east, west, zoomIn, zoomOut) {
        fun pan(dx: Double, dy: Double): Boolean {
            val size = camera.viewportSize
            if (size.width == 0 || size.height == 0) return false
            val to = camera.fromScreen(
                PixelPoint(size.width / 2.0 + dx * size.width, size.height / 2.0 + dy * size.height),
            ) ?: return false
            camera.move(to)
            return true
        }
        listOf(
            CustomAccessibilityAction(north) { pan(0.0, -PAN_FRACTION) },
            CustomAccessibilityAction(south) { pan(0.0, PAN_FRACTION) },
            CustomAccessibilityAction(east) { pan(PAN_FRACTION, 0.0) },
            CustomAccessibilityAction(west) { pan(-PAN_FRACTION, 0.0) },
            CustomAccessibilityAction(zoomIn) { camera.canZoomIn.also { if (it) camera.zoomBy(1.0) } },
            CustomAccessibilityAction(zoomOut) { camera.canZoomOut.also { if (it) camera.zoomBy(-1.0) } },
        )
    }
}

/** Share of the viewport one accessibility pan moves. */
private const val PAN_FRACTION = 1.0 / 3.0

/** Visual treatment of the raster tiles. */
enum class MapTileStyle {
    /** OSM tiles as served (Flutter parity). */
    Standard,

    /** Inverted lightness with hues kept, for the dark brand look. Colour filter only. */
    Dark,
}

@Composable
private fun OsmTileLayer(
    camera: OsmCameraState,
    onMapClick: ((LatLng) -> Unit)?,
    onMapLongClick: ((LatLng) -> Unit)?,
    tileStyle: MapTileStyle,
) {
    val context = LocalContext.current
    val currentClick by rememberUpdatedState(onMapClick)
    val currentLongClick by rememberUpdatedState(onMapLongClick)
    val mapView = remember(context) {
        createMapView(
            context = context,
            camera = camera,
            onTap = { p -> currentClick?.let { it(p); true } ?: false },
            onLongPress = { p -> currentLongClick?.let { it(p); true } ?: false },
        )
    }

    val loadingBackground = MaterialTheme.colorScheme.surfaceContainer.toArgb()
    val loadingLines = MaterialTheme.colorScheme.outlineVariant.toArgb()
    SideEffect {
        mapView.overlayManager.tilesOverlay.apply {
            setLoadingBackgroundColor(loadingBackground)
            setLoadingLineColor(loadingLines)
            setColorFilter(TileFilters.forStyle(tileStyle))
        }
    }

    DisposableEffect(mapView, camera) {
        val driver = object : MapDriver {
            override fun move(position: CameraPosition, animate: Boolean) = mapView.moveCamera(position, animate)

            override fun tilesLoaded(): Boolean {
                val states = mapView.overlayManager.tilesOverlay.tileStates
                // Scaled = placeholder from another zoom level (blurry); expired tiles are fine.
                return states.isDone && states.total > 0 && states.notFound == 0 && states.scaled == 0
            }
        }
        mapView.setMinZoomLevel(camera.minZoom)
        mapView.setMaxZoomLevel(camera.maxZoom)
        camera.attach(driver)
        mapView.moveCamera(camera.position, animate = false)
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = report()
            override fun onZoom(event: ZoomEvent?): Boolean = report()
            private fun report(): Boolean {
                if (mapView.width > 0 && mapView.height > 0) {
                    val center = mapView.mapCenter
                    camera.onMapMoved(
                        CameraPosition(LatLng(center.latitude, center.longitude), mapView.zoomLevelDouble),
                    )
                }
                return false
            }
        }
        mapView.addMapListener(listener)
        onDispose {
            mapView.removeMapListener(listener)
            camera.detach(driver)
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(mapView) {
        onDispose { mapView.onDetach() }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

private fun createMapView(
    context: Context,
    camera: OsmCameraState,
    onTap: (LatLng) -> Boolean,
    onLongPress: (LatLng) -> Boolean,
): MapView {
    OsmConfiguration.ensureConfigured(context)
    return MapView(context).apply {
        setTileSource(OsmConfiguration.OsmStandardTiles)
        setTilesScaledToDpi(true)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        setMinZoomLevel(camera.minZoom)
        setMaxZoomLevel(camera.maxZoom)
        setHorizontalMapRepetitionEnabled(false)
        setVerticalMapRepetitionEnabled(false)
        setScrollableAreaLimitLatitude(WebMercator.MAX_LATITUDE, -WebMercator.MAX_LATITUDE, 0)
        // No horizontal repetition, so keep the centre inside the one world (no grey void).
        setScrollableAreaLimitLongitude(-180.0, 180.0, 0)
        // The composable carries the semantics; the raw view has nothing meaningful to announce.
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        overlays.add(
            0,
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = onTap(LatLng(p.latitude, p.longitude))
                override fun longPressHelper(p: GeoPoint): Boolean = onLongPress(LatLng(p.latitude, p.longitude))
            }),
        )
        moveCamera(camera.position, animate = false)
    }
}

private fun MapView.moveCamera(position: CameraPosition, animate: Boolean) {
    val point = GeoPoint(position.target.latitude, position.target.longitude)
    if (animate) {
        controller.animateTo(point, position.zoom, OsmCameraState.CAMERA_ANIMATION_MS)
    } else {
        controller.setZoom(position.zoom)
        controller.setCenter(point)
    }
}

/** Grid of tile borders that moves with the camera (previews, tests). */
@Composable
private fun TileGridPlaceholder(
    camera: OsmCameraState,
    onMapClick: ((LatLng) -> Unit)?,
    onMapLongClick: ((LatLng) -> Unit)?,
) {
    val background = MaterialTheme.colorScheme.surfaceContainer
    val lines = MaterialTheme.colorScheme.outlineVariant
    val currentClick by rememberUpdatedState(onMapClick)
    val currentLongClick by rememberUpdatedState(onMapLongClick)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(camera) {
                detectTapGestures(
                    onTap = { o -> camera.fromScreen(PixelPoint(o.x.toDouble(), o.y.toDouble()))?.let { currentClick?.invoke(it) } },
                    onLongPress = { o ->
                        camera.fromScreen(PixelPoint(o.x.toDouble(), o.y.toDouble()))?.let { currentLongClick?.invoke(it) }
                    },
                )
            }
            .drawBehind {
                drawRect(background)
                val tile = camera.tileSizePx.takeIf { it > 0 } ?: return@drawBehind
                val world = WebMercator.worldSize(camera.position.zoom, tile)
                val originX = size.width / 2 - WebMercator.x(camera.position.target.longitude) * world
                val originY = size.height / 2 - WebMercator.y(camera.position.target.latitude) * world
                // Width of one tile at the integer zoom the tiles come from.
                val step = world / 2.0.pow(floor(camera.position.zoom))
                var x = originX - floor(originX / step) * step
                while (x < size.width) {
                    drawLine(lines, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height))
                    x += step
                }
                var y = originY - floor(originY / step) * step
                while (y < size.height) {
                    drawLine(lines, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()))
                    y += step
                }
            },
    )
}

/** Places circles (full size) and markers (at their projected anchor) over the tiles. */
@Composable
private fun OverlayLayer(camera: OsmCameraState, content: @Composable OsmMapScope.() -> Unit) {
    val scope = remember(camera) { OsmMapScope(camera) }
    Layout(content = { scope.content() }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val measured = measurables.map { measurable ->
            val slot = measurable.layoutId as? MarkerSlot
            val placeable = if (slot != null) {
                measurable.measure(Constraints())
            } else {
                measurable.measure(Constraints.fixed(width, height))
            }
            slot to placeable
        }
        layout(width, height) {
            // Reads the camera here, so camera moves only re-place the markers.
            measured.forEach { (slot, placeable) ->
                if (slot == null) {
                    placeable.place(0, 0)
                    return@forEach
                }
                val point = camera.toScreen(slot.position) ?: return@forEach
                val x = point.x - placeable.width * slot.anchor.x
                val y = point.y - placeable.height * slot.anchor.y
                // Skip markers far outside the viewport: they would not be visible, and unplaced
                // nodes stay out of the accessibility tree.
                val margin = maxOf(placeable.width, placeable.height).toDouble()
                if (x > width + margin || y > height + margin || x < -2 * margin || y < -2 * margin) return@forEach
                if (abs(x) > Int.MAX_VALUE / 4 || abs(y) > Int.MAX_VALUE / 4) return@forEach
                placeable.place(x.roundToInt(), y.roundToInt())
            }
        }
    }
}

internal data class MarkerSlot(val position: LatLng, val anchor: MarkerAnchor)
