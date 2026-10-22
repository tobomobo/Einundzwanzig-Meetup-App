package space.einundzwanzig.meetup.feature.proofs.platform

import android.os.Looper
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateError
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateResult
import space.einundzwanzig.meetup.feature.proofs.testing.FakePlatformProofRepository
import space.einundzwanzig.meetup.feature.proofs.testing.FakeRepublisher

@RunWith(AndroidJUnit4::class)
class PlatformProofsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = mutableListOf<PlatformProofsAction>()
    private val telegram = PlatformProof("telegram", "satoshi_fan", "a".repeat(128), 1_709_640_000)
    private val custom = PlatformProof("bisq", "bisq_trader", "b".repeat(128), 1_709_640_000)

    private val revokeTelegram = "Verknüpfung mit Telegram (satoshi_fan) widerrufen"

    /** The screen is a LazyColumn: scroll items in like a user would (default phone height). */
    private fun scrollListTo(matcher: SemanticsMatcher) {
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    }

    private fun show(state: PlatformProofsUiState) {
        rule.setContent {
            EinundzwanzigTheme { PlatformProofsScreen(state, onAction = { actions += it }, onBack = {}) }
        }
    }

    @Test
    fun `loading state shows no content`() {
        show(PlatformProofsUiState(isLoading = true))
        rule.onNodeWithText("SO FUNKTIONIERT'S").assertDoesNotExist()
        rule.onNodeWithTag(PlatformProofsTags.PRIMARY_BUTTON).assertDoesNotExist()
    }

    @Test
    fun `empty list shows the info box and the link button`() {
        show(PlatformProofsUiState(isLoading = false))
        rule.onNodeWithText("PLATTFORM-VERKNÜPFUNG").assertExists()
        rule.onNodeWithText("SO FUNKTIONIERT'S").assertExists()
        rule.onNodeWithText("1. Wähle eine Plattform", substring = true).assertExists()
        rule.onNodeWithText("AKTIVE VERKNÜPFUNGEN").assertDoesNotExist()
        scrollListTo(hasText("PLATTFORM VERKNÜPFEN"))
        rule.onNodeWithText("PLATTFORM VERKNÜPFEN").performClick()
        assertEquals(listOf<PlatformProofsAction>(PlatformProofsAction.OpenSheet), actions)
    }

    @Test
    fun `proof cards show name, username, date and revoke`() {
        show(PlatformProofsUiState(isLoading = false, proofs = listOf(telegram, custom)))
        scrollListTo(hasText("AKTIVE VERKNÜPFUNGEN", substring = true))
        rule.onNodeWithText("AKTIVE VERKNÜPFUNGEN").assertExists()
        rule.onNodeWithText("Telegram", substring = true).assertExists()
        rule.onNodeWithText("satoshi_fan", substring = true).assertExists()
        // Each revoke button names its platform and user (TalkBack, several cards).
        rule.onNodeWithContentDescription(revokeTelegram).performClick()
        assertEquals(PlatformProofsAction.RequestRevoke(telegram), actions.last())
        scrollListTo(hasText("bisq", substring = true))
        rule.onNodeWithText("bisq", substring = true).assertExists() // custom platform: raw id
        rule.onNodeWithContentDescription("Verknüpfung mit bisq (bisq_trader) widerrufen").assertExists()
        rule.onAllNodesWithText("Erstellt: ", substring = true, useUnmergedTree = true).assertCountEquals(2)
        scrollListTo(hasText("WEITERE PLATTFORM"))
        rule.onNodeWithText("WEITERE PLATTFORM").assertExists()
    }

    @Test
    fun `revoke dialog names the username and the raw platform id`() {
        show(PlatformProofsUiState(isLoading = false, proofs = listOf(telegram), pendingRevoke = telegram))
        rule.onNodeWithText("VERKNÜPFUNG AUFHEBEN?").assertExists()
        rule.onNodeWithText("\"satoshi_fan\" auf telegram", substring = true).assertExists()
        rule.onNodeWithText("WIDERRUFEN").performClick()
        assertEquals(PlatformProofsAction.ConfirmRevoke, actions.last())
        rule.onNodeWithText("ABBRECHEN").performClick()
        assertEquals(PlatformProofsAction.DismissRevoke, actions.last())
    }

    @Test
    fun `creating shows the progress state on the primary button`() {
        show(PlatformProofsUiState(isLoading = false, isCreating = true))
        scrollListTo(hasText("Beweis wird signiert …"))
        rule.onNodeWithText("Beweis wird signiert …").assertExists()
        rule.onNodeWithTag(PlatformProofsTags.PRIMARY_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun `sheet - chips, other field, hint and create button state`() {
        show(
            PlatformProofsUiState(
                isLoading = false,
                proofs = listOf(telegram),
                sheet = CreateSheetState(selectedPlatform = "other"),
            ),
        )
        rule.onNodeWithText("AUF PLATTFORM TEILEN").assertExists()
        rule.onNodeWithText("Satoshi-Kleinanzeigen").assertExists()
        rule.onNodeWithText("RoboSats").assertExists()
        rule.onNodeWithText("Andere Plattform").assertExists()
        rule.onNodeWithContentDescription("Bereits verknüpft").assertExists() // telegram is linked
        rule.onNodeWithTag(PlatformProofsTags.CUSTOM_NAME_FIELD).assertExists()
        rule.onNodeWithText("Dein Benutzername auf der Plattform").assertExists()
        rule.onNodeWithTag(PlatformProofsTags.CREATE_BUTTON).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("RoboSats").performClick()
        assertEquals(PlatformProofsAction.SelectPlatform("robosats"), actions.last())
    }

    @Test
    fun `sheet with valid input enables create`() {
        show(
            PlatformProofsUiState(
                isLoading = false,
                sheet = CreateSheetState(selectedPlatform = "telegram", username = TextFieldState("satoshi")),
            ),
        )
        rule.onNodeWithText("Dein Telegram @username (ohne @)").assertDoesNotExist() // value shown instead of hint
        rule.onNodeWithTag(PlatformProofsTags.CUSTOM_NAME_FIELD).assertDoesNotExist()
        rule.onNodeWithTag(PlatformProofsTags.CREATE_BUTTON).performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertTrue(actions.contains(PlatformProofsAction.Create))
    }

    // ---- Route flows with the real ViewModel ----

    private class RecordingNavigator : Navigator {
        var backs = 0
        override fun navigate(key: NavKey) = Unit
        override fun replaceTop(key: NavKey) = Unit
        override fun back() {
            backs++
        }
        override fun backTo(key: NavKey, inclusive: Boolean) = Unit
        override fun resetTo(key: NavKey) = Unit
        override fun navigateFromRoot(key: NavKey) = Unit
        override fun openUrl(url: String) = Unit
    }

    /**
     * The ViewModel resumes on the Android main looper after the application-scope work
     * (Dispatchers.Default, like production); Robolectric's paused looper must be pumped.
     */
    private fun waitFor(condition: () -> Boolean) {
        rule.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private fun openSheetAndType(platform: String, username: String) {
        scrollListTo(hasText("PLATTFORM VERKNÜPFEN"))
        rule.onNodeWithText("PLATTFORM VERKNÜPFEN").performClick()
        rule.onNodeWithText(platform).performClick()
        rule.onNodeWithTag(PlatformProofsTags.USERNAME_FIELD).performTextInput(username)
    }

    private fun showRoute(repo: FakePlatformProofRepository, republisher: FakeRepublisher, navigator: Navigator, host: SnackbarHostState) {
        val vm = PlatformProofsViewModel(repo, republisher, CoroutineScope(SupervisorJob() + Dispatchers.Default)) // like @ApplicationScope
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator, LocalAppSnackbarHostState provides host) {
                    PlatformProofsRoute(vm)
                }
            }
        }
    }

    @Test
    fun `create flow - sign, snackbar on the app host, screen closes`() {
        val repo = FakePlatformProofRepository()
        val republisher = FakeRepublisher()
        val navigator = RecordingNavigator()
        val host = SnackbarHostState()
        showRoute(repo, republisher, navigator, host)

        openSheetAndType("Telegram", " satoshi ")
        rule.onNodeWithTag(PlatformProofsTags.CREATE_BUTTON).performScrollTo().performClick()
        waitFor { navigator.backs == 1 }

        assertEquals("satoshi", repo.createCalls.single().second)
        assertEquals(1, republisher.calls)
        waitFor { host.currentSnackbarData != null }
        assertEquals(
            "Verknüpfung gespeichert! Wird automatisch in deinen Reputation-QR eingebettet.",
            host.currentSnackbarData?.visuals?.message,
        )
    }

    @Test
    fun `create failure keeps the screen and shows the signer reason`() {
        val repo = FakePlatformProofRepository().apply {
            createResult = ProofCreateResult.Failure(ProofCreateError.SIGNER_OR_UNKNOWN, "Permission denied")
        }
        val navigator = RecordingNavigator()
        val host = SnackbarHostState()
        showRoute(repo, FakeRepublisher(), navigator, host)

        openSheetAndType("Nostr", "bob")
        rule.onNodeWithTag(PlatformProofsTags.CREATE_BUTTON).performScrollTo().performClick()
        waitFor { host.currentSnackbarData != null }
        assertEquals("Fehler: Permission denied", host.currentSnackbarData?.visuals?.message)
        assertEquals(true, host.currentSnackbarData?.visuals?.withDismissAction)
        assertEquals(0, navigator.backs)
    }

    @Test
    fun `revoke flow removes the card`() {
        val repo = FakePlatformProofRepository(listOf(telegram))
        val republisher = FakeRepublisher()
        showRoute(repo, republisher, RecordingNavigator(), SnackbarHostState())

        scrollListTo(hasText("satoshi_fan", substring = true))
        rule.onNodeWithContentDescription(revokeTelegram).performClick()
        rule.onNodeWithText("WIDERRUFEN").performClick()
        waitFor { repo.removeCalls.isNotEmpty() && republisher.calls == 1 }
        rule.waitForIdle()
        rule.onNodeWithText("satoshi_fan", substring = true).assertDoesNotExist()
        scrollListTo(hasText("PLATTFORM VERKNÜPFEN"))
        rule.onNodeWithText("PLATTFORM VERKNÜPFEN").assertExists()
    }

    @Test
    fun `failed revoke keeps the card and shows the error snackbar`() {
        val repo = FakePlatformProofRepository(listOf(telegram)).apply { removeSucceeds = false }
        val republisher = FakeRepublisher()
        val host = SnackbarHostState()
        showRoute(repo, republisher, RecordingNavigator(), host)

        scrollListTo(hasText("satoshi_fan", substring = true))
        rule.onNodeWithContentDescription(revokeTelegram).performClick()
        rule.onNodeWithText("WIDERRUFEN").performClick()
        waitFor { host.currentSnackbarData != null }
        assertEquals("Die Verknüpfung konnte nicht gelöscht werden.", host.currentSnackbarData?.visuals?.message)
        rule.onNodeWithText("satoshi_fan", substring = true).assertExists()
        assertEquals(0, republisher.calls)
    }

    @Test
    fun `IME done on the username submits like the button`() {
        val repo = FakePlatformProofRepository()
        val navigator = RecordingNavigator()
        showRoute(repo, FakeRepublisher(), navigator, SnackbarHostState())

        openSheetAndType("Telegram", "satoshi")
        rule.onNodeWithTag(PlatformProofsTags.USERNAME_FIELD).performImeAction()
        waitFor { navigator.backs == 1 }
        assertEquals(1, repo.createCalls.size)
        assertEquals("satoshi", repo.createCalls.single().second)
    }
}
