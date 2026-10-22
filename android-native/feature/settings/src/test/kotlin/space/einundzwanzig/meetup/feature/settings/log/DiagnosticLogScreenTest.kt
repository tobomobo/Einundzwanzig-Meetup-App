package space.einundzwanzig.meetup.feature.settings.log

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.feature.settings.R
import space.einundzwanzig.meetup.feature.settings.entry
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class DiagnosticLogScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<DiagnosticLogAction>()

    private fun show(state: DiagnosticLogUiState) {
        rule.setContent {
            EinundzwanzigTheme { DiagnosticLogScreen(state, SnackbarHostState()) { actions += it } }
        }
    }

    private val time = 1_760_000_000_000L

    private fun hhmmss(): String {
        val t = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
        return "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
    }

    @Test
    fun `renders terminal lines in the Flutter log screen format`() {
        show(DiagnosticLogUiState(entries = listOf(entry("ERROR", "Badge", "Signatur ungültig", time), entry("INFO", "System", "App 1.7.2", time))))
        rule.onNodeWithText("${hhmmss()}  ERROR  Badge: Signatur ungültig", substring = true).assertExists()
        rule.onNodeWithText("${hhmmss()}  INFO   System: App 1.7.2", substring = true).assertExists()
        rule.onNodeWithTag(DiagnosticLogTags.COUNT).assertTextEquals("2 Einträge")
        rule.onNodeWithText(context.getString(R.string.native_log_title)).assertExists()
    }

    @Test
    fun `the whole log is one selectable text, so a selection can span every line`() {
        show(DiagnosticLogUiState(entries = listOf(entry("WARN", "Chat", "a", time), entry("SEC", "Signer", "b", time))))
        rule.onNodeWithTag(DiagnosticLogTags.LIST).assertTextEquals("${hhmmss()}  WARN   Chat: a\n${hhmmss()}  SEC    Signer: b")
    }

    @Test
    fun `empty log shows Keine Eintraege and a zero count`() {
        show(DiagnosticLogUiState())
        rule.onNodeWithText("Keine Einträge").assertExists()
        rule.onNodeWithTag(DiagnosticLogTags.COUNT).assertTextEquals("0 Einträge")
    }

    @Test
    fun `app bar actions and clear button emit their actions`() {
        show(DiagnosticLogUiState(entries = listOf(entry("WARN"))))
        rule.onNodeWithTag(DiagnosticLogTags.PROBE).performClick()
        rule.onNodeWithTag(DiagnosticLogTags.FILTER).performClick()
        rule.onNodeWithTag(DiagnosticLogTags.VERBOSE).performClick()
        rule.onNodeWithTag(DiagnosticLogTags.COPY).performClick()
        rule.onNodeWithTag(DiagnosticLogTags.SHARE).performClick()
        rule.onNodeWithTag(DiagnosticLogTags.CLEAR).performClick()
        assertEquals(
            listOf(
                DiagnosticLogAction.Probe,
                DiagnosticLogAction.ToggleFilter,
                DiagnosticLogAction.ToggleVerbose,
                DiagnosticLogAction.Copy,
                DiagnosticLogAction.Share,
                DiagnosticLogAction.Clear,
            ),
            actions,
        )
    }

    @Test
    fun `probe button is disabled while probing`() {
        show(DiagnosticLogUiState(probing = true))
        rule.onNodeWithTag(DiagnosticLogTags.PROBE).assertIsNotEnabled()
        rule.onNodeWithContentDescription(context.getString(R.string.native_log_probe_running)).assertExists()
    }

    @Test
    fun `filter and verbose toggles reflect the state with German labels`() {
        show(DiagnosticLogUiState(onlyProblems = true, verbose = false))
        rule.onNodeWithTag(DiagnosticLogTags.FILTER).assertIsOn()
        rule.onNodeWithTag(DiagnosticLogTags.VERBOSE).assertIsOff()
        rule.onNodeWithContentDescription("Nur Warnungen/Fehler").assertExists()
        rule.onNodeWithContentDescription("Ausführliches Log einschalten (mehr Details für die Fehlersuche)").assertExists()
        rule.onNodeWithContentDescription("NIP-44-Verbindung prüfen (Freigabe im Signer eventuell nötig)").assertExists()
    }

    @Test
    fun `snackbar texts are the Flutter German ones`() {
        assertEquals("NIP-44-Probe erfolgreich.", context.getString(LogMessage.ProbeOk.res))
        assertEquals("NIP-44-Probe fehlgeschlagen — Ursache im Diagnose-Log.", context.getString(LogMessage.ProbeFailed.res))
        assertEquals("Ausführliches Log an — Fehler jetzt nachstellen, dann Log teilen.", context.getString(LogMessage.VerboseOn.res))
        assertEquals("Ausführliches Log aus.", context.getString(LogMessage.VerboseOff.res))
        assertEquals("Log kopiert", context.getString(LogMessage.Copied.res))
    }
}
