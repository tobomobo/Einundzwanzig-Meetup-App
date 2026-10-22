package space.einundzwanzig.meetup.core.map

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmCameraStateTest {

    private class FakeDriver : MapDriver {
        val moves = mutableListOf<Pair<CameraPosition, Boolean>>()
        var loaded = false
        override fun move(position: CameraPosition, animate: Boolean) {
            moves += position to animate
        }
        override fun tilesLoaded(): Boolean = loaded
    }

    private val vienna = LatLng(48.2082, 16.3738)

    private fun state(zoom: Double = 5.0) = OsmCameraState(CameraPosition(LatLng.GermanyCenter, zoom))

    @Test
    fun `move without a map view updates the position`() {
        val s = state()
        s.move(vienna, 11.0)
        assertEquals(CameraPosition(vienna, 11.0), s.position)
        s.animateTo(LatLng.DachCenter)
        assertEquals(CameraPosition(LatLng.DachCenter, 11.0), s.position)
    }

    @Test
    fun `zoom is clamped to the limits`() {
        val s = state()
        s.move(vienna, 25.0)
        assertEquals(WebMercator.OSM_MAX_ZOOM, s.position.zoom, 0.0)
        assertFalse(s.canZoomIn)
        assertTrue(s.canZoomOut)
        s.move(vienna, -3.0)
        assertEquals(WebMercator.MIN_ZOOM, s.position.zoom, 0.0)
        assertFalse(s.canZoomOut)
        val initial = OsmCameraState(CameraPosition(vienna, 30.0))
        assertEquals(WebMercator.OSM_MAX_ZOOM, initial.position.zoom, 0.0)
    }

    @Test
    fun `latitude is clamped to the mercator limit`() {
        val s = state()
        s.move(LatLng(89.0, 0.0))
        assertEquals(WebMercator.MAX_LATITUDE, s.position.target.latitude, 1e-12)
    }

    @Test
    fun `zoomBy changes zoom around the centre`() {
        val s = state(5.0)
        s.zoomBy(1.0)
        assertEquals(6.0, s.position.zoom, 0.0)
        s.zoomBy(-2.0)
        assertEquals(4.0, s.position.zoom, 0.0)
        assertEquals(LatLng.GermanyCenter, s.position.target)
    }

    @Test
    fun `commands go to the attached driver`() {
        val s = state()
        val d = FakeDriver()
        s.attach(d)
        s.move(vienna, 11.0)
        s.animateTo(LatLng.DachCenter, 9.0)
        assertEquals(listOf(CameraPosition(vienna, 11.0) to false, CameraPosition(LatLng.DachCenter, 9.0) to true), d.moves)
        // jump is applied at once, the animation is reported by the map
        assertEquals(CameraPosition(vienna, 11.0), s.position)
        s.onMapMoved(CameraPosition(LatLng.DachCenter, 9.0))
        assertEquals(CameraPosition(LatLng.DachCenter, 9.0), s.position)
        s.detach(d)
        s.move(vienna)
        assertEquals(2, d.moves.size)
    }

    @Test
    fun `quick zoom taps build on the pending animation target`() {
        val s = state(5.0)
        val d = FakeDriver() // records moves, never reports them back
        s.attach(d)
        s.zoomBy(1.0)
        s.zoomBy(1.0)
        assertEquals(listOf(6.0, 7.0), d.moves.map { it.first.zoom })
        assertEquals(5.0, s.position.zoom, 0.0)
        // the map arrives: the target is cleared and position takes over
        s.onMapMoved(CameraPosition(LatLng.GermanyCenter, 7.0))
        s.zoomBy(-1.0)
        assertEquals(6.0, d.moves.last().first.zoom, 0.0)
    }

    @Test
    fun `zoom limits follow the pending target`() {
        val s = state(WebMercator.OSM_MAX_ZOOM - 1)
        s.attach(FakeDriver())
        assertTrue(s.canZoomIn)
        s.zoomBy(1.0)
        assertFalse(s.canZoomIn)
        // a jump replaces the pending animation
        s.move(vienna, 10.0)
        assertTrue(s.canZoomIn)
        assertEquals(10.0, s.position.zoom, 0.0)
    }

    @Test
    fun `longitude is kept inside the single world`() {
        val s = state()
        s.move(LatLng(0.0, 200.0))
        assertEquals(180.0, s.position.target.longitude, 0.0)
        s.move(LatLng(0.0, -190.0))
        assertEquals(-180.0, s.position.target.longitude, 0.0)
    }

    @Test
    fun `detach of another driver keeps the current one`() {
        val s = state()
        val a = FakeDriver()
        s.attach(a)
        s.detach(FakeDriver())
        s.move(vienna)
        assertEquals(1, a.moves.size)
    }

    @Test
    fun `screen projection needs a viewport`() {
        val s = state()
        assertNull(s.toScreen(vienna))
        assertNull(s.fromScreen(PixelPoint(0.0, 0.0)))
        s.onViewport(IntSize(400, 300), 1f)
        val c = s.toScreen(LatLng.GermanyCenter)!!
        assertEquals(200.0, c.x, 1e-9)
        assertEquals(150.0, c.y, 1e-9)
        val back = s.fromScreen(PixelPoint(200.0, 150.0))!!
        assertEquals(LatLng.GermanyCenter.latitude, back.latitude, 1e-9)
        assertEquals(LatLng.GermanyCenter.longitude, back.longitude, 1e-9)
    }

    @Test
    fun `tile size follows the density`() {
        val s = state()
        s.onViewport(IntSize(1080, 1920), 2.75f)
        assertEquals(704.0, s.tileSizePx, 0.0)
    }

    @Test
    fun `fitPoints of nothing does nothing`() = runTest {
        val s = state()
        assertFalse(s.fitPoints(emptyList()))
        assertEquals(CameraPosition(LatLng.GermanyCenter, 5.0), s.position)
    }

    @Test
    fun `fitPoints waits for the viewport and fits with padding in dp`() = runTest {
        val s = state()
        val pts = listOf(LatLng(0.0, 0.0), LatLng(0.0, 90.0))
        val result = async { s.fitPoints(pts, padding = 32.dp, maxZoom = 12.0) }
        runCurrent()
        assertFalse(result.isCompleted)
        s.onViewport(IntSize(1024, 1024), 2f)
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertTrue(result.await())
        // 1024 px - 2·64 px = 896 px for a span of 0.25 · 512 px → log2(7)
        assertEquals(kotlin.math.log2(7.0), s.position.zoom, 1e-9)
        assertEquals(45.0, s.position.target.longitude, 1e-9)
    }

    @Test
    fun `fitPoints of one point uses maxZoom`() = runTest {
        val s = state()
        s.onViewport(IntSize(500, 500), 1f)
        assertTrue(s.fitPoints(listOf(vienna), padding = 60.dp, maxZoom = 12.0))
        assertEquals(12.0, s.position.zoom, 0.0)
        assertEquals(vienna.latitude, s.position.target.latitude, 1e-9)
        assertEquals(vienna.longitude, s.position.target.longitude, 1e-9)
    }

    @Test
    fun `fitPoints animates through the driver when asked`() = runTest {
        val s = state()
        val d = FakeDriver()
        s.attach(d)
        s.onViewport(IntSize(500, 500), 1f)
        s.fitPoints(listOf(vienna, LatLng.DachCenter), padding = 50.dp, maxZoom = 12.0, animate = true)
        assertEquals(1, d.moves.size)
        assertTrue(d.moves.single().second)
    }

    @Test
    fun `awaitTilesLoaded polls the driver until loaded`() = runTest {
        val s = state()
        val d = FakeDriver()
        s.attach(d)
        val r = async { s.awaitTilesLoaded(timeoutMillis = 2_000) }
        advanceTimeBy(350)
        assertFalse(r.isCompleted)
        d.loaded = true
        advanceTimeBy(150)
        assertTrue(r.await())
    }

    @Test
    fun `awaitTilesLoaded gives up after the timeout`() = runTest {
        val s = state()
        s.attach(FakeDriver())
        assertFalse(s.awaitTilesLoaded(timeoutMillis = 500))
    }

    @Test
    fun `awaitTilesLoaded without map view returns at once`() = runTest {
        assertTrue(state().awaitTilesLoaded())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `saver round trips camera and limits`() {
        val s = OsmCameraState(CameraPosition(vienna, 11.5), minZoom = 3.0, maxZoom = 17.0)
        val saver = OsmCameraState.Saver as Saver<OsmCameraState, Any>
        val saved = with(saver) { SaverScope { true }.save(s) }
        assertNotNull(saved)
        val restored = saver.restore(saved!!)!!
        assertEquals(s.position, restored.position)
        assertEquals(3.0, restored.minZoom, 0.0)
        assertEquals(17.0, restored.maxZoom, 0.0)
    }
}
