package space.einundzwanzig.meetup.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log2

class WebMercatorTest {
    private val eps = 1e-9

    @Test
    fun `normalised projection of known points`() {
        assertEquals(0.5, WebMercator.x(0.0), eps)
        assertEquals(0.0, WebMercator.x(-180.0), eps)
        assertEquals(1.0, WebMercator.x(180.0), eps)
        assertEquals(0.5, WebMercator.y(0.0), eps)
        assertEquals(0.0, WebMercator.y(WebMercator.MAX_LATITUDE), 1e-9)
        assertEquals(1.0, WebMercator.y(-WebMercator.MAX_LATITUDE), 1e-9)
    }

    @Test
    fun `latitudes beyond the mercator limit are clamped`() {
        assertEquals(WebMercator.y(WebMercator.MAX_LATITUDE), WebMercator.y(89.9), eps)
        assertEquals(WebMercator.MAX_LATITUDE, WebMercator.clampLatitude(90.0), eps)
        assertEquals(-WebMercator.MAX_LATITUDE, WebMercator.clampLatitude(-90.0), eps)
        assertEquals(48.2, WebMercator.clampLatitude(48.2), eps)
    }

    @Test
    fun `projection round trips`() {
        listOf(LatLng(48.2082, 16.3738), LatLng(-33.86, 151.21), LatLng(0.0, 0.0), LatLng(64.1, -21.9)).forEach { p ->
            assertEquals(p.latitude, WebMercator.latitude(WebMercator.y(p.latitude)), 1e-9)
            assertEquals(p.longitude, WebMercator.longitude(WebMercator.x(p.longitude)), 1e-9)
        }
    }

    @Test
    fun `world size doubles per zoom level`() {
        assertEquals(256.0, WebMercator.worldSize(0.0, 256.0), eps)
        assertEquals(2048.0, WebMercator.worldSize(3.0, 256.0), eps)
        assertEquals(256.0 * Math.pow(2.0, 4.5), WebMercator.worldSize(4.5, 256.0), 1e-6)
    }

    @Test
    fun `osmdroid tile size truncates density scaled 256`() {
        assertEquals(256.0, WebMercator.tileSizePx(1f), eps)
        assertEquals(704.0, WebMercator.tileSizePx(2.75f), eps)
        assertEquals(672.0, WebMercator.tileSizePx(2.625f), eps)
    }

    @Test
    fun `camera target projects to the viewport centre`() {
        val camera = CameraPosition(LatLng(48.2, 16.37), 11.0)
        val p = WebMercator.toScreen(camera.target, camera, 400, 300, 256.0)
        assertEquals(200.0, p.x, eps)
        assertEquals(150.0, p.y, eps)
    }

    @Test
    fun `east is right and north is up`() {
        val camera = CameraPosition(LatLng(0.0, 0.0), 2.0)
        val east = WebMercator.toScreen(LatLng(0.0, 90.0), camera, 1024, 1024, 256.0)
        // zoom 2: world 1024 px, 90° = a quarter
        assertEquals(512.0 + 256.0, east.x, eps)
        assertEquals(512.0, east.y, eps)
        val north = WebMercator.toScreen(LatLng(10.0, 0.0), camera, 1024, 1024, 256.0)
        assertTrue(north.y < 512.0)
    }

    @Test
    fun `fromScreen inverts toScreen`() {
        val camera = CameraPosition(LatLng(51.1657, 10.4515), 5.0)
        val point = LatLng(47.0707, 15.4395)
        val px = WebMercator.toScreen(point, camera, 1080, 1920, 704.0)
        val back = WebMercator.fromScreen(px, camera, 1080, 1920, 704.0)
        assertEquals(point.latitude, back.latitude, 1e-9)
        assertEquals(point.longitude, back.longitude, 1e-9)
    }

    @Test
    fun `fit of no points is null`() {
        assertNull(WebMercator.fitCamera(emptyList(), 500, 500, 0.0, 256.0))
    }

    @Test
    fun `fit of one point uses max zoom`() {
        val p = LatLng(48.2, 16.37)
        val cam = WebMercator.fitCamera(listOf(p), 500, 500, 60.0, 256.0, maxZoom = 12.0)!!
        assertEquals(12.0, cam.zoom, eps)
        assertEquals(p.latitude, cam.target.latitude, 1e-9)
        assertEquals(p.longitude, cam.target.longitude, 1e-9)
        // identical points behave the same
        assertEquals(12.0, WebMercator.fitCamera(listOf(p, p), 500, 500, 60.0, 256.0, maxZoom = 12.0)!!.zoom, eps)
    }

    @Test
    fun `fit zoom follows flutter_map CameraFit coordinates`() {
        // x span 0.25 world = 64 px at zoom 0; 512 px viewport → zoom 3.
        val pts = listOf(LatLng(0.0, 0.0), LatLng(0.0, 90.0))
        val cam = WebMercator.fitCamera(pts, 512, 512, 0.0, 256.0)!!
        assertEquals(3.0, cam.zoom, eps)
        assertEquals(0.0, cam.target.latitude, 1e-9)
        assertEquals(45.0, cam.target.longitude, 1e-9)
        // padding on both sides: 512 - 2·64 = 384 → log2(6)
        val padded = WebMercator.fitCamera(pts, 512, 512, 64.0, 256.0)!!
        assertEquals(log2(6.0), padded.zoom, 1e-9)
    }

    @Test
    fun `fit uses the tighter axis and stays inside the padded viewport`() {
        val pts = listOf(LatLng(48.2082, 16.3738), LatLng(52.52, 13.405), LatLng(47.0707, 15.4395), LatLng(53.55, 9.99))
        val cam = WebMercator.fitCamera(pts, 1080, 1500, 60.0 * 2.75, 704.0, maxZoom = 12.0)!!
        pts.forEach { p ->
            val s = WebMercator.toScreen(p, cam, 1080, 1500, 704.0)
            assertTrue("$p x=${s.x}", s.x >= 60 * 2.75 - 1e-6 && s.x <= 1080 - 60 * 2.75 + 1e-6)
            assertTrue("$p y=${s.y}", s.y >= 60 * 2.75 - 1e-6 && s.y <= 1500 - 60 * 2.75 + 1e-6)
        }
        // the north-south span is the binding one: it touches the padded edges
        val ys = pts.map { WebMercator.toScreen(it, cam, 1080, 1500, 704.0).y }
        assertEquals(60 * 2.75, ys.min(), 1e-6)
        assertEquals(1500 - 60 * 2.75, ys.max(), 1e-6)
    }

    @Test
    fun `fit centres on the projected middle not the latitude average`() {
        val pts = listOf(LatLng(0.0, 0.0), LatLng(80.0, 0.0))
        val cam = WebMercator.fitCamera(pts, 500, 500, 0.0, 256.0)!!
        val expected = WebMercator.latitude((WebMercator.y(0.0) + WebMercator.y(80.0)) / 2)
        assertEquals(expected, cam.target.latitude, 1e-9)
        assertTrue(cam.target.latitude > 40.0)
    }

    @Test
    fun `fit clamps to max and min zoom`() {
        val close = listOf(LatLng(48.20, 16.37), LatLng(48.2001, 16.3701))
        assertEquals(12.0, WebMercator.fitCamera(close, 500, 500, 50.0, 256.0, maxZoom = 12.0)!!.zoom, eps)
        val world = listOf(LatLng(-80.0, -170.0), LatLng(80.0, 170.0))
        assertEquals(2.0, WebMercator.fitCamera(world, 300, 300, 50.0, 256.0, minZoom = 2.0)!!.zoom, eps)
    }

    @Test
    fun `padding larger than the viewport does not crash`() {
        val cam = WebMercator.fitCamera(listOf(LatLng(0.0, 0.0), LatLng(10.0, 10.0)), 100, 100, 80.0, 256.0, minZoom = 0.0)!!
        assertTrue(cam.zoom.isFinite())
    }

    @Test
    fun `metres to pixels at the equator`() {
        // zoom 0, 256 px: 40 075 016.686 m per world → 156 543.034 m per px
        assertEquals(1.0, WebMercator.metersToPixels(156_543.03392804097, 0.0, 0.0, 256.0), 1e-9)
        // twice the pixels per zoom level
        assertEquals(2.0, WebMercator.metersToPixels(156_543.03392804097, 0.0, 1.0, 256.0), 1e-9)
    }

    @Test
    fun `metres to pixels grows with latitude`() {
        val equator = WebMercator.metersToPixels(25_000.0, 0.0, 9.0, 256.0)
        val vienna = WebMercator.metersToPixels(25_000.0, 48.2, 9.0, 256.0)
        assertEquals(1.0 / kotlin.math.cos(Math.toRadians(48.2)), vienna / equator, 1e-9)
    }

    @Test
    fun `fit of vienna and san francisco keeps both on a phone screen`() {
        // Spec 03 §2.4: badges across continents, padding 60 dp, 360 × 600 dp at density 1.
        val vienna = LatLng(48.2082, 16.3738)
        val sf = LatLng(37.7749, -122.4194)
        val cam = WebMercator.fitCamera(listOf(vienna, sf), 360, 600, 60.0, 256.0, maxZoom = 12.0)!!
        assertTrue(cam.zoom < 2.0)
        assertTrue(cam.zoom >= WebMercator.MIN_ZOOM)
        for (p in listOf(vienna, sf)) {
            val px = WebMercator.toScreen(p, cam, 360, 600, 256.0)
            assertTrue("$p x=${px.x}", px.x in 0.0..360.0)
            assertTrue("$p y=${px.y}", px.y in 0.0..600.0)
        }
    }
}
