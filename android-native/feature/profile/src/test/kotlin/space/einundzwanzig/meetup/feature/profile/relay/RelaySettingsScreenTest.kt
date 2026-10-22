package space.einundzwanzig.meetup.feature.profile.relay

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

@RunWith(AndroidJUnit4::class)
class RelaySettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = mutableListOf<RelaySettingsAction>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = context.getString(id)

    private val defaults = listOf(
        RelayRow("wss://relay.damus.io", true),
        RelayRow("wss://nos.lol", false),
    )

    private fun show(state: RelaySettingsUiState) {
        rule.setContent { EinundzwanzigTheme { RelaySettingsScreen(state = state, onAction = { actions += it }) } }
    }

    @Test
    fun `defaults show switches and the toggle is dispatched`() {
        show(RelaySettingsUiState(isLoading = false, defaultRelays = defaults, activeCount = 1))
        rule.onNodeWithText(str(L10nR.string.rsTitle)).assertExists()
        rule.onNodeWithTag(RelaySettingsTestTags.switch("wss://relay.damus.io")).assertIsOn()
        rule.onNodeWithTag(RelaySettingsTestTags.switch("wss://nos.lol")).assertIsOff().performClick()
        assertEquals(listOf<RelaySettingsAction>(RelaySettingsAction.ToggleDefault("wss://nos.lol", true)), actions)
    }

    @Test
    fun `empty custom list and green summary`() {
        show(RelaySettingsUiState(isLoading = false, defaultRelays = defaults, activeCount = 3))
        rule.onNodeWithTag(RelaySettingsTestTags.LIST).performScrollToNode(hasTestTag(RelaySettingsTestTags.SUMMARY))
        rule.onNodeWithText(str(L10nR.string.rsNoCustomRelays)).assertExists()
        rule.onNodeWithText("3 aktive Relays").assertExists()
    }

    @Test
    fun `no active relay turns the summary red`() {
        show(RelaySettingsUiState(isLoading = false, defaultRelays = defaults.map { it.copy(enabled = false) }, activeCount = 0))
        rule.onNodeWithTag(RelaySettingsTestTags.LIST).performScrollToNode(hasTestTag(RelaySettingsTestTags.SUMMARY))
        rule.onNodeWithText(str(L10nR.string.rsNoRelaysActive)).assertExists()
    }

    @Test
    fun `custom relay can be removed without confirmation`() {
        show(RelaySettingsUiState(isLoading = false, defaultRelays = defaults, customRelays = listOf("wss://mein.io"), activeCount = 2))
        rule.onNodeWithTag(RelaySettingsTestTags.LIST).performScrollToNode(hasTestTag(RelaySettingsTestTags.SUMMARY))
        rule.onNodeWithContentDescription("Entfernen").performClick()
        assertEquals(listOf<RelaySettingsAction>(RelaySettingsAction.RemoveCustom("wss://mein.io")), actions)
    }

    @Test
    fun `add pill opens the dialog, which submits the prefilled url`() {
        show(RelaySettingsUiState(isLoading = false, defaultRelays = defaults, activeCount = 1))
        rule.onNodeWithText(str(L10nR.string.rsAdd)).performClick()
        assertEquals(RelaySettingsAction.OpenAddDialog, actions.single())
    }

    @Test
    fun `add dialog sends the typed url and disables while testing`() {
        show(RelaySettingsUiState(isLoading = false, defaultRelays = defaults, activeCount = 1, addDialogOpen = true))
        rule.onNodeWithText(str(L10nR.string.rsAddRelay)).assertExists()
        rule.onNodeWithTag(RelaySettingsTestTags.INPUT).performTextInput("relay.example.com")
        rule.onNodeWithTag(RelaySettingsTestTags.ADD_CONFIRM).performClick()
        assertEquals(RelaySettingsAction.AddRelay("wss://relay.example.com"), actions.single())
    }

    @Test
    fun `testing state disables the add button`() {
        show(RelaySettingsUiState(isLoading = false, addDialogOpen = true, isTestingRelay = true))
        rule.onNodeWithTag(RelaySettingsTestTags.ADD_CONFIRM).assertIsNotEnabled()
    }
}
