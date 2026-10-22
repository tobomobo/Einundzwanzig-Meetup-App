package space.einundzwanzig.meetup.core.map

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Tests with the real osmdroid view (no tiles load under Robolectric). Markers are placed with
 * [WebMercator] from [OsmCameraState.position]; these tests check that this matches where osmdroid
 * draws the tiles, and that osmdroid's camera flows back into the state.
 */
@RunWith(AndroidJUnit4::class)
class OsmdroidMapTest {

    @get:Rule
    val rule = createComposeRule()

    private val vienna = LatLng(48.2082, 16.3738)
    private val points = listOf(
        vienna,
        LatLng(52.52, 13.405),
        LatLng(40.4168, -3.7038),
        LatLng(-33.8688, 151.2093),
        LatLng(0.0, 0.0),
    )

    @Test
    fun `configuration sets an identifying user agent and an app private cache`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        OsmConfiguration.ensureConfigured(context)
        val config = Configuration.getInstance()
        assertTrue(config.userAgentValue.startsWith(context.packageName))
        // process-wide and set once; Robolectric gives each test a fresh cache dir
        assertTrue(config.osmdroidTileCache.absolutePath.endsWith("/cache/osmdroid/tiles"))
    }

    @Test
    fun `real map view follows camera commands`() {
        val (camera, map) = showMap()
        rule.onNodeWithContentDescription("Karte").assertIsDisplayed()
        rule.runOnIdle { camera.move(LatLng(52.52, 13.405), 12.0) }
        rule.runOnIdle {
            assertEquals(12.0, camera.position.zoom, 1e-6)
            assertEquals(52.52, camera.position.target.latitude, 1e-3)
            assertEquals(13.405, camera.position.target.longitude, 1e-3)
            assertMapMatchesCamera(map(), camera)
        }
    }

    @Test
    fun `animated camera moves reach the map view and the state`() {
        val (camera, map) = showMap()
        rule.runOnIdle { camera.animateTo(LatLng(40.4168, -3.7038), 8.0) }
        ShadowLooper.idleMainLooper(OsmCameraState.CAMERA_ANIMATION_MS + 200, TimeUnit.MILLISECONDS)
        rule.runOnIdle {
            assertMapMatchesCamera(map(), camera)
            assertEquals(8.0, camera.position.zoom, 1e-3)
            // the target is on screen centre within a pixel
            val target = camera.toScreen(LatLng(40.4168, -3.7038))!!
            assertEquals(camera.viewportSize.width / 2.0, target.x, 1.0)
            assertEquals(camera.viewportSize.height / 2.0, target.y, 1.0)
        }
    }

    @Test
    fun `compose projection matches osmdroid at integer, fractional and low zoom`() {
        val (camera, map) = showMap()
        assertProjectionAcrossZooms(camera, map)
    }

    @Test
    @Config(qualifiers = "420dpi")
    fun `compose projection matches osmdroid at a fractional density`() {
        val (camera, map) = showMap()
        rule.runOnIdle { assertEquals(672.0, camera.tileSizePx, 0.0) }
        assertProjectionAcrossZooms(camera, map)
    }

    @Test
    fun `scrolling the map view updates the camera state`() {
        val (camera, map) = showMap()
        val before = rule.runOnIdle { camera.position.target }
        rule.runOnIdle { map().scrollBy(100, 0) }
        rule.runOnIdle {
            assertTrue(camera.position.target.longitude > before.longitude)
            assertMapMatchesCamera(map(), camera)
        }
    }

    @Test
    fun `map keeps working after its view is detached and reattached`() {
        val (camera, map) = showMap()
        rule.runOnIdle {
            val view = map()
            val parent = view.parent as ViewGroup
            val index = parent.indexOfChild(view)
            parent.removeViewAt(index)
            parent.addView(view, index)
        }
        val before = rule.runOnIdle { camera.position.target }
        rule.runOnIdle { map().scrollBy(100, 0) }
        rule.runOnIdle {
            assertNotEquals(before.longitude, camera.position.target.longitude, 1e-9)
            assertMapMatchesCamera(map(), camera)
        }
        // and commands still reach osmdroid
        rule.runOnIdle { camera.move(LatLng(52.52, 13.405), 11.0) }
        rule.runOnIdle { assertMapMatchesCamera(map(), camera) }
    }

    private fun assertProjectionAcrossZooms(camera: OsmCameraState, map: () -> MapView) {
        for (zoom in listOf(10.0, 10.37, 1.5)) {
            for (center in listOf(vienna, LatLng(0.0, 0.0), LatLng(70.0, 170.0))) {
                rule.runOnIdle { camera.move(center, zoom) }
                rule.runOnIdle {
                    val view = map()
                    assertMapMatchesCamera(view, camera)
                    for (p in points) {
                        val expected = camera.toScreen(p)!!
                        val actual = view.projection.toPixels(GeoPoint(p.latitude, p.longitude), null)
                        val where = "zoom $zoom centre $center point $p: osmdroid $actual, compose $expected"
                        // osmdroid keeps whole-pixel scroll offsets and returns whole pixels.
                        assertTrue(where, abs(actual.x - expected.x) <= PIXEL_TOLERANCE)
                        assertTrue(where, abs(actual.y - expected.y) <= PIXEL_TOLERANCE)
                    }
                }
            }
        }
    }

    /**
     * osmdroid's zoom equals the state's, and its centre is within a pixel of the state's (osmdroid keeps
     * its scroll offset in whole pixels).
     */
    private fun assertMapMatchesCamera(view: MapView, camera: OsmCameraState) {
        val c = camera.position
        assertEquals("zoom", c.zoom, view.zoomLevelDouble, 1e-6)
        val center = camera.toScreen(LatLng(view.mapCenter.latitude, view.mapCenter.longitude))!!
        val where = "osmdroid centre ${view.mapCenter} vs state ${c.target}"
        assertEquals(where, camera.viewportSize.width / 2.0, center.x, 1.0)
        assertEquals(where, camera.viewportSize.height / 2.0, center.y, 1.0)
    }

    private fun showMap(): Pair<OsmCameraState, () -> MapView> {
        lateinit var camera: OsmCameraState
        lateinit var root: View
        rule.setContent {
            root = LocalView.current
            EinundzwanzigTheme {
                camera = rememberOsmCameraState(vienna, 10.0)
                OsmMap(camera, Modifier.size(300.dp, 200.dp))
            }
        }
        rule.waitForIdle()
        return camera to { checkNotNull(findMapView(root.rootView)) { "no MapView" } }
    }

    private companion object {
        const val PIXEL_TOLERANCE = 1.5
    }

    private fun findMapView(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findMapView(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
