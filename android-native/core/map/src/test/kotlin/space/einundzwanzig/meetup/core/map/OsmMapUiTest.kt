package space.einundzwanzig.meetup.core.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

@RunWith(AndroidJUnit4::class)
class OsmMapUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val vienna = LatLng(48.2082, 16.3738)

    private fun setMap(content: @Composable () -> Unit) {
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalOsmTileRendering provides false) { content() }
            }
        }
    }

    @Test
    fun `map has an accessible description and the osm attribution`() {
        setMap {
            OsmMap(rememberOsmCameraState(vienna, 10.0), Modifier.size(300.dp, 200.dp))
        }
        rule.onNodeWithContentDescription("Karte").assertIsDisplayed()
        rule.onNodeWithText("© OpenStreetMap-Mitwirkende").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun `attribution can be hidden`() {
        setMap {
            OsmMap(rememberOsmCameraState(vienna, 10.0), Modifier.size(300.dp, 200.dp), showAttribution = false)
        }
        rule.onNodeWithText("© OpenStreetMap-Mitwirkende").assertDoesNotExist()
    }

    @Test
    fun `centred marker sits on its coordinate`() {
        setMap {
            OsmMap(rememberOsmCameraState(vienna, 10.0), Modifier.size(300.dp, 200.dp)) {
                Marker(vienna, Modifier.testTag("m")) { Box(Modifier.size(40.dp)) }
            }
        }
        val b = rule.onNodeWithTag("m").getBoundsInRoot()
        assertEquals(130f, b.left.value, 0.5f)
        assertEquals(80f, b.top.value, 0.5f)
        assertEquals(170f, b.right.value, 0.5f)
    }

    @Test
    fun `bottom anchored marker has its tip on the coordinate`() {
        setMap {
            OsmMap(rememberOsmCameraState(vienna, 10.0), Modifier.size(300.dp, 200.dp)) {
                Marker(vienna, Modifier.testTag("pin"), anchor = MarkerAnchor.BottomCenter) { Box(Modifier.size(44.dp)) }
            }
        }
        val b = rule.onNodeWithTag("pin").getBoundsInRoot()
        assertEquals(100f, b.bottom.value, 0.5f)
        assertEquals(150f, (b.left.value + b.right.value) / 2, 0.5f)
    }

    @Test
    fun `markers follow the camera`() {
        lateinit var camera: OsmCameraState
        setMap {
            camera = rememberOsmCameraState(vienna, 10.0)
            OsmMap(camera, Modifier.size(300.dp, 200.dp)) {
                Marker(vienna, Modifier.testTag("m")) { Box(Modifier.size(40.dp)) }
            }
        }
        // move the camera east by 100 px at zoom 10 (world = 256 · 1024 px)
        val east = WebMercator.longitude(WebMercator.x(vienna.longitude) + 100.0 / (256.0 * 1024))
        rule.runOnIdle { camera.move(LatLng(vienna.latitude, east)) }
        rule.waitForIdle()
        val b = rule.onNodeWithTag("m").getBoundsInRoot()
        assertEquals(30f, b.left.value, 0.5f)
    }

    @Test
    fun `markers far outside the viewport are not placed`() {
        setMap {
            OsmMap(rememberOsmCameraState(vienna, 10.0), Modifier.size(300.dp, 200.dp)) {
                Marker(LatLng(-33.86, 151.21), Modifier.testTag("far")) { Box(Modifier.size(40.dp)) }
            }
        }
        rule.onNodeWithTag("far").assertIsNotDisplayed()
    }

    @Test
    fun `marker click is not a map click`() {
        var markerClicks = 0
        var mapClicks = 0
        setMap {
            OsmMap(
                rememberOsmCameraState(vienna, 10.0),
                Modifier.size(300.dp, 200.dp),
                onMapClick = { mapClicks++ },
            ) {
                Marker(vienna, contentDescription = "Wien", onClick = { markerClicks++ }) { Box(Modifier.size(40.dp)) }
            }
        }
        rule.onNodeWithContentDescription("Wien").assertHasClickAction().performClick()
        rule.runOnIdle {
            assertEquals(1, markerClicks)
            assertEquals(0, mapClicks)
        }
    }

    @Test
    fun `tap and long press report the coordinate under the finger`() {
        var tapped: LatLng? = null
        var pressed: LatLng? = null
        setMap {
            OsmMap(
                rememberOsmCameraState(vienna, 10.0),
                Modifier.size(300.dp, 200.dp).testTag("map"),
                onMapClick = { tapped = it },
                onMapLongClick = { pressed = it },
            )
        }
        rule.onNodeWithTag("map").performTouchInput { click(Offset(150f, 100f)) }
        rule.runOnIdle {
            assertNotNull(tapped)
            assertEquals(vienna.latitude, tapped!!.latitude, 1e-6)
            assertEquals(vienna.longitude, tapped!!.longitude, 1e-6)
        }
        rule.onNodeWithTag("map").performTouchInput { longClick(Offset(250f, 100f)) }
        rule.runOnIdle {
            assertNotNull(pressed)
            assertTrue(pressed!!.longitude > vienna.longitude)
            assertEquals(vienna.latitude, pressed!!.latitude, 1e-6)
        }
    }

    @Test
    fun `zoom controls change the zoom`() {
        lateinit var camera: OsmCameraState
        setMap {
            camera = rememberOsmCameraState(vienna, 10.0)
            Box {
                OsmMap(camera, Modifier.size(300.dp, 200.dp))
                MapZoomControls(camera)
            }
        }
        rule.onNodeWithContentDescription("Hineinzoomen").performClick()
        rule.runOnIdle { assertEquals(11.0, camera.position.zoom, 0.0) }
        rule.onNodeWithContentDescription("Herauszoomen").performClick()
        rule.onNodeWithContentDescription("Herauszoomen").performClick()
        rule.runOnIdle { assertEquals(9.0, camera.position.zoom, 0.0) }
    }

    @Test
    fun `location picker sets the pick on tap and shows the pin`() {
        var picked by mutableStateOf<LatLng?>(null)
        setMap {
            LocationPickerMap(
                cameraState = rememberLocationPickerCameraState(null),
                picked = picked,
                onPick = { picked = it },
                modifier = Modifier.size(300.dp, 200.dp).testTag("picker"),
            )
        }
        rule.onNodeWithContentDescription("Gewählter Ort", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("picker").performTouchInput { click(Offset(150f, 100f)) }
        rule.runOnIdle {
            assertEquals(LatLng.GermanyCenter.latitude, picked!!.latitude, 1e-6)
            assertEquals(LatLng.GermanyCenter.longitude, picked!!.longitude, 1e-6)
        }
        rule.onNodeWithContentDescription("Gewählter Ort: 51.16570, 10.45150").assertIsDisplayed()
    }

    @Test
    fun `location picker preset pick is centred at zoom 15`() {
        lateinit var camera: OsmCameraState
        val pick = LatLng(48.2082, 16.3738)
        setMap {
            camera = rememberLocationPickerCameraState(pick)
            LocationPickerMap(camera, pick, {}, Modifier.size(300.dp, 200.dp))
        }
        rule.runOnIdle { assertEquals(CameraPosition(pick, 15.0), camera.position) }
        // the glyph tip (y = 21.5 of 24) sits on the point, not the padded box bottom
        val b = rule.onNodeWithContentDescription("Gewählter Ort: 48.20820, 16.37380").getBoundsInRoot()
        assertEquals(44f, b.bottom.value - b.top.value, 0.5f)
        assertEquals(100f, b.top.value + 44f * 21.5f / 24f, 0.5f)
        assertEquals(150f, (b.left.value + b.right.value) / 2, 0.5f)
    }

    @Test
    fun `location picker click action picks the camera centre`() {
        var picked by mutableStateOf<LatLng?>(null)
        lateinit var camera: OsmCameraState
        setMap {
            camera = rememberLocationPickerCameraState(null)
            LocationPickerMap(camera, picked, { picked = it }, Modifier.size(300.dp, 200.dp))
        }
        rule.runOnIdle { camera.move(vienna, 12.0) }
        rule.onNodeWithContentDescription("Karte")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Karte")))
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.runOnIdle { assertEquals(vienna, picked) }
        val label = rule.onNodeWithContentDescription("Karte").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].label
        assertEquals("Kartenmitte als Ort wählen", label)
    }

    @Test
    fun `plain map has no click action but pan and zoom actions`() {
        lateinit var camera: OsmCameraState
        setMap {
            camera = rememberOsmCameraState(vienna, 10.0)
            OsmMap(camera, Modifier.size(300.dp, 200.dp))
        }
        val map = rule.onNodeWithContentDescription("Karte")
        map.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        fun action(label: String) = map.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        val worldPx = 256.0 * 1024 // zoom 10, density 1 in Robolectric
        rule.runOnIdle { action("Karte nach Osten verschieben").action() }
        rule.runOnIdle {
            assertEquals(WebMercator.x(vienna.longitude) + 100.0 / worldPx, WebMercator.x(camera.position.target.longitude), 1e-9)
            assertEquals(vienna.latitude, camera.position.target.latitude, 1e-9)
        }
        rule.runOnIdle { action("Karte nach Westen verschieben").action() }
        rule.runOnIdle { action("Karte nach Norden verschieben").action() }
        rule.runOnIdle {
            assertEquals(vienna.longitude, camera.position.target.longitude, 1e-9)
            assertTrue(camera.position.target.latitude > vienna.latitude)
        }
        rule.runOnIdle { action("Karte nach Süden verschieben").action() }
        rule.runOnIdle { assertEquals(vienna.latitude, camera.position.target.latitude, 1e-9) }
        rule.runOnIdle { action("Hineinzoomen").action() }
        rule.runOnIdle { assertEquals(11.0, camera.position.zoom, 0.0) }
        rule.runOnIdle { action("Herauszoomen").action() }
        rule.runOnIdle { assertEquals(10.0, camera.position.zoom, 0.0) }
    }

    @Test
    fun `camera survives recreation`() {
        val restoration = StateRestorationTester(rule)
        lateinit var camera: OsmCameraState
        restoration.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalOsmTileRendering provides false) {
                    camera = rememberOsmCameraState(vienna, 10.0, minZoom = 3.0, maxZoom = 16.0)
                    OsmMap(camera, Modifier.size(300.dp, 200.dp))
                }
            }
        }
        rule.runOnIdle { camera.move(LatLng.GermanyCenter, 7.5) }
        val before = camera
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle {
            assertTrue(before !== camera)
            assertEquals(CameraPosition(LatLng.GermanyCenter, 7.5), camera.position)
            assertEquals(3.0, camera.minZoom, 0.0)
            assertEquals(16.0, camera.maxZoom, 0.0)
        }
    }

    @Test
    fun `camera zoom limits come from rememberOsmCameraState`() {
        lateinit var camera: OsmCameraState
        setMap { camera = rememberOsmCameraState(vienna, 30.0, minZoom = 4.0, maxZoom = 12.0) }
        rule.runOnIdle {
            assertEquals(12.0, camera.position.zoom, 0.0)
            camera.move(vienna, 1.0)
            assertEquals(4.0, camera.position.zoom, 0.0)
        }
    }

    @Test
    fun `fitPoints fits the laid out map`() {
        lateinit var camera: OsmCameraState
        setMap {
            camera = rememberOsmCameraState(vienna, 4.0)
            OsmMap(camera, Modifier.size(256.dp, 256.dp))
        }
        rule.waitForIdle()
        runBlocking { camera.fitPoints(listOf(LatLng(0.0, 0.0), LatLng(0.0, 90.0)), maxZoom = 12.0) }
        rule.runOnIdle { // 0.25 of the world = 64 px at zoom 0 in 256 px → zoom 2
            assertEquals(2.0, camera.position.zoom, 1e-6) }
    }

    @Test
    fun `snapshot captures the marked area at the requested pixel ratio`() {
        lateinit var snapshot: MapSnapshotState
        setMap {
            snapshot = rememberMapSnapshotState()
            OsmMap(rememberOsmCameraState(vienna, 10.0), Modifier.size(300.dp, 200.dp).mapSnapshotArea(snapshot))
        }
        rule.waitForIdle()
        val bmp = captureOnMainLooper(snapshot)
        assertNotNull(bmp)
        bmp!!
        assertEquals(750, bmp.width)
        assertEquals(500, bmp.height)
    }

    @Test
    fun `snapshot before layout is null`() {
        assertNull(MapSnapshotState().areaBounds)
        assertFalse(runBlocking { MapSnapshotState().capture() } != null)
    }

    @Test
    fun `osm tile source follows the tile policy`() {
        val src = OsmConfiguration.OsmStandardTiles
        assertEquals(0, src.minimumZoomLevel)
        assertEquals(19, src.maximumZoomLevel)
        assertEquals(256, src.tileSizePixels)
        assertEquals(2, src.tileSourcePolicy.maxConcurrent)
        assertFalse(src.tileSourcePolicy.acceptsBulkDownload())
        assertFalse(src.tileSourcePolicy.acceptsPreventive())
        assertTrue(src.baseUrl.startsWith("https://tile.openstreetmap.org/"))
    }
}
