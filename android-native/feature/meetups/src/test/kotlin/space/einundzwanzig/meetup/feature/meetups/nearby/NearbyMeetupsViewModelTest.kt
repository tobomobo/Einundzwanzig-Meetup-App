package space.einundzwanzig.meetup.feature.meetups.nearby

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.map.LatLng
import space.einundzwanzig.meetup.data.meetups.geocoding.GeoPlace
import space.einundzwanzig.meetup.data.meetups.location.LocationResult
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import space.einundzwanzig.meetup.data.meetups.location.Position
import space.einundzwanzig.meetup.data.meetups.nearby.DateMode
import space.einundzwanzig.meetup.data.meetups.nearby.NearbyMeetup
import space.einundzwanzig.meetup.data.meetups.nearby.SearchMode
import space.einundzwanzig.meetup.feature.meetups.FakeGeocoding
import space.einundzwanzig.meetup.feature.meetups.FakeLocationService
import space.einundzwanzig.meetup.feature.meetups.FakeNearbyService
import space.einundzwanzig.meetup.feature.meetups.FixedTime
import space.einundzwanzig.meetup.feature.meetups.MainDispatcherRule
import space.einundzwanzig.meetup.feature.meetups.SearchCall
import space.einundzwanzig.meetup.feature.meetups.meetup
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyMeetupsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val here = LocationResult(LocationStatus.OK, Position(52.52, 13.405, 10.0, 0))
    private val location = FakeLocationService(here)
    private val nearby = FakeNearbyService(listOf(NearbyMeetup(meetup("1", "Berlin"), distanceKm = 1.0)))
    private val geocoding = FakeGeocoding(listOf(GeoPlace("Hamburg, Deutschland", "Hamburg", 53.55, 9.99)))

    private fun vm() = NearbyMeetupsViewModel(location, nearby, geocoding, FixedTime())

    @Test
    fun `init locates and searches around the position with the defaults`() {
        val vm = vm()
        val s = vm.state.value
        assertEquals(LatLng(52.52, 13.405), s.hereCenter)
        assertEquals(LatLng(52.52, 13.405), s.center)
        assertFalse(s.loading)
        assertEquals(1, s.results.size)
        assertEquals(1, s.fitToken)
        assertEquals(listOf(SearchCall(52.52, 13.405, 25.0, DateMode.ANY, null, null)), nearby.calls)
    }

    @Test
    fun `while locating the screen is loading`() {
        location.gate = CompletableDeferred()
        val vm = vm()
        assertTrue(vm.state.value.loading)
        assertTrue(vm.state.value.locating)
        location.gate!!.complete(Unit)
        assertFalse(vm.state.value.locating)
    }

    @Test
    fun `denied location shows the hint, no search, retry asks again`() {
        location.result = LocationResult(LocationStatus.DENIED)
        val vm = vm()
        val s = vm.state.value
        assertNull(s.center)
        assertTrue(s.showLocationHint)
        assertFalse(s.loading)
        assertTrue(s.results.isEmpty())
        assertTrue(nearby.calls.isEmpty())
        location.result = here
        vm.onAction(NearbyAction.RetryLocation)
        assertEquals(2, location.calls)
        assertFalse(vm.state.value.showLocationHint)
        assertEquals(1, nearby.calls.size)
    }

    @Test
    fun `settings return retries the location once`() {
        location.result = LocationResult(LocationStatus.DENIED_FOREVER)
        val vm = vm()
        vm.onResume()
        assertEquals(1, location.calls)
        vm.onAction(NearbyAction.OpenAppSettings)
        assertEquals(1, location.openedAppSettings)
        vm.onResume()
        assertEquals(2, location.calls)
        vm.onResume()
        assertEquals(2, location.calls)
        vm.onAction(NearbyAction.OpenLocationSettings)
        assertEquals(1, location.openedLocationSettings)
    }

    @Test
    fun `radius changes update the label and search only when finished`() {
        val vm = vm()
        vm.onAction(NearbyAction.RadiusChanging(50.0))
        assertEquals(50.0, vm.state.value.radiusKm, 0.0)
        assertEquals(1, nearby.calls.size)
        vm.onAction(NearbyAction.RadiusChangeFinished)
        assertEquals(50.0, nearby.calls.last().radiusKm, 0.0)
    }

    @Test
    fun `date filters set the mode and search`() {
        val vm = vm()
        val d = LocalDate.of(2026, 8, 1)
        vm.onAction(NearbyAction.DaySelected(d))
        assertEquals(SearchCall(52.52, 13.405, 25.0, DateMode.SINGLE_DAY, d, null), nearby.calls.last())
        vm.onAction(NearbyAction.RangeSelected(d, d.plusDays(3)))
        assertEquals(SearchCall(52.52, 13.405, 25.0, DateMode.RANGE, d, d.plusDays(3)), nearby.calls.last())
        vm.onAction(NearbyAction.DateAny)
        assertEquals(DateMode.ANY, vm.state.value.dateMode)
        assertNull(vm.state.value.dayFrom)
        assertEquals(SearchCall(52.52, 13.405, 25.0, DateMode.ANY, null, null), nearby.calls.last())
    }

    @Test
    fun `planned mode waits for a place, geocodes debounced, and searches around it`() {
        val vm = vm()
        vm.onAction(NearbyAction.SetMode(SearchMode.PLANNED))
        assertNull(vm.state.value.center)
        assertTrue(vm.state.value.results.isEmpty())
        vm.onAction(NearbyAction.QueryChanged("H"))
        main.dispatcher.scheduler.advanceTimeBy(1_000)
        assertTrue(geocoding.queries.isEmpty())
        vm.onAction(NearbyAction.QueryChanged("Ham"))
        main.dispatcher.scheduler.advanceTimeBy(500)
        assertTrue(geocoding.queries.isEmpty())
        vm.onAction(NearbyAction.QueryChanged("Hamb"))
        main.dispatcher.scheduler.advanceTimeBy(601)
        assertEquals(listOf("Hamb"), geocoding.queries)
        assertEquals(1, vm.state.value.placeResults.size)
        vm.onAction(NearbyAction.PlaceSelected(geocoding.places[0]))
        val s = vm.state.value
        assertEquals("Hamburg", s.query)
        assertEquals("Hamburg", s.placeLabel)
        assertTrue(s.placeResults.isEmpty())
        assertEquals(LatLng(53.55, 9.99), s.center)
        assertEquals(53.55, nearby.calls.last().lat, 0.0)
    }

    @Test
    fun `back to here and now uses the GPS center, not the place`() {
        val vm = vm()
        vm.onAction(NearbyAction.SetMode(SearchMode.PLANNED))
        vm.onAction(NearbyAction.PlaceSelected(geocoding.places[0]))
        vm.onAction(NearbyAction.SetMode(SearchMode.HERE))
        assertEquals(LatLng(52.52, 13.405), vm.state.value.center)
        assertEquals(52.52, nearby.calls.last().lat, 0.0)
        assertEquals(1, location.calls)
    }
}
