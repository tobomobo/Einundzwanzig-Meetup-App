package space.einundzwanzig.meetup.feature.reputation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.proofs.platform.ProofVerifyResult
import space.einundzwanzig.meetup.data.proofs.platform.VerifyLevel
import space.einundzwanzig.meetup.data.proofs.platform.VerifyMessage
import space.einundzwanzig.meetup.data.reputation.publish.PublishResult
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQr
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrCodec
import space.einundzwanzig.meetup.data.socialgraph.coattendance.PresenceCheck
import space.einundzwanzig.meetup.data.trust.layers.LayersInput
import space.einundzwanzig.meetup.feature.reputation.card.ReputationCardAction
import space.einundzwanzig.meetup.feature.reputation.card.ReputationCardInfo
import space.einundzwanzig.meetup.feature.reputation.card.ReputationCardScreen
import space.einundzwanzig.meetup.feature.reputation.card.ReputationCardTags
import space.einundzwanzig.meetup.feature.reputation.card.ReputationCardUiState
import space.einundzwanzig.meetup.feature.reputation.common.LayerLoad
import space.einundzwanzig.meetup.feature.reputation.common.LiveLayers
import space.einundzwanzig.meetup.feature.reputation.hub.TrustNetworkHubAction
import space.einundzwanzig.meetup.feature.reputation.hub.TrustNetworkHubScreen
import space.einundzwanzig.meetup.feature.reputation.hub.TrustNetworkHubTags
import space.einundzwanzig.meetup.feature.reputation.person.VerifyPersonAction
import space.einundzwanzig.meetup.feature.reputation.person.VerifyPersonError
import space.einundzwanzig.meetup.feature.reputation.person.VerifyPersonScreen
import space.einundzwanzig.meetup.feature.reputation.person.VerifyPersonTags
import space.einundzwanzig.meetup.feature.reputation.person.VerifyPersonUiState
import space.einundzwanzig.meetup.feature.reputation.qr.ReputationQrAction
import space.einundzwanzig.meetup.feature.reputation.qr.ReputationQrContent
import space.einundzwanzig.meetup.feature.reputation.qr.ReputationQrScreen
import space.einundzwanzig.meetup.feature.reputation.qr.ReputationQrTags
import space.einundzwanzig.meetup.feature.reputation.qr.ReputationQrUiState
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScanResultScreen
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScanResultUiState
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScannerAction
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScannerScreen
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScannerTags
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScannerUiState
import space.einundzwanzig.meetup.feature.reputation.scan.ScanResultTags
import space.einundzwanzig.meetup.feature.reputation.verify.LayersTags
import space.einundzwanzig.meetup.feature.reputation.verify.ReputationLayers
import space.einundzwanzig.meetup.feature.reputation.verify.ReputationVerifyAction
import space.einundzwanzig.meetup.feature.reputation.verify.ReputationVerifyScreen
import space.einundzwanzig.meetup.feature.reputation.verify.ReputationVerifyTags
import space.einundzwanzig.meetup.feature.reputation.verify.ReputationVerifyUiState
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class ReputationScreensUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { EinundzwanzigTheme { content() } }
    }

    private val qrContent = ReputationQrContent(
        nickname = "Satoshi",
        score = 12.3,
        level = "AKTIV",
        qrData = "21:abc",
        isSigned = true,
        verifiedBadgeCount = 4,
        totalBadges = 4,
        uniqueMeetups = 3,
        uniqueSigners = 2,
        boundBadgeCount = 2,
        countedBadgeCount = 4,
        organizerMeetupCount = 1,
        proofStatus = ReputationQr.ProofStatus.PARTLY_BOUND,
    )

    // --- ReputationQr (spec 04 §2.1) ---

    @Test
    fun `qr empty view shows the no-badges text and scan button`() {
        val actions = mutableListOf<ReputationQrAction>()
        show { ReputationQrScreen(ReputationQrUiState(isLoading = false), onAction = { actions += it }, onBack = {}) }
        rule.onNodeWithText(s(L10nR.string.reputationNoBadges)).assertIsDisplayed()
        rule.onNodeWithText(s(L10nR.string.reputationScanQr)).performClick()
        assertEquals(listOf<ReputationQrAction>(ReputationQrAction.OpenScanner), actions)
    }

    @Test
    fun `qr content shows card, warning and triggers share and publish`() {
        val actions = mutableListOf<ReputationQrAction>()
        val state = ReputationQrUiState(isLoading = false, hasIdentity = false, content = qrContent)
        show { ReputationQrScreen(state, onAction = { actions += it }, onBack = {}) }
        rule.onNodeWithTag(ReputationQrTags.CARD).assertExists()
        rule.onNodeWithText(s(L10nR.string.reputationNoIdentity)).assertExists()
        rule.onNodeWithTag(ReputationQrTags.SHARE_BUTTON).performScrollTo().performClick()
        rule.onNodeWithTag(ReputationQrTags.PUBLISH_BUTTON).performScrollTo().performClick()
        rule.onNodeWithTag(ReputationQrTags.OPEN_CARD).performClick()
        assertEquals(
            listOf(ReputationQrAction.ShareImage, ReputationQrAction.Publish, ReputationQrAction.OpenCard),
            actions,
        )
    }

    @Test
    fun `qr publishing disables the button and shows the last result`() {
        val publishing = ReputationQrUiState(isLoading = false, hasIdentity = true, content = qrContent, isPublishing = true)
        show { ReputationQrScreen(publishing, onAction = {}, onBack = {}) }
        rule.onNodeWithText(s(L10nR.string.reputationPublishing)).performScrollTo().assertExists()
        rule.onNodeWithTag(ReputationQrTags.PUBLISH_BUTTON).assertIsNotEnabled()
        rule.onNodeWithText(s(L10nR.string.reputationNoIdentity)).assertDoesNotExist()
    }

    @Test
    fun `qr publish info line shows the localized result`() {
        val state = ReputationQrUiState(isLoading = false, hasIdentity = true, content = qrContent, lastPublish = PublishResult.UpToDate)
        show { ReputationQrScreen(state, onAction = {}, onBack = {}) }
        rule.onNodeWithTag(ReputationQrTags.PUBLISH_INFO).performScrollTo().assertExists()
        rule.onNodeWithText(s(R.string.native_repPublishUpToDate)).assertExists()
    }

    @Test
    fun `qr share button shows progress and is disabled while sharing`() {
        val actions = mutableListOf<ReputationQrAction>()
        val state = ReputationQrUiState(isLoading = false, hasIdentity = true, content = qrContent)
        show { ReputationQrScreen(state, onAction = { actions += it }, onBack = {}, isSharing = true) }
        rule.onNodeWithTag(ReputationQrTags.SHARE_BUTTON).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(ReputationQrTags.SHARE_PROGRESS, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(ReputationQrTags.SHARE_BUTTON).performClick()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `qr card action is disabled while badges are unreadable`() {
        show { ReputationQrScreen(ReputationQrUiState(isLoading = false, badgesUnreadable = true), onAction = {}, onBack = {}) }
        rule.onNodeWithTag(ReputationQrTags.OPEN_CARD).assertIsNotEnabled()
        rule.onNodeWithText(s(L10nR.string.badgesUnreadableBody)).assertExists()
    }

    // --- ReputationCard (spec 04 §2.2) ---

    private val cardInfo = ReputationCardInfo(
        displayName = "Satoshi",
        score = 12.3,
        level = "AKTIV",
        totalBadges = 4,
        uniqueMeetups = 3,
        uniqueCities = 2,
        uniqueSigners = 2,
        accountAgeDays = 45,
    )

    @Test
    fun `card shows score and shares`() {
        val actions = mutableListOf<ReputationCardAction>()
        show {
            ReputationCardScreen(ReputationCardUiState(isLoading = false, info = cardInfo), isSharing = false, onAction = { actions += it }, onBack = {})
        }
        rule.onNodeWithTag(ReputationCardTags.CARD).assertExists()
        rule.onNodeWithText("12.3").assertExists()
        rule.onNodeWithText("Satoshi").assertExists()
        rule.onNodeWithTag(ReputationCardTags.SHARE_BUTTON).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf<ReputationCardAction>(ReputationCardAction.Share), actions)
    }

    @Test
    fun `card without badges shows the no-data box and a sharing label`() {
        show {
            ReputationCardScreen(
                ReputationCardUiState(isLoading = false, info = cardInfo.copy(totalBadges = 0, displayName = null)),
                isSharing = true,
                onAction = {},
                onBack = {},
            )
        }
        rule.onNodeWithText(s(L10nR.string.rcNoData)).assertExists()
        rule.onNodeWithText(s(R.string.native_rcNoDataHint)).assertExists()
        rule.onNodeWithTag(ReputationCardTags.SHARE_BUTTON).performScrollTo().assertIsNotEnabled()
    }

    // --- Scanner + result (spec 04 §2.3) ---

    @Test
    fun `scanner gallery button picks an image`() {
        val actions = mutableListOf<ReputationScannerAction>()
        show { ReputationScannerScreen(ReputationScannerUiState(), onAction = { actions += it }, onBack = {}) }
        rule.onNodeWithTag(ReputationScannerTags.HINT).assertExists()
        rule.onNodeWithTag(ReputationScannerTags.GALLERY_BUTTON).performClick()
        assertEquals(listOf<ReputationScannerAction>(ReputationScannerAction.PickFromGallery), actions)
    }

    @Test
    fun `scanner shows the demo dialog`() {
        show { ReputationScannerScreen(ReputationScannerUiState(isScanned = true, showDemoDialog = true), onAction = {}, onBack = {}) }
        rule.onNodeWithTag(ReputationScannerTags.DEMO_DIALOG).assertExists()
        rule.onNodeWithText("Demo scan OK").assertExists()
    }

    @Test
    fun `scan result of the signed vector is verified and back pops`() {
        var back = 0
        show { ReputationScanResultScreen(ReputationScanResultUiState(ReputationQrCodec.parse(Vectors.qrString)), onBack = { back++ }) }
        rule.onNodeWithTag(ScanResultTags.HEADER).assert(hasText(s(L10nR.string.qrVerified)))
        rule.onNodeWithTag(ScanResultTags.MISMATCH).assertDoesNotExist()
        rule.onNodeWithTag(ScanResultTags.BACK).performScrollTo().performClick()
        assertEquals(1, back)
    }

    @Test
    fun `scan result of a tampered code shows the invalid header`() {
        show { ReputationScanResultScreen(ReputationScanResultUiState(ReputationQrCodec.parse(Vectors.tamperedQrString)), onBack = {}) }
        rule.onNodeWithText(s(L10nR.string.qrSigInvalid), substring = true, ignoreCase = true).assertExists()
        rule.onNodeWithText(s(L10nR.string.qrVerified), ignoreCase = true).assertDoesNotExist()
        // Flutter `_showFailed` passes version 0: no version chip on a failed result.
        rule.onNodeWithText("v3").assertDoesNotExist()
        rule.onNodeWithText("v2").assertDoesNotExist()
    }

    @Test
    fun `scan result shows the loading row while live layers load`() {
        val state = ReputationScanResultUiState(
            ReputationQrCodec.parse(Vectors.qrString),
            layers = LiveLayers(pending = setOf(LayerLoad.NIP05)),
        )
        show { ReputationScanResultScreen(state, onBack = {}) }
        rule.onNodeWithTag(ScanResultTags.LAYERS_LOADING).performScrollTo().assertExists()
        rule.onNodeWithText(s(L10nR.string.qrCheckingNip05)).assertExists()
    }

    // --- ReputationVerify (spec 04 §2.4) + layers (§2.5) ---

    @Test
    fun `verify button sends verify and the result renders with layers`() {
        val actions = mutableListOf<ReputationVerifyAction>()
        val state = ReputationVerifyUiState(
            input = OTHER_NPUB,
            result = ProofVerifyResult(VerifyLevel.PARTIAL, VerifyMessage.NpubWithBindings, npub = OTHER_NPUB, reputation = reputationEvent()),
        )
        show { ReputationVerifyScreen(state, onAction = { actions += it }, onPaste = {}, onBack = {}) }
        rule.onNodeWithTag(ReputationVerifyTags.VERIFY).performClick()
        assertEquals(listOf<ReputationVerifyAction>(ReputationVerifyAction.Verify), actions)
        rule.onNodeWithTag(ReputationVerifyTags.RESULT).performScrollTo().assertExists()
        rule.onNodeWithText(s(L10nR.string.rvPartiallyVerified), substring = true, ignoreCase = true).assertExists()
        rule.onNodeWithText("Hal").assertExists()
        rule.onNodeWithTag(LayersTags.SIGNAL).performScrollTo().assertExists()
    }

    @Test
    fun `format only verify result does not claim a checked signature`() {
        val state = ReputationVerifyUiState(
            input = "21rep::$OTHER_NPUB::telegram::x::sig=${"a".repeat(128)}",
            result = ProofVerifyResult(VerifyLevel.SIGNATURE_ONLY, VerifyMessage.NoReputation, npub = OTHER_NPUB),
        )
        show { ReputationVerifyScreen(state, onAction = {}, onPaste = {}, onBack = {}) }
        rule.onNodeWithText(s(R.string.native_rvFormatOnly), substring = true, ignoreCase = true).performScrollTo().assertExists()
        rule.onNodeWithText(s(R.string.native_rvMsgNoReputation)).performScrollTo().assertExists()
        rule.onNodeWithText(s(L10nR.string.rvSignatureOnly), substring = true, ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText("Signatur gültig", substring = true).assertDoesNotExist()
    }

    @Test
    fun `verify shows the status line of the running layer loader`() {
        val state = ReputationVerifyUiState(
            input = OTHER_NPUB,
            result = ProofVerifyResult(VerifyLevel.PARTIAL, VerifyMessage.NpubWithBindings, npub = OTHER_NPUB, reputation = reputationEvent()),
            layers = LiveLayers(pending = setOf(LayerLoad.NIP05, LayerLoad.LIGHTNING)),
        )
        show { ReputationVerifyScreen(state, onAction = {}, onPaste = {}, onBack = {}) }
        rule.onNodeWithTag(ReputationVerifyTags.LAYERS_STATUS).performScrollTo().assertExists()
        rule.onNodeWithText(s(L10nR.string.rvCheckingLightning)).assertExists()
    }

    @Test
    fun `verify paste button calls the clipboard callback`() {
        var pasted = 0
        show { ReputationVerifyScreen(ReputationVerifyUiState(), onAction = {}, onPaste = { pasted++ }, onBack = {}) }
        rule.onNodeWithTag(ReputationVerifyTags.PASTE).performClick()
        assertEquals(1, pasted)
    }

    @Test
    fun `layers render the four layer titles`() {
        show {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ReputationLayers(LayersInput(badgeCount = 5, boundBadges = 5, meetupCount = 3, signerCount = 2))
            }
        }
        rule.onNodeWithTag(LayersTags.SIGNAL).assertExists()
        rule.onNodeWithText(s(L10nR.string.rlMeetupProofs), ignoreCase = true).assertExists()
        rule.onNodeWithText(s(L10nR.string.rlIdentityTitle), ignoreCase = true).performScrollTo().assertExists()
    }

    // --- VerifyPerson (spec 04 §2.7) ---

    @Test
    fun `verify person check and scan buttons send actions`() {
        val actions = mutableListOf<VerifyPersonAction>()
        show { VerifyPersonScreen(VerifyPersonUiState(input = OTHER_NPUB), onAction = { actions += it }, onBack = {}) }
        rule.onNodeWithTag(VerifyPersonTags.CHECK).performClick()
        rule.onNodeWithTag(VerifyPersonTags.SCAN).performClick()
        assertEquals(listOf(VerifyPersonAction.Check, VerifyPersonAction.OpenScanner), actions)
    }

    @Test
    fun `verify person shows the invalid npub error`() {
        show { VerifyPersonScreen(VerifyPersonUiState(input = "x", error = VerifyPersonError.INVALID_NPUB), onAction = {}, onBack = {}) }
        rule.onNodeWithTag(VerifyPersonTags.ERROR).assertExists()
        rule.onNodeWithText(s(L10nR.string.vpInvalidNpub)).assertExists()
    }

    @Test
    fun `verify person shows loading while checking`() {
        show { VerifyPersonScreen(VerifyPersonUiState(input = OTHER_NPUB, checking = true), onAction = {}, onBack = {}) }
        rule.onNodeWithTag(VerifyPersonTags.LOADING).assertExists()
        rule.onNodeWithTag(VerifyPersonTags.CHECK).assertIsNotEnabled()
    }

    @Test
    fun `verify person result shows the path and opens nostr for the target`() {
        val actions = mutableListOf<VerifyPersonAction>()
        val check = PresenceCheck(OTHER_NPUB, 1, listOf(Vectors.signerNpub, OTHER_NPUB), setOf("m1@x"), true, 2)
        show { VerifyPersonScreen(VerifyPersonUiState(input = OTHER_NPUB, result = check), onAction = { actions += it }, onBack = {}) }
        rule.onNodeWithTag(VerifyPersonTags.STATUS).assertExists()
        rule.onNodeWithText(s(L10nR.string.vpYou)).performScrollTo().assertExists()
        rule.onNodeWithText(s(L10nR.string.vpTarget)).performScrollTo().performClick()
        rule.onNodeWithTag(VerifyPersonTags.HINT).performScrollTo().assertExists()
        assertTrue(actions.contains(VerifyPersonAction.OpenNostr(OTHER_NPUB)))
    }

    // --- TrustNetworkHub (spec 04 §2.8) ---

    @Test
    fun `hub cards open verify person and reputation verify`() {
        val actions = mutableListOf<TrustNetworkHubAction>()
        show { TrustNetworkHubScreen(onAction = { actions += it }, onBack = {}) }
        rule.onNodeWithText(s(L10nR.string.tnHubNetTitle)).assertExists()
        rule.onNodeWithTag(TrustNetworkHubTags.NETWORK_CARD).performClick()
        rule.onNodeWithTag(TrustNetworkHubTags.VERIFY_CARD).performClick()
        assertEquals(listOf(TrustNetworkHubAction.OpenVerifyPerson, TrustNetworkHubAction.OpenReputationVerify), actions)
    }
}
