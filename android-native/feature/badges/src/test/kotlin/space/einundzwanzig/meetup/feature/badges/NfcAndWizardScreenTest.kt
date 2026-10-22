package space.einundzwanzig.meetup.feature.badges

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEventBus
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.feature.badges.camera.BadgeQrCameraRoute
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.camera.CameraPermissionContent
import space.einundzwanzig.meetup.core.camera.CameraPermissionStatus
import space.einundzwanzig.meetup.core.camera.CameraUnavailableContent
import space.einundzwanzig.meetup.core.camera.QrScannerTestTags
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.issuance.nfc.NfcWriteError
import space.einundzwanzig.meetup.feature.badges.camera.BadgeQrCameraScreen
import space.einundzwanzig.meetup.feature.badges.camera.BadgeQrCameraTags
import space.einundzwanzig.meetup.feature.badges.wizard.MeetupSessionWizardScreen
import space.einundzwanzig.meetup.feature.badges.wizard.MeetupSessionWizardTags
import space.einundzwanzig.meetup.feature.badges.writer.HomeMeetupInfo
import space.einundzwanzig.meetup.feature.badges.writer.NfcTagWriterAction
import space.einundzwanzig.meetup.feature.badges.writer.NfcTagWriterScreen
import space.einundzwanzig.meetup.feature.badges.writer.NfcTagWriterTags
import space.einundzwanzig.meetup.feature.badges.writer.NfcTagWriterUiState
import space.einundzwanzig.meetup.feature.badges.writer.WriterDialog
import space.einundzwanzig.meetup.feature.badges.writer.WriterStatus
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** NFC writer, session wizard and badge camera chrome. */
@RunWith(AndroidJUnit4::class)
class NfcAndWizardScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private val writerActions = mutableListOf<NfcTagWriterAction>()

    private fun showWriter(state: NfcTagWriterUiState) {
        rule.setContent { EinundzwanzigTheme { NfcTagWriterScreen(state, onAction = { writerActions += it }) } }
    }

    @Test
    fun `writer shows home meetup, size and validity boxes`() {
        showWriter(NfcTagWriterUiState(home = HomeMeetupInfo.Found("Würzburg", "DE"), estimatedSize = 312))
        rule.onNodeWithText(s(L10nR.string.writerCreateMeetupTag)).assertExists()
        rule.onNodeWithText(s(L10nR.string.writerMeetupTag)).assertExists()
        rule.onNodeWithText("📍 Würzburg, DE").assertExists()
        rule.onNodeWithText(s(L10nR.string.writerFitsNtag215, 312)).assertExists()
        rule.onNodeWithText(s(L10nR.string.writerValidity)).assertExists()
        rule.onNodeWithText(s(L10nR.string.writerReadyToWrite)).assertExists()
        rule.onNodeWithTag(NfcTagWriterTags.CREATE).performScrollTo().performClick()
        assertEquals(listOf<NfcTagWriterAction>(NfcTagWriterAction.CreateTag), writerActions)
    }

    @Test
    fun `writer without home meetup asks to select one`() {
        showWriter(NfcTagWriterUiState(home = HomeMeetupInfo.NoHome, status = WriterStatus.NoHomeMeetup))
        rule.onNodeWithText(s(L10nR.string.writerSelectHomeFirst)).assertExists()
        rule.onNodeWithText(s(L10nR.string.writerNoHomeMeetup)).assertExists()
    }

    @Test
    fun `writer errors carry the cross prefix`() {
        showWriter(
            NfcTagWriterUiState(
                home = HomeMeetupInfo.NotFound("999"),
                status = WriterStatus.TooSmall(320, 137),
            ),
        )
        rule.onNodeWithText("Meetup: 999").assertExists()
        val text = "❌ " + s(L10nR.string.writerTagTooSmall, 320, 137) + s(L10nR.string.writerUseNtag215)
        rule.onNodeWithText(text).assertExists()
    }

    @Test
    fun `writer maps a removed tag`() {
        showWriter(NfcTagWriterUiState(home = HomeMeetupInfo.NoHome, status = WriterStatus.WriteFailed(NfcWriteError.TAG_REMOVED_EARLY, "x")))
        rule.onNodeWithText("❌ " + s(L10nR.string.writerTagRemovedEarly)).assertExists()
    }

    @Test
    fun `writer success shows the summary`() {
        showWriter(NfcTagWriterUiState(success = true, status = WriterStatus.Written(301)))
        rule.onNodeWithText(s(L10nR.string.writerSuccess)).assertExists()
        val text = s(L10nR.string.writerTagWritten) + s(L10nR.string.writerCompactSize, 301) +
            s(L10nR.string.writerValidHours, 4) + s(L10nR.string.writerJumpToQr)
        rule.onNodeWithText(text).assertExists()
    }

    @Test
    fun `writer disabled dialog opens the settings`() {
        showWriter(NfcTagWriterUiState(home = HomeMeetupInfo.NoHome, dialog = WriterDialog.NFC_DISABLED))
        rule.onNodeWithText(s(L10nR.string.nfcDisabled)).assertExists()
        rule.onNodeWithText(s(L10nR.string.nfcOpenSettings)).performClick()
        assertEquals(listOf<NfcTagWriterAction>(NfcTagWriterAction.OpenNfcSettings), writerActions)
    }

    @Test
    fun `wizard offers writing a tag or skipping to the QR`() {
        val calls = mutableListOf<String>()
        rule.setContent {
            EinundzwanzigTheme {
                MeetupSessionWizardScreen(onBack = { calls += "back" }, onWriteTag = { calls += "write" }, onSkip = { calls += "skip" })
            }
        }
        rule.onNodeWithText(s(L10nR.string.mwStartMeetup)).assertExists()
        rule.onNodeWithText(s(L10nR.string.mwStep1Nfc)).assertExists()
        rule.onNodeWithText(s(L10nR.string.mwNfcIntro1) + s(L10nR.string.mwNfcIntro2)).assertExists()
        rule.onNodeWithTag(MeetupSessionWizardTags.WRITE).performScrollTo().performClick()
        rule.onNodeWithTag(MeetupSessionWizardTags.SKIP).performScrollTo().performClick()
        rule.onNodeWithContentDescription(s(L10nR.string.actionBack)).performClick()
        assertEquals(listOf("write", "skip", "back"), calls)
    }

    @Test
    fun `badge camera shows title, instruction and gallery action`() {
        var gallery = 0
        rule.setContent {
            EinundzwanzigTheme { BadgeQrCameraScreen(onBack = {}, onPickImage = { gallery++ }) { Box {} } }
        }
        rule.onNodeWithText(s(L10nR.string.verifyScanQr)).assertExists()
        rule.onNodeWithTag(BadgeQrCameraTags.INSTRUCTION).assertExists()
        rule.onNodeWithText(s(L10nR.string.verifyScanQrInstruction)).assertExists()
        rule.onNodeWithContentDescription(s(L10nR.string.qrLoadFromGallery)).performClick()
        assertEquals(1, gallery)
    }

    @Test
    fun `writer cannot start while the home meetup is still loading`() {
        showWriter(NfcTagWriterUiState(home = HomeMeetupInfo.Loading))
        rule.onNodeWithTag(NfcTagWriterTags.CREATE).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `badge camera without permission shows the permission panel without the instruction box`() {
        var requests = 0
        rule.setContent {
            EinundzwanzigTheme {
                BadgeQrCameraScreen(onBack = {}, onPickImage = {}, showInstruction = false) {
                    CameraPermissionContent(CameraPermissionStatus.Denied, onRequestPermission = { requests++ }, onOpenSettings = {})
                }
            }
        }
        rule.onNodeWithTag(QrScannerTestTags.Permission).assertExists()
        rule.onNodeWithTag(BadgeQrCameraTags.INSTRUCTION).assertDoesNotExist()
        rule.onNodeWithText(s(L10nR.string.verifyScanQr)).assertExists()
        rule.onNodeWithText(s(space.einundzwanzig.meetup.core.camera.R.string.native_camera_permission_grant)).performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `badge camera unavailable shows the retry panel without the instruction box`() {
        var retries = 0
        rule.setContent {
            EinundzwanzigTheme {
                BadgeQrCameraScreen(onBack = {}, onPickImage = {}, showInstruction = false) {
                    CameraUnavailableContent(onRetry = { retries++ })
                }
            }
        }
        rule.onNodeWithTag(QrScannerTestTags.Unavailable).assertExists()
        rule.onNodeWithTag(BadgeQrCameraTags.INSTRUCTION).assertDoesNotExist()
        rule.onNodeWithText(s(space.einundzwanzig.meetup.core.camera.R.string.native_camera_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `badge camera route hides the instruction until the permission is granted`() {
        // Robolectric grants no camera permission: the real route shows the core:camera panel.
        rule.setContent {
            CompositionLocalProvider(
                LocalNavigator provides RecordingNavigator(),
                LocalResultEventBus provides remember { ResultEventBus() },
            ) { EinundzwanzigTheme { BadgeQrCameraRoute() } }
        }
        rule.onNodeWithTag(QrScannerTestTags.Permission).assertExists()
        rule.onNodeWithTag(BadgeQrCameraTags.INSTRUCTION).assertDoesNotExist()
    }
}
