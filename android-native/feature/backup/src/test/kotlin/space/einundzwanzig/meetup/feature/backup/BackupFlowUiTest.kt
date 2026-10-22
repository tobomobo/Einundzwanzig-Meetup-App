package space.einundzwanzig.meetup.feature.backup

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.backup.RestoreConfirmation

/** End-to-end flows: the real coordinator driving the real dialogs (fakes below the coordinator). */
@RunWith(AndroidJUnit4::class)
class BackupFlowUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val h = Harness(scope, Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun mount() {
        h.coordinator.attach(h.platform)
        rule.setContent {
            val state by h.coordinator.ui.collectAsState()
            EinundzwanzigTheme { BackupDialogs(state, h.coordinator::onAction) }
        }
    }

    @Test
    fun `export through the dialog writes the chosen document`() {
        mount()
        var result: Boolean? = null
        h.coordinator.launchExport { result = it }
        rule.waitUntil { h.coordinator.ui.value.dialog != null }

        rule.onNodeWithTag(BackupTestTags.PASSWORD_FIELD).performTextInput("correct horse")
        rule.onNodeWithTag(BackupTestTags.CONFIRM_FIELD).performTextInput("correct horse")
        rule.onNodeWithTag(BackupTestTags.SUBMIT).performClick()

        rule.waitUntil { h.platform.created.isNotEmpty() }
        assertEquals(listOf("correct horse"), h.exporter.passwords)
        h.coordinator.onDocumentResult("content://backup/1")

        rule.waitUntil { result != null }
        assertEquals(true, result)
        assertEquals("enc_v2:a:b:c", h.documents.written["content://backup/1"])
        rule.onNodeWithTag(BackupTestTags.SUBMIT).assertDoesNotExist()
    }

    @Test
    fun `import of a foreign identity asks before replacing`() {
        h.documents.files["content://backup/2"] = VALID_BACKUP
        h.engine.confirmation = RestoreConfirmation.Replace("npub1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa11111111", "npub1bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb22222222")
        mount()
        var result: Boolean? = null
        h.coordinator.launchRestore { result = it }

        rule.waitUntil { h.platform.opened == 1 }
        h.coordinator.onDocumentResult("content://backup/2")

        rule.waitUntil { h.coordinator.ui.value.dialog != null }
        rule.onNodeWithText("npub1bbbbbbb...22222222", substring = true).assertExists()
        rule.onNodeWithText("Abbrechen").performClick()

        rule.waitUntil { result != null }
        assertEquals(false, result)
    }
}
