package space.einundzwanzig.meetup.core.map

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.config.Configuration
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/** Smoke test with the real osmdroid view (no tiles load under Robolectric). */
@RunWith(AndroidJUnit4::class)
class OsmdroidMapTest {

    @get:Rule
    val rule = createComposeRule()

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
        lateinit var camera: OsmCameraState
        rule.setContent {
            EinundzwanzigTheme {
                camera = rememberOsmCameraState(LatLng(48.2082, 16.3738), 10.0)
                OsmMap(camera, Modifier.size(300.dp, 200.dp))
            }
        }
        rule.onNodeWithContentDescription("Karte").assertIsDisplayed()
        rule.runOnIdle { camera.move(LatLng(52.52, 13.405), 12.0) }
        rule.runOnIdle {
            assertEquals(12.0, camera.position.zoom, 1e-6)
            assertEquals(52.52, camera.position.target.latitude, 1e-3)
            assertEquals(13.405, camera.position.target.longitude, 1e-3)
        }
    }
}
