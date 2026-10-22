package space.einundzwanzig.meetup.feature.meetups.nearby

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.map.CameraPosition
import space.einundzwanzig.meetup.core.map.LatLng
import space.einundzwanzig.meetup.core.map.OsmCameraState
import space.einundzwanzig.meetup.core.map.LocalOsmTileRendering
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.meetups.geocoding.GeoPlace
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import space.einundzwanzig.meetup.data.meetups.nearby.DateMode
import space.einundzwanzig.meetup.data.meetups.nearby.MeetupEventInfo
import space.einundzwanzig.meetup.data.meetups.nearby.NearbyMeetup
import space.einundzwanzig.meetup.data.meetups.nearby.SearchMode
import space.einundzwanzig.meetup.feature.meetups.R
import space.einundzwanzig.meetup.feature.meetups.meetup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class NearbyScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private val actions = mutableListOf<NearbyAction>()
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-07-20T08:00:00Z")

    private fun show(state: NearbyUiState) {
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalOsmTileRendering provides false) {
                    NearbyMeetupsScreen(state, SnackbarHostState()) { actions += it }
                }
            }
        }
    }

    private val berlin = meetup("1", "Berlin", "Einundzwanzig Berlin", 52.52, 13.405)
    private val potsdam = meetup("2", "Potsdam", lat = 52.39, lng = 13.06)
    private val results = listOf(
        NearbyMeetup(
            berlin,
            MeetupEventInfo("Berlin", location = "Bitcoin Bar", start = Instant.parse("2026-07-20T17:00:00Z")),
            listOf(
                MeetupEventInfo("Berlin", location = "Bitcoin Bar", start = Instant.parse("2026-07-20T17:00:00Z")),
                MeetupEventInfo("Berlin", start = Instant.parse("2026-08-03T17:00:00Z")),
                MeetupEventInfo("Berlin", start = Instant.parse("2026-08-17T17:00:00Z")),
            ),
            distanceKm = 2.25,
        ),
        NearbyMeetup(potsdam, distanceKm = 12.5),
    )

    private fun scrollTo(tag: String) = rule.onNodeWithTag(NearbyTags.LIST).performScrollToNode(hasTestTag(tag))

    private fun scrollToText(text: String) = rule.onNodeWithTag(NearbyTags.LIST).performScrollToNode(hasText(text))

    @Test
    fun `results show header, distance, next date and more dates`() {
        show(NearbyUiState(hereCenter = LatLng(52.5, 13.4), loading = false, results = results, now = now, zone = zone))
        rule.onNodeWithText(s(L10nR.string.nbTitle)).assertExists()
        scrollTo(NearbyTags.RESULTS_HEADER)
        rule.onNodeWithText(s(L10nR.string.nbResultsHeader, 2)).assertExists()
        scrollTo(NearbyTags.card("1"))
        rule.onNodeWithText(s(L10nR.string.nbKmAway, "2.3")).assertExists()
        rule.onNodeWithText("${s(L10nR.string.nbToday)}, 19:00").assertExists()
        rule.onNodeWithText("Bitcoin Bar").assertExists()
        rule.onNodeWithText(s(L10nR.string.nbMoreDates, 2)).assertExists()
        scrollTo(NearbyTags.card("2"))
        rule.onNodeWithText(s(L10nR.string.nbKmAway, "13")).assertExists()
        rule.onNodeWithText(s(L10nR.string.nbNoDate)).assertExists()
    }

    @Test
    fun `details, directions and map pins emit actions`() {
        show(NearbyUiState(hereCenter = LatLng(52.5, 13.4), loading = false, results = results, now = now, zone = zone))
        scrollTo(NearbyTags.details("2"))
        rule.onNodeWithTag(NearbyTags.details("2")).performClick()
        scrollTo(NearbyTags.card("1"))
        rule.onAllNodesWithText("Berlin")[0].assertExists()
        rule.onNodeWithContentDescription("Potsdam").performClick()
        assertEquals(NearbyAction.OpenDetails("2"), actions[0])
        assertEquals(NearbyAction.OpenDetails("2"), actions[1])
    }

    @Test
    fun `empty result shows the none in radius box`() {
        show(NearbyUiState(hereCenter = LatLng(52.5, 13.4), loading = false, now = now))
        scrollTo(NearbyTags.EMPTY)
        rule.onNodeWithText(s(L10nR.string.nbNoneInRadius)).assertExists()
        rule.onNodeWithText(s(L10nR.string.nbNoneInRadiusSub)).assertExists()
    }

    @Test
    fun `denied forever offers retry and app settings`() {
        show(NearbyUiState(locStatus = LocationStatus.DENIED_FOREVER, loading = false, now = now))
        rule.onNodeWithTag(NearbyTags.LOCATION_HINT).assertExists()
        rule.onNodeWithText(s(L10nR.string.nbLocationDenied)).assertExists()
        scrollToText(s(R.string.native_meetups_open_app_settings))
        rule.onNodeWithText(s(L10nR.string.nbRetryLocation)).performClick()
        rule.onNodeWithText(s(R.string.native_meetups_open_app_settings)).performClick()
        assertEquals(listOf(NearbyAction.RetryLocation, NearbyAction.OpenAppSettings), actions)
    }

    @Test
    fun `disabled location services offer the location settings`() {
        show(NearbyUiState(locStatus = LocationStatus.SERVICE_DISABLED, loading = false, now = now))
        rule.onNodeWithText(s(L10nR.string.nbServiceDisabled)).assertExists()
        scrollToText(s(R.string.native_meetups_open_location_settings))
        rule.onNodeWithText(s(R.string.native_meetups_open_location_settings)).performClick()
        assertEquals(listOf<NearbyAction>(NearbyAction.OpenLocationSettings), actions)
    }

    @Test
    fun `planned mode without a place shows the search hint and place results`() {
        val place = GeoPlace("Hamburg, Deutschland", "Hamburg", 53.55, 9.99)
        show(NearbyUiState(mode = SearchMode.PLANNED, loading = false, placeResults = listOf(place), query = "Ham", now = now))
        rule.onNodeWithText("Hamburg, Deutschland").assertExists()
        rule.onNodeWithTag(NearbyTags.PLACE_FIELD).performTextInput("b")
        rule.onNodeWithTag(NearbyTags.place(0)).performClick()
        assertTrue(actions.contains(NearbyAction.PlaceSelected(place)))
        assertTrue(actions.any { it is NearbyAction.QueryChanged })
        scrollTo(NearbyTags.SEARCH_HINT)
        rule.onNodeWithTag(NearbyTags.SEARCH_HINT).assertExists()
    }

    @Test
    fun `mode toggle and date chips emit actions and show the chosen dates`() {
        val d = LocalDate.of(2026, 8, 1)
        show(
            NearbyUiState(
                hereCenter = LatLng(52.5, 13.4),
                loading = false,
                dateMode = DateMode.RANGE,
                dayFrom = d,
                dayTo = d.plusDays(2),
                now = now,
            ),
        )
        rule.onNodeWithText(s(L10nR.string.nbRadiusValue, "25")).assertExists()
        scrollTo(NearbyTags.DATE_RANGE)
        rule.onNodeWithText(s(L10nR.string.nbDateFromTo, "01.08.", "03.08.")).assertExists()
        rule.onNodeWithText(s(L10nR.string.nbPickDay)).assertExists()
        rule.onNodeWithTag(NearbyTags.DATE_ANY).performClick()
        scrollTo(NearbyTags.mode(SearchMode.PLANNED))
        rule.onNodeWithTag(NearbyTags.mode(SearchMode.PLANNED)).performClick()
        assertEquals(listOf(NearbyAction.DateAny, NearbyAction.SetMode(SearchMode.PLANNED)), actions)
    }

    @Test
    fun `single day chip opens the date picker`() {
        show(NearbyUiState(hereCenter = LatLng(52.5, 13.4), loading = false, now = now))
        scrollTo(NearbyTags.DATE_RANGE)
        rule.onNodeWithTag(NearbyTags.DATE_DAY).performClick()
        rule.onNodeWithText(s(L10nR.string.actionCancel)).performClick()
        assertTrue(actions.isEmpty())
    }

    // ---- Camera fit once per search -----------------------------------------------------------

    private val berlinPoint = LatLng(52.52, 13.405)
    private val userCamera = CameraPosition(LatLng(48.0, 11.0), 14.0)

    @Test
    fun `fit runs once per search and survives recreation without moving the camera`() {
        val camera = OsmCameraState(CameraPosition(LatLng.DachCenter, 5.0))
        var token by mutableIntStateOf(1)
        var fits = 0
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            FitCameraOnNewSearch(token, camera) {
                fits++
                listOf(berlinPoint)
            }
        }
        rule.waitForIdle()
        assertEquals(CameraPosition(berlinPoint, 10.0), camera.position)
        // The user zooms/pans, then the device rotates (same fitToken).
        rule.runOnIdle { camera.move(userCamera.target, userCamera.zoom) }
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(userCamera, camera.position)
        assertEquals(1, fits)
        // A new search fits again.
        rule.runOnIdle { token = 2 }
        rule.waitForIdle()
        assertEquals(CameraPosition(berlinPoint, 10.0), camera.position)
        assertEquals(2, fits)
    }

    @Test
    fun `coming back from details with the same fitToken keeps the camera`() {
        val camera = OsmCameraState(CameraPosition(LatLng.DachCenter, 5.0))
        var onTop by mutableStateOf(true)
        var fits = 0
        rule.setContent {
            // Navigation 3 keeps the saved state of a covered entry and recomposes it on return.
            val holder = rememberSaveableStateHolder()
            if (onTop) {
                holder.SaveableStateProvider("nearby") {
                    FitCameraOnNewSearch(1, camera) {
                        fits++
                        listOf(berlinPoint)
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { camera.move(userCamera.target, userCamera.zoom) }
        rule.runOnIdle { onTop = false }
        rule.waitForIdle()
        rule.runOnIdle { onTop = true }
        rule.waitForIdle()
        assertEquals(userCamera, camera.position)
        assertEquals(1, fits)
    }

    @Test
    fun `radius slider announces its title and km value`() {
        show(NearbyUiState(hereCenter = LatLng(52.5, 13.4), loading = false, now = now, radiusKm = 25.0))
        scrollTo(NearbyTags.RADIUS)
        rule.onNodeWithTag(NearbyTags.RADIUS)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(s(L10nR.string.nbRadius))))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, s(L10nR.string.nbRadiusValue, "25")))
    }
}
