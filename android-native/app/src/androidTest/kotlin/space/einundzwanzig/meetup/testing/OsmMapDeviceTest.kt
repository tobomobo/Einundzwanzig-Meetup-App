package space.einundzwanzig.meetup.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** Exercises actual osmdroid inflation, projection and overlay drawing with the network disabled. */
@RunWith(AndroidJUnit4::class)
class OsmMapDeviceTest {
    @Test fun actualMapRendersMarkerAndProjectsCoordinatesOffline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val map = MapView(instrumentation.targetContext)
            val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            try {
                map.setUseDataConnection(false)
                map.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
                map.layout(0, 0, 800, 600)
                val vienna = GeoPoint(48.2082, 16.3738)
                map.controller.setZoom(13.0)
                map.controller.setCenter(vienna)
                map.overlays.add(Marker(map).apply { position = vienna; title = "Offline device fixture" })
                map.draw(Canvas(bitmap))
                val projected = map.projection.toPixels(vienna, Point())
                assertTrue(projected.x in 398..402)
                assertTrue(projected.y in 298..302)
                val restored = map.projection.fromPixels(projected.x, projected.y)
                assertEquals(vienna.latitude, restored.latitude, 0.001)
                assertEquals(vienna.longitude, restored.longitude, 0.001)
            } finally {
                map.onDetach()
                bitmap.recycle()
            }
        }
    }
}
