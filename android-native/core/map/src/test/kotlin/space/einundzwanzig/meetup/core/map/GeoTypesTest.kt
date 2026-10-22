package space.einundzwanzig.meetup.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTypesTest {

    @Test
    fun `zero coordinate is the no-location sentinel`() {
        assertTrue(LatLng(0.0, 0.0).isZero)
        assertFalse(LatLng(0.0, 1.0).isZero)
        assertFalse(LatLng(1.0, 0.0).isZero)
    }

    @Test
    fun `bounds of points`() {
        assertNull(LatLngBounds.of(emptyList()))
        val b = LatLngBounds.of(listOf(LatLng(48.2, 16.4), LatLng(52.5, 13.4), LatLng(47.1, 15.4)))!!
        assertEquals(LatLngBounds(47.1, 13.4, 52.5, 16.4), b)
        assertEquals((13.4 + 16.4) / 2, b.center.longitude, 1e-12)
        // mercator middle lies north of the arithmetic middle
        assertTrue(b.center.latitude > (47.1 + 52.5) / 2)
    }

    @Test
    fun `coordinates format with dart toStringAsFixed`() {
        assertEquals("48.20820, 16.37380", LatLng(48.2082, 16.3738).format(5))
        assertEquals("-33.8600, 151.2100", LatLng(-33.86, 151.21).format(4))
        assertEquals("0.00000, 0.00000", LatLng(0.0, 0.0).format(5))
        assertEquals("2.00000, -1.00000", LatLng(1.9999999, -0.9999999).format(5))
    }

    @Test
    fun `location picker presets a pick only for a real coordinate`() {
        assertNull(LocationPicker.initialPick(null, null))
        assertNull(LocationPicker.initialPick(48.0, null))
        assertNull(LocationPicker.initialPick(null, 16.0))
        assertNull(LocationPicker.initialPick(0.0, 0.0))
        assertEquals(LatLng(0.0, 16.0), LocationPicker.initialPick(0.0, 16.0))
        assertEquals(LatLng(48.2, 16.3), LocationPicker.initialPick(48.2, 16.3))
    }

    @Test
    fun `location picker initial camera`() {
        assertEquals(CameraPosition(LatLng(51.1657, 10.4515), 5.0), LocationPicker.initialCamera(null))
        val pick = LatLng(48.2, 16.3)
        assertEquals(CameraPosition(pick, 15.0), LocationPicker.initialCamera(pick))
    }

    @Test
    fun `osm marker url uses dart double formatting`() {
        assertEquals(
            "https://www.openstreetmap.org/?mlat=48.2082&mlon=16.0#map=15/48.2082/16.0",
            OsmUrls.marker(48.2082, 16.0),
        )
        assertEquals(
            "https://www.openstreetmap.org/?mlat=-33.86&mlon=151.21#map=15/-33.86/151.21",
            OsmUrls.marker(-33.86, 151.21),
        )
    }

    @Test
    fun `user agent identifies the app`() {
        assertEquals("space.einundzwanzig.meetup/1.4.0 (Android)", OsmConfiguration.userAgent("space.einundzwanzig.meetup", "1.4.0"))
        assertEquals("space.einundzwanzig.meetup (Android)", OsmConfiguration.userAgent("space.einundzwanzig.meetup", null))
        assertEquals("space.einundzwanzig.meetup (Android)", OsmConfiguration.userAgent("space.einundzwanzig.meetup", " "))
    }

    @Test
    fun `snapshot output size scales area`() {
        val (w, h) = MapSnapshotState.outputSize(androidx.compose.ui.geometry.Rect(0f, 0f, 1080f, 1650f), 2.5f / 2.75f)
        assertEquals(982, w)
        assertEquals(1500, h)
    }
}
