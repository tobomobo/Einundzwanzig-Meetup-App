package space.einundzwanzig.meetup.feature.events

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.MeetupCalendar
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.calendar.model.FavoriteCards
import space.einundzwanzig.meetup.data.calendar.model.FavoriteMeetupCard
import space.einundzwanzig.meetup.data.meetups.portal.PortalResult
import space.einundzwanzig.meetup.feature.events.common.UiText
import space.einundzwanzig.meetup.feature.events.meetups.MeetupCalendarAction
import space.einundzwanzig.meetup.feature.events.meetups.MeetupCalendarViewModel

class MeetupCalendarViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val munich = meetupDate()
    private val berlin = meetupDate(title = "Einundzwanzig Berlin", id = 2, meetupId = "200", location = "Kreuzberg")
    private val calendar = FakeMeetupCalendarService(listOf(munich, berlin))
    private val myEvents = FakeMyEventsService()
    private val meetups = FakeMeetupService(listOf(meetup(), meetup("200", "Berlin", "Einundzwanzig Berlin")))
    private val profiles = FakeProfiles()
    private val portal = FakePortal()

    private fun vm(key: MeetupCalendar = MeetupCalendar()) =
        MeetupCalendarViewModel(key, calendar, myEvents, meetups, profiles, portal, berlinZone)

    @Test
    fun `loads portal dates with formatted rows and RSVP rows`() {
        val s = vm().state.value
        assertFalse(s.loading)
        assertEquals(2, s.rows.size)
        val row = s.rows[0]
        assertEquals("22", row.dayText)
        assertEquals("10", row.monthText)
        assertEquals("12:00", row.timeText)
        assertEquals("107", row.favKey)
        assertFalse(row.isFavorite)
        assertNotNull(row.rsvp)
    }

    @Test
    fun `search filters by title and location case-insensitively`() {
        val vm = vm()
        vm.onAction(MeetupCalendarAction.Search("KREUZ"))
        assertEquals(listOf(berlin), vm.state.value.rows.map { it.date })
        vm.onAction(MeetupCalendarAction.Search("nichts"))
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test
    fun `prefilled search filters by exact meetup while unchanged`() {
        val vm = vm(MeetupCalendar(initialSearch = "Berlin", initialMeetupId = "200"))
        assertEquals("Berlin", vm.state.value.query)
        assertEquals(listOf(berlin), vm.state.value.rows.map { it.date })
    }

    @Test
    fun `widget entry preselects the front favorite card`() {
        myEvents.cards = FavoriteCards.Loaded(listOf(FavoriteMeetupCard("200", "Berlin", "Berlin", berlin)), true)
        val vm = vm(MeetupCalendar(preselectNextFavorite = true))
        assertEquals("Berlin", vm.state.value.query)
        assertEquals(listOf(berlin), vm.state.value.rows.map { it.date })
    }

    @Test
    fun `favorite toggle persists, sets home meetup and shows snackbars`() = runTest {
        val vm = vm()
        vm.onAction(MeetupCalendarAction.ToggleFavorite("107"))
        assertEquals(listOf("107"), profiles.current.favoriteMeetupIds)
        assertEquals("107", profiles.current.homeMeetupId)
        assertTrue(vm.state.value.rows[0].isFavorite)
        val added = vm.messages.first()
        assertEquals(UiText.res(L10nR.string.calFavAdded, "München"), added.text)
        assertEquals(Tone.Success, added.tone)
        vm.onAction(MeetupCalendarAction.ToggleFavorite("107"))
        assertEquals(emptyList<String>(), profiles.current.favoriteMeetupIds)
        assertEquals("", profiles.current.homeMeetupId)
        assertEquals(UiText.res(L10nR.string.calFavRemoved, "München"), vm.messages.first().text)
    }

    @Test
    fun `rsvp with a valid token sets the status locally`() {
        val vm = vm()
        vm.onAction(MeetupCalendarAction.Rsvp(1, "attending"))
        assertEquals(listOf(1 to "attending"), portal.rsvps)
        val rsvp = vm.state.value.rows[0].rsvp!!
        assertTrue(rsvp.going)
        assertFalse(rsvp.busy)
        assertEquals(0, portal.logins)
    }

    @Test
    fun `rsvp without token logs in first with progress snackbars`() = runTest {
        portal.tokenOk = false
        val vm = vm()
        vm.onAction(MeetupCalendarAction.Rsvp(1, "attending"))
        assertEquals(1, portal.logins)
        val msgs = vm.messages.take(2).toList()
        assertEquals(UiText.res(L10nR.string.portalConnecting), msgs[0].text)
        assertEquals(UiText.res(L10nR.string.portalConnected), msgs[1].text)
        assertEquals(listOf(1 to "attending"), portal.rsvps)
    }

    @Test
    fun `failed login aborts the rsvp`() = runTest {
        portal.tokenOk = false
        portal.loginResult = PortalResult(ok = false, statusCode = 401, error = "nein")
        val vm = vm()
        vm.onAction(MeetupCalendarAction.Rsvp(1, "attending"))
        assertTrue(portal.rsvps.isEmpty())
        val msgs = vm.messages.take(2).toList()
        assertEquals(Tone.Error, msgs[1].tone)
    }

    @Test
    fun `rsvp failure shows rsvpFailed with the error`() = runTest {
        portal.rsvpResult = PortalResult(ok = false, statusCode = 500, error = "kaputt")
        val vm = vm()
        vm.onAction(MeetupCalendarAction.Rsvp(1, "attending"))
        assertEquals(UiText.res(L10nR.string.rsvpFailed, "kaputt"), vm.messages.first().text)
        assertFalse(vm.state.value.rows[0].rsvp!!.going)
    }

    @Test
    fun `attendee list loads on first expand`() {
        portal.rsvpMap = mapOf(1 to buildJsonObject { put("attendees", 3) })
        val vm = vm()
        assertEquals(3, vm.state.value.rows[0].rsvp!!.count)
        vm.onAction(MeetupCalendarAction.ToggleAttendees(1))
        val rsvp = vm.state.value.rows[0].rsvp!!
        assertTrue(rsvp.expanded)
        assertEquals("Satoshi", rsvp.attendees!!.single().name)
        vm.onAction(MeetupCalendarAction.ToggleAttendees(1))
        assertFalse(vm.state.value.rows[0].rsvp!!.expanded)
    }

    @Test
    fun `details sheet toggles attending and withdraws on second tap`() {
        val vm = vm()
        vm.onAction(MeetupCalendarAction.OpenDetails(0))
        val details = vm.state.value.details!!
        assertEquals("22.10.2026 · 12:00", details.whenText)
        vm.onAction(MeetupCalendarAction.SheetRsvp("attending"))
        assertEquals("attending", vm.state.value.details!!.row.rsvp!!.status)
        vm.onAction(MeetupCalendarAction.SheetRsvp("attending"))
        assertEquals(listOf(1 to "attending", 1 to "none"), portal.rsvps)
        assertNull(vm.state.value.details!!.busyButton)
        vm.onAction(MeetupCalendarAction.CloseDetails)
        assertNull(vm.state.value.details)
    }

    @Test
    fun `share fallback shows evShareCopied`() = runTest {
        val vm = vm()
        vm.onAction(MeetupCalendarAction.ShareCopied)
        assertEquals(UiText.res(L10nR.string.evShareCopied), vm.messages.first().text)
    }
}
