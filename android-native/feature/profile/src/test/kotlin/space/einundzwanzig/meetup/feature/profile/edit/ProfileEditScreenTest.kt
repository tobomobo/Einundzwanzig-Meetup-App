package space.einundzwanzig.meetup.feature.profile.edit

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.feature.profile.bunker.BunkerAction
import space.einundzwanzig.meetup.feature.profile.bunker.BunkerSheetContent
import space.einundzwanzig.meetup.feature.profile.bunker.BunkerSheetState
import space.einundzwanzig.meetup.feature.profile.bunker.BunkerStep
import space.einundzwanzig.meetup.feature.profile.bunker.BunkerTags
import space.einundzwanzig.meetup.feature.profile.common.UiText
import space.einundzwanzig.meetup.feature.profile.testing.NPUB_A
import space.einundzwanzig.meetup.feature.profile.testing.meetup

@RunWith(AndroidJUnit4::class)
class ProfileEditScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = mutableListOf<ProfileEditAction>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val loaded = ProfileEditUiState(isLoading = false, nickname = "Satoshi", savedNickname = "Satoshi")

    private fun show(state: ProfileEditUiState) {
        rule.setContent { EinundzwanzigTheme { ProfileEditScreen(state = state, onAction = { actions += it }) } }
    }

    @Test
    fun `loading shows the indicator only`() {
        show(ProfileEditUiState(isLoading = true))
        rule.onNodeWithTag(ProfileEditTags.LOADING).assertExists()
        rule.onNodeWithTag(ProfileEditTags.CONTENT).assertDoesNotExist()
    }

    @Test
    fun `load error offers a retry`() {
        show(ProfileEditUiState(isLoading = false, loadError = "kaputt"))
        rule.onNodeWithText(str(L10nR.string.errorGeneric, "kaputt")).assertExists()
        rule.onNodeWithText(str(L10nR.string.actionRetry)).performClick()
        assertEquals(listOf<ProfileEditAction>(ProfileEditAction.Retry), actions)
    }

    @Test
    fun `edit form without key shows the no-key card and dispatches its actions`() {
        show(loaded)
        rule.onNodeWithText(str(L10nR.string.profileEditTitle)).assertExists()
        rule.onNodeWithText(str(L10nR.string.profileNoKey)).assertExists()
        rule.onNodeWithTag(ProfileEditTags.CREATE_KEY).performScrollTo().performClick()
        rule.onNodeWithTag(ProfileEditTags.CONNECT_AMBER).performScrollTo().performClick()
        rule.onNodeWithTag(ProfileEditTags.CONNECT_BUNKER).performScrollTo().performClick()
        rule.onNodeWithTag(ProfileEditTags.IMPORT_NSEC).performScrollTo().performClick()
        rule.onNodeWithTag(ProfileEditTags.HOME_FIELD).performScrollTo().performClick()
        rule.onNodeWithTag(ProfileEditTags.SAVE).performScrollTo().performClick()
        assertEquals(
            listOf(
                ProfileEditAction.CreateKey,
                ProfileEditAction.ConnectAmber,
                ProfileEditAction.ConnectBunker,
                ProfileEditAction.OpenImportNsec,
                ProfileEditAction.OpenMeetupPicker,
                ProfileEditAction.Save,
            ),
            actions,
        )
    }

    @Test
    fun `typing the nickname dispatches changes`() {
        show(loaded.copy(nickname = ""))
        rule.onNodeWithTag(ProfileEditTags.NICKNAME).performTextInput("Neo")
        assertEquals(ProfileEditAction.NicknameChanged("Neo"), actions.last())
    }

    @Test
    fun `validation errors are shown after a save attempt`() {
        show(loaded.copy(nickname = "anon", showValidation = true, homeMissing = true))
        rule.onNodeWithText(str(L10nR.string.profileNicknameAnon)).assertExists()
        rule.onNodeWithText(str(L10nR.string.profileMeetupReq)).assertExists()
        rule.onNodeWithText(str(L10nR.string.profileChooseMeetup)).assertExists()
    }

    @Test
    fun `generating key disables creation and shows the progress label`() {
        show(loaded.copy(isGeneratingKey = true))
        rule.onNodeWithText(str(L10nR.string.profileCreating)).assertExists()
        rule.onNodeWithTag(ProfileEditTags.CREATE_KEY).assertIsNotEnabled()
        rule.onNodeWithTag(ProfileEditTags.CONNECT_BUNKER).assertIsNotEnabled()
    }

    @Test
    fun `local key card shows npub, nsec and export buttons`() {
        show(loaded.copy(hasLocalKey = true, npub = NPUB_A, canSign = true))
        rule.onNodeWithText(str(L10nR.string.profileKeyActiveCaps)).assertExists()
        rule.onNodeWithText(NPUB_A).assertExists()
        rule.onNodeWithTag(ProfileEditTags.NPUB_COPY).performScrollTo().performClick()
        rule.onNodeWithTag(ProfileEditTags.NSEC_SHOW).performScrollTo().performClick()
        rule.onNodeWithTag(ProfileEditTags.EXPORT).performScrollTo().performClick()
        rule.onNodeWithTag(ProfileEditTags.DISCONNECT).assertDoesNotExist()
        assertEquals(
            listOf(ProfileEditAction.CopyNpub, ProfileEditAction.ShowNsec, ProfileEditAction.ExportEncrypted),
            actions,
        )
    }

    @Test
    fun `bunker identity hides secrets and offers check and disconnect`() {
        show(loaded.copy(signingMode = SigningMode.NIP46, npub = NPUB_A, canSign = true))
        rule.onNodeWithTag(ProfileEditTags.NSEC_SHOW).assertDoesNotExist()
        rule.onNodeWithTag(ProfileEditTags.EXPORT).assertDoesNotExist()
        rule.onNodeWithText(str(L10nR.string.profileSwitchSignerHint)).assertExists()
        rule.onNodeWithTag(ProfileEditTags.CHECK_BUNKER).performScrollTo().performClick()
        rule.onNodeWithTag(ProfileEditTags.DISCONNECT).performScrollTo().performClick()
        assertEquals(listOf(ProfileEditAction.CheckBunker, ProfileEditAction.Disconnect), actions)
    }

    @Test
    fun `unusable signer shows the orange hint`() {
        show(loaded.copy(signingMode = SigningMode.NIP46, npub = NPUB_A, canSign = false))
        rule.onNodeWithText(str(L10nR.string.profileSignerUnusable)).assertExists()
    }

    @Test
    fun `identity tiles show the proof status and navigate`() {
        show(loaded.copy(platformProofCount = 2, humanityVerified = true))
        rule.onNodeWithText("2 Plattformen aktiv").assertExists()
        rule.onNodeWithText(str(L10nR.string.profileLightningActive)).assertExists()
        rule.onNodeWithTag(ProfileEditTags.TILE_PLATFORMS).performScrollTo().performClick()
        rule.onNodeWithTag(ProfileEditTags.TILE_HUMANITY).performScrollTo().performClick()
        assertEquals(listOf(ProfileEditAction.OpenPlatformProofs, ProfileEditAction.OpenHumanityProof), actions)
    }

    @Test
    fun `avatar is a labelled button that opens the picker`() {
        show(loaded)
        rule.onNodeWithContentDescription("Profilbild").performClick()
        assertEquals(listOf<ProfileEditAction>(ProfileEditAction.PickAvatar), actions)
    }

    @Test
    fun `read-only view shows the verified profile and the unlock warning`() {
        show(loaded.copy(isEditing = false, selectedHome = "107", signingMode = SigningMode.AMBER, npub = NPUB_A, canSign = true))
        rule.onNodeWithText(str(L10nR.string.profileTitle)).assertExists()
        rule.onNodeWithText(str(L10nR.string.profileVerified)).assertExists()
        rule.onNodeWithText("107").assertExists()
        rule.onNodeWithText("Nostr").assertExists()
        rule.onNodeWithTag(ProfileEditTags.SAVE).assertDoesNotExist()
        rule.onNodeWithTag(ProfileEditTags.DISCONNECT).assertExists()
        rule.onNodeWithTag(ProfileEditTags.EDIT_LOSE_STATUS).performScrollTo().performClick()
        assertEquals(listOf<ProfileEditAction>(ProfileEditAction.RequestEdit), actions)
    }

    @Test
    fun `unlock dialog confirms editing`() {
        show(loaded.copy(isEditing = false, dialog = ProfileDialog.UnlockWarning))
        rule.onNodeWithText(str(L10nR.string.profileWarning)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.profileEdit)).performClick()
        assertEquals(listOf<ProfileEditAction>(ProfileEditAction.ConfirmEdit), actions)
    }

    @Test
    fun `key education dialog confirms or cancels`() {
        show(loaded.copy(dialog = ProfileDialog.KeyEducation))
        rule.onNodeWithText(str(L10nR.string.keyEduTitle)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.keyEduUnderstood)).performClick()
        rule.onNodeWithText(str(L10nR.string.keyEduCancel)).performClick()
        assertEquals(
            listOf(ProfileEditAction.KeyEducationResult(true), ProfileEditAction.KeyEducationResult(false)),
            actions,
        )
    }

    @Test
    fun `nsec backup dialog shows the key and copies it`() {
        show(loaded.copy(dialog = ProfileDialog.NsecBackup("nsec1geheim")))
        rule.onNodeWithText(str(L10nR.string.profileSecureKey)).assertIsDisplayed()
        rule.onNodeWithText("nsec1geheim").assertExists()
        rule.onNodeWithText(str(L10nR.string.profileCopy)).performClick()
        rule.onNodeWithText(str(L10nR.string.profileKeySecured)).performClick()
        assertEquals(listOf(ProfileEditAction.NsecCopied, ProfileEditAction.DismissDialog), actions)
    }

    @Test
    fun `import dialog hands the typed nsec over`() {
        show(loaded.copy(dialog = ProfileDialog.ImportNsec))
        rule.onNodeWithTag(ProfileEditTags.NSEC_INPUT).performTextInput("nsec1abc")
        rule.onNodeWithText(str(L10nR.string.profileImport)).performClick()
        assertEquals(listOf<ProfileEditAction>(ProfileEditAction.ImportNsec("nsec1abc")), actions)
    }

    @Test
    fun `import button is disabled while the import runs`() {
        show(loaded.copy(dialog = ProfileDialog.ImportNsec, isImportingNsec = true))
        rule.onNodeWithTag(ProfileEditTags.NSEC_IMPORT).assertIsNotEnabled()
    }

    @Test
    fun `ncryptsec sheet from the vault offers copy and another password`() {
        show(loaded.copy(dialog = ProfileDialog.NcryptsecSheet("ncryptsec1vault", fromVault = true)))
        rule.onNodeWithText(str(L10nR.string.keyExportReadyTitle)).assertExists()
        rule.onNodeWithText(str(L10nR.string.keyExportFromVault)).assertExists()
        rule.onNodeWithText("ncryptsec1vault").assertExists()
        rule.onNodeWithTag(ProfileEditTags.NCRYPTSEC_OTHER_PASSWORD).performClick()
        rule.onNodeWithTag(ProfileEditTags.NCRYPTSEC_COPY).performClick()
        assertEquals(listOf(ProfileEditAction.ExportOtherPassword, ProfileEditAction.NcryptsecCopied), actions)
    }

    @Test
    fun `fresh ncryptsec sheet has no vault hint and no other-password button`() {
        show(loaded.copy(dialog = ProfileDialog.NcryptsecSheet("ncryptsec1new", fromVault = false)))
        rule.onNodeWithText("ncryptsec1new").assertExists()
        rule.onNodeWithText(str(L10nR.string.keyExportFromVault)).assertDoesNotExist()
        rule.onNodeWithTag(ProfileEditTags.NCRYPTSEC_OTHER_PASSWORD).assertDoesNotExist()
        rule.onNodeWithTag(ProfileEditTags.NCRYPTSEC_COPY).performClick()
        assertEquals(listOf<ProfileEditAction>(ProfileEditAction.NcryptsecCopied), actions)
    }

    @Test
    fun `more favourites badge is localised and labelled`() {
        show(loaded.copy(selectedHome = "12", favorites = listOf("12", "107", "5")))
        rule.onNodeWithText("+2 ★").assertExists()
        rule.onNodeWithContentDescription("2 weitere Favoriten").assertExists()
    }

    @Test
    fun `export password dialog validates locally`() {
        show(loaded.copy(dialog = ProfileDialog.ExportPassword))
        rule.onNodeWithText(str(L10nR.string.keyExportAction)).performClick()
        rule.onNodeWithText(str(L10nR.string.backupPasswordEmpty)).assertExists()
        rule.onNodeWithTag(ProfileEditTags.PASSWORD).performTextInput("abc")
        rule.onNodeWithTag(ProfileEditTags.PASSWORD_CONFIRM).performTextInput("abd")
        rule.onNodeWithText(str(L10nR.string.keyExportAction)).performClick()
        rule.onNodeWithText(str(L10nR.string.keyExportMismatch)).assertExists()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `export password dialog returns a matching password`() {
        show(loaded.copy(dialog = ProfileDialog.ExportPassword))
        rule.onNodeWithTag(ProfileEditTags.PASSWORD).performTextInput("pw")
        rule.onNodeWithTag(ProfileEditTags.PASSWORD_CONFIRM).performTextInput("pw")
        rule.onNodeWithText(str(L10nR.string.keyExportAction)).performClick()
        assertEquals(listOf<ProfileEditAction>(ProfileEditAction.ExportPassword("pw")), actions)
    }

    @Test
    fun `switch signer and disconnect confirmations`() {
        show(loaded.copy(dialog = ProfileDialog.SwitchSignerConfirm(SignerTarget.BUNKER)))
        rule.onNodeWithText(str(L10nR.string.profileSwitchSignerContinue)).performClick()
        assertEquals(listOf<ProfileEditAction>(ProfileEditAction.SwitchSignerConfirmed(SignerTarget.BUNKER)), actions)
    }

    @Test
    fun `amber missing dialog explains the install`() {
        show(loaded.copy(dialog = ProfileDialog.AmberNotFound))
        rule.onNodeWithText(str(L10nR.string.profileAmberNotFound)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.actionOk)).performClick()
        assertEquals(listOf<ProfileEditAction>(ProfileEditAction.DismissDialog), actions)
    }

    @Test
    fun `meetup picker toggles favourites and returns them in order`() {
        val list = listOf(meetup("1", "Berlin", "Einundzwanzig Berlin Mitte"), meetup("2", "Berlin", "Einundzwanzig Berlin Ost"), meetup("3", "Wien"))
        show(loaded.copy(meetups = list, favorites = listOf("3"), dialog = ProfileDialog.MeetupPicker))
        rule.onNodeWithText("1 ★").assertExists()
        rule.onNodeWithTag(ProfileEditTags.meetupRow("2")).performClick()
        rule.onNodeWithTag(ProfileEditTags.MEETUP_DONE).performClick()
        assertEquals(listOf<ProfileEditAction>(ProfileEditAction.MeetupPickerDone(listOf("3", "2"))), actions)
    }

    @Test
    fun `bunker sheet choose and paste steps`() {
        val bunkerActions = mutableListOf<BunkerAction>()
        var state by androidx.compose.runtime.mutableStateOf(BunkerSheetState())
        rule.setContent { EinundzwanzigTheme { BunkerSheetContent(state, onAction = { bunkerActions += it }) } }
        rule.onNodeWithText(str(L10nR.string.bunkerTitle)).assertExists()
        rule.onNodeWithTag(BunkerTags.MODE_PASTE).performClick()
        rule.onNodeWithTag(BunkerTags.MODE_SIGNER).performClick()
        assertEquals(listOf(BunkerAction.ShowPaste, BunkerAction.StartSignerApp), bunkerActions)
        state = BunkerSheetState(step = BunkerStep.PASTE, error = UiText.Raw("Signer lehnt ab"))
        rule.onNodeWithTag(BunkerTags.ERROR).assertExists()
        rule.onNodeWithText("Signer lehnt ab").assertExists()
        rule.onNodeWithTag(BunkerTags.CONNECT).performClick()
        assertEquals(BunkerAction.ConnectPasted, bunkerActions.last())
        state = state.copy(busy = true)
        rule.onNodeWithTag(BunkerTags.CONNECT).assertIsNotEnabled()
        rule.onNodeWithTag(BunkerTags.WAITING).assertExists()
    }
}
