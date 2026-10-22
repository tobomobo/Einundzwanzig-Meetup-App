package space.einundzwanzig.meetup.core.map

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/** Pixel checks of the share-image capture (spec 03 §2.4) with real rendering. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OsmMapRenderTest {

    @get:Rule
    val rule = createComposeRule()

    private val vienna = LatLng(48.2082, 16.3738)

    @Test
    fun `capture contains the compose markers at their scaled position`() {
        lateinit var snapshot: MapSnapshotState
        var primary = 0
        var background = 0
        rule.setContent {
            EinundzwanzigTheme {
                primary = MaterialTheme.colorScheme.primary.toArgb()
                background = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                CompositionLocalProvider(LocalOsmTileRendering provides false) {
                    snapshot = rememberMapSnapshotState()
                    val camera = rememberOsmCameraState(vienna, 10.0)
                    OsmMap(camera, Modifier.size(300.dp, 200.dp).mapSnapshotArea(snapshot), showAttribution = false) {
                        // 100 dp east of the centre
                        val east = WebMercator.longitude(WebMercator.x(vienna.longitude) + 100.0 / (256.0 * 1024))
                        Marker(LatLng(vienna.latitude, east)) {
                            Box(Modifier.size(20.dp).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val bmp = captureOnMainLooper(snapshot, pixelRatio = 2.5f)!!
        assertEquals(750, bmp.width)
        assertEquals(500, bmp.height)
        // marker centre at (250 dp, 100 dp) · 2.5
        assertEquals(primary, bmp.getPixel(625, 250))
        assertNotEquals(primary, bmp.getPixel(375, 250))
        // the map background is drawn as well
        assertEquals(background, bmp.getPixel(380, 60))
    }

    @Test
    fun `capture contains the badge marker glow outside its circle`() {
        lateinit var snapshot: MapSnapshotState
        var primary = 0
        var background = 0
        rule.setContent {
            EinundzwanzigTheme {
                primary = MaterialTheme.colorScheme.primary.toArgb()
                background = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                CompositionLocalProvider(LocalOsmTileRendering provides false) {
                    snapshot = rememberMapSnapshotState()
                    val camera = rememberOsmCameraState(vienna, 10.0)
                    OsmMap(camera, Modifier.size(300.dp, 200.dp).mapSnapshotArea(snapshot), showAttribution = false) {
                        Marker(vienna) { BitcoinBadgeMarker() }
                    }
                }
            }
        }
        rule.waitForIdle()
        val bmp = captureOnMainLooper(snapshot, pixelRatio = 1f)!!
        // badge centred at (150, 100), radius 20 dp; 2 dp outside the circle lies in the glow
        assertEquals(primary, bmp.getPixel(150, 90))
        val glow = bmp.getPixel(172, 100)
        assertNotEquals(background, glow)
        val bg = Color.valueOf(background)
        val g = Color.valueOf(glow)
        assertTrue("glow is tinted towards primary: ${Integer.toHexString(glow)}", g.red() - g.blue() > bg.red() - bg.blue() + 0.1f)
        // the glow fades out: far outside it is plain background
        assertEquals(background, bmp.getPixel(190, 100))
    }

    @Test
    fun `capture includes the osmdroid view`() {
        lateinit var snapshot: MapSnapshotState
        var loadingBackground = 0
        rule.setContent {
            EinundzwanzigTheme {
                loadingBackground = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                snapshot = rememberMapSnapshotState()
                OsmMap(
                    rememberOsmCameraState(vienna, 10.0),
                    Modifier.size(300.dp, 200.dp).mapSnapshotArea(snapshot),
                    showAttribution = false,
                )
            }
        }
        rule.waitForIdle()
        val bmp = captureOnMainLooper(snapshot, pixelRatio = 1f)!!
        // No tiles load under Robolectric: osmdroid paints its loading tiles in the themed colour.
        val pixels = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        val share = pixels.count { it == loadingBackground }.toDouble() / pixels.size
        assertTrue("loading background share $share", share > 0.5)
    }
}
