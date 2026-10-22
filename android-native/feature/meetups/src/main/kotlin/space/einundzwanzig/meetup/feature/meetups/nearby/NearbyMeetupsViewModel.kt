package space.einundzwanzig.meetup.feature.meetups.nearby

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.map.LatLng
import space.einundzwanzig.meetup.data.meetups.geocoding.GeoPlace
import space.einundzwanzig.meetup.data.meetups.geocoding.GeocodingService
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import space.einundzwanzig.meetup.data.meetups.location.MeetupLocationService
import space.einundzwanzig.meetup.data.meetups.nearby.DateMode
import space.einundzwanzig.meetup.data.meetups.nearby.NearbyMeetup
import space.einundzwanzig.meetup.data.meetups.nearby.NearbyMeetupService
import space.einundzwanzig.meetup.data.meetups.nearby.SearchMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.random.Random

/**
 * State of "MEETUPS IN DER NÄHE" (Dart `_NearbyMeetupsScreenState`, spec 09 §2.2).
 *
 * Native fix (spec 09 §14 item 13): the GPS center ([hereCenter]) and the geocoded place
 * ([placeCenter]) are kept apart, so switching back to "Hier & jetzt" never labels a searched
 * place as "Mein Standort".
 */
@Immutable
data class NearbyUiState(
    val mode: SearchMode = SearchMode.HERE,
    val radiusKm: Double = NearbyMeetupService.DEFAULT_RADIUS_KM,
    val hereCenter: LatLng? = null,
    val placeCenter: LatLng? = null,
    val placeLabel: String = "",
    val locStatus: LocationStatus = LocationStatus.OK,
    val locating: Boolean = false,
    val dateMode: DateMode = DateMode.ANY,
    val dayFrom: LocalDate? = null,
    val dayTo: LocalDate? = null,
    val query: String = "",
    val placeResults: List<GeoPlace> = emptyList(),
    val searchingPlace: Boolean = false,
    val loading: Boolean = true,
    val results: List<NearbyMeetup> = emptyList(),
    /** Incremented after every finished search; the map refits its camera (Dart `_fitMap`). */
    val fitToken: Int = 0,
    /** Random id of this ViewModel instance; scopes [fitToken] so a token saved before process death never matches a new search. */
    val fitSession: Long = 0,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** The active search center. */
    val center: LatLng? get() = if (mode == SearchMode.HERE) hereCenter else placeCenter

    /** `true` when the "Hier & jetzt" location hint row is shown. */
    val showLocationHint: Boolean get() = mode == SearchMode.HERE && locStatus != LocationStatus.OK && !locating

    val today: LocalDate get() = now.atZone(zone).toLocalDate()

    /** Nearby date pickers: [today − 1 day, today + 365 days] (spec 09 §8.13). */
    val firstPickDate: LocalDate get() = today.minusDays(1)
    val lastPickDate: LocalDate get() = today.plusDays(365)
}

sealed interface NearbyAction {
    data object Back : NearbyAction
    data class SetMode(val mode: SearchMode) : NearbyAction
    data object RetryLocation : NearbyAction
    data object OpenAppSettings : NearbyAction
    data object OpenLocationSettings : NearbyAction
    data class RadiusChanging(val km: Double) : NearbyAction
    data object RadiusChangeFinished : NearbyAction
    data object DateAny : NearbyAction
    data class DaySelected(val day: LocalDate) : NearbyAction
    data class RangeSelected(val from: LocalDate, val to: LocalDate) : NearbyAction
    data class QueryChanged(val query: String) : NearbyAction
    data class PlaceSelected(val place: GeoPlace) : NearbyAction
    data class OpenDetails(val meetupId: String) : NearbyAction
    data class OpenDirections(val nearby: NearbyMeetup) : NearbyAction
}

@HiltViewModel
class NearbyMeetupsViewModel @Inject constructor(
    private val location: MeetupLocationService,
    private val nearby: NearbyMeetupService,
    private val geocoding: GeocodingService,
    private val time: TimeSource,
) : ViewModel() {

    private val _state = MutableStateFlow(
        NearbyUiState(now = Instant.ofEpochMilli(time.nowMillis()), fitSession = Random.nextLong()),
    )
    val state: StateFlow<NearbyUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var geocodeJob: Job? = null
    private var locateJob: Job? = null
    private var retryOnResume = false

    init {
        initHere()
    }

    fun onAction(action: NearbyAction) {
        when (action) {
            is NearbyAction.SetMode -> setMode(action.mode)
            NearbyAction.RetryLocation -> initHere()
            NearbyAction.OpenAppSettings -> {
                retryOnResume = location.openAppSettings()
            }
            NearbyAction.OpenLocationSettings -> {
                retryOnResume = location.openLocationSettings()
            }
            is NearbyAction.RadiusChanging -> _state.update { it.copy(radiusKm = action.km) }
            NearbyAction.RadiusChangeFinished -> runSearch()
            NearbyAction.DateAny -> {
                _state.update { it.copy(dateMode = DateMode.ANY, dayFrom = null, dayTo = null) }
                runSearch()
            }
            is NearbyAction.DaySelected -> {
                _state.update { it.copy(dateMode = DateMode.SINGLE_DAY, dayFrom = action.day, dayTo = null) }
                runSearch()
            }
            is NearbyAction.RangeSelected -> {
                _state.update { it.copy(dateMode = DateMode.RANGE, dayFrom = action.from, dayTo = action.to) }
                runSearch()
            }
            is NearbyAction.QueryChanged -> onQuery(action.query)
            is NearbyAction.PlaceSelected -> selectPlace(action.place)
            NearbyAction.Back, is NearbyAction.OpenDetails, is NearbyAction.OpenDirections -> Unit
        }
    }

    /** Back from the system settings: try the location again (native addition). */
    fun onResume() {
        if (!retryOnResume) return
        retryOnResume = false
        if (_state.value.mode == SearchMode.HERE) initHere()
    }

    /** Dart `_initHere`: acquire the position (prompts for permission), then search. */
    private fun initHere() {
        locateJob?.cancel()
        searchJob?.cancel()
        _state.update { it.copy(loading = true, locating = true) }
        locateJob = viewModelScope.launch {
            val loc = location.getCurrentLocation()
            val pos = loc.position
            _state.update {
                it.copy(
                    locating = false,
                    locStatus = loc.status,
                    hereCenter = if (loc.status == LocationStatus.OK && pos != null) LatLng(pos.latitude, pos.longitude) else null,
                )
            }
            runSearch()
        }
    }

    private fun setMode(mode: SearchMode) {
        val s = _state.value
        if (s.mode == mode) return
        _state.update { it.copy(mode = mode) }
        when (mode) {
            SearchMode.HERE -> if (s.hereCenter == null) initHere() else runSearch()
            // Dart waits for a place; a place picked earlier is searched again.
            SearchMode.PLANNED -> runSearch()
        }
    }

    private fun runSearch() {
        searchJob?.cancel()
        val s = _state.value
        val center = s.center
        if (center == null) {
            _state.update { it.copy(loading = false, results = emptyList()) }
            return
        }
        _state.update { it.copy(loading = true) }
        searchJob = viewModelScope.launch {
            val now = Instant.ofEpochMilli(time.nowMillis())
            val results = nearby.search(
                centerLat = center.latitude,
                centerLng = center.longitude,
                radiusKm = s.radiusKm,
                dateMode = s.dateMode,
                dayFrom = s.dayFrom,
                dayTo = s.dayTo,
                zone = s.zone,
            )
            _state.update { it.copy(loading = false, results = results, now = now, fitToken = it.fitToken + 1) }
        }
    }

    private fun onQuery(q: String) {
        geocodeJob?.cancel()
        _state.update { it.copy(query = q) }
        if (q.trim().length < GeocodingService.MIN_QUERY_LENGTH) {
            _state.update { it.copy(placeResults = emptyList(), searchingPlace = false) }
            return
        }
        geocodeJob = viewModelScope.launch {
            delay(GeocodingService.DEBOUNCE_MS)
            _state.update { it.copy(searchingPlace = true) }
            val places = geocoding.search(q)
            _state.update { it.copy(placeResults = places, searchingPlace = false) }
        }
    }

    private fun selectPlace(place: GeoPlace) {
        geocodeJob?.cancel()
        _state.update {
            it.copy(
                placeCenter = LatLng(place.lat, place.lng),
                placeLabel = place.shortName,
                placeResults = emptyList(),
                searchingPlace = false,
                query = place.shortName,
            )
        }
        runSearch()
    }
}
