package space.einundzwanzig.meetup.feature.events

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.meetups.portal.Attendee
import space.einundzwanzig.meetup.feature.events.meetups.MeetupCalendarAction
import space.einundzwanzig.meetup.feature.events.meetups.MeetupCalendarScreen
import space.einundzwanzig.meetup.feature.events.meetups.MeetupCalendarTags
import space.einundzwanzig.meetup.feature.events.meetups.MeetupCalendarUiState
import space.einundzwanzig.meetup.feature.events.meetups.MeetupDateDetails
import space.einundzwanzig.meetup.feature.events.meetups.MeetupDateRow
import space.einundzwanzig.meetup.feature.events.meetups.RsvpRowState

@RunWith(AndroidJUnit4::class)
class MeetupCalendarScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<MeetupCalendarAction>()
    private fun s(id: Int) = context.getString(id)

    private fun row(going: Boolean = false, expanded: Boolean = false, attendees: List<Attendee>? = null) = MeetupDateRow(
        index = 0, date = meetupDate(), dayText = "22", monthText = "10", timeText = "19:30", crestUrl = "",
        favKey = "107", isFavorite = false,
        rsvp = RsvpRowState(1, 4, 1, going, if (going) "attending" else "", false, expanded, attendees),
    )

    private fun show(state: MeetupCalendarUiState) {
        rule.setContent { EinundzwanzigTheme { MeetupCalendarScreen(state, onAction = { actions += it }) } }
    }

    @Test
    fun `loading and empty states`() {
        show(MeetupCalendarUiState(loading = true))
        rule.onNodeWithTag(MeetupCalendarTags.LOADING).assertExists()
    }

    @Test
    fun `empty list shows calendarNoEvents`() {
        show(MeetupCalendarUiState(loading = false))
        rule.onNodeWithText(s(L10nR.string.calendarNoEvents)).assertExists()
    }

    @Test
    fun `card shows date, time with Uhr and dispatches star, rsvp and details`() {
        show(MeetupCalendarUiState(loading = false, rows = listOf(row())))
        rule.onNodeWithText("Einundzwanzig München").assertExists()
        rule.onNodeWithText("19:30 Uhr").assertExists()
        rule.onNodeWithText("4 ${s(L10nR.string.rsvpCount)}").assertExists()
        rule.onNodeWithContentDescription(context.getString(R.string.native_events_favorite_add)).performClick()
        rule.onNodeWithText(s(L10nR.string.rsvpGoing)).performClick()
        rule.onNodeWithText("Einundzwanzig München").performClick()
        assertEquals(
            listOf(
                MeetupCalendarAction.ToggleFavorite("107"),
                MeetupCalendarAction.Rsvp(1, "attending"),
                MeetupCalendarAction.OpenDetails(0),
            ),
            actions,
        )
    }

    @Test
    fun `going row shows rsvpYouGo and cancels with none`() {
        show(MeetupCalendarUiState(loading = false, rows = listOf(row(going = true))))
        rule.onNodeWithText(s(L10nR.string.rsvpYouGo)).assertExists()
        rule.onNodeWithText(s(L10nR.string.rsvpCancel)).performClick()
        assertEquals(listOf(MeetupCalendarAction.Rsvp(1, "none")), actions)
    }

    @Test
    fun `expanded attendees show names, anonymous and empty hint`() {
        show(
            MeetupCalendarUiState(
                loading = false,
                rows = listOf(row(expanded = true, attendees = listOf(Attendee("npub1abcdefghijklmnopqrstuv", ""), Attendee("", "Hal")))),
            ),
        )
        rule.onNodeWithText(s(L10nR.string.rsvpAnon)).assertExists()
        rule.onNodeWithText("Hal").assertExists()
        rule.onNodeWithText("npub1abcde…qrstuv").assertExists()
    }

    @Test
    fun `search input dispatches Search`() {
        show(MeetupCalendarUiState(loading = false))
        rule.onNodeWithTag(MeetupCalendarTags.SEARCH).performTextInput("Wien")
        assertTrue(actions.contains(MeetupCalendarAction.Search("Wien")))
    }

    @Test
    fun `details sheet shows when line, counts, toggles and actions`() {
        val r = row()
        show(MeetupCalendarUiState(loading = false, rows = listOf(r), details = MeetupDateDetails(r, "22.10.2026 · 19:30", null)))
        rule.onNodeWithText("22.10.2026 · 19:30").assertExists()
        rule.onNodeWithText("4 ${s(L10nR.string.rsvpCount)} · 1 ${s(L10nR.string.rsvpMaybe)}").assertExists()
        rule.onNodeWithText(s(L10nR.string.rsvpImComing)).performClick()
        rule.onNodeWithText(s(L10nR.string.evShare)).performClick()
        rule.onNodeWithText(s(L10nR.string.evToCalendar)).performClick()
        rule.onNodeWithText(s(L10nR.string.evOpenLink)).performClick()
        assertEquals(MeetupCalendarAction.SheetRsvp("attending"), actions[0])
        assertTrue(actions[1] is MeetupCalendarAction.Share)
        assertTrue((actions[1] as MeetupCalendarAction.Share).text.startsWith("Einundzwanzig München · 22.10.2026 · 19:30\nAugustiner"))
        assertTrue(actions[2] is MeetupCalendarAction.AddToCalendar)
        assertEquals(MeetupCalendarAction.OpenUrl("https://portal.einundzwanzig.space/m/107"), actions[3])
    }
}
