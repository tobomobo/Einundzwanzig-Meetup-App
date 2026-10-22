package space.einundzwanzig.meetup.feature.events

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEventBus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.EventCalendar
import space.einundzwanzig.meetup.core.navigation.EventChat
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.navigation.PickedLocation
import space.einundzwanzig.meetup.core.navigation.RollingQr
import space.einundzwanzig.meetup.core.navigation.sendResult
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideHost
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.InMemoryTourProgressStore
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.calendar.model.CalendarItem
import space.einundzwanzig.meetup.data.issuance.event.EventSessionError
import space.einundzwanzig.meetup.data.issuance.event.EventSessionResult
import space.einundzwanzig.meetup.data.issuance.session.MeetupSession
import space.einundzwanzig.meetup.feature.events.calendar.CalendarDetailsContent
import space.einundzwanzig.meetup.feature.events.calendar.CalendarItemDetails
import space.einundzwanzig.meetup.feature.events.calendar.CalendarSheetTags
import space.einundzwanzig.meetup.feature.events.calendar.EventCalendarAction
import space.einundzwanzig.meetup.feature.events.calendar.EventCalendarRoute
import space.einundzwanzig.meetup.feature.events.calendar.EventCalendarViewModel
import space.einundzwanzig.meetup.feature.events.common.DetailsSheetLayout
import space.einundzwanzig.meetup.feature.events.common.EventDetailsSheet
import space.einundzwanzig.meetup.feature.events.editor.EventEditorRoute
import space.einundzwanzig.meetup.feature.events.editor.EventEditorViewModel

/**
 * The real modal details sheet (port of `event_details_sheet_test.dart`: phone sizes, text scale
 * 2.0, insets, landscape) and the route wiring of the calendar and the editor.
 */
@RunWith(AndroidJUnit4::class)
class SheetAndRouteTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)

    private val badge = nostrEvent(
        "b1", pubkey = PK_ME, badge = true, lat = 1.0, lng = 1.0,
        description = (1..40).joinToString("\n") { "Programmpunkt $it mit etwas längerem Text" },
    )

    private val longDetails = CalendarItemDetails(
        item = CalendarItem.fromNostr(badge), iAmIssuer = true, isCreator = true, showSessionButton = true,
        sessionRunning = false, links = listOf("https://example.org/info"), rsvp = null, rsvpBusy = false,
    )

    // --- event_details_sheet_test.dart -------------------------------------------------------

    private fun showModalSheet(fontScale: Float = 2f) {
        RuntimeEnvironment.setFontScale(fontScale)
        rule.setContent {
            EinundzwanzigTheme {
                EventDetailsSheet(onDismiss = {}, skipPartiallyExpanded = false) {
                    CalendarDetailsContent(longDetails, BERLIN, false, false) {}
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertCloseUsableAndCalendarReachable() {
        val close = rule.onNodeWithContentDescription(s(L10nR.string.actionClose))
        close.assertIsDisplayed().assertHasClickAction()
        val bounds = close.getUnclippedBoundsInRoot()
        assertTrue("close button on screen: $bounds", bounds.top >= 0.dp)
        assertTouchTarget48()
        rule.onNodeWithText(s(L10nR.string.evToCalendar)).performScrollTo().assertIsDisplayed()
        // The close button stays reachable after scrolling the body (it is outside the scroll area).
        close.assertIsDisplayed()
    }

    /** The close button's touch area is at least 48 × 48 dp. */
    private fun assertTouchTarget48() {
        val node = rule.onNodeWithContentDescription(s(L10nR.string.actionClose)).fetchSemanticsNode()
        val minPx = with(rule.density) { 48.dp.toPx() } - 1f
        val touch = node.touchBoundsInRoot
        assertTrue("touch target $touch", touch.width >= minPx && touch.height >= minPx)
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp")
    fun `modal sheet at 402x874 with text scale 2 keeps close usable and calendar reachable`() {
        showModalSheet()
        assertCloseUsableAndCalendarReachable()
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp")
    fun `modal sheet at 320x568 with text scale 2 keeps close usable and calendar reachable`() {
        showModalSheet()
        assertCloseUsableAndCalendarReachable()
    }

    @Test
    @Config(qualifiers = "w874dp-h402dp-land")
    fun `modal sheet in landscape keeps the same close button`() {
        showModalSheet(fontScale = 1f)
        val bounds = rule.onNodeWithContentDescription(s(L10nR.string.actionClose)).getUnclippedBoundsInRoot()
        // Same 40dp icon button (48dp touch target) as in portrait.
        assertEquals(40f, bounds.width.value, 0.5f)
        assertEquals(40f, bounds.height.value, 0.5f)
        assertCloseUsableAndCalendarReachable()
    }

    /**
     * Robolectric does not report system bar insets to the sheet's dialog window, so the safe area
     * of the Dart test (top 62 / bottom 34) is applied as padding around the bounded layout.
     */
    private fun assertBoundedInsideInsets(w: Int, h: Int) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                EinundzwanzigTheme {
                    Box(Modifier.size(w.dp, h.dp).padding(top = 62.dp, bottom = 34.dp)) {
                        DetailsSheetLayout(onClose = {}) { CalendarDetailsContent(longDetails, BERLIN, false, false) {} }
                    }
                }
            }
        }
        val close = rule.onNodeWithContentDescription(s(L10nR.string.actionClose))
        close.assertHasClickAction()
        assertTouchTarget48()
        val bounds = close.getUnclippedBoundsInRoot()
        assertTrue("close below the top inset: $bounds", bounds.top >= 62.dp)
        assertTrue("close above the bottom inset: $bounds", bounds.bottom <= (h - 34).dp)
        rule.onNodeWithText(s(L10nR.string.evToCalendar)).performScrollTo().assertIsDisplayed()
        close.assertIsDisplayed()
    }

    /**
     * Robolectric does not report system bar insets to the sheet's dialog window, so the safe area
     * of the Dart test (top 62 / bottom 34) is applied as padding around the bounded layout.
     */
    @Test
    @Config(qualifiers = "w402dp-h874dp")
    fun `bounded layout inside 62 and 34 dp insets at 402x874 and text scale 2`() = assertBoundedInsideInsets(402, 874)

    @Test
    @Config(qualifiers = "w320dp-h568dp")
    fun `bounded layout inside 62 and 34 dp insets at 320x568 and text scale 2`() = assertBoundedInsideInsets(320, 568)

    // --- Route wiring --------------------------------------------------------------------------

    private class RecordingNavigator : Navigator {
        val pushed = mutableListOf<NavKey>()
        var backs = 0
        override fun navigate(key: NavKey) { pushed += key }
        override fun replaceTop(key: NavKey) { pushed += key }
        override fun back() { backs++ }
        override fun backTo(key: NavKey, inclusive: Boolean) = Unit
        override fun resetTo(key: NavKey) { pushed += key }
        override fun navigateFromRoot(key: NavKey) { pushed += key }
        override fun openUrl(url: String) = Unit
    }

    private val navigator = RecordingNavigator()
    private val bus = ResultEventBus()
    private val repo = FakeEventCalendarRepository(listOf(CalendarItem.fromNostr(badge)))
    private val sessionService = FakeSessionService()

    private fun calendarVm() = EventCalendarViewModel(
        EventCalendar(), repo, FakeCalendarEventService(), FakeRsvpService(), sessionService, FakeIdentity(),
        FakeSessions(), { "" }, FixedTime(), berlinZone,
    )

    private fun showCalendarRoute(vm: EventCalendarViewModel) {
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator, LocalResultEventBus provides bus) {
                    EventCalendarRoute(EventCalendar(), vm)
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `session error while the sheet is open shows a tappable settings action inside it`() {
        sessionService.result = EventSessionResult(error = EventSessionError.LOCATION_SERVICE_OFF)
        val vm = calendarVm()
        showCalendarRoute(vm)
        rule.runOnIdle { vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(badge))) }
        rule.waitForIdle()
        rule.runOnIdle { vm.onAction(EventCalendarAction.StartSession) }
        rule.waitForIdle()
        rule.onNodeWithText(s(L10nR.string.evSessionLocationOff)).assertIsDisplayed()
        rule.onNodeWithText(s(L10nR.string.evSessionOpenSettings)).assertIsDisplayed().assertHasClickAction().performClick()
        val started = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, started?.action)
    }

    @Test
    fun `calendar route reloads with force on EventPublished`() {
        val vm = calendarVm()
        showCalendarRoute(vm)
        assertEquals(listOf(false), repo.forced)
        rule.runOnIdle { bus.sendResult(NavResults.EventPublished, Unit) }
        rule.waitForIdle()
        assertEquals(listOf(false, true), repo.forced)
    }

    @Test
    fun `calendar route opens the rolling QR after a session start and the event chat`() {
        sessionService.result = EventSessionResult(
            session = MeetupSession(
                seed = "s", startedAt = NOW.epochSecond, expiresAt = NOW.epochSecond + 3_600, meetupId = "ev|x",
                meetupName = "x", meetupCountry = "", blockHeight = 1, pubkey = "npub1x", lat = 0.0, lng = 0.0,
            ),
        )
        val vm = calendarVm()
        showCalendarRoute(vm)
        rule.runOnIdle { vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(badge))) }
        rule.waitForIdle()
        rule.onNodeWithText(s(L10nR.string.evBadgeStartSession)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf<NavKey>(RollingQr), navigator.pushed)

        rule.runOnIdle { vm.onAction(EventCalendarAction.OpenDetails(CalendarItem.fromNostr(badge))) }
        rule.waitForIdle()
        rule.onNodeWithTag(CalendarSheetTags.CHAT).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(EventChat(badge.address, badge.title), navigator.pushed.last())
        assertEquals(null, vm.state.value.details)
    }

    @Test
    fun `editor route forwards LocationPicked to the view model`() {
        val vm = EventEditorViewModel(FakeCalendarEventService(), FakeAuth(), FakeBlossom(), FakePreparer(), FixedTime(), berlinZone)
        val store = InMemoryTourProgressStore()
        runBlocking { store.setCompleted(GuideTour.Events, true) }
        val guide = GuideController(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator, LocalResultEventBus provides bus) {
                    GuideHost(guide) { EventEditorRoute(vm) }
                }
            }
        }
        rule.runOnIdle { bus.sendResult(NavResults.LocationPicked, PickedLocation(50.08804, 14.42076)) }
        rule.waitForIdle()
        assertEquals(50.08804, vm.state.value.lat, 1e-9)
        assertEquals(14.42076, vm.state.value.lng, 1e-9)
    }
}
