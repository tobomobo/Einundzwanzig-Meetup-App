package space.einundzwanzig.meetup.feature.community

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextEquals
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.proofs.satoshiduell.DuellStatus
import space.einundzwanzig.meetup.feature.community.hub.CommunityHubAction
import space.einundzwanzig.meetup.feature.community.hub.CommunityHubScreen
import space.einundzwanzig.meetup.feature.community.hub.CommunityHubUiState
import space.einundzwanzig.meetup.feature.community.portal.PortalAreaRow
import space.einundzwanzig.meetup.feature.community.portal.PortalAreaScreen
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class HubAndPortalScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)

    private val hubActions = mutableListOf<CommunityHubAction>()

    private fun showHub(state: CommunityHubUiState) {
        rule.setContent { EinundzwanzigTheme { CommunityHubScreen(state, onAction = { hubActions += it }, onBack = {}) } }
    }

    @Test
    fun `hub shows all cards and the default duell subtitle`() {
        showHub(CommunityHubUiState())
        listOf(L10nR.string.chPortal, L10nR.string.chNews, L10nR.string.chNostr, L10nR.string.chShoutout, L10nR.string.chPodcast)
            .forEach { rule.onNodeWithText(s(it)).assertExists() }
        rule.onNodeWithText("SatoshiDuell").assertExists()
        rule.onNodeWithText(s(L10nR.string.chDuellSub)).assertExists()
        rule.onNodeWithText("PlebRap").assertExists()
        rule.onAllNodesWithTag("duellCount").assertCountEquals(0)
    }

    @Test
    fun `hub duell card shows live status and count badge`() {
        showHub(CommunityHubUiState(DuellStatus(myTurn = 2, waiting = 1, lobby = 3)))
        rule.onNodeWithTag("duellCount", useUnmergedTree = true).assertTextEquals("5")
        val expected = "⚡ 2 ${s(L10nR.string.sdShortTurn)}  ·  3 ${s(L10nR.string.sdShortLobby)}  ·  1 ${s(L10nR.string.sdShortWait)}"
        rule.onNodeWithText(expected, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `hub cards dispatch their actions`() {
        showHub(CommunityHubUiState(DuellStatus.EMPTY))
        rule.onNodeWithText(s(L10nR.string.chPortal)).performClick()
        rule.onNodeWithText(s(L10nR.string.chNews)).performClick()
        rule.onNodeWithText(s(L10nR.string.chNostr)).performClick()
        rule.onNodeWithText(s(L10nR.string.chShoutout)).performScrollTo().performClick()
        rule.onNodeWithText(s(L10nR.string.chPodcast)).performScrollTo().performClick()
        rule.onNodeWithText("SatoshiDuell").performScrollTo().performClick()
        rule.onNodeWithText("PlebRap").performScrollTo().performClick()
        assertEquals(
            listOf(
                CommunityHubAction.OpenPortal,
                CommunityHubAction.OpenNews,
                CommunityHubAction.OpenNostr,
                CommunityHubAction.OpenShoutout,
                CommunityHubAction.OpenPodcast,
                CommunityHubAction.OpenSatoshiDuell,
                CommunityHubAction.OpenPlebrap,
            ),
            hubActions,
        )
    }

    @Test
    fun `portal area shows six rows in order and dispatches them`() {
        val rows = mutableListOf<PortalAreaRow>()
        rule.setContent { EinundzwanzigTheme { PortalAreaScreen(onBack = {}, onRowClick = { rows += it }) } }
        val titles = listOf(
            L10nR.string.paMeetups, L10nR.string.paEvents, L10nR.string.paCourses,
            L10nR.string.paMap, L10nR.string.paMine, L10nR.string.paWeb,
        )
        titles.forEach { rule.onNodeWithText(s(it)).performScrollTo().performClick() }
        assertEquals(PortalAreaRow.entries.toList(), rows)
        rule.onNodeWithText(s(L10nR.string.paMeetupsSub)).assertExists()
    }
}
