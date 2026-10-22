package space.einundzwanzig.meetup.feature.events

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.calendar.model.CalendarItem
import space.einundzwanzig.meetup.data.calendar.model.CalendarTypeFilter
import space.einundzwanzig.meetup.data.calendar.model.RsvpStatus
import space.einundzwanzig.meetup.feature.events.calendar.CalendarDetailsContent
import space.einundzwanzig.meetup.feature.events.calendar.CalendarItemDetails
import space.einundzwanzig.meetup.feature.events.calendar.CalendarSheetTags
import space.einundzwanzig.meetup.feature.events.calendar.CalendarView
import space.einundzwanzig.meetup.feature.events.calendar.EventCalendarAction
import space.einundzwanzig.meetup.feature.events.calendar.EventCalendarScreen
import space.einundzwanzig.meetup.feature.events.calendar.EventCalendarTags
import space.einundzwanzig.meetup.feature.events.calendar.EventCalendarUiState
import space.einundzwanzig.meetup.feature.events.calendar.HelperEditorContent
import space.einundzwanzig.meetup.feature.events.calendar.HelperEditorState
import space.einundzwanzig.meetup.feature.events.common.UiText
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class EventCalendarScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<EventCalendarAction>()
    private fun s(id: Int) = context.getString(id)

    private val today = LocalDate.of(2026, 10, 21)
    private val nostr = nostrEvent("n1", title = "Bitcoin Konferenz", description = "Infos https://example.org/info")
    private val item = CalendarItem.fromNostr(nostr)

    private fun state(
        items: List<CalendarItem> = listOf(item),
        loading: Boolean = false,
        view: CalendarView = CalendarView.MONTH,
        worldwide: Boolean = false,
        type: CalendarTypeFilter = CalendarTypeFilter.ALL,
    ) = EventCalendarUiState(
        zone = BERLIN, today = today, focused = YearMonth.of(2026, 10), selected = today, now = NOW,
        loading = loading, view = view, items = items, filtered = items,
        byDay = items.groupBy { it.day(BERLIN) }, worldwide = worldwide, typeFilter = type,
    )

    private fun show(state: EventCalendarUiState) {
        rule.setContent { EinundzwanzigTheme { EventCalendarScreen(state, onAction = { actions += it }) } }
    }

    private fun scrollTo(text: String) {
        rule.onNodeWithTag(EventCalendarTags.LIST).performScrollToNode(hasText(text))
    }

    @Test
    fun `loading shows calLoading`() {
        show(state(loading = true))
        rule.onNodeWithTag(EventCalendarTags.LOADING).assertExists()
        rule.onNodeWithText(s(L10nR.string.calLoading)).assertExists()
        rule.onNodeWithTag(EventCalendarTags.REFRESH).assertIsNotEnabled()
    }

    @Test
    fun `month view lists the selected day and opens the details`() {
        show(state())
        rule.onNodeWithText("${s(L10nR.string.calMonth10)} 2026").assertExists()
        scrollTo("Bitcoin Konferenz")
        rule.onNodeWithText("Bitcoin Konferenz").performClick()
        assertEquals(listOf<EventCalendarAction>(EventCalendarAction.OpenDetails(item)), actions)
    }

    @Test
    fun `empty day shows calNoEvents and footer`() {
        show(state(items = emptyList()))
        rule.onNodeWithTag(EventCalendarTags.LIST).performScrollToNode(hasTestTag(EventCalendarTags.DAY_EMPTY))
        rule.onNodeWithText(s(L10nR.string.calNoEvents)).assertExists()
    }

    @Test
    fun `day cell and month arrows dispatch`() {
        show(state())
        rule.onNodeWithTag(EventCalendarTags.LIST).performScrollToNode(hasTestTag(EventCalendarTags.day(today.plusDays(2))))
        rule.onNodeWithTag(EventCalendarTags.day(today.plusDays(2))).performClick()
        rule.onNodeWithTag(EventCalendarTags.LIST).performScrollToNode(hasTestTag(EventCalendarTags.MONTH_HEADER))
        rule.onNodeWithContentDescription(s(R.string.native_events_next_month)).performClick()
        assertEquals(listOf(EventCalendarAction.SelectDay(today.plusDays(2)), EventCalendarAction.ShiftMonth(1)), actions)
    }

    @Test
    fun `year view shows month tiles and opens a month`() {
        show(state(view = CalendarView.YEAR))
        rule.onNodeWithTag(EventCalendarTags.LIST).performScrollToNode(hasTestTag(EventCalendarTags.month(10)))
        rule.onNodeWithTag(EventCalendarTags.month(10)).performClick()
        assertEquals(listOf<EventCalendarAction>(EventCalendarAction.OpenMonth(10)), actions)
    }

    @Test
    fun `list view empty shows calNoEventsRange`() {
        show(state(items = emptyList(), view = CalendarView.LIST))
        rule.onNodeWithTag(EventCalendarTags.LIST).performScrollToNode(hasTestTag(EventCalendarTags.RANGE_EMPTY))
        rule.onNodeWithText(s(L10nR.string.calNoEventsRange)).assertExists()
    }

    @Test
    fun `view switcher, fab and worldwide row dispatch`() {
        show(state())
        rule.onNodeWithText(s(L10nR.string.calViewList)).performClick()
        rule.onNodeWithTag(EventCalendarTags.WORLDWIDE).performClick()
        rule.onNodeWithTag(EventCalendarTags.FAB).performClick()
        assertEquals(
            listOf(
                EventCalendarAction.SetView(CalendarView.LIST),
                EventCalendarAction.ToggleWorldwide,
                EventCalendarAction.AddEvent,
            ),
            actions,
        )
    }

    @Test
    fun `worldwide shows the hint, active filter shows the clear button`() {
        show(state(worldwide = true, type = CalendarTypeFilter.COURSES))
        rule.onNodeWithText(s(L10nR.string.calWorldwideHint)).assertExists()
        rule.onNodeWithTag(EventCalendarTags.CLEAR).performClick()
        rule.onNodeWithTag(EventCalendarTags.QUERY).performTextInput("Wien")
        assertEquals(EventCalendarAction.ClearFilter, actions[0])
        assertEquals(EventCalendarAction.SetQuery("Wien"), actions[1])
    }

    private fun showDetails(details: CalendarItemDetails, starting: Boolean = false) {
        rule.setContent {
            EinundzwanzigTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CalendarDetailsContent(details, BERLIN, starting, false) { actions += it }
                }
            }
        }
    }

    private fun details(
        event: space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent = nostr,
        issuer: Boolean = false,
        creator: Boolean = false,
        session: Boolean = false,
        running: Boolean = false,
        rsvp: RsvpStatus? = null,
    ) = CalendarItemDetails(
        item = CalendarItem.fromNostr(event), iAmIssuer = issuer, isCreator = creator, showSessionButton = session,
        sessionRunning = running, links = listOf("https://example.org/info"), rsvp = rsvp, rsvpBusy = false,
    )

    @Test
    fun `details of a foreign event show rsvp, chat, links and calendar`() {
        showDetails(details())
        rule.onNodeWithText(s(L10nR.string.calLegendEvent)).assertExists()
        rule.onNodeWithTag(CalendarSheetTags.CANCEL_BUTTON).assertDoesNotExist()
        rule.onNodeWithTag(CalendarSheetTags.RSVP_YES).performClick()
        rule.onNodeWithTag(CalendarSheetTags.RSVP_NO).performClick()
        rule.onNodeWithTag(CalendarSheetTags.CHAT).performScrollTo().performClick()
        rule.onNodeWithText("https://example.org/info").performScrollTo().performClick()
        rule.onNodeWithText(s(L10nR.string.evToCalendar)).performScrollTo().performClick()
        assertEquals(
            listOf(
                EventCalendarAction.SetRsvp(RsvpStatus.ACCEPTED),
                EventCalendarAction.SetRsvp(RsvpStatus.DECLINED),
                EventCalendarAction.OpenChat(nostr),
                EventCalendarAction.OpenUrl("https://example.org/info"),
                EventCalendarAction.AddToCalendar(CalendarItem.fromNostr(nostr)),
            ),
            actions,
        )
    }

    @Test
    fun `creator sees cancel and helper edit, issuer sees the session button`() {
        val badge = nostrEvent("b", pubkey = PK_ME, badge = true, lat = 1.0, lng = 1.0)
        showDetails(details(badge, issuer = true, creator = true, session = true))
        rule.onNodeWithText(s(L10nR.string.evBadgeYouIssue)).assertExists()
        rule.onNodeWithText(s(L10nR.string.evIssuerCreator)).assertExists()
        rule.onNodeWithText(s(L10nR.string.evBadgeStartSession)).performClick()
        rule.onNodeWithText(s(L10nR.string.evIssuersEdit)).performScrollTo().performClick()
        rule.onNodeWithTag(CalendarSheetTags.CANCEL_BUTTON).performScrollTo().performClick()
        assertEquals(
            listOf(EventCalendarAction.StartSession, EventCalendarAction.OpenHelperEditor, EventCalendarAction.RequestCancel),
            actions,
        )
    }

    @Test
    fun `running session shows QR anzeigen, busy shows locating hint`() {
        val badge = nostrEvent("b", pubkey = PK_ME, badge = true, lat = 1.0, lng = 1.0)
        showDetails(details(badge, issuer = true, session = true, running = true))
        rule.onNodeWithText(s(L10nR.string.evBadgeShowSession)).assertExists()
    }

    @Test
    fun `starting session shows the locating state`() {
        val badge = nostrEvent("b", pubkey = PK_ME, badge = true, lat = 1.0, lng = 1.0)
        showDetails(details(badge, issuer = true, session = true), starting = true)
        rule.onNodeWithText(s(L10nR.string.evBadgeLocating)).assertExists()
        rule.onNodeWithText(s(L10nR.string.evBadgeLocatingHint)).assertExists()
    }

    @Test
    fun `badge without location warns and has no session button`() {
        showDetails(details(nostrEvent("b", badge = true)))
        rule.onNodeWithText(s(L10nR.string.evBadgeAvailable)).assertExists()
        rule.onNodeWithText(s(L10nR.string.evBadgeNoLocationSet)).assertExists()
        rule.onNodeWithTag(CalendarSheetTags.SESSION_BUTTON).assertDoesNotExist()
    }

    @Test
    fun `helper editor shows none, error and dispatches add and save`() {
        rule.setContent {
            EinundzwanzigTheme {
                HelperEditorContent(
                    HelperEditorState(creator = PK_ME, helpers = emptyList(), input = "x", error = UiText.res(L10nR.string.evIssuersInvalid)),
                ) { actions += it }
            }
        }
        rule.onNodeWithText(s(L10nR.string.evIssuersNone)).assertExists()
        rule.onNodeWithText(s(L10nR.string.evIssuersInvalid)).assertExists()
        rule.onNodeWithTag(CalendarSheetTags.HELPER_ADD).performClick()
        rule.onNodeWithTag(CalendarSheetTags.HELPER_SAVE).performClick()
        assertEquals(listOf(EventCalendarAction.AddHelper, EventCalendarAction.SaveHelpers), actions)
    }
}
