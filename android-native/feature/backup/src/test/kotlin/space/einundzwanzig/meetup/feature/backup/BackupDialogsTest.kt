package space.einundzwanzig.meetup.feature.backup

import androidx.activity.ComponentDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowDialog
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

@RunWith(AndroidJUnit4::class)
class BackupDialogsTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = mutableListOf<BackupAction>()

    private fun show(state: BackupUiState) {
        rule.setContent { EinundzwanzigTheme { BackupDialogs(state) { actions += it } } }
    }

    @Test
    fun `export dialog enables the button only when both rules pass`() {
        show(BackupUiState(dialog = BackupDialog.Password(isExport = true)))
        rule.onNodeWithText("Backup verschlüsseln").assertIsDisplayed()
        rule.onNodeWithText("Mindestens 8 Zeichen", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Beide Eingaben stimmen überein").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(BackupTestTags.SUBMIT).assertIsNotEnabled()

        rule.onNodeWithTag(BackupTestTags.PASSWORD_FIELD).performTextInput("short")
        rule.onNodeWithTag(BackupTestTags.CONFIRM_FIELD).performTextInput("short")
        rule.onNodeWithTag(BackupTestTags.SUBMIT).assertIsNotEnabled()

        rule.onNodeWithTag(BackupTestTags.PASSWORD_FIELD).performTextInput("-but-long")
        rule.onNodeWithTag(BackupTestTags.SUBMIT).assertIsNotEnabled() // no match yet
        rule.onNodeWithTag(BackupTestTags.CONFIRM_FIELD).performTextInput("-but-long")
        rule.onNodeWithTag(BackupTestTags.SUBMIT).assertIsEnabled().performClick()

        assertEquals(listOf<BackupAction>(BackupAction.SubmitPassword("short-but-long")), actions)
    }

    @Test
    fun `cancel returns CancelPassword`() {
        show(BackupUiState(dialog = BackupDialog.Password(isExport = true)))
        rule.onNodeWithText("Abbrechen").performClick()
        assertEquals(listOf<BackupAction>(BackupAction.CancelPassword), actions)
    }

    @Test
    fun `eye toggle switches its description`() {
        show(BackupUiState(dialog = BackupDialog.Password(isExport = false)))
        rule.onNodeWithContentDescription("Passwort anzeigen").performClick()
        rule.onNodeWithContentDescription("Passwort verbergen").assertIsDisplayed()
    }

    @Test
    fun `import dialog shows an inline error for an empty password`() {
        show(BackupUiState(dialog = BackupDialog.Password(isExport = false)))
        rule.onNodeWithText("Backup entschlüsseln").assertIsDisplayed()
        rule.onNodeWithTag(BackupTestTags.CONFIRM_FIELD).assertDoesNotExist()
        rule.onNodeWithTag(BackupTestTags.SUBMIT).assertIsEnabled().performClick()
        rule.onNodeWithText("Passwort darf nicht leer sein").assertIsDisplayed()
        assertTrue(actions.isEmpty())

        rule.onNodeWithTag(BackupTestTags.PASSWORD_FIELD).performTextInput("abc")
        rule.onNodeWithText("Passwort darf nicht leer sein").assertDoesNotExist()
        rule.onNodeWithText("Entschlüsseln & Laden").performClick()
        assertEquals(listOf<BackupAction>(BackupAction.SubmitPassword("abc")), actions)
    }

    @Test
    fun `replace dialog names both identities`() {
        show(BackupUiState(dialog = BackupDialog.ConfirmReplace("npub1old...11111111", "npub1new...22222222")))
        rule.onNodeWithText("Backup ersetzt diese Identität").assertIsDisplayed()
        rule.onNodeWithText("npub1old...11111111", substring = true).assertIsDisplayed()
        rule.onNodeWithText("npub1new...22222222", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Ersetzen").performClick()
        rule.onNodeWithText("Abbrechen").performClick()
        assertEquals(listOf(BackupAction.Confirm, BackupAction.Decline), actions)
    }

    @Test
    fun `add-unknown dialog confirms`() {
        show(BackupUiState(dialog = BackupDialog.ConfirmAddUnknown("npub1me...33333333")))
        rule.onNodeWithText("Backup ohne Identität").assertIsDisplayed()
        rule.onNodeWithText("npub1me...33333333", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Hinzufügen").performClick()
        assertEquals(listOf<BackupAction>(BackupAction.Confirm), actions)
    }

    @Test
    fun `overlay follows the loading flag`() {
        var state by mutableStateOf(BackupUiState(loading = true))
        rule.setContent { EinundzwanzigTheme { BackupDialogs(state) {} } }
        rule.onNodeWithContentDescription("Backup wird verarbeitet …").assertExists()
        state = BackupUiState(loading = false)
        rule.onNodeWithContentDescription("Backup wird verarbeitet …").assertDoesNotExist()
    }

    @Test
    fun `export dialog focuses the password field`() {
        show(BackupUiState(dialog = BackupDialog.Password(isExport = true)))
        rule.waitForIdle()
        rule.onNodeWithTag(BackupTestTags.PASSWORD_FIELD).assertIsFocused()
        rule.onNodeWithTag(BackupTestTags.CONFIRM_FIELD).assertIsNotFocused()
    }

    @Test
    fun `import dialog focuses the password field`() {
        show(BackupUiState(dialog = BackupDialog.Password(isExport = false)))
        rule.waitForIdle()
        rule.onNodeWithTag(BackupTestTags.PASSWORD_FIELD).assertIsFocused()
    }

    @Test
    fun `back on the replace dialog declines`() {
        show(BackupUiState(dialog = BackupDialog.ConfirmReplace("npub1aaaaaaa...11111111", "npub1bbbbbbb...22222222")))
        pressBackOnDialog()
        assertEquals(listOf<BackupAction>(BackupAction.Decline), actions)
    }

    @Test
    fun `back on the add-unknown dialog declines`() {
        show(BackupUiState(dialog = BackupDialog.ConfirmAddUnknown("npub1aaaaaaa...11111111")))
        pressBackOnDialog()
        assertEquals(listOf<BackupAction>(BackupAction.Decline), actions)
    }

    private fun pressBackOnDialog() {
        rule.waitForIdle()
        rule.runOnUiThread {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        rule.waitForIdle()
    }
}
