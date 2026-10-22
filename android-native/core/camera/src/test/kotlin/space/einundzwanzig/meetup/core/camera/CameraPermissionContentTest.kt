package space.einundzwanzig.meetup.core.camera

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.motion.LocalReducedMotion
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

@RunWith(AndroidJUnit4::class)
class CameraPermissionContentTest {

    @get:Rule
    val rule = createComposeRule()

    private var requests = 0
    private var settings = 0

    private fun show(status: CameraPermissionStatus) {
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    CameraPermissionContent(status, onRequestPermission = { requests++ }, onOpenSettings = { settings++ })
                }
            }
        }
    }

    @Test
    fun `first ask explains and requests`() {
        show(CameraPermissionStatus.NotRequested)
        rule.onNodeWithText("Kamerazugriff benötigt").assertIsDisplayed()
        rule.onNodeWithText("Um QR-Codes zu scannen", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Kamera erlauben").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, requests)
        rule.onNodeWithText("Einstellungen öffnen").assertDoesNotExist()
    }

    @Test
    fun `denied shows the rationale`() {
        show(CameraPermissionStatus.Denied)
        rule.onNodeWithText("Ohne Kamerazugriff", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Kamera erlauben").performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `denied forever links to the settings and can ask again`() {
        show(CameraPermissionStatus.PermanentlyDenied)
        rule.onNodeWithText("App-Einstellungen", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Einstellungen öffnen").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, settings)
        rule.onNodeWithText("Erneut fragen").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, requests)
        rule.onNodeWithText("Kamera erlauben").assertDoesNotExist()
    }

    @Test
    fun `unavailable panel retries`() {
        var retries = 0
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) { CameraUnavailableContent(onRetry = { retries++ }) }
            }
        }
        rule.onNodeWithText("Kamera nicht verfügbar").assertIsDisplayed()
        rule.onNodeWithText("Erneut versuchen").performClick()
        assertEquals(1, retries)
    }
}
