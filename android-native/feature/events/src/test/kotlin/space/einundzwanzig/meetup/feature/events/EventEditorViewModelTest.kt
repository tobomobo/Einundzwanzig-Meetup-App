package space.einundzwanzig.meetup.feature.events

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeRight
import space.einundzwanzig.meetup.data.profile.blossom.UploadResult
import space.einundzwanzig.meetup.feature.events.common.UiText
import space.einundzwanzig.meetup.feature.events.editor.DateField
import space.einundzwanzig.meetup.feature.events.editor.EventEditorAction
import space.einundzwanzig.meetup.feature.events.editor.EventEditorEffect
import space.einundzwanzig.meetup.feature.events.editor.EventEditorViewModel
import space.einundzwanzig.meetup.feature.events.editor.eventBadgeTourSteps
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class EventEditorViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val events = FakeCalendarEventService()
    private val auth = FakeAuth()
    private val blossom = FakeBlossom()
    private val preparer = FakePreparer()

    private fun vm() = EventEditorViewModel(events, auth, blossom, preparer, FixedTime(), berlinZone)

    private val day = LocalDate.of(2026, 11, 5)

    @Test
    fun `permission is resolved on init`() {
        assertEquals(EventBadgeRight.PORTAL_LEADER, vm().state.value.right)
        auth.right = EventBadgeRight.NONE
        val vm = vm()
        assertFalse(vm.state.value.mayCreateBadge)
        vm.onAction(EventEditorAction.SetBadgeEnabled(true))
        assertFalse(vm.state.value.badgeEnabled)
    }

    @Test
    fun `date picking uses the time, 19 00 when dismissed, midnight for all day`() {
        val vm = vm()
        vm.onAction(EventEditorAction.DatePicked(DateField.START, day, LocalTime.of(18, 30)))
        assertEquals(ZonedDateTime.of(day, LocalTime.of(18, 30), BERLIN).toInstant(), vm.state.value.start)
        vm.onAction(EventEditorAction.DatePicked(DateField.END, day, null))
        assertEquals(ZonedDateTime.of(day, LocalTime.of(19, 0), BERLIN).toInstant(), vm.state.value.end)
        vm.onAction(EventEditorAction.SetAllDay(true))
        assertNull(vm.state.value.start)
        assertNull(vm.state.value.end)
        vm.onAction(EventEditorAction.DatePicked(DateField.START, day, LocalTime.of(18, 30)))
        assertEquals(day.atStartOfDay(BERLIN).toInstant(), vm.state.value.start)
        vm.onAction(EventEditorAction.DatePicked(DateField.END, day.plusDays(1), null))
        vm.onAction(EventEditorAction.ClearEnd)
        assertNull(vm.state.value.end)
    }

    @Test
    fun `picker bounds are now minus one day to now plus five years`() {
        val s = vm().state.value
        assertEquals(LocalDate.of(2026, 10, 20), s.firstDate)
        assertEquals(LocalDate.of(2031, 10, 20), s.lastDate)
    }

    @Test
    fun `validation order title, start, badge location`() = runTest {
        val vm = vm()
        vm.onAction(EventEditorAction.Publish)
        assertEquals(UiText.res(L10nR.string.calNeedTitle), vm.messages.first().text)
        vm.onAction(EventEditorAction.SetTitle("  BTC Prag  "))
        vm.onAction(EventEditorAction.Publish)
        assertEquals(UiText.res(L10nR.string.calNeedStart), vm.messages.first().text)
        vm.onAction(EventEditorAction.DatePicked(DateField.START, day, null))
        vm.onAction(EventEditorAction.SetBadgeEnabled(true))
        vm.onAction(EventEditorAction.Publish)
        assertEquals(UiText.res(L10nR.string.evBadgeNeedLocation), vm.messages.first().text)
        assertTrue(events.published.isEmpty())
    }

    @Test
    fun `publish without badge sends trimmed fields and no badge data`() = runTest {
        val vm = vm()
        vm.onAction(EventEditorAction.SetTitle("  BTC Prag "))
        vm.onAction(EventEditorAction.SetLocation(" Prag "))
        vm.onAction(EventEditorAction.SetDescription(" Treffen "))
        vm.onAction(EventEditorAction.DatePicked(DateField.START, day, null))
        vm.onAction(EventEditorAction.SetBadgeImage("https://x/y.png"))
        vm.onAction(EventEditorAction.LocationPicked(50.0, 14.0))
        vm.onAction(EventEditorAction.Publish)
        val d = events.published.single()
        assertEquals("BTC Prag", d.title)
        assertEquals("Prag", d.location)
        assertEquals("Treffen", d.description)
        assertEquals(ZonedDateTime.of(day, LocalTime.of(19, 0), BERLIN), d.start)
        assertFalse(d.badgeEnabled)
        assertEquals("", d.badgeImageUrl)
        assertEquals(0.0, d.lat, 0.0)
        assertTrue(d.issuers.isEmpty())
        assertEquals(EventEditorEffect.Published, vm.effects.first())
    }

    @Test
    fun `publish with badge sends image, location and valid deduplicated helpers`() {
        val vm = vm()
        vm.onAction(EventEditorAction.SetTitle("Badge Event"))
        vm.onAction(EventEditorAction.DatePicked(DateField.START, day, LocalTime.NOON))
        vm.onAction(EventEditorAction.SetBadgeEnabled(true))
        vm.onAction(EventEditorAction.SetBadgeImage(" https://x/y.png "))
        vm.onAction(EventEditorAction.LocationPicked(50.1, 14.4))
        vm.onAction(EventEditorAction.SetIssuerRow(0, Nip19.encodeNpub(PK_HELPER)))
        vm.onAction(EventEditorAction.AddIssuerRow)
        vm.onAction(EventEditorAction.SetIssuerRow(1, "kaputt"))
        vm.onAction(EventEditorAction.AddIssuerRow)
        vm.onAction(EventEditorAction.SetIssuerRow(2, PK_HELPER.uppercase()))
        vm.onAction(EventEditorAction.Publish)
        val d = events.published.single()
        assertTrue(d.badgeEnabled)
        assertEquals("https://x/y.png", d.badgeImageUrl)
        assertEquals(50.1, d.lat, 0.0)
        assertEquals(14.4, d.lng, 0.0)
        assertEquals(listOf(PK_HELPER), d.issuers)
    }

    @Test
    fun `publish failure shows calPublishFail`() = runTest {
        events.publishResult = 0
        val vm = vm()
        vm.onAction(EventEditorAction.SetTitle("X"))
        vm.onAction(EventEditorAction.DatePicked(DateField.START, day, null))
        vm.onAction(EventEditorAction.Publish)
        assertEquals(UiText.res(L10nR.string.calPublishFail), vm.messages.first().text)
        assertFalse(vm.state.value.publishing)
    }

    @Test
    fun `issuer rows add and remove, last removal leaves one empty row`() {
        val vm = vm()
        vm.onAction(EventEditorAction.AddIssuerRow)
        vm.onAction(EventEditorAction.SetIssuerRow(1, "b"))
        assertEquals(listOf("", "b"), vm.state.value.issuerRows)
        vm.onAction(EventEditorAction.RemoveIssuerRow(0))
        assertEquals(listOf("b"), vm.state.value.issuerRows)
        vm.onAction(EventEditorAction.RemoveIssuerRow(0))
        assertEquals(listOf(""), vm.state.value.issuerRows)
    }

    @Test
    fun `location clear resets to 0 0`() {
        val vm = vm()
        vm.onAction(EventEditorAction.LocationPicked(1.5, 2.5))
        assertTrue(vm.state.value.hasLocation)
        vm.onAction(EventEditorAction.ClearLocation)
        assertFalse(vm.state.value.hasLocation)
    }

    @Test
    fun `image upload success sets the URL`() {
        val vm = vm()
        vm.onAction(EventEditorAction.ImagePicked("content://media/1"))
        assertEquals(listOf("content://media/1"), preparer.prepared)
        assertEquals("https://blossom.example/x.jpg", vm.state.value.badgeImage)
        assertFalse(vm.state.value.uploading)
    }

    @Test
    fun `image upload failure shows evBadgeUploadFailed with the error`() = runTest {
        blossom.result = UploadResult.failure("HTTP 500")
        val vm = vm()
        vm.onAction(EventEditorAction.ImagePicked("content://media/1"))
        assertEquals(UiText.res(L10nR.string.evBadgeUploadFailed, "HTTP 500"), vm.messages.first().text)
        assertEquals("", vm.state.value.badgeImage)
    }

    @Test
    fun `tour has badge steps only when permitted`() {
        assertEquals(2, eventBadgeTourSteps(false).size)
        val steps = eventBadgeTourSteps(true)
        assertEquals(6, steps.size)
        assertEquals(L10nR.string.guideEvBadgeSwitchHint, steps[2].hint)
        assertEquals(900L, steps[3].waitTimeoutMillis)
    }
}
