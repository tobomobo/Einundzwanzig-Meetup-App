package space.einundzwanzig.meetup.feature.meetups.portal

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsFocused
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetup
import space.einundzwanzig.meetup.feature.meetups.R
import java.time.ZoneId
import java.time.ZonedDateTime
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class PortalScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)
    private val npub = "npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc"

    private fun show(content: @Composable () -> Unit) = rule.setContent { EinundzwanzigTheme { content() } }

    // ---- Meine Meetups ------------------------------------------------------------------------

    @Test
    fun `not connected shows the login card and logs in`() {
        val actions = mutableListOf<PortalMeetupsAction>()
        show { PortalMeetupsScreen(PortalMeetupsUiState(checking = false), SnackbarHostState()) { actions += it } }
        rule.onNodeWithText(s(L10nR.string.portalNotConnected)).assertExists()
        rule.onNodeWithText(s(L10nR.string.portalConnectInfo)).assertExists()
        rule.onNodeWithContentDescription(s(L10nR.string.portalLogout)).assertDoesNotExist()
        rule.onNodeWithTag(PortalMeetupsTags.LOGIN).performClick()
        assertEquals(listOf<PortalMeetupsAction>(PortalMeetupsAction.Login), actions)
    }

    @Test
    fun `logging in disables the button and shows the progress label`() {
        show { PortalMeetupsScreen(PortalMeetupsUiState(checking = false, loggingIn = true), SnackbarHostState()) {} }
        rule.onNodeWithText(s(L10nR.string.portalConnecting)).assertExists()
        rule.onNodeWithTag(PortalMeetupsTags.LOGIN).assertIsNotEnabled()
    }

    @Test
    fun `loading meetups shows the loading text`() {
        show {
            PortalMeetupsScreen(PortalMeetupsUiState(checking = false, connected = true, loadingMeetups = true), SnackbarHostState()) {}
        }
        rule.onNodeWithText(s(L10nR.string.portalLoadingMeetups)).assertExists()
    }

    @Test
    fun `connected without meetups shows the empty state, footer and logout`() {
        val actions = mutableListOf<PortalMeetupsAction>()
        show { PortalMeetupsScreen(PortalMeetupsUiState(checking = false, connected = true), SnackbarHostState()) { actions += it } }
        rule.onNodeWithText(s(L10nR.string.portalNoMeetups)).assertExists()
        rule.onNodeWithText(s(L10nR.string.portalSource)).assertExists()
        rule.onNodeWithContentDescription(s(L10nR.string.portalLogout)).performClick()
        assertEquals(listOf<PortalMeetupsAction>(PortalMeetupsAction.Logout), actions)
    }

    @Test
    fun `leader card shows badge, admins and actions`() {
        val actions = mutableListOf<PortalMeetupsAction>()
        val meetup = PortalMeetup(1, "Einundzwanzig Göttingen", "goe", isLeader = true)
        val hal = LeaderRow(7, "Hal", npub, false)
        val state = PortalMeetupsUiState(
            checking = false,
            connected = true,
            cards = listOf(
                PortalMeetupCard(meetup, leaders = listOf(LeaderRow(3, "Satoshi", npub, true), hal)),
                PortalMeetupCard(PortalMeetup(2, "Other", "o")),
            ),
        )
        show { PortalMeetupsScreen(state, SnackbarHostState()) { actions += it } }
        rule.onNodeWithText("Einundzwanzig Göttingen").assertExists()
        rule.onNodeWithText(s(L10nR.string.portalLeader)).assertExists()
        rule.onNodeWithText(s(L10nR.string.ldTitle)).assertExists()
        rule.onNodeWithText(s(L10nR.string.ldCreator)).assertExists()
        rule.onNodeWithText("Hal").assertExists()
        rule.onNodeWithTag(PortalMeetupsTags.manage(1)).performClick()
        rule.onNodeWithTag(PortalMeetupsTags.removeLeader(1, 1)).performClick()
        rule.onNodeWithTag(PortalMeetupsTags.addLeader(1)).performScrollTo().performClick()
        rule.onNodeWithTag(PortalMeetupsTags.addLeader(2)).assertDoesNotExist()
        assertEquals(
            listOf(
                PortalMeetupsAction.ManageEvents(meetup),
                PortalMeetupsAction.AskRemoveLeader(1, hal),
                PortalMeetupsAction.AskAddLeader(1),
            ),
            actions,
        )
    }

    @Test
    fun `add dialog sends the typed npub`() {
        val actions = mutableListOf<PortalMeetupsAction>()
        val state = PortalMeetupsUiState(checking = false, connected = true, dialog = PortalDialog.AddLeader(1))
        show { PortalMeetupsScreen(state, SnackbarHostState()) { actions += it } }
        rule.onNodeWithText(s(L10nR.string.ldAdd)).assertExists()
        rule.onNodeWithTag(PortalMeetupsTags.ADD_FIELD).performTextInput(npub)
        rule.onNodeWithText(s(L10nR.string.ldAddDo)).performClick()
        assertEquals(listOf<PortalMeetupsAction>(PortalMeetupsAction.ConfirmAddLeader(npub)), actions)
    }

    @Test
    fun `a busy card disables remove and add`() {
        val meetup = PortalMeetup(1, "A", "a", isLeader = true)
        val state = PortalMeetupsUiState(
            checking = false,
            connected = true,
            cards = listOf(PortalMeetupCard(meetup, leaders = listOf(LeaderRow(7, "Hal", npub, false)), busy = true)),
        )
        show { PortalMeetupsScreen(state, SnackbarHostState()) {} }
        rule.onNodeWithTag(PortalMeetupsTags.removeLeader(1, 0)).assertIsNotEnabled()
        rule.onNodeWithTag(PortalMeetupsTags.addLeader(1)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `add dialog works on a large screen`() {
        val actions = mutableListOf<PortalMeetupsAction>()
        val state = PortalMeetupsUiState(checking = false, connected = true, dialog = PortalDialog.AddLeader(1))
        show { PortalMeetupsScreen(state, SnackbarHostState()) { actions += it } }
        rule.onNodeWithTag(PortalMeetupsTags.ADD_FIELD).assertIsFocused().performTextInput(npub)
        rule.onNodeWithText(s(L10nR.string.ldAddDo)).performClick()
        assertEquals(listOf<PortalMeetupsAction>(PortalMeetupsAction.ConfirmAddLeader(npub)), actions)
    }

    @Test
    fun `remove dialog confirms`() {
        val actions = mutableListOf<PortalMeetupsAction>()
        val state = PortalMeetupsUiState(checking = false, connected = true, dialog = PortalDialog.RemoveLeader(1, 7))
        show { PortalMeetupsScreen(state, SnackbarHostState()) { actions += it } }
        rule.onNodeWithText(s(L10nR.string.ldRemoveConfirm)).assertExists()
        rule.onAllNodesWithText(s(L10nR.string.ldRemove)).filter(hasClickAction()).onFirst().performClick()
        assertEquals(listOf<PortalMeetupsAction>(PortalMeetupsAction.ConfirmRemoveLeader), actions)
    }

    // ---- Termine verwalten --------------------------------------------------------------------

    @Test
    fun `event list shows rows, tap-to-edit fallback and the FAB`() {
        val actions = mutableListOf<PortalMeetupEventsAction>()
        val rows = listOf(PortalEventRow(11, 0, "21.07.2026  18:00", "Dinx"), PortalEventRow(null, 1, "kaputt", ""))
        show {
            PortalMeetupEventsScreen(PortalMeetupEventsUiState("Göttingen", loading = false, rows = rows), SnackbarHostState()) {
                actions += it
            }
        }
        rule.onNodeWithText(s(L10nR.string.portalExistingEvents).uppercase()).assertExists()
        rule.onNodeWithText("21.07.2026  18:00").assertExists()
        rule.onNodeWithText(s(L10nR.string.portalTapToEdit)).assertExists()
        rule.onNodeWithTag(PortalEventsTags.row(1)).performClick()
        rule.onNodeWithTag(PortalEventsTags.FAB).performClick()
        assertEquals(listOf(PortalMeetupEventsAction.Edit(rows[1]), PortalMeetupEventsAction.Create), actions)
    }

    @Test
    fun `event list loading and empty states`() {
        var state by mutableStateOf(PortalMeetupEventsUiState("G"))
        show { PortalMeetupEventsScreen(state, SnackbarHostState()) {} }
        rule.onNodeWithText(s(L10nR.string.portalLoadingEvents)).assertExists()
        state = PortalMeetupEventsUiState("G", loading = false)
        rule.onNodeWithText(s(L10nR.string.portalNoEvents)).assertExists()
    }

    // ---- Editor -------------------------------------------------------------------------------

    private val now = ZonedDateTime.of(2026, 7, 14, 10, 0, 0, 0, ZoneId.of("Europe/Berlin"))

    @Test
    fun `create editor shows the placeholder and saves`() {
        val actions = mutableListOf<PortalEventEditorAction>()
        val state = PortalEventEditorUiState("Göttingen", isEdit = false, today = now.toLocalDate(), now = now)
        show { PortalEventEditorScreen(state, SnackbarHostState()) { actions += it } }
        rule.onNodeWithText(s(L10nR.string.portalEventTitle)).assertExists()
        rule.onNodeWithText(s(L10nR.string.portalPickDate)).assertExists()
        rule.onNodeWithText(s(L10nR.string.portalSave)).assertExists()
        rule.onNodeWithTag(PortalEditorTags.LOCATION).performTextInput("Dinx")
        rule.onNodeWithTag(PortalEditorTags.SAVE).performScrollTo().performClick()
        assertEquals(listOf(PortalEventEditorAction.LocationChanged("Dinx"), PortalEventEditorAction.Save), actions)
    }

    @Test
    fun `edit editor shows the start with two spaces and the update label`() {
        val state = PortalEventEditorUiState(
            "Göttingen",
            isEdit = true,
            start = now.withHour(18).withMinute(0),
            today = now.toLocalDate(),
            now = now,
        )
        show { PortalEventEditorScreen(state, SnackbarHostState()) {} }
        rule.onNodeWithText(s(L10nR.string.portalEditEvent)).assertExists()
        rule.onNodeWithText("14.07.2026  18:00").assertExists()
        rule.onNodeWithText(s(L10nR.string.portalUpdate)).assertExists()
    }

    @Test
    fun `saving shows the progress label and disables save`() {
        val state = PortalEventEditorUiState("G", isEdit = false, saving = true, today = now.toLocalDate(), now = now)
        show { PortalEventEditorScreen(state, SnackbarHostState()) {} }
        rule.onNodeWithText(s(L10nR.string.portalSaving)).assertExists()
        rule.onNodeWithTag(PortalEditorTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun `tapping the start opens the date picker`() {
        val state = PortalEventEditorUiState("G", isEdit = false, today = now.toLocalDate(), now = now)
        show { PortalEventEditorScreen(state, SnackbarHostState()) {} }
        rule.onNodeWithTag(PortalEditorTags.START).performClick()
        rule.onNodeWithText(s(L10nR.string.actionCancel)).assertExists()
    }

    @Test
    fun `confirmed date with a dismissed time dialog sends the date without time`() {
        val actions = mutableListOf<PortalEventEditorAction>()
        val state = PortalEventEditorUiState("G", isEdit = false, today = now.toLocalDate(), now = now)
        show { PortalEventEditorScreen(state, SnackbarHostState()) { actions += it } }
        rule.onNodeWithTag(PortalEditorTags.START).performClick()
        rule.onNodeWithText(s(android.R.string.ok)).performClick()
        // Time dialog is up now; cancel → null → the VM applies 19:00.
        rule.onNodeWithText(s(L10nR.string.actionCancel)).performClick()
        assertEquals(listOf<PortalEventEditorAction>(PortalEventEditorAction.StartPicked(now.toLocalDate(), null)), actions)
        rule.onNodeWithText(s(L10nR.string.actionCancel)).assertDoesNotExist()
    }

    @Test
    fun `dismissing the date picker changes nothing`() {
        val actions = mutableListOf<PortalEventEditorAction>()
        val state = PortalEventEditorUiState("G", isEdit = false, today = now.toLocalDate(), now = now)
        show { PortalEventEditorScreen(state, SnackbarHostState()) { actions += it } }
        rule.onNodeWithTag(PortalEditorTags.START).performClick()
        rule.onNodeWithText(s(L10nR.string.actionCancel)).performClick()
        rule.onNodeWithText(s(L10nR.string.actionCancel)).assertDoesNotExist()
        assertEquals(emptyList<PortalEventEditorAction>(), actions)
    }

    @Test
    fun `resolving the edited date shows the loading indicator`() {
        val state = PortalEventEditorUiState("G", isEdit = true, loading = true, today = now.toLocalDate(), now = now)
        show { PortalEventEditorScreen(state, SnackbarHostState()) {} }
        rule.onNodeWithTag(PortalEditorTags.LOADING).assertExists()
        rule.onNodeWithTag(PortalEditorTags.SAVE).assertDoesNotExist()
        rule.onNodeWithText(s(R.string.native_portal_event_not_found)).assertDoesNotExist()
    }

    @Test
    fun `unknown date shows not found`() {
        val state = PortalEventEditorUiState("G", isEdit = true, notFound = true, today = now.toLocalDate(), now = now)
        show { PortalEventEditorScreen(state, SnackbarHostState()) {} }
        rule.onNodeWithText(s(R.string.native_portal_event_not_found)).assertExists()
    }
}
