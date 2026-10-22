package space.einundzwanzig.meetup.feature.reputation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEventBus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NavResultEffect
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.navigation.ReputationScanResult
import space.einundzwanzig.meetup.core.camera.QrImageResult
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarVisuals
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.reputation.publish.PublishResult
import space.einundzwanzig.meetup.feature.reputation.qr.ReputationQrRoute
import space.einundzwanzig.meetup.feature.reputation.qr.ReputationQrTags
import space.einundzwanzig.meetup.feature.reputation.qr.ReputationQrViewModel
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationGalleryPicker
import space.einundzwanzig.meetup.data.identity.review.AppReviewDemo
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrCodec
import space.einundzwanzig.meetup.feature.reputation.person.VerifyPersonQrCameraRoute
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScanResultScreen
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScanResultUiState
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScannerRoute
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScannerTags
import space.einundzwanzig.meetup.feature.reputation.scan.ScanResultTags
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Records every navigator call. */
private class RecordingNavigator : Navigator {
    val calls = mutableListOf<String>()
    val replaced = mutableListOf<NavKey>()
    override fun navigate(key: NavKey) { calls += "navigate" }
    override fun replaceTop(key: NavKey) { calls += "replaceTop"; replaced += key }
    override fun back() { calls += "back" }
    override fun backTo(key: NavKey, inclusive: Boolean) { calls += "backTo" }
    override fun resetTo(key: NavKey) { calls += "resetTo" }
    override fun navigateFromRoot(key: NavKey) { calls += "navigateFromRoot" }
    override fun openUrl(url: String) { calls += "openUrl" }
}

/** Route-level contracts of ROUTES.md and the S-8 banner (camera replaced by a fake slot). */
@RunWith(AndroidJUnit4::class)
class ReputationRoutesUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val navigator = RecordingNavigator()

    /** The last `onResult` the fake camera slot received, plus its `enabled` flag. */
    private var deliver: ((String) -> Boolean)? = null
    private var cameraEnabled = true

    /** The last `onResult` the fake gallery slot received. */
    private var galleryResult: ((QrImageResult) -> Unit)? = null

    /** App snackbar host; its current visuals are asserted (message and tone). */
    private val snackbars = SnackbarHostState()

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        EinundzwanzigTheme {
            CompositionLocalProvider(LocalNavigator provides navigator, LocalAppSnackbarHostState provides snackbars) { content() }
        }
    }

    private fun scannerRoute(): @Composable () -> Unit = {
        Host {
            ReputationScannerRoute(
                scanner = { onResult, _, enabled ->
                    deliver = onResult
                    cameraEnabled = enabled
                },
                gallery = { onResult ->
                    galleryResult = onResult
                    ReputationGalleryPicker(isDecoding = false, launch = {})
                },
            )
        }
    }

    private fun currentSnackbar(): AppSnackbarVisuals? {
        rule.waitForIdle()
        return snackbars.currentSnackbarData?.visuals as? AppSnackbarVisuals
    }

    private fun gallery(result: QrImageResult) {
        rule.runOnIdle { galleryResult!!(result) }
    }

    // --- Gallery (spec 04 §2.3) ---

    @Test
    fun `gallery image without a code shows qrNoCodeInImage`() {
        rule.setContent(scannerRoute())
        gallery(QrImageResult.NoCode)
        val snackbar = currentSnackbar()!!
        assertEquals(context.getString(L10nR.string.qrNoCodeInImage), snackbar.message)
        assertEquals(Tone.Warning, snackbar.tone)
        assertTrue(navigator.calls.isEmpty())
    }

    @Test
    fun `gallery image with a foreign code shows qrNotEinundzwanzig`() {
        rule.setContent(scannerRoute())
        gallery(QrImageResult.Codes(listOf("https://example.com")))
        val snackbar = currentSnackbar()!!
        assertEquals(context.getString(L10nR.string.qrNotEinundzwanzig), snackbar.message)
        assertEquals(Tone.Warning, snackbar.tone)
        assertTrue(navigator.calls.isEmpty())
    }

    @Test
    fun `gallery decoding failure shows qrProcessError`() {
        rule.setContent(scannerRoute())
        val error = IllegalStateException("broken")
        gallery(QrImageResult.Failed(error))
        val snackbar = currentSnackbar()!!
        assertEquals(context.getString(L10nR.string.qrProcessError, error.toString()), snackbar.message)
        assertEquals(Tone.Error, snackbar.tone)
    }

    @Test
    fun `gallery reputation code replaces the scanner with the result`() {
        rule.setContent(scannerRoute())
        gallery(QrImageResult.Codes(listOf("https://example.com", Vectors.qrString)))
        rule.waitForIdle()
        assertEquals(ReputationScanResult(Vectors.qrString), navigator.replaced.single())
        assertEquals(null, currentSnackbar())
    }

    @Test
    fun `gallery demo code shows the demo dialog`() {
        rule.setContent(scannerRoute())
        gallery(QrImageResult.Codes(listOf(AppReviewDemo.PAYLOAD)))
        rule.onNodeWithTag(ReputationScannerTags.DEMO_DIALOG).assertExists()
        assertTrue(navigator.calls.isEmpty())
    }

    // --- ReputationQr route (spec 04 §2.1) ---

    @Test
    fun `qr route shows native_repSignFailed when signing failed`() {
        val source = FakeReputationDataSource().apply { qr = { qrContent(signError = IllegalStateException("rejected")) } }
        val vm = ReputationQrViewModel(source)
        rule.setContent { Host { ReputationQrRoute(vm) } }
        rule.waitUntil(5_000) { snackbars.currentSnackbarData != null }
        val snackbar = currentSnackbar()!!
        assertEquals(context.getString(R.string.native_repSignFailed), snackbar.message)
        assertEquals(Tone.Error, snackbar.tone)
        assertTrue(snackbar.withDismissAction)
    }

    @Test
    fun `qr route publish shows a success snackbar`() {
        val source = FakeReputationDataSource().apply { publishResult = { PublishResult.Sent(3, "e".repeat(64)) } }
        val vm = ReputationQrViewModel(source)
        rule.setContent { Host { ReputationQrRoute(vm) } }
        rule.onNodeWithTag(ReputationQrTags.PUBLISH_BUTTON).performScrollTo().performClick()
        rule.waitUntil(5_000) { snackbars.currentSnackbarData != null }
        val snackbar = currentSnackbar()!!
        assertEquals(context.getString(R.string.native_repPublishOk, 3), snackbar.message)
        assertEquals(Tone.Success, snackbar.tone)
    }

    @Test
    fun `qr route publish without relay shows an error snackbar`() {
        val source = FakeReputationDataSource().apply { publishResult = { PublishResult.NoRelay("e") } }
        val vm = ReputationQrViewModel(source)
        rule.setContent { Host { ReputationQrRoute(vm) } }
        rule.onNodeWithTag(ReputationQrTags.PUBLISH_BUTTON).performScrollTo().performClick()
        rule.waitUntil(5_000) { snackbars.currentSnackbarData != null }
        val snackbar = currentSnackbar()!!
        assertEquals(context.getString(R.string.native_repPublishNoRelay), snackbar.message)
        assertEquals(Tone.Error, snackbar.tone)
    }

    @Test
    fun `scanner camera hit replaces the scanner with the scan result`() {
        rule.setContent(scannerRoute())
        rule.waitForIdle()
        assertFalse(rule.runOnIdle { deliver!!("hello") })
        assertTrue(navigator.calls.isEmpty())
        assertTrue(rule.runOnIdle { deliver!!(Vectors.qrString) })
        assertEquals(listOf("replaceTop"), navigator.calls)
        assertEquals(ReputationScanResult(Vectors.qrString), navigator.replaced.single())
        // Further hits are ignored.
        assertFalse(rule.runOnIdle { deliver!!(Vectors.qrString) })
        assertEquals(1, navigator.replaced.size)
    }

    @Test
    fun `demo dialog survives recreation and dismissing it resumes the camera`() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent(scannerRoute())
        rule.waitForIdle()
        assertTrue(rule.runOnIdle { deliver!!(AppReviewDemo.PAYLOAD) })
        rule.onNodeWithTag(ReputationScannerTags.DEMO_DIALOG).assertExists()
        assertFalse(cameraEnabled)

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithTag(ReputationScannerTags.DEMO_DIALOG).assertExists()
        assertFalse(cameraEnabled)
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithTag(ReputationScannerTags.DEMO_DIALOG).assertDoesNotExist()
        assertTrue(cameraEnabled)
        assertTrue(rule.runOnIdle { deliver!!(Vectors.qrString) })
        assertEquals(listOf("replaceTop"), navigator.calls)
    }

    @Test
    fun `verify-person camera sends NpubScanned once and pops`() {
        val received = mutableListOf<String>()
        rule.setContent {
            val bus = remember { ResultEventBus() }
            CompositionLocalProvider(LocalResultEventBus provides bus) {
                NavResultEffect(NavResults.NpubScanned, bus) { received += it }
                Host {
                    VerifyPersonQrCameraRoute(scanner = { onResult, enabled ->
                        deliver = onResult
                        cameraEnabled = enabled
                    })
                }
            }
        }
        rule.waitForIdle()
        assertFalse(rule.runOnIdle { deliver!!("no npub here") })
        assertTrue(navigator.calls.isEmpty())
        assertTrue(rule.runOnIdle { deliver!!("nostr:$OTHER_NPUB") })
        rule.waitForIdle()
        assertEquals(listOf(OTHER_NPUB), received)
        assertEquals(listOf("back"), navigator.calls)
        assertFalse(cameraEnabled)
        assertTrue(rule.runOnIdle { deliver!!(OTHER_NPUB) })
        assertEquals(listOf("back"), navigator.calls)
    }

    @Test
    fun `scan result of an S-8 mismatch shows the banner and the no-identity header`() {
        val (code, _) = mismatchQr()
        rule.setContent {
            EinundzwanzigTheme { ReputationScanResultScreen(ReputationScanResultUiState(ReputationQrCodec.parse(code)), onBack = {}) }
        }
        rule.onNodeWithTag(ScanResultTags.MISMATCH).assertIsDisplayed()
        rule.onNodeWithTag(ScanResultTags.HEADER).assert(hasText(context.getString(L10nR.string.qrVerified)))
        rule.onNodeWithText(context.getString(R.string.native_qrIdentityMismatch)).assertIsDisplayed()
    }
}
