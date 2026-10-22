package space.einundzwanzig.meetup.feature.badges

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.issuance.session.SessionRemaining
import space.einundzwanzig.meetup.feature.badges.rollingqr.RollingQrAction
import space.einundzwanzig.meetup.feature.badges.rollingqr.RollingQrEvent
import space.einundzwanzig.meetup.feature.badges.rollingqr.RollingQrViewModel

class RollingQrViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val time = SchedulerTime(BASE_MILLIS) { dispatcher.scheduler.currentTime }
    private val service = FakeRollingQrService()
    private val profiles = FakeProfiles(UserProfile(nostrNpub = "npub1me"))
    private val events = mutableListOf<RollingQrEvent>()
    private var vm: RollingQrViewModel? = null

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        vm?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun TestScope.create(resume: Boolean = true): RollingQrViewModel {
        val model = RollingQrViewModel(service, profiles, time).also { it.log = NoLog }
        backgroundScope.launch(dispatcher) { model.events.collect { events += it } }
        if (resume) model.onAction(RollingQrAction.Resumed)
        vm = model
        return model
    }

    @Test
    fun `no session shows the empty state`() = runTest(dispatcher) {
        val model = create()
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.session)
        assertEquals(0, service.generated)
    }

    @Test
    fun `expired session shows the empty state`() = runTest(dispatcher) {
        service.stored = session(expiresAt = BASE_SECONDS - 1)
        val model = create()
        assertNull(model.state.value.session)
    }

    @Test
    fun `active session renders the first code, countdown, remaining time and npub`() = runTest(dispatcher) {
        service.stored = session(expiresAt = BASE_SECONDS + 3 * 3600 + 12 * 60 + 30)
        val model = create()
        val state = model.state.value
        assertEquals("Würzburg, DE", state.meetupInfo)
        assertEquals("qr-1", state.qr)
        // BASE_SECONDS ends in 5 → 10 - 5.
        assertEquals(5, state.secondsLeft)
        assertEquals(SessionRemaining.HoursMinutes(3, 12), state.remaining)
        assertEquals("npub1me", state.npub)
    }

    @Test
    fun `npub falls back to the session organizer`() = runTest(dispatcher) {
        profiles.current = UserProfile()
        service.stored = session(expiresAt = BASE_SECONDS + 3600)
        assertEquals("npub1session", create().state.value.npub)
    }

    @Test
    fun `code regenerates on every 10-second step and the countdown ticks`() = runTest(dispatcher) {
        service.stored = session(expiresAt = BASE_SECONDS + 3600)
        val model = create()
        advanceTimeBy(1_001)
        assertEquals(4, model.state.value.secondsLeft)
        assertEquals(1, service.generated)
        advanceTimeBy(4_000)
        assertEquals(10, model.state.value.secondsLeft)
        assertEquals("qr-2", model.state.value.qr)
        advanceTimeBy(10_000)
        assertEquals("qr-3", model.state.value.qr)
    }

    @Test
    fun `slow generation across step boundaries regenerates the current step right away`() = runTest(dispatcher) {
        service.stored = session(expiresAt = BASE_SECONDS + 3600)
        // Starts 5 s into a step and takes 22 s (signer prompt): ends 7 s into step +2.
        service.generationDelays += 22_000L
        val model = create()
        assertNull(model.state.value.qr)
        advanceTimeBy(21_999)
        assertEquals(0, service.generated)
        advanceTimeBy(2)
        // The stale step's code is replaced immediately, not at the next boundary 3 s later.
        assertEquals(2, service.generated)
        assertEquals("qr-2", model.state.value.qr)
        advanceTimeBy(2_000)
        assertEquals(2, service.generated)
        advanceTimeBy(1_100)
        assertEquals(3, service.generated)
    }

    @Test
    fun `paused screen stops regenerating and resumes with a fresh code`() = runTest(dispatcher) {
        service.stored = session(expiresAt = BASE_SECONDS + 3600)
        val model = create()
        model.onAction(RollingQrAction.Paused)
        advanceTimeBy(30_000)
        assertEquals(1, service.generated)
        model.onAction(RollingQrAction.Resumed)
        assertEquals(2, service.generated)
    }

    @Test
    fun `expiry stops the UI without ending the session`() = runTest(dispatcher) {
        service.stored = session(expiresAt = BASE_SECONDS + 3)
        val model = create()
        assertTrue(model.state.value.session != null)
        advanceTimeBy(5_000)
        assertNull(model.state.value.session)
        assertEquals(0, service.ended)
    }

    @Test
    fun `generation errors keep the screen alive`() = runTest(dispatcher) {
        service.stored = session(expiresAt = BASE_SECONDS + 3600)
        service.qrError = IllegalStateException("Kein signierter Base-Payload")
        val model = create()
        assertNull(model.state.value.qr)
        assertTrue(model.state.value.session != null)
    }

    @Test
    fun `ending asks first, then wipes the session and pops`() = runTest(dispatcher) {
        service.stored = session(expiresAt = BASE_SECONDS + 3600)
        val model = create()
        model.onAction(RollingQrAction.EndClicked)
        assertTrue(model.state.value.confirmEnd)
        model.onAction(RollingQrAction.EndDismissed)
        assertFalse(model.state.value.confirmEnd)
        assertEquals(0, service.ended)
        model.onAction(RollingQrAction.EndClicked)
        model.onAction(RollingQrAction.EndConfirmed)
        assertEquals(1, service.ended)
        assertEquals(listOf<RollingQrEvent>(RollingQrEvent.Back), events)
    }

    @Test
    fun `end is not offered without a session`() = runTest(dispatcher) {
        val model = create()
        model.onAction(RollingQrAction.EndClicked)
        assertFalse(model.state.value.confirmEnd)
    }

    @Test
    fun `back pops`() = runTest(dispatcher) {
        create().onAction(RollingQrAction.Back)
        assertEquals(listOf<RollingQrEvent>(RollingQrEvent.Back), events)
    }

    private companion object {
        const val BASE_SECONDS = 1_760_000_005L
        const val BASE_MILLIS = BASE_SECONDS * 1000
    }
}
