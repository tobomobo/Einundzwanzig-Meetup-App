package space.einundzwanzig.meetup.feature.meetups.portal

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.navigation.PortalEventEditor
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.meetups.portal.PortalErrorKind
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetupEvent
import space.einundzwanzig.meetup.data.meetups.portal.PortalResult
import space.einundzwanzig.meetup.feature.meetups.FakePortal
import space.einundzwanzig.meetup.feature.meetups.FixedTime
import space.einundzwanzig.meetup.feature.meetups.MainDispatcherRule
import space.einundzwanzig.meetup.feature.meetups.R
import space.einundzwanzig.meetup.feature.meetups.common.UiText
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.TimeZone
import space.einundzwanzig.meetup.core.l10n.R as L10nR

class PortalEventsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val portal = FakePortal()
    private lateinit var savedZone: TimeZone

    // 2026-07-14T03:33:20Z
    private val time = FixedTime(1_784_000_000_000)

    @Before
    fun berlin() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    @After
    fun restore() = TimeZone.setDefault(savedZone)

    private val events = listOf(
        PortalMeetupEvent(12, 1, "2026-08-01 17:00:00", "Bar", "d", "https://x"),
        PortalMeetupEvent(11, 1, "2026-07-21 16:00:00", null),
        PortalMeetupEvent(99, 2, "2026-07-01 10:00:00", "Other"),
        PortalMeetupEvent(null, 1, "kaputt", ""),
    )

    // ---- ManageEventsScreen -------------------------------------------------------------------

    @Test
    fun `events of the meetup are sorted by the raw start and formatted in local time`() {
        portal.myEvents = events
        val vm = PortalMeetupEventsViewModel(1, "A", portal)
        val rows = vm.state.value.rows
        assertFalse(vm.state.value.loading)
        assertEquals(listOf(11, 12, null), rows.map { it.eventId })
        assertEquals(listOf(0, 1, 2), rows.map { it.index })
        assertEquals("21.07.2026  18:00", rows[0].whenText)
        assertEquals("01.08.2026  19:00", rows[1].whenText)
        assertEquals("kaputt", rows[2].whenText)
        assertNull(rows[0].location)
    }

    @Test
    fun `saved result shows the green snackbar and reloads`() = runTest {
        val vm = PortalMeetupEventsViewModel(1, "A", portal)
        vm.onSaved(created = true)
        val m = vm.messages.first()
        assertEquals(UiText.Res(L10nR.string.portalCreatedOk), m.text)
        assertEquals(Tone.Success, m.tone)
        assertEquals(2, portal.getMyEventsCalls)
        vm.onSaved(created = false)
        assertEquals(UiText.Res(L10nR.string.portalUpdatedOk), vm.messages.first().text)
    }

    @Test
    fun `pull to refresh reloads`() {
        val vm = PortalMeetupEventsViewModel(1, "A", portal)
        portal.myEvents = events
        vm.onAction(PortalMeetupEventsAction.Refresh)
        assertFalse(vm.state.value.refreshing)
        assertEquals(3, vm.state.value.rows.size)
    }

    // ---- EventEditor --------------------------------------------------------------------------

    private fun editor(key: PortalEventEditor, saved: SavedStateHandle = SavedStateHandle()) =
        PortalEventEditorViewModel(key, portal, time, saved)

    @Test
    fun `create without a start asks for date and time`() = runTest {
        val vm = editor(PortalEventEditor(1, "A"))
        assertFalse(vm.state.value.isEdit)
        vm.onAction(PortalEventEditorAction.Save)
        assertEquals(UiText.Res(L10nR.string.portalNeedStart), vm.messages.first().text)
        assertTrue(portal.created.isEmpty())
    }

    @Test
    fun `dismissed time picker means 19 00 and create sends UTC with omitted empties`() = runTest {
        val vm = editor(PortalEventEditor(1, "A"))
        vm.onAction(PortalEventEditorAction.StartPicked(LocalDate.of(2026, 7, 21), null))
        assertEquals(LocalTime.of(19, 0), vm.state.value.start!!.toLocalTime())
        vm.onAction(PortalEventEditorAction.LocationChanged("  Dinx  "))
        vm.onAction(PortalEventEditorAction.LinkChanged("   "))
        vm.onAction(PortalEventEditorAction.Save)
        assertEquals(listOf("1", "2026-07-21T17:00:00.000Z", "Dinx", null, null), portal.created.single())
        assertEquals(PortalEventEditorEvent.Saved(created = true), vm.events.first())
    }

    @Test
    fun `date picker range is today minus one day to plus 1095 days`() {
        val s = editor(PortalEventEditor(1, "A")).state.value
        assertEquals(LocalDate.of(2026, 7, 14), s.today)
        assertEquals(LocalDate.of(2026, 7, 13), s.firstDate)
        assertEquals(LocalDate.of(2029, 7, 13), s.lastDate)
    }

    @Test
    fun `edit prefills with portalStartUtc and an unchanged save keeps the time`() = runTest {
        portal.primeLastEvents(events)
        val vm = editor(PortalEventEditor(1, "A", eventId = 11, eventIndex = 0))
        val s = vm.state.value
        assertTrue(s.isEdit)
        assertFalse(s.notFound)
        assertEquals(ZonedDateTime.parse("2026-07-21T18:00+02:00[Europe/Berlin]"), s.start)
        vm.onAction(PortalEventEditorAction.Save)
        val call = portal.updated.single()
        assertEquals(11, call[0])
        assertEquals("2026-07-21T16:00:00.000Z", call[2])
        assertNull(call[3])
        assertEquals(PortalEventEditorEvent.Saved(created = false), vm.events.first())
    }

    @Test
    fun `edit prefills text fields`() {
        portal.primeLastEvents(events)
        val s = editor(PortalEventEditor(1, "A", eventId = 12, eventIndex = 1)).state.value
        assertEquals("Bar", s.location)
        assertEquals("d", s.description)
        assertEquals("https://x", s.link)
    }

    @Test
    fun `a date without portal id opens but cannot be saved`() = runTest {
        portal.primeLastEvents(events + PortalMeetupEvent(null, 1, "2026-09-01 10:00:00", "X"))
        val vm = editor(PortalEventEditor(1, "A", eventId = null, eventIndex = 2))
        assertEquals("X", vm.state.value.location)
        assertFalse(vm.state.value.notFound)
        vm.onAction(PortalEventEditorAction.Save)
        assertEquals(UiText.Res(R.string.native_portal_event_without_id), vm.messages.first().text)
        assertTrue(portal.updated.isEmpty())
    }

    @Test
    fun `an unknown date shows not found only after asking the portal`() {
        val vm = editor(PortalEventEditor(1, "A", eventId = 404, eventIndex = 0))
        assertTrue(vm.state.value.notFound)
        assertFalse(vm.state.value.loading)
        assertEquals(1, portal.getMyEventsCalls)
    }

    @Test
    fun `empty cache after process death loads the date from the portal and prefills`() {
        portal.myEvents = events
        val vm = editor(PortalEventEditor(1, "A", eventId = 12, eventIndex = 1))
        val s = vm.state.value
        assertFalse(s.notFound)
        assertFalse(s.loading)
        assertEquals(1, portal.getMyEventsCalls)
        assertEquals("Bar", s.location)
        assertEquals(ZonedDateTime.parse("2026-08-01T19:00+02:00[Europe/Berlin]"), s.start)
    }

    @Test
    fun `a filled cache needs no network call`() {
        portal.primeLastEvents(events)
        editor(PortalEventEditor(1, "A", eventId = 12, eventIndex = 1))
        assertEquals(0, portal.getMyEventsCalls)
    }

    @Test
    fun `typed values survive process death and win over the prefill`() {
        portal.primeLastEvents(events)
        val saved = SavedStateHandle()
        val vm = editor(PortalEventEditor(1, "A", eventId = 12, eventIndex = 1), saved)
        vm.onAction(PortalEventEditorAction.StartPicked(LocalDate.of(2026, 9, 3), LocalTime.of(20, 30)))
        vm.onAction(PortalEventEditorAction.LocationChanged("Neu"))
        vm.onAction(PortalEventEditorAction.DescriptionChanged(""))
        vm.onAction(PortalEventEditorAction.LinkChanged("https://y"))
        val restored = editor(PortalEventEditor(1, "A", eventId = 12, eventIndex = 1), saved).state.value
        assertEquals(ZonedDateTime.parse("2026-09-03T20:30+02:00[Europe/Berlin]"), restored.start)
        assertEquals("Neu", restored.location)
        assertEquals("", restored.description)
        assertEquals("https://y", restored.link)
    }

    @Test
    fun `portal failure shows the portal error and stays`() = runTest {
        portal.writeResult = PortalResult(false, 422, "a · b (HTTP 422)", PortalErrorKind.SERVER_MESSAGE)
        val vm = editor(PortalEventEditor(1, "A"))
        vm.onAction(PortalEventEditorAction.StartPicked(LocalDate.of(2026, 7, 21), LocalTime.of(20, 15)))
        vm.onAction(PortalEventEditorAction.Save)
        val m = vm.messages.first()
        assertEquals(UiText.Portal("a · b (HTTP 422)", PortalErrorKind.SERVER_MESSAGE), m.text)
        assertEquals(Tone.Error, m.tone)
        assertFalse(vm.state.value.saving)
    }
}
