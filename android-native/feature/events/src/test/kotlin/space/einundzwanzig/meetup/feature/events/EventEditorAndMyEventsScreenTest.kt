package space.einundzwanzig.meetup.feature.events

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.calendar.model.MyMeetupDate
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeRight
import space.einundzwanzig.meetup.feature.events.editor.EventEditorAction
import space.einundzwanzig.meetup.feature.events.editor.EventEditorScreen
import space.einundzwanzig.meetup.feature.events.editor.EventEditorTags
import space.einundzwanzig.meetup.feature.events.editor.EventEditorUiState
import space.einundzwanzig.meetup.feature.events.myevents.MyEventsAction
import space.einundzwanzig.meetup.feature.events.myevents.MyEventsScreen
import space.einundzwanzig.meetup.feature.events.myevents.MyEventsTags
import space.einundzwanzig.meetup.feature.events.myevents.MyEventsUiState
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class EventEditorAndMyEventsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)

    private val editorActions = mutableListOf<EventEditorAction>()
    private val myActions = mutableListOf<MyEventsAction>()

    private fun editor(state: EventEditorUiState) {
        rule.setContent { EinundzwanzigTheme { EventEditorScreen(state, onAction = { editorActions += it }) } }
    }

    private fun editorState(
        right: EventBadgeRight? = EventBadgeRight.PORTAL_LEADER,
        badge: Boolean = false,
    ) = EventEditorUiState(zone = BERLIN, now = NOW, right = right, badgeEnabled = badge)

    @Test
    fun `editor shows checking while the permission is unknown`() {
        editor(editorState(right = null))
        rule.onNodeWithText(s(L10nR.string.evBadgeChecking)).performScrollTo().assertExists()
        rule.onNodeWithTag(EventEditorTags.BADGE_SWITCH).assertIsNotEnabled()
        rule.onAllNodesWithText(s(L10nR.string.calPickDateTime)).assertCountEquals(2)
    }

    @Test
    fun `editor explains when badges are not allowed`() {
        editor(editorState(right = EventBadgeRight.NONE))
        rule.onNodeWithText(s(L10nR.string.evBadgeNotAllowed)).performScrollTo().assertExists()
        rule.onNodeWithTag(EventEditorTags.BADGE_SWITCH).assertIsNotEnabled()
    }

    @Test
    fun `editor inputs dispatch actions`() {
        editor(editorState())
        rule.onNodeWithTag(EventEditorTags.TITLE).performTextInput("BTC Prag")
        rule.onNodeWithTag(EventEditorTags.ALL_DAY).performClick()
        rule.onNodeWithTag(EventEditorTags.BADGE_SWITCH).performScrollTo().performClick()
        rule.onNodeWithTag(EventEditorTags.PUBLISH).performScrollTo().performClick()
        assertEquals(
            listOf(
                EventEditorAction.SetTitle("BTC Prag"),
                EventEditorAction.SetAllDay(true),
                EventEditorAction.SetBadgeEnabled(true),
                EventEditorAction.Publish,
            ),
            editorActions,
        )
    }

    @Test
    @Config(qualifiers = "de-w411dp-h891dp")
    fun `start button opens the date picker`() {
        editor(editorState())
        rule.onNodeWithTag(EventEditorTags.START).performClick()
        rule.onNodeWithText(s(L10nR.string.actionCancel)).assertExists()
    }

    @Test
    fun `set dates are formatted and the end can be cleared`() {
        val start = ZonedDateTime.of(2026, 11, 5, 19, 0, 0, 0, BERLIN).toInstant()
        editor(editorState().copy(start = start, end = start.plusSeconds(7_200)))
        rule.onNodeWithText("05.11.2026  19:00").assertExists()
        rule.onNodeWithText("05.11.2026  21:00").assertExists()
        rule.onNodeWithTag(EventEditorTags.CLEAR_END).performScrollTo().performClick()
        assertEquals(listOf<EventEditorAction>(EventEditorAction.ClearEnd), editorActions)
    }

    @Test
    fun `badge section shows image, location and issuer rows`() {
        editor(editorState(badge = true).copy(lat = 50.0880412, lng = 14.4207, issuerRows = listOf("kaputt")))
        rule.onNodeWithText("50.08804, 14.42070").performScrollTo().assertExists()
        rule.onNodeWithText(s(L10nR.string.evBadgeImageInfo)).performScrollTo().assertExists()
        rule.onNodeWithTag(EventEditorTags.PICK_IMAGE).performScrollTo().performClick()
        rule.onNodeWithContentDescription(s(R.string.native_events_clear_location)).performScrollTo().performClick()
        rule.onNodeWithContentDescription(s(R.string.native_events_add_issuer_row)).performScrollTo().performClick()
        assertEquals(
            listOf(EventEditorAction.PickImage, EventEditorAction.ClearLocation, EventEditorAction.AddIssuerRow),
            editorActions,
        )
    }

    @Test
    fun `unset badge location offers the map picker`() {
        editor(editorState(badge = true))
        rule.onNodeWithTag(EventEditorTags.PICK_LOCATION).performScrollTo().performClick()
        assertEquals(listOf<EventEditorAction>(EventEditorAction.PickLocation), editorActions)
    }

    @Test
    fun `publishing shows calPublishing`() {
        editor(editorState().copy(publishing = true))
        rule.onNodeWithText(s(L10nR.string.calPublishing)).performScrollTo().assertExists()
    }

    private fun myEvents(state: MyEventsUiState) {
        rule.setContent { EinundzwanzigTheme { MyEventsScreen(state, onAction = { myActions += it }) } }
    }

    @Test
    fun `my events loading and empty states`() {
        myEvents(MyEventsUiState(BERLIN, NOW, loading = false))
        rule.onNodeWithTag(MyEventsTags.EMPTY).assertExists()
        rule.onNodeWithText(s(L10nR.string.eventChatsEmpty)).assertExists()
    }

    @Test
    fun `my events loading indicator`() {
        myEvents(MyEventsUiState(BERLIN, NOW, loading = true))
        rule.onNodeWithTag(MyEventsTags.LOADING).assertExists()
    }

    @Test
    fun `my events lists sections, unread badge and dispatches chat, withdraw`() {
        val e = nostrEvent("e1", title = "Konferenz", start = NOW.plusSeconds(86_400))
        val date = MyMeetupDate("107", "München", meetupDate(), 5)
        myEvents(
            MyEventsUiState(BERLIN, NOW, loading = false, events = listOf(e), meetupDates = listOf(date), unread = mapOf(e.address to 150)),
        )
        rule.onNodeWithText(s(L10nR.string.eventChatsMeetups).uppercase()).assertExists()
        rule.onNodeWithText("22.10.2026 · 12:00 · ${context.getString(L10nR.string.rsvpAttendees, 5)}").assertExists()
        rule.onNodeWithTag(MyEventsTags.meetupChat(0)).performClick()
        rule.onNodeWithTag(MyEventsTags.LIST).performScrollToNode(hasText("Konferenz"))
        rule.onNodeWithText("99+").assertExists()
        rule.onNodeWithText("22.10.2026 · Wien").assertExists()
        rule.onNodeWithText("Konferenz").performClick()
        rule.onNodeWithTag(MyEventsTags.event(e.address)).performTouchInput { longClick() }
        assertEquals(
            listOf(MyEventsAction.OpenMeetupChat(date), MyEventsAction.OpenEventChat(e), MyEventsAction.RequestWithdraw(e)),
            myActions,
        )
    }

    @Test
    fun `withdraw dialog confirms`() {
        val e = nostrEvent("e1", title = "Konferenz")
        myEvents(MyEventsUiState(BERLIN, NOW, loading = false, events = listOf(e), withdrawTarget = e))
        rule.onNodeWithText(s(L10nR.string.rsvpWithdrawTitle)).assertExists()
        rule.onNodeWithText(s(L10nR.string.rsvpWithdrawConfirm)).performClick()
        assertTrue(myActions.contains(MyEventsAction.ConfirmWithdraw))
    }
}
