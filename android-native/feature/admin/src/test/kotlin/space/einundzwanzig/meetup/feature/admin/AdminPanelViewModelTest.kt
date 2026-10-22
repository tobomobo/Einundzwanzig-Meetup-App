package space.einundzwanzig.meetup.feature.admin

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.navigation.MeetupSessionWizard
import space.einundzwanzig.meetup.core.navigation.RollingQr
import space.einundzwanzig.meetup.data.meetups.location.GpsStatus
import space.einundzwanzig.meetup.data.meetups.location.LocationMeetupResult

@OptIn(ExperimentalCoroutinesApi::class)
class AdminPanelViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val time = SchedulerTime(BASE) { dispatcher.scheduler.currentTime }
    private val repo = FakeAdminPanelRepository(time)
    private val events = mutableListOf<AdminPanelEvent>()
    private var vm: AdminPanelViewModel? = null

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        vm?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    /** Like [AdminPanelRoute]: the first resume right after composition triggers the session check. */
    private fun TestScope.create(nfc: Boolean = false, resume: Boolean = true): AdminPanelViewModel {
        val model = AdminPanelViewModel(repo, time, nfc, NoLog)
        backgroundScope.launch(dispatcher) { model.events.collect { events += it } }
        if (resume) model.onAction(AdminPanelAction.Resumed)
        vm = model
        return model
    }

    private val AdminPanelViewModel.dialog get() = state.value.dialog

    private fun gpsOk(vararg candidates: Pair<String, Double>, lat: Double = 49.79, lng: Double = 9.95) =
        LocationMeetupResult(GpsStatus.OK, lat, lng, candidates.map { candidate(it.first, it.second) })

    private fun AdminPanelViewModel.start() {
        onAction(AdminPanelAction.StartClicked)
        assertEquals(AdminDialog.ConfirmStart, dialog)
        onAction(AdminPanelAction.ConfirmStart)
    }

    // ---- Header and session state ----

    @Test
    fun `loads npub, trust-score source and super admin flag`() = runTest(dispatcher) {
        repo.info = AdminInfo("npub1abc", "trust_score", isSuperAdmin = true)
        val s = create().state.value
        assertEquals("npub1abc", s.adminNpub)
        assertTrue(s.viaTrustScore)
        assertTrue(s.isSuperAdmin)
    }

    @Test
    fun `other promotion sources show the organizer label`() = runTest(dispatcher) {
        repo.info = AdminInfo("npub1abc", "portal_organizer", isSuperAdmin = false)
        assertFalse(create().state.value.viaTrustScore)
    }

    @Test
    fun `no stored session shows the start tile`() = runTest(dispatcher) {
        val s = create().state.value
        assertTrue(s.sessionChecked)
        assertNull(s.session)
    }

    @Test
    fun `active session shows a ticking countdown`() = runTest(dispatcher) {
        repo.stored = session("Würzburg", BASE / 1000 + 2 * 3600 + 5 * 60 + 9)
        val model = create()
        assertEquals("2h 05m 09s", model.state.value.session?.timeLeft)
        assertEquals("Würzburg", model.state.value.session?.meetupName)
        advanceTimeBy(1_001)
        assertEquals("2h 05m 08s", model.state.value.session?.timeLeft)
    }

    @Test
    fun `expired stored session is ended on open`() = runTest(dispatcher) {
        repo.stored = session("Würzburg", BASE / 1000 - 10)
        val s = create().state.value
        assertNull(s.session)
        assertEquals(1, repo.endCalls)
    }

    @Test
    fun `countdown reaching expiry rechecks and clears the session`() = runTest(dispatcher) {
        repo.stored = session("Würzburg", BASE / 1000 + 3)
        val model = create()
        assertNotNull(model.state.value.session)
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(model.state.value.session)
        assertEquals(1, repo.endCalls)
    }

    @Test
    fun `timeLeft formats hours without padding and pads minutes and seconds`() {
        assertEquals("3h 59m 59s", AdminPanelViewModel.timeLeft(4 * 3600, 1_000))
        assertEquals("0h 00m 00s", AdminPanelViewModel.timeLeft(10, 20_000))
        assertEquals("12h 01m 05s", AdminPanelViewModel.timeLeft(12 * 3600 + 65, 0))
    }

    @Test
    fun `end early asks for confirmation then ends the session`() = runTest(dispatcher) {
        repo.stored = session("Würzburg", BASE / 1000 + 3600)
        val model = create()
        model.onAction(AdminPanelAction.EndClicked)
        assertEquals(AdminDialog.ConfirmEnd, model.dialog)
        model.onAction(AdminPanelAction.DismissDialog)
        assertNull(model.dialog)
        assertEquals(0, repo.endCalls)

        model.onAction(AdminPanelAction.EndClicked)
        model.onAction(AdminPanelAction.ConfirmEnd)
        assertNull(model.state.value.session)
        assertEquals(1, repo.endCalls)
    }

    @Test
    fun `open active meetup navigates to the rolling QR`() = runTest(dispatcher) {
        create().onAction(AdminPanelAction.OpenActiveMeetup)
        assertEquals(listOf(AdminPanelEvent.Navigate(RollingQr)), events)
    }

    @Test
    fun `back emits navigate back`() = runTest(dispatcher) {
        create().onAction(AdminPanelAction.Back)
        assertEquals(listOf(AdminPanelEvent.NavigateBack), events)
    }

    @Test
    fun `resume rechecks the stored session`() = runTest(dispatcher) {
        val model = create()
        repo.stored = session("Köln", BASE / 1000 + 3600)
        model.onAction(AdminPanelAction.Resumed)
        assertEquals("Köln", model.state.value.session?.meetupName)
    }

    // ---- Start flow ----

    @Test
    fun `cancelling the start confirmation does nothing`() = runTest(dispatcher) {
        val model = create()
        model.onAction(AdminPanelAction.StartClicked)
        model.onAction(AdminPanelAction.DismissDialog)
        assertNull(model.dialog)
        assertEquals(0, repo.resolveCalls)
    }

    @Test
    fun `one nearby meetup starts directly with the measured position`() = runTest(dispatcher) {
        repo.locations = mutableListOf(gpsOk("Würzburg" to 1.2))
        val model = create()
        model.start()
        assertEquals(listOf(FakeAdminPanelRepository.StartCall("Würzburg", "DE", 49.79, 9.95)), repo.starts)
        assertEquals(listOf(AdminPanelEvent.Navigate(RollingQr)), events)
        assertEquals("Würzburg", model.state.value.session?.meetupName)
        assertFalse(model.state.value.busy)
        assertNull(model.dialog)
    }

    @Test
    fun `nfc enabled opens the session wizard`() = runTest(dispatcher) {
        repo.locations = mutableListOf(gpsOk("Würzburg" to 1.2))
        create(nfc = true).start()
        assertEquals(listOf(AdminPanelEvent.Navigate(MeetupSessionWizard)), events)
    }

    @Test
    fun `several nearby meetups open the picker sheet with at most five`() = runTest(dispatcher) {
        val names = listOf("A", "B", "C", "D", "E", "F")
        repo.locations = mutableListOf(gpsOk(*names.mapIndexed { i, n -> n to i.toDouble() }.toTypedArray()))
        val model = create()
        model.start()
        val sheet = model.dialog as AdminDialog.PickNearby
        assertEquals(listOf("A", "B", "C", "D", "E"), sheet.candidates.map { it.meetup.city })
        model.onAction(AdminPanelAction.NearbyPicked(sheet.candidates[2]))
        assertEquals("C", repo.starts.single().name)
    }

    @Test
    fun `dismissing the nearby sheet aborts`() = runTest(dispatcher) {
        repo.locations = mutableListOf(gpsOk("A" to 1.0, "B" to 2.0))
        val model = create()
        model.start()
        model.onAction(AdminPanelAction.DismissDialog)
        assertNull(model.dialog)
        assertTrue(repo.starts.isEmpty())
    }

    @Test
    fun `candidates beyond 10 km do not count as nearby`() = runTest(dispatcher) {
        repo.locations = mutableListOf(gpsOk("Fern" to 25.0))
        repo.meetups = listOf(meetup("Würzburg"))
        val model = create()
        model.start()
        assertTrue(model.dialog is AdminDialog.PortalPicker)
    }

    @Test
    fun `denied gps offers app settings, retry and continue`() = runTest(dispatcher) {
        repo.locations = mutableListOf(LocationMeetupResult(GpsStatus.DENIED), gpsOk("Würzburg" to 1.0))
        val model = create()
        model.start()
        assertEquals(AdminDialog.ContinueWithoutGps(GpsStatus.DENIED), model.dialog)
        model.onAction(AdminPanelAction.OpenAppSettings)
        assertEquals(1, repo.appSettingsOpened)
        assertEquals(AdminDialog.ContinueWithoutGps(GpsStatus.DENIED), model.dialog)

        model.onAction(AdminPanelAction.GpsRetry)
        assertEquals(2, repo.resolveCalls)
        assertEquals("Würzburg", repo.starts.single().name)
    }

    @Test
    fun `disabled gps opens location settings and dismiss aborts`() = runTest(dispatcher) {
        repo.locations = mutableListOf(LocationMeetupResult(GpsStatus.SERVICE_DISABLED))
        val model = create()
        model.start()
        model.onAction(AdminPanelAction.OpenLocationSettings)
        assertEquals(1, repo.locationSettingsOpened)
        model.onAction(AdminPanelAction.DismissDialog)
        assertNull(model.dialog)
        assertEquals(1, repo.resolveCalls)
        assertTrue(repo.starts.isEmpty())
    }

    @Test
    fun `without gps the portal picker lists own meetups first`() = runTest(dispatcher) {
        repo.meetups = listOf(meetup("Zürich"), meetup("Aachen"), meetup("Würzburg", name = "Einundzwanzig Würzburg"))
        repo.mine = setOf("einundzwanzig würzburg")
        val model = create()
        model.start()
        model.onAction(AdminPanelAction.GpsContinue)
        val picker = model.dialog as AdminDialog.PortalPicker
        assertEquals(listOf("Würzburg", "Aachen", "Zürich"), picker.rows.map { it.meetup.city })
        assertTrue(picker.rows.first().isMine)

        model.onAction(AdminPanelAction.PickerQueryChanged("  ZÜR "))
        assertEquals(listOf("Zürich"), (model.dialog as AdminDialog.PortalPicker).visibleRows.map { it.meetup.city })
    }

    @Test
    fun `picking a portal meetup confirms and starts at 0,0 without gps`() = runTest(dispatcher) {
        val wue = meetup("Würzburg", country = "DE")
        repo.meetups = listOf(wue)
        val model = create()
        model.start()
        model.onAction(AdminPanelAction.GpsContinue)
        model.onAction(AdminPanelAction.PickerRowClicked(wue))
        assertEquals(wue, (model.dialog as AdminDialog.PortalPicker).confirming)
        model.onAction(AdminPanelAction.PickerConfirmYes)
        // Portal coordinates are never used as organizer position.
        assertEquals(listOf(FakeAdminPanelRepository.StartCall("Würzburg", "DE", 0.0, 0.0)), repo.starts)
    }

    @Test
    fun `cancelling the pick confirmation returns to the list`() = runTest(dispatcher) {
        val wue = meetup("Würzburg")
        repo.meetups = listOf(wue)
        val model = create()
        model.start()
        model.onAction(AdminPanelAction.GpsContinue)
        model.onAction(AdminPanelAction.PickerRowClicked(wue))
        model.onAction(AdminPanelAction.DismissDialog)
        val picker = model.dialog as AdminDialog.PortalPicker
        assertNull(picker.confirming)
        assertTrue(repo.starts.isEmpty())
    }

    @Test
    fun `meetup without coordinates and no gps asks for no-reference confirmation`() = runTest(dispatcher) {
        val nowhere = meetup("Irgendwo", lat = 0.0, lng = 0.0)
        repo.meetups = listOf(nowhere)
        val model = create()
        model.start()
        model.onAction(AdminPanelAction.GpsContinue)
        model.onAction(AdminPanelAction.PickerRowClicked(nowhere))
        model.onAction(AdminPanelAction.PickerConfirmYes)
        assertEquals(AdminDialog.NoReference("Irgendwo"), model.dialog)
        model.onAction(AdminPanelAction.NoReferenceContinue)
        assertEquals("Irgendwo", repo.starts.single().name)
    }

    @Test
    fun `declining no-reference aborts`() = runTest(dispatcher) {
        val nowhere = meetup("Irgendwo", lat = 0.0, lng = 0.0)
        repo.meetups = listOf(nowhere)
        val model = create()
        model.start()
        model.onAction(AdminPanelAction.GpsContinue)
        model.onAction(AdminPanelAction.PickerRowClicked(nowhere))
        model.onAction(AdminPanelAction.PickerConfirmYes)
        model.onAction(AdminPanelAction.DismissDialog)
        assertNull(model.dialog)
        assertTrue(repo.starts.isEmpty())
    }

    @Test
    fun `manual entry without gps needs a location and retry restarts`() = runTest(dispatcher) {
        repo.meetups = listOf(meetup("Würzburg"))
        val model = create()
        model.start()
        model.onAction(AdminPanelAction.GpsContinue)
        model.onAction(AdminPanelAction.PickerEnterManually)
        assertEquals(AdminDialog.CustomNeedsLocation, model.dialog)
        model.onAction(AdminPanelAction.GpsRetry)
        assertEquals(2, repo.resolveCalls)
        assertTrue(model.dialog is AdminDialog.ContinueWithoutGps)
    }

    @Test
    fun `dismissing the picker means enter manually`() = runTest(dispatcher) {
        repo.locations = mutableListOf(gpsOk())
        repo.meetups = listOf(meetup("Würzburg"))
        val model = create()
        model.start()
        model.onAction(AdminPanelAction.DismissDialog)
        assertEquals(AdminDialog.ManualName(), model.dialog)
    }

    @Test
    fun `manual name validates and starts with trimmed name and no country`() = runTest(dispatcher) {
        repo.locations = mutableListOf(gpsOk())
        repo.meetups = listOf(meetup("Würzburg"))
        val model = create()
        model.start()
        model.onAction(AdminPanelAction.PickerEnterManually)
        model.onAction(AdminPanelAction.ManualNameChanged("   "))
        model.onAction(AdminPanelAction.ManualNameSubmit)
        assertEquals(AdminDialog.ManualName("   ", showError = true), model.dialog)

        model.onAction(AdminPanelAction.ManualNameChanged("  Garten Meetup "))
        assertEquals(AdminDialog.ManualName("  Garten Meetup ", showError = false), model.dialog)
        model.onAction(AdminPanelAction.ManualNameSubmit)
        assertEquals(listOf(FakeAdminPanelRepository.StartCall("Garten Meetup", "", 49.79, 9.95)), repo.starts)
    }

    @Test
    fun `cancelling the manual name aborts`() = runTest(dispatcher) {
        repo.locations = mutableListOf(gpsOk())
        val model = create()
        model.start()
        // Empty directory skips the picker (Dart returns null → manual).
        assertEquals(AdminDialog.ManualName(), model.dialog)
        model.onAction(AdminPanelAction.DismissDialog)
        assertNull(model.dialog)
        assertTrue(repo.starts.isEmpty())
    }

    @Test
    fun `start failure shows the error snackbar`() = runTest(dispatcher) {
        repo.locations = mutableListOf(gpsOk("Würzburg" to 1.0))
        repo.startError = IllegalStateException("kein Signer")
        val model = create()
        model.start()
        assertEquals(listOf(AdminPanelEvent.Error("kein Signer")), events)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.session)
    }

    @Test
    fun `the session is checked on resume only, not twice on first display`() = runTest(dispatcher) {
        repo.stored = session("Würzburg", BASE / 1000 + 3600)
        val model = create(resume = false)
        assertFalse(model.state.value.sessionChecked)
        model.onAction(AdminPanelAction.Resumed)
        assertEquals("Würzburg", model.state.value.session?.meetupName)
    }

    @Test
    fun `failing admin info does not crash and the session is still checked`() = runTest(dispatcher) {
        repo.infoError = IllegalStateException("store kaputt")
        val s = create().state.value
        assertEquals("", s.adminNpub)
        assertTrue(s.sessionChecked)
    }

    @Test
    fun `failing location lookup ends the flow with the error snackbar`() = runTest(dispatcher) {
        repo.resolveError = IllegalStateException("gps kaputt")
        val model = create()
        model.start()
        assertEquals(listOf(AdminPanelEvent.Error("gps kaputt")), events)
        assertFalse(model.state.value.busy)
        assertNull(model.dialog)
        assertTrue(repo.starts.isEmpty())

        // The flow is not stuck: a new start runs again.
        repo.resolveError = null
        repo.locations = mutableListOf(gpsOk("Würzburg" to 1.0))
        model.start()
        assertEquals("Würzburg", repo.starts.single().name)
    }

    @Test
    fun `failing end early still clears the session and shows the error`() = runTest(dispatcher) {
        repo.stored = session("Würzburg", BASE / 1000 + 3600)
        val model = create()
        repo.endError = IllegalStateException("wipe kaputt")
        model.onAction(AdminPanelAction.EndClicked)
        model.onAction(AdminPanelAction.ConfirmEnd)
        assertEquals(listOf(AdminPanelEvent.Error("wipe kaputt")), events)
        assertNull(model.state.value.session)
        advanceTimeBy(5_000)
        assertNull(model.state.value.session)
    }

    @Test
    fun `failing end of an expired session still clears it and shows the error`() = runTest(dispatcher) {
        repo.autoExpire = false
        repo.stored = session("Würzburg", BASE / 1000 - 10)
        repo.endError = IllegalStateException("wipe kaputt")
        val s = create().state.value
        assertEquals(1, repo.endCalls)
        assertTrue(s.sessionChecked)
        assertNull(s.session)
        assertEquals(listOf(AdminPanelEvent.Error("wipe kaputt")), events)
    }

    @Test
    fun `empty directory with gps goes straight to the manual name`() = runTest(dispatcher) {
        repo.locations = mutableListOf(gpsOk())
        repo.meetups = emptyList()
        val model = create()
        model.start()
        assertEquals(AdminDialog.ManualName(), model.dialog)
    }

    @Test
    fun `empty directory without gps needs a location`() = runTest(dispatcher) {
        repo.meetups = emptyList()
        val model = create()
        model.start()
        model.onAction(AdminPanelAction.GpsContinue)
        assertEquals(AdminDialog.CustomNeedsLocation, model.dialog)
        model.onAction(AdminPanelAction.DismissDialog)
        assertNull(model.dialog)
        assertTrue(repo.starts.isEmpty())
    }

    private companion object {
        const val BASE = 1_790_000_000_000L
    }
}
