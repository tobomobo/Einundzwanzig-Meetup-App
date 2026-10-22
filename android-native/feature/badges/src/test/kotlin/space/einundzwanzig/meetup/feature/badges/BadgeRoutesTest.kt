package space.einundzwanzig.meetup.feature.badges

import android.content.Context
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEventBus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import space.einundzwanzig.meetup.core.camera.QrImageResult
import space.einundzwanzig.meetup.core.navigation.BadgeWallet
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NavResultEffect
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.navigation.NfcTagWriter
import space.einundzwanzig.meetup.core.navigation.RollingQr
import space.einundzwanzig.meetup.core.navigation.sendResult
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.review.AppReviewDemo
import space.einundzwanzig.meetup.data.issuance.scan.SaveOutcome
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher
import space.einundzwanzig.meetup.feature.badges.camera.BadgeQrCameraRoute
import space.einundzwanzig.meetup.feature.badges.camera.BadgeQrCameraTags
import space.einundzwanzig.meetup.feature.badges.camera.galleryCode
import space.einundzwanzig.meetup.feature.badges.scanner.BadgeScannerRoute
import space.einundzwanzig.meetup.feature.badges.scanner.BadgeScannerTags
import space.einundzwanzig.meetup.feature.badges.scanner.BadgeScannerViewModel
import space.einundzwanzig.meetup.feature.badges.scanner.ScanResultUi
import space.einundzwanzig.meetup.feature.badges.wizard.MeetupSessionWizardRoute
import space.einundzwanzig.meetup.feature.badges.wizard.MeetupSessionWizardTags
import space.einundzwanzig.meetup.feature.badges.writer.NfcTagWriterAction
import space.einundzwanzig.meetup.feature.badges.writer.NfcTagWriterRoute
import space.einundzwanzig.meetup.feature.badges.writer.NfcTagWriterViewModel
import java.time.Duration
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Route-level navigation contracts of ROUTES.md with a recording [Navigator] and a real result bus. */
@RunWith(AndroidJUnit4::class)
class BadgeRoutesTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val navigator = RecordingNavigator()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    // --- BadgeQrCamera ----------------------------------------------------------------------

    private var deliver: ((String) -> Boolean)? = null
    private var cameraEnabled = true
    private var galleryHandler: ((QrImageResult) -> Unit)? = null
    private var galleryLaunches = 0

    private fun showCamera(received: MutableList<String>) {
        rule.setContent {
            val bus = remember { ResultEventBus() }
            CompositionLocalProvider(LocalResultEventBus provides bus, LocalNavigator provides navigator) {
                NavResultEffect(NavResults.BadgeQrScanned, bus) { received += it }
                EinundzwanzigTheme {
                    BadgeQrCameraRoute(
                        scanner = { onResult, enabled ->
                            deliver = onResult
                            cameraEnabled = enabled
                        },
                        galleryResults = { handler ->
                            galleryHandler = handler
                            { galleryLaunches++ }
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `camera sends BadgeQrScanned once and pops exactly once`() {
        val received = mutableListOf<String>()
        showCamera(received)
        // Non-badge codes are ignored: no result, no pop, camera stays on.
        assertFalse(rule.runOnIdle { deliver!!("hello world") })
        assertFalse(rule.runOnIdle { deliver!!("{\"x\":1}") })
        assertTrue(navigator.calls.isEmpty())
        assertTrue(cameraEnabled)

        val badge = "{\"t\":\"B\",\"m\":\"wuerzburg-de\"}"
        assertTrue(rule.runOnIdle { deliver!!(badge) })
        rule.waitForIdle()
        assertEquals(listOf(badge), received)
        assertEquals(listOf("back"), navigator.calls)
        assertFalse(cameraEnabled)

        // A later hit (next frame, gallery) neither sends nor pops again.
        assertTrue(rule.runOnIdle { deliver!!("{\"t\":\"B\",\"m\":\"other\"}") })
        rule.runOnIdle { galleryHandler!!(QrImageResult.Codes(listOf(AppReviewDemo.PAYLOAD))) }
        rule.waitForIdle()
        assertEquals(listOf(badge), received)
        assertEquals(listOf("back"), navigator.calls)
    }

    @Test
    fun `gallery stays silent without a match and delivers an accepted code`() {
        val received = mutableListOf<String>()
        showCamera(received)
        rule.onNodeWithTag(BadgeQrCameraTags.GALLERY).performClick()
        assertEquals(1, galleryLaunches)
        rule.runOnIdle {
            galleryHandler!!(QrImageResult.NoCode)
            galleryHandler!!(QrImageResult.Failed(IllegalStateException("decode")))
            galleryHandler!!(QrImageResult.Codes(listOf("no badge", "{}")))
        }
        rule.waitForIdle()
        assertTrue(received.isEmpty())
        assertTrue(navigator.calls.isEmpty())

        rule.runOnIdle { galleryHandler!!(QrImageResult.Codes(listOf("nope", AppReviewDemo.PAYLOAD))) }
        rule.waitForIdle()
        assertEquals(listOf(AppReviewDemo.PAYLOAD), received)
        assertEquals(listOf("back"), navigator.calls)
    }

    @Test
    fun `gallery code picks the first accepted value`() {
        assertNull(galleryCode(QrImageResult.NoCode))
        assertNull(galleryCode(QrImageResult.Codes(listOf("x"))))
        assertEquals("{\"t\":\"B\"}", galleryCode(QrImageResult.Codes(listOf("x", "{\"t\":\"B\"}"))))
    }

    // --- BadgeScanner -----------------------------------------------------------------------

    private val processor = FakeProcessor()
    private val save = FakeSaveUseCase()
    private val reputation: ReputationPublisher = stub { _, _ -> Unit }

    private fun scannerViewModel(): BadgeScannerViewModel {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scopes += it }
        return BadgeScannerViewModel(processor, save, reputation, FakeNfc(), FixedLabels, appScope, false, NoLog)
            .also { scopes += it.viewModelScope }
    }

    @Test
    fun `scanner processes the camera result, saves, snackbars on the app host and replaces with the wallet`() {
        val model = scannerViewModel()
        val appHost = SnackbarHostState()
        lateinit var bus: ResultEventBus
        processor.qrResult = { found() }
        save.outcomes += SaveOutcome.Saved(badge(), presenceVerified = true, askCoAttendance = false, collection = listOf(badge()))
        rule.setContent {
            bus = remember { ResultEventBus() }
            CompositionLocalProvider(
                LocalResultEventBus provides bus,
                LocalNavigator provides navigator,
                LocalAppSnackbarHostState provides appHost,
            ) {
                EinundzwanzigTheme { BadgeScannerRoute(model) }
            }
        }
        rule.waitForIdle()

        // Scan button pushes the camera.
        rule.onNodeWithTag(BadgeScannerTags.SCAN_QR).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("navigate:BadgeQrCamera"), navigator.calls)

        // The camera's NavResult reaches the ViewModel.
        rule.runOnIdle { bus.sendResult(NavResults.BadgeQrScanned, "{\"t\":\"B\"}") }
        rule.waitUntil(5_000) { model.state.value.result is ScanResultUi.Found }
        assertEquals("{\"t\":\"B\"}", processor.qrCalls.single().first)

        rule.onNodeWithTag(BadgeScannerTags.ADD).performScrollTo().performClick()
        rule.waitUntil(5_000) { navigator.calls.size == 2 }
        assertEquals("replaceTop:BadgeWallet", navigator.calls[1])
        rule.waitUntil(5_000) { appHost.currentSnackbarData != null }
        assertEquals(context.getString(L10nR.string.verifyBadgeSaved), appHost.currentSnackbarData!!.visuals.message)
    }

    @Test
    fun `scanner back pops`() {
        val model = scannerViewModel()
        rule.setContent {
            CompositionLocalProvider(
                LocalResultEventBus provides remember { ResultEventBus() },
                LocalNavigator provides navigator,
            ) {
                EinundzwanzigTheme { BadgeScannerRoute(model) }
            }
        }
        rule.onNodeWithContentDescription(context.getString(L10nR.string.actionBack)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("back"), navigator.calls)
    }

    // --- MeetupSessionWizard / NfcTagWriter ------------------------------------------------

    @Test
    fun `wizard pushes the writer and skips to the rolling QR from the root`() {
        rule.setContent {
            CompositionLocalProvider(LocalNavigator provides navigator) { EinundzwanzigTheme { MeetupSessionWizardRoute() } }
        }
        rule.onNodeWithTag(MeetupSessionWizardTags.WRITE).performScrollTo().performClick()
        rule.onNodeWithTag(MeetupSessionWizardTags.SKIP).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("navigate:NfcTagWriter", "navigateFromRoot:RollingQr"), navigator.calls)
    }

    @Test
    fun `writer opens the rolling QR from the root 3 s after a written tag`() {
        val sessions = FakeRollingQrService().apply {
            basePayload = buildJsonObject {
                put("v", JsonPrimitive(2))
                put("t", JsonPrimitive("B"))
                put("m", JsonPrimitive("wuerzburg-de"))
            }
        }
        val directory = listOf(meetup("Würzburg", id = "107"))
        val meetups: MeetupService = stub { name, args ->
            when (name) {
                "fetchMeetups" -> directory
                "getFallbackMeetups" -> emptyList<Meetup>()
                "resolveFavorite" -> directory.firstOrNull { it.id == args[0] }
                else -> error("unexpected $name")
            }
        }
        val model = NfcTagWriterViewModel(sessions, FakeProfiles(UserProfile(homeMeetupId = "107")), meetups, FakeNfc())
            .also { it.log = NoLog; scopes += it.viewModelScope }
        rule.setContent {
            CompositionLocalProvider(LocalNavigator provides navigator) { EinundzwanzigTheme { NfcTagWriterRoute(model) } }
        }
        rule.waitUntil(5_000) { model.state.value.home is space.einundzwanzig.meetup.feature.badges.writer.HomeMeetupInfo.Found }
        // Both in one main-thread turn: Robolectric has no NFC adapter, so the reader-mode effect
        // would end the wait before a tag could be delivered.
        rule.runOnIdle {
            model.onAction(NfcTagWriterAction.CreateTag)
            assertTrue(model.state.value.waitingForTag)
            model.onAction(NfcTagWriterAction.TagDiscovered(FakeTag()))
        }
        rule.waitUntil(5_000) { model.state.value.success }
        assertTrue(navigator.calls.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_001))
        rule.waitUntil(5_000) { navigator.calls.isNotEmpty() }
        assertEquals(listOf("navigateFromRoot:RollingQr"), navigator.calls)
    }
}

/** Records every navigation call as `"<method>[:<key>]"`. */
private class RecordingNavigator : Navigator {
    val calls = mutableListOf<String>()
    private fun name(key: NavKey) = when (key) {
        BadgeWallet -> "BadgeWallet"
        RollingQr -> "RollingQr"
        NfcTagWriter -> "NfcTagWriter"
        else -> key::class.simpleName.orEmpty()
    }
    override fun navigate(key: NavKey) { calls += "navigate:${name(key)}" }
    override fun replaceTop(key: NavKey) { calls += "replaceTop:${name(key)}" }
    override fun back() { calls += "back" }
    override fun backTo(key: NavKey, inclusive: Boolean) { calls += "backTo:${name(key)}" }
    override fun resetTo(key: NavKey) { calls += "resetTo:${name(key)}" }
    override fun navigateFromRoot(key: NavKey) { calls += "navigateFromRoot:${name(key)}" }
    override fun openUrl(url: String) { calls += "openUrl:$url" }
}
