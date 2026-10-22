package space.einundzwanzig.meetup.feature.proofs.humanity

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityOutcome
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityStatus
import space.einundzwanzig.meetup.data.proofs.humanity.LastChecked
import space.einundzwanzig.meetup.feature.proofs.testing.FakeHumanityProofRepository
import space.einundzwanzig.meetup.feature.proofs.testing.FakeRepublisher
import space.einundzwanzig.meetup.feature.proofs.testing.FakeTimeSource

@RunWith(AndroidJUnit4::class)
class HumanityProofScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = mutableListOf<HumanityProofAction>()

    private fun show(state: HumanityProofUiState) {
        rule.setContent {
            EinundzwanzigTheme { HumanityProofScreen(state, onAction = { actions += it }, onBack = {}) }
        }
    }

    @Test
    fun `loading shows neither card nor button`() {
        show(HumanityProofUiState(isLoading = true))
        rule.onNodeWithText("PROOF OF HUMANITY").assertExists()
        rule.onNodeWithTag(HumanityProofTags.CHECK_BUTTON).assertDoesNotExist()
    }

    @Test
    fun `not verified - amber status, intro, check now`() {
        show(HumanityProofUiState(isLoading = false, status = HumanityStatus(verified = false)))
        rule.onNodeWithText("NICHT VERIFIZIERT").assertExists()
        rule.onNodeWithText("Beweise, dass du ein Mensch bist — indem du nachweist, dass du", substring = true).assertExists()
        rule.onNodeWithText("WIE FUNKTIONIERT DAS?").assertExists()
        rule.onNodeWithText("Du zappst irgendjemanden auf Nostr").assertExists()
        rule.onNodeWithText("Lightning-Beweis aktiv").assertDoesNotExist()
        rule.onNodeWithText("Letzte Prüfung", substring = true).assertDoesNotExist()
        rule.onNodeWithText("JETZT PRÜFEN").performScrollTo().performClick()
        assertEquals(listOf<HumanityProofAction>(HumanityProofAction.Check), actions)
    }

    @Test
    fun `verified - date sentence, lightning chip, check again, last check`() {
        show(
            HumanityProofUiState(
                isLoading = false,
                status = HumanityStatus(verified = true, firstZapAt = 1, lastCheckedAt = 1),
                firstZapDate = "3.5.2025",
                lastChecked = LastChecked.HoursAgo(2),
            ),
        )
        rule.onNodeWithText("MENSCH VERIFIZIERT").assertExists()
        rule.onNodeWithText(
            "Du hast am 3.5.2025 eine Lightning-Zahlung auf dem Nostr-Netzwerk geleistet. " +
                "Dieser Beweis ist in deinem Reputation-Event gespeichert.",
        ).assertExists()
        rule.onNodeWithText("Lightning-Beweis aktiv").assertExists()
        rule.onNodeWithText("ERNEUT PRÜFEN").performScrollTo().assertExists()
        rule.onNodeWithText("Letzte Prüfung: Vor 2 Std.").performScrollTo().assertExists()
    }

    @Test
    fun `checking disables the button and shows the relay search label`() {
        show(HumanityProofUiState(isLoading = false, status = HumanityStatus(verified = false), isChecking = true))
        rule.onNodeWithText("SUCHE AUF RELAYS...").assertExists()
        rule.onNodeWithTag(HumanityProofTags.CHECK_BUTTON).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `result box shows the localized outcome`() {
        show(
            HumanityProofUiState(
                isLoading = false,
                status = HumanityStatus(verified = false),
                result = HumanityOutcome.NOT_FOUND,
            ),
        )
        rule.onNodeWithTag(HumanityProofTags.RESULT_BOX).performScrollTo()
        rule.onNodeWithText("Kein Zap-Receipt gefunden.", substring = true).assertExists()
    }

    @Test
    fun `route flow - check finds a receipt and flips to verified`() {
        val repo = FakeHumanityProofRepository().apply { nextOutcome = HumanityOutcome.FOUND }
        val republisher = FakeRepublisher()
        val vm = HumanityProofViewModel(repo, republisher, FakeTimeSource(now = 1_700_000_000_000))
        rule.setContent { EinundzwanzigTheme { HumanityProofScreen(vmState(vm), vm::onAction, onBack = {}) } }

        rule.onNodeWithTag(HumanityProofTags.CHECK_BUTTON).performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { vm.state.value.result != null }
        rule.onNodeWithText("MENSCH VERIFIZIERT").assertExists()
        rule.onNodeWithText("Lightning-Zahlung gefunden — du bist verifiziert!").performScrollTo().assertExists()
        rule.onNodeWithText("Letzte Prüfung: Gerade eben").performScrollTo().assertExists()
        assertEquals(1, republisher.calls)
    }

    @androidx.compose.runtime.Composable
    private fun vmState(vm: HumanityProofViewModel): HumanityProofUiState =
        vm.state.collectAsState().value
}
