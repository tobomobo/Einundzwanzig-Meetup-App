package space.einundzwanzig.meetup.feature.badges

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
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
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.identity.review.AppReviewDemo
import space.einundzwanzig.meetup.data.issuance.session.NonceStatus
import space.einundzwanzig.meetup.data.issuance.session.NonceValidation
import space.einundzwanzig.meetup.feature.badges.scanner.BadgeScannerAction
import space.einundzwanzig.meetup.feature.badges.scanner.BadgeScannerScreen
import space.einundzwanzig.meetup.feature.badges.scanner.BadgeScannerTags
import space.einundzwanzig.meetup.feature.badges.scanner.BadgeScannerUiState
import space.einundzwanzig.meetup.feature.badges.scanner.ScanResultUi
import space.einundzwanzig.meetup.feature.badges.scanner.ScanStatus
import space.einundzwanzig.meetup.feature.badges.scanner.ScannerDialog
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class BadgeScannerScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<BadgeScannerAction>()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(state: BadgeScannerUiState) {
        rule.setContent { EinundzwanzigTheme { BadgeScannerScreen(state, onAction = { actions += it }) } }
    }

    @Test
    fun `scanner view shows title, instruction, QR button and ready status`() {
        show(BadgeScannerUiState())
        rule.onNodeWithText(s(L10nR.string.verifyScanInstruction)).assertExists()
        rule.onNodeWithText(s(L10nR.string.verifyScanQrCaps)).assertExists()
        rule.onNodeWithText(s(L10nR.string.verifyReadyToScan)).assertExists()
        rule.onNodeWithTag(BadgeScannerTags.SCAN_NFC).assertDoesNotExist()
        rule.onNodeWithTag(BadgeScannerTags.SCAN_QR).performScrollTo().performClick()
        assertEquals(listOf<BadgeScannerAction>(BadgeScannerAction.ScanQrClicked), actions)
    }

    @Test
    fun `nfc button appears behind the flag`() {
        show(BadgeScannerUiState(nfcEnabled = true))
        rule.onNodeWithTag(BadgeScannerTags.SCAN_NFC).performScrollTo().performClick()
        assertEquals(listOf<BadgeScannerAction>(BadgeScannerAction.ScanNfcClicked), actions)
    }

    @Test
    fun `self scan status uses the exact concatenation`() {
        show(BadgeScannerUiState(status = ScanStatus.SelfScan))
        val text = "✗ " + s(L10nR.string.verifyCantSelfBadge) + s(L10nR.string.verifyAskScan)
        rule.onNodeWithTag(BadgeScannerTags.STATUS).assertExists()
        rule.onNodeWithText(text).assertExists()
    }

    @Test
    fun `expired code status is localized with its age`() {
        show(BadgeScannerUiState(status = ScanStatus.NonceExpired(NonceValidation(false, NonceStatus.EXPIRED, 42, "x"))))
        val inner = s(R.string.native_nonce_expired, 42)
        rule.onNodeWithText(s(L10nR.string.verifyErrQrExpired, inner)).assertExists()
    }

    @Test
    fun `processing shows the checking indicator instead of the status`() {
        show(BadgeScannerUiState(processing = true))
        rule.onNodeWithText(s(R.string.native_scanner_checking)).assertExists()
        rule.onNodeWithTag(BadgeScannerTags.STATUS).assertDoesNotExist()
    }

    @Test
    fun `found badge shows the confirmation text and adds to the wallet`() {
        val outcome = found()
        show(BadgeScannerUiState(result = ScanResultUi.Found(outcome.pending, outcome.info)))
        rule.onNodeWithTag(BadgeScannerTags.CONFIRMATION).assertExists()
        rule.onNodeWithText(s(L10nR.string.verifyMsgLocation, "Würzburg, DE"), substring = true).assertExists()
        rule.onNodeWithText(s(L10nR.string.mvKnownOrganizer, "Satoshi"), substring = true).assertExists()
        rule.onNodeWithText(s(L10nR.string.dialogCancel)).assertExists()
        rule.onNodeWithTag(BadgeScannerTags.ADD).performScrollTo().performClick()
        rule.onNodeWithTag(BadgeScannerTags.CLOSE).performScrollTo().performClick()
        assertEquals(listOf(BadgeScannerAction.AddToWallet, BadgeScannerAction.Close), actions)
    }

    @Test
    fun `already collected offers the wallet and close`() {
        show(BadgeScannerUiState(result = ScanResultUi.AlreadyCollected("Würzburg, DE", isEvent = false)))
        rule.onNodeWithText(s(L10nR.string.verifyAlreadyCollected)).assertExists()
        rule.onNodeWithText(s(L10nR.string.verifyAlreadyToday, "Würzburg, DE")).assertExists()
        rule.onNodeWithText(s(L10nR.string.verifyClose)).assertExists()
        rule.onNodeWithTag(BadgeScannerTags.OPEN_WALLET).performScrollTo().performClick()
        assertEquals(listOf<BadgeScannerAction>(BadgeScannerAction.OpenWallet), actions)
    }

    @Test
    fun `event already collected uses the event text`() {
        show(BadgeScannerUiState(result = ScanResultUi.AlreadyCollected("Bitcoin Konferenz", isEvent = true)))
        rule.onNodeWithText(s(L10nR.string.verifyAlreadyEvent, "Bitcoin Konferenz")).assertExists()
    }

    @Test
    fun `kept aside shows the aside text`() {
        show(BadgeScannerUiState(result = ScanResultUi.KeptAside("Würzburg, DE")))
        rule.onNodeWithText(s(L10nR.string.badgesHeldAside)).assertExists()
        rule.onNodeWithTag(BadgeScannerTags.OPEN_WALLET).assertExists()
    }

    @Test
    fun `too far dialog shows distance and applied radius`() {
        show(BadgeScannerUiState(result = ScanResultUi.Found(found().pending, foundInfo()), dialog = ScannerDialog.TooFar(73.46, 50.0)))
        rule.onNodeWithText(s(L10nR.string.gpsTooFar)).assertExists()
        rule.onNodeWithText(s(L10nR.string.gpsTooFarSub, "73.5", "50")).assertExists()
        rule.onNodeWithText(s(L10nR.string.actionOk)).performClick()
        assertEquals(listOf<BadgeScannerAction>(BadgeScannerAction.DismissDialog), actions)
    }

    @Test
    fun `co-attendance opt-in offers yes and no`() {
        show(BadgeScannerUiState(dialog = ScannerDialog.CoAttendanceOptIn(badge())))
        rule.onNodeWithText(s(L10nR.string.caOptInTitle)).assertExists()
        rule.onNodeWithText(s(L10nR.string.caOptInPrivacy)).assertExists()
        rule.onNodeWithText(s(L10nR.string.caOptInYes)).performClick()
        rule.onNodeWithText(s(L10nR.string.caOptInNo)).performClick()
        assertEquals(
            listOf<BadgeScannerAction>(
                BadgeScannerAction.CoAttendanceAnswered(true),
                BadgeScannerAction.CoAttendanceAnswered(false),
            ),
            actions,
        )
    }

    @Test
    fun `review demo dialog is English`() {
        show(BadgeScannerUiState(dialog = ScannerDialog.ReviewDemo))
        rule.onNodeWithText(AppReviewDemo.SUCCESS_TITLE).assertExists()
        rule.onNodeWithText(AppReviewDemo.SUCCESS_BODY).assertExists()
    }

    @Test
    fun `nfc unavailable dialog offers the QR scanner`() {
        show(BadgeScannerUiState(nfcEnabled = true, dialog = ScannerDialog.NfcUnavailable))
        rule.onNodeWithText(s(L10nR.string.nfcUnavailable)).assertExists()
        rule.onNodeWithText(
            s(L10nR.string.verifyNoNfcLong) + s(L10nR.string.verifyUseQrInstead) + s(L10nR.string.verifyToGetBadge),
        ).assertExists()
        // Two "QR-CODE SCANNEN" labels exist (button + dialog); the dialog one is the last.
        rule.onNodeWithText(s(L10nR.string.nfcUnavailable)).assertIsDisplayed()
    }

    @Test
    fun `saving shows the blocking indicator`() {
        show(BadgeScannerUiState(result = ScanResultUi.Found(found().pending, foundInfo()), saving = true))
        rule.onNodeWithContentDescription(s(R.string.native_scanner_locating)).assertExists()
    }

    @Test
    fun `back closes`() {
        show(BadgeScannerUiState())
        rule.onNodeWithContentDescription(s(L10nR.string.actionBack)).performClick()
        assertEquals(listOf<BadgeScannerAction>(BadgeScannerAction.Close), actions)
    }
}
