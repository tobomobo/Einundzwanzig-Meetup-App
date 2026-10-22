package space.einundzwanzig.meetup.feature.events

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.EventCalendar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.calendar.RsvpError
import space.einundzwanzig.meetup.data.calendar.model.CalendarItem
import space.einundzwanzig.meetup.data.calendar.model.CalendarTypeFilter
import space.einundzwanzig.meetup.data.calendar.model.RsvpStatus
import space.einundzwanzig.meetup.data.issuance.event.EventSessionError
import space.einundzwanzig.meetup.data.issuance.event.EventSessionResult
import space.einundzwanzig.meetup.data.issuance.session.MeetupSession
import space.einundzwanzig.meetup.feature.events.calendar.CalendarView
import space.einundzwanzig.meetup.feature.events.calendar.EventCalendarAction
import space.einundzwanzig.meetup.feature.events.calendar.EventCalendarEffect
import space.einundzwanzig.meetup.feature.events.calendar.EventCalendarViewModel
import space.einundzwanzig.meetup.feature.events.common.MessageAction
import space.einundzwanzig.meetup.feature.events.common.UiText
import java.time.LocalDate
import java.time.YearMonth

class EventCalendarViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val meetupItem = CalendarItem.fromMeetup(meetupDate(start = NOW.plusSeconds(3_600)))
    private val nostr = nostrEvent("n1", start = NOW.plusSeconds(7_200), location = "")
    private val nostrItem = CalendarItem.fromNostr(nostr)
    private val repo = FakeEventCalendarRepository(listOf(meetupItem, nostrItem))
    private val events = FakeCalendarEventService()
    private val rsvp = FakeRsvpService()
    private val sessionService = FakeSessionService()
    private val identity = FakeIdentity()
    private val sessions = FakeSessions()

    private fun vm(key: EventCalendar = EventCalendar()) = EventCalendarViewModel(
        key, repo, events, rsvp, sessionService, identity, sessions, { "" }, FixedTime(), berlinZone,
    )

    @Test
    fun `init loads community items, selects today and focuses its month`() {
        val vm = vm()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf(false), repo.calls)
        assertEquals(LocalDate.of(2026, 10, 21), s.selected)
        assertEquals(YearMonth.of(2026, 10), s.focused)
        assertEquals(CalendarView.MONTH, s.view)
        assertEquals(2, s.selectedItems.size)
        assertEquals(PK_ME, s.myPubkey)
    }

    @Test
    fun `initial day from the key is selected and focused`() {
        val vm = vm(EventCalendar("2027-02-03"))
        assertEquals(LocalDate.of(2027, 2, 3), vm.state.value.selected)
        assertEquals(YearMonth.of(2027, 2), vm.state.value.focused)
    }

    @Test
    fun `type filter and location query filter, clear resets both`() {
        val vm = vm()
        vm.onAction(EventCalendarAction.SetType(CalendarTypeFilter.MEETUPS))
        assertEquals(listOf(meetupItem), vm.state.value.filtered)
        assertTrue(vm.state.value.filterActive)
        vm.onAction(EventCalendarAction.SetType(CalendarTypeFilter.ALL))
        vm.onAction(EventCalendarAction.SetQuery("zzz"))
        // Items with an empty location are always kept.
        assertEquals(listOf(nostrItem), vm.state.value.filtered)
        vm.onAction(EventCalendarAction.ClearFilter)
        assertEquals(2, vm.state.value.filtered.size)
        assertFalse(vm.state.value.filterActive)
    }

    @Test
    fun `worldwide toggle reloads in worldwide mode`() {
        val vm = vm()
        vm.onAction(EventCalendarAction.ToggleWorldwide)
        assertTrue(vm.state.value.worldwide)
        assertEquals(listOf(false, true), repo.calls)
    }

    @Test
    fun `month and year navigation, year tile opens the month view`() {
        val vm = vm()
        vm.onAction(EventCalendarAction.ShiftMonth(1))
        assertEquals(YearMonth.of(2026, 11), vm.state.value.focused)
        vm.onAction(EventCalendarAction.SetView(CalendarView.YEAR))
        vm.onAction(EventCalendarAction.ShiftYear(-1))
        assertEquals(mapOf<Int, Int>(), vm.state.value.yearCounts)
        vm.onAction(EventCalendarAction.ShiftYear(1))
        assertEquals(mapOf(10 to 2), vm.state.value.yearCounts)
        vm.onAction(EventCalendarAction.OpenMonth(3))
        assertEquals(YearMonth.of(2026, 3), vm.state.value.focused)
        assertEquals(CalendarView.MONTH, vm.state.value.view)
    }

    @Test
    fun `refresh is ignored while loading and reloads otherwise`() {
        val vm = vm()
        vm.onAction(EventCalendarAction.Refresh)
        assertEquals(2, repo.calls.size)
    }

    @Test
    fun `refresh bypasses the meetup date cache, the initial load does not`() {
        val vm = vm()
        vm.onAction(EventCalendarAction.ToggleWorldwide)
        vm.onAction(EventCalendarAction.Refresh)
        assertEquals(listOf(false, false, true), repo.forced)
    }

    @Test
    fun `rsvp is optimistic and kept on success`() {
        val vm = vm()
        vm.onAction(EventCalendarAction.OpenDetails(nostrItem))
        vm.onAction(EventCalendarAction.SetRsvp(RsvpStatus.ACCEPTED))
        assertEquals(listOf(nostr.address to RsvpStatus.ACCEPTED), rsvp.sent)
        assertEquals(RsvpStatus.ACCEPTED, vm.state.value.details?.rsvp)
        assertFalse(vm.state.value.details!!.rsvpBusy)
    }

    @Test
    fun `rsvp failure restores the previous status and shows rsvpFailed`() = runTest {
        rsvp.mine = mapOf(nostr.address to RsvpStatus.DECLINED)
        rsvp.error = RsvpError.NotAccepted
        val vm = vm()
        vm.onAction(EventCalendarAction.OpenDetails(nostrItem))
        vm.onAction(EventCalendarAction.SetRsvp(RsvpStatus.ACCEPTED))
        assertEquals(RsvpStatus.DECLINED, vm.state.value.rsvps[nostr.address])
        val m = vm.messages.first()
        assertEquals(L10nR.string.rsvpFailed, (m.text as UiText.Res).id)
        assertEquals(Tone.Error, m.tone)
    }

    @Test
    fun `details flags for an issuer on the event day with coordinates`() {
        val badge = nostrEvent("b1", pubkey = PK_ME, badge = true, lat = 48.2, lng = 16.3)
        val vm = vm()
        vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(badge)))
        val d = vm.state.value.details!!
        assertTrue(d.iAmIssuer)
        assertTrue(d.isCreator)
        assertTrue(d.showSessionButton)
        assertFalse(d.sessionRunning)
        assertTrue(identity.prefetched.contains(PK_ME))
    }

    @Test
    fun `no session button without coordinates or for non issuers`() {
        val vm = vm()
        vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(nostrEvent("x", pubkey = PK_ME, badge = true))))
        assertFalse(vm.state.value.details!!.showSessionButton)
        vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(nostrEvent("y", badge = true, lat = 1.0, lng = 1.0))))
        assertFalse(vm.state.value.details!!.iAmIssuer)
        assertFalse(vm.state.value.details!!.showSessionButton)
    }

    @Test
    fun `running session containing the dTag shows QR anzeigen`() {
        sessions.running = "ev|31923:$PK_ME:b1"
        val vm = vm()
        vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(nostrEvent("b1", pubkey = PK_ME, badge = true, lat = 1.0, lng = 1.0))))
        assertTrue(vm.state.value.details!!.sessionRunning)
    }

    @Test
    fun `session start success closes the sheet and opens the rolling QR`() = runTest {
        sessionService.result = EventSessionResult(session = session())
        val vm = vm()
        val badge = nostrEvent("b1", pubkey = PK_ME, badge = true, lat = 1.0, lng = 1.0)
        vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(badge)))
        vm.onAction(EventCalendarAction.StartSession)
        assertEquals(listOf(badge), sessionService.started)
        assertNull(vm.state.value.details)
        assertFalse(vm.state.value.startingSession)
        assertEquals(EventCalendarEffect.OpenRollingQr, vm.effects.first())
    }

    @Test
    fun `session errors map to their message, duration and settings action`() {
        val tooFar = EventCalendarViewModel.sessionErrorMessage(EventSessionError.TOO_FAR_AWAY, 7.26)
        assertEquals(UiText.res(L10nR.string.evSessionTooFar, "7.3"), tooFar.text)
        assertNull(tooFar.action)
        assertNull(tooFar.actionLabel)
        assertEquals(AppSnackbarDurations.Long, tooFar.durationMillis)
        val noFix = EventCalendarViewModel.sessionErrorMessage(EventSessionError.LOCATION_NO_FIX, null)
        assertEquals(AppSnackbarDurations.VeryLong, noFix.durationMillis)
        val off = EventCalendarViewModel.sessionErrorMessage(EventSessionError.LOCATION_SERVICE_OFF, null)
        assertEquals(MessageAction.OPEN_LOCATION_SETTINGS, off.action)
        assertEquals(L10nR.string.evSessionOpenSettings, off.actionLabel)
        val forever = EventCalendarViewModel.sessionErrorMessage(EventSessionError.LOCATION_DENIED_FOREVER, null)
        assertEquals(MessageAction.OPEN_APP_SETTINGS, forever.action)
        val failed = EventCalendarViewModel.sessionErrorMessage(null, null)
        assertEquals(UiText.res(L10nR.string.evSessionFailed), failed.text)
        assertEquals(
            UiText.res(L10nR.string.evSessionTooFar, "0.0"),
            EventCalendarViewModel.sessionErrorMessage(EventSessionError.TOO_FAR_AWAY, null).text,
        )
    }

    @Test
    fun `session failure shows a message and keeps the sheet`() = runTest {
        sessionService.result = EventSessionResult(error = EventSessionError.LOCATION_DENIED)
        val vm = vm()
        vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(nostrEvent("b1", pubkey = PK_ME, badge = true, lat = 1.0, lng = 1.0))))
        vm.onAction(EventCalendarAction.StartSession)
        assertNotNull(vm.state.value.details)
        assertEquals(UiText.res(L10nR.string.evSessionLocationDenied), vm.messages.first().text)
    }

    @Test
    fun `cancel confirm cancels, closes the sheet and reloads`() = runTest {
        val own = nostrEvent("o1", pubkey = PK_ME)
        val vm = vm()
        vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(own)))
        vm.onAction(EventCalendarAction.RequestCancel)
        assertTrue(vm.state.value.confirmCancel)
        vm.onAction(EventCalendarAction.ConfirmCancel)
        assertEquals(listOf(own), events.cancelled)
        assertNull(vm.state.value.details)
        assertFalse(vm.state.value.confirmCancel)
        assertEquals(2, repo.calls.size)
        assertEquals(UiText.res(L10nR.string.evCancelDone), vm.messages.first().text)
    }

    @Test
    fun `cancel failure shows evCancelFailed without reload`() = runTest {
        events.cancelResult = false
        val vm = vm()
        vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(nostrEvent("o1", pubkey = PK_ME))))
        vm.onAction(EventCalendarAction.ConfirmCancel)
        assertEquals(1, repo.calls.size)
        assertEquals(UiText.res(L10nR.string.evCancelFailed), vm.messages.first().text)
    }

    @Test
    fun `helper editor validates, adds, removes and saves`() = runTest {
        val own = nostrEvent("o1", pubkey = PK_ME, badge = true, issuers = listOf(PK_ME, PK_OTHER.uppercase()))
        val vm = vm()
        vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(own)))
        vm.onAction(EventCalendarAction.OpenHelperEditor)
        assertEquals(listOf(PK_OTHER), vm.state.value.helperEditor!!.helpers)

        vm.onAction(EventCalendarAction.HelperInput("npub1invalid"))
        vm.onAction(EventCalendarAction.AddHelper)
        assertEquals(UiText.res(L10nR.string.evIssuersInvalid), vm.state.value.helperEditor!!.error)

        vm.onAction(EventCalendarAction.HelperInput(PK_ME))
        vm.onAction(EventCalendarAction.AddHelper)
        assertEquals(UiText.res(L10nR.string.evIssuersDuplicate), vm.state.value.helperEditor!!.error)

        vm.onAction(EventCalendarAction.HelperInput(PK_HELPER))
        vm.onAction(EventCalendarAction.AddHelper)
        assertEquals(listOf(PK_OTHER, PK_HELPER), vm.state.value.helperEditor!!.helpers)
        assertEquals("", vm.state.value.helperEditor!!.input)
        assertNull(vm.state.value.helperEditor!!.error)

        vm.onAction(EventCalendarAction.RemoveHelper(PK_OTHER))
        vm.onAction(EventCalendarAction.SaveHelpers)
        assertEquals(listOf(own to listOf(PK_HELPER)), events.updated)
        assertNull(vm.state.value.helperEditor)
        assertNull(vm.state.value.details)
        assertEquals(UiText.res(L10nR.string.evIssuersSaved), vm.messages.first().text)
    }

    @Test
    fun `helper save failure keeps the details sheet`() = runTest {
        events.updateResult = false
        val vm = vm()
        vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(nostrEvent("o1", pubkey = PK_ME, badge = true))))
        vm.onAction(EventCalendarAction.OpenHelperEditor)
        vm.onAction(EventCalendarAction.SaveHelpers)
        assertNull(vm.state.value.helperEditor)
        assertNotNull(vm.state.value.details)
        assertEquals(UiText.res(L10nR.string.evIssuersFailed), vm.messages.first().text)
    }

    private fun session() = MeetupSession(
        seed = "s", startedAt = NOW.epochSecond, expiresAt = NOW.epochSecond + 3_600, meetupId = "ev|x",
        meetupName = "x", meetupCountry = "", blockHeight = 1, pubkey = "npub1x", lat = 0.0, lng = 0.0,
    )
}
