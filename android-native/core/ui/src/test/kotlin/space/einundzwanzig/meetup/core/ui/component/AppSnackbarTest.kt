package space.einundzwanzig.meetup.core.ui.component

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.BrandColors
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/** Port of `test/snackbar_theme_test.dart` plus the timed / dismissable snackbar helpers. */
@RunWith(AndroidJUnit4::class)
class AppSnackbarTest {

    @get:Rule
    val rule = createComposeRule()

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    @Test
    fun `snackbar text is readable on the dark card background`() {
        lateinit var colors: SnackbarToneColors
        rule.setContent { EinundzwanzigTheme { colors = AppSnackbarDefaults.colors(Tone.Neutral) } }
        assertEquals(BrandColors.TextPrimary, colors.content)
        assertEquals(BrandColors.Card, colors.container)
        assertTrue(contrast(colors.content, colors.container) >= 4.5f)
        // explicit card background, as in the second Dart case
        assertTrue(contrast(colors.content, BrandColors.Card) >= 4.5f)
    }

    @Test
    fun `every tone keeps at least 4_5 contrast`() {
        val all = mutableMapOf<Tone, SnackbarToneColors>()
        rule.setContent { EinundzwanzigTheme { Tone.entries.forEach { all[it] = AppSnackbarDefaults.colors(it) } } }
        all.forEach { (tone, c) -> assertTrue("$tone", contrast(c.content, c.container) >= 4.5f) }
    }

    private lateinit var scope: CoroutineScope
    private val host = SnackbarHostState()

    private fun setHost() {
        rule.setContent {
            EinundzwanzigTheme {
                scope = rememberCoroutineScope()
                AppSnackbarHost(host)
            }
        }
    }

    @Test
    fun `timed snackbar disappears after its duration`() {
        setHost()
        scope.launch { host.showAppSnackbar("npub kopiert", durationMillis = AppSnackbarDurations.Short) }
        rule.waitForIdle()
        rule.onNodeWithText("npub kopiert").assertExists()
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithText("npub kopiert").assertExists()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithText("npub kopiert").assertDoesNotExist()
    }

    @Test
    fun `long dismissable snackbar stays 10 seconds and can be closed`() {
        setHost()
        val message = "Fehler: Der Signer hat abgelehnt: Permission denied for sign_event kind:21003"
        scope.launch { host.showLongDismissable(message) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(9_000)
        rule.onNodeWithText(message).assertExists()
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        rule.onNodeWithText(message).assertDoesNotExist()

        scope.launch { host.showLongDismissable(message) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Schließen").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(message).assertDoesNotExist()
    }
}
