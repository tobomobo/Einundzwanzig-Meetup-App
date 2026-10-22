package space.einundzwanzig.meetup.feature.meetups

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.map.LocalOsmTileRendering
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.MeetupDetails
import space.einundzwanzig.meetup.core.navigation.NavResultEffect
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.navigation.PickedLocation
import space.einundzwanzig.meetup.core.navigation.PortalEventEditor
import space.einundzwanzig.meetup.core.navigation.PortalMeetupEvents
import space.einundzwanzig.meetup.core.navigation.sendResult
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideHost
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.InMemoryTourProgressStore
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.meetups.location.LocationResult
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import space.einundzwanzig.meetup.data.meetups.location.Position
import space.einundzwanzig.meetup.data.meetups.nearby.NearbyMeetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetupEvent
import space.einundzwanzig.meetup.feature.meetups.nearby.NearbyMeetupsRoute
import space.einundzwanzig.meetup.feature.meetups.nearby.NearbyMeetupsViewModel
import space.einundzwanzig.meetup.feature.meetups.nearby.NearbyTags
import space.einundzwanzig.meetup.feature.meetups.picker.LocationPickerRoute
import space.einundzwanzig.meetup.feature.meetups.picker.LocationPickerTags
import space.einundzwanzig.meetup.feature.meetups.picker.LocationPickerViewModel
import space.einundzwanzig.meetup.feature.meetups.portal.PortalEditorTags
import space.einundzwanzig.meetup.feature.meetups.portal.PortalEventEditorRoute
import space.einundzwanzig.meetup.feature.meetups.portal.PortalEventEditorViewModel
import space.einundzwanzig.meetup.feature.meetups.portal.PortalEventsTags
import space.einundzwanzig.meetup.feature.meetups.portal.PortalMeetupEventsRoute
import space.einundzwanzig.meetup.feature.meetups.portal.PortalMeetupEventsViewModel
import space.einundzwanzig.meetup.feature.meetups.portal.PortalMeetupsRoute
import space.einundzwanzig.meetup.feature.meetups.portal.PortalMeetupsTags
import space.einundzwanzig.meetup.feature.meetups.portal.PortalMeetupsViewModel
import space.einundzwanzig.meetup.feature.meetups.selection.MeetupSelectionRoute
import space.einundzwanzig.meetup.feature.meetups.selection.MeetupSelectionTags
import space.einundzwanzig.meetup.feature.meetups.selection.MeetupSelectionViewModel
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Route-level wiring: NavKeys, results, back. */
@RunWith(AndroidJUnit4::class)
class RouteWiringTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val navigator = RecordingNavigator()
    private val bus = ResultEventBus()
    private val portal = FakePortal()

    private fun show(content: @Composable () -> Unit) {
        val store = InMemoryTourProgressStore()
        runBlocking { store.setCompleted(GuideTour.MyMeetups, true) }
        val controller = GuideController(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(
                    LocalNavigator provides navigator,
                    LocalResultEventBus provides bus,
                    LocalOsmTileRendering provides false,
                ) {
                    GuideHost(controller) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `my meetups opens the events of a meetup and back pops`() {
        portal.token = "t"
        portal.myMeetups = listOf(PortalMeetup(1, "A", "a"))
        show { PortalMeetupsRoute(PortalMeetupsViewModel(portal)) }
        rule.onNodeWithTag(PortalMeetupsTags.manage(1)).performClick()
        rule.onNodeWithContentDescription(context.getString(L10nR.string.actionBack)).performClick()
        assertEquals(listOf<NavKey>(PortalMeetupEvents(1, "A")), navigator.keys)
        assertEquals(1, navigator.backCalls)
    }

    @Test
    fun `events route opens the editor and reloads after a saved result`() {
        portal.myEvents = listOf(PortalMeetupEvent(11, 1, "2026-07-21 16:00:00", "Dinx"))
        show { PortalMeetupEventsRoute(1, "A", PortalMeetupEventsViewModel(1, "A", portal)) }
        rule.onNodeWithTag(PortalEventsTags.FAB).performClick()
        rule.onNodeWithTag(PortalEventsTags.row(0)).performClick()
        assertEquals(
            listOf<NavKey>(PortalEventEditor(1, "A"), PortalEventEditor(1, "A", eventId = 11, eventIndex = 0)),
            navigator.keys,
        )
        rule.runOnIdle { bus.sendResult(NavResults.PortalEventSaved, true) }
        rule.waitForIdle()
        assertEquals(2, portal.getMyEventsCalls)
    }

    @Test
    fun `editor sends the saved result and pops`() {
        portal.primeLastEvents(listOf(PortalMeetupEvent(11, 1, "2026-07-21 16:00:00", "Dinx")))
        val key = PortalEventEditor(1, "A", eventId = 11, eventIndex = 0)
        val received = mutableListOf<Boolean>()
        show {
            NavResultEffect(NavResults.PortalEventSaved) { received += it }
            PortalEventEditorRoute(key, PortalEventEditorViewModel(key, portal, FixedTime(), SavedStateHandle()))
        }
        rule.onNodeWithTag(PortalEditorTags.SAVE).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf(false), received)
        assertEquals(1, navigator.backCalls)
        assertEquals(1, portal.updated.size)
    }

    @Test
    fun `nearby details opens the meetup`() {
        val location = FakeLocationService(LocationResult(LocationStatus.OK, Position(52.52, 13.405, 5.0, 0)))
        val nearby = FakeNearbyService(listOf(NearbyMeetup(meetup("1", "Berlin", lat = 52.52, lng = 13.405), distanceKm = 0.0)))
        show { NearbyMeetupsRoute(NearbyMeetupsViewModel(location, nearby, FakeGeocoding(), FixedTime())) }
        rule.onNodeWithTag(NearbyTags.LIST).performScrollToNode(hasTestTag(NearbyTags.details("1")))
        rule.onNodeWithTag(NearbyTags.details("1")).performClick()
        assertEquals(listOf<NavKey>(MeetupDetails("1")), navigator.keys)
    }

    @Test
    fun `location picker returns the preset pick`() {
        val received = mutableListOf<PickedLocation>()
        val vm = LocationPickerViewModel(FakeLocationService(), SavedStateHandle())
        show {
            NavResultEffect(NavResults.LocationPicked) { received += it }
            LocationPickerRoute(48.1, 11.5, vm)
        }
        rule.onNodeWithTag(LocationPickerTags.CONFIRM).performClick()
        rule.waitForIdle()
        assertEquals(listOf(PickedLocation(48.1, 11.5)), received)
        assertEquals(1, navigator.backCalls)
    }

    @Test
    fun `selection save pops`() {
        val vm = MeetupSelectionViewModel(FakeProfiles(), FakeMeetupService(listOf(meetup("1", "Berlin"))))
        show { MeetupSelectionRoute(vm) }
        rule.onNodeWithTag(MeetupSelectionTags.row("1")).performClick()
        rule.onNodeWithTag(MeetupSelectionTags.SAVE).performClick()
        rule.waitForIdle()
        assertEquals(1, navigator.backCalls)
    }
}

class RecordingNavigator : Navigator {
    val keys = mutableListOf<NavKey>()
    var backCalls = 0
    override fun navigate(key: NavKey) {
        keys += key
    }
    override fun replaceTop(key: NavKey) = Unit
    override fun back() {
        backCalls++
    }
    override fun backTo(key: NavKey, inclusive: Boolean) = Unit
    override fun resetTo(key: NavKey) = Unit
    override fun navigateFromRoot(key: NavKey) = Unit
    override fun openUrl(url: String) = Unit
}
