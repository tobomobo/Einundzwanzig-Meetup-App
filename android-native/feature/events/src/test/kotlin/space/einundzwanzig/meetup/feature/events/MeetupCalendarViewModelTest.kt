package space.einundzwanzig.meetup.feature.events

import kotlinx.coroutines.CompletableDeferred
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
        MeetupCalendarViewModel(key, calendar, myEvents, meetups, profiles, portal, berlinZone, main.dispatcher)

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

    @Test
    fun `quick favorite double tap toggles twice instead of adding twice`() = runTest {
        val gate = CompletableDeferred<Unit>()
        profiles.updateGate = gate
        val vm = vm()
        vm.onAction(MeetupCalendarAction.ToggleFavorite("107"))
        vm.onAction(MeetupCalendarAction.ToggleFavorite("107"))
        gate.complete(Unit)
        assertEquals(emptyList<String>(), profiles.current.favoriteMeetupIds)
        assertFalse(vm.state.value.rows[0].isFavorite)
        val msgs = vm.messages.take(2).toList()
        assertEquals(UiText.res(L10nR.string.calFavAdded, "München"), msgs[0].text)
        assertEquals(UiText.res(L10nR.string.calFavRemoved, "München"), msgs[1].text)
    }

    @Test
    fun `failed favorite save keeps the star and shows no success snackbar`() = runTest {
        profiles.failUpdate = true
        val vm = vm()
        vm.onAction(MeetupCalendarAction.ToggleFavorite("107"))
        assertFalse(vm.state.value.rows[0].isFavorite)
        vm.onAction(MeetupCalendarAction.ShareCopied)
        // The next message is the share one: nothing was queued by the failed toggle.
        assertEquals(UiText.res(L10nR.string.evShareCopied), vm.messages.first().text)
    }

    @Test
    fun `rsvp double tap during the portal login starts only one login`() = runTest {
        portal.tokenOk = false
        val gate = CompletableDeferred<Unit>()
        portal.loginGate = gate
        val vm = vm()
        vm.onAction(MeetupCalendarAction.Rsvp(1, "attending"))
        assertTrue(vm.state.value.rows[0].rsvp!!.busy)
        vm.onAction(MeetupCalendarAction.Rsvp(1, "attending"))
        gate.complete(Unit)
        assertEquals(1, portal.logins)
        assertEquals(listOf(1 to "attending"), portal.rsvps)
        assertFalse(vm.state.value.rows[0].rsvp!!.busy)
    }

    @Test
    fun `search over 200 ICS dates and 300 meetups stays within a frame budget`() {
        // ICS dates (no meetupId/portal link) resolve through the city regex matcher: that must
        // happen once per data change, not per keystroke.
        meetups.meetups = (0 until 300).map { meetup("m$it", "Stadt$it", "Einundzwanzig Stadt$it") }
        calendar.dates = (0 until 200).map {
            meetupDate(title = "Stammtisch Stadt$it", id = null, meetupId = "", location = "Bar $it")
                .copy(url = "")
        }
        val vm = vm()
        assertEquals(200, vm.state.value.rows.size)
        assertEquals("m7", vm.state.value.rows[7].favKey)
        val query = "stammtisch stadt1"
        // Warm-up (JIT), then measure every prefix of the query as typed.
        repeat(3) { vm.onAction(MeetupCalendarAction.Search(query)) }
        val started = System.nanoTime()
        for (i in 1..query.length) vm.onAction(MeetupCalendarAction.Search(query.take(i)))
        val perKeyMs = (System.nanoTime() - started) / 1_000_000.0 / query.length
        assertTrue("rebuild per keystroke took $perKeyMs ms", perKeyMs < 16.0)
        assertEquals(111, vm.state.value.rows.size) // Stadt1, Stadt10–19, Stadt100–199
    }
}
