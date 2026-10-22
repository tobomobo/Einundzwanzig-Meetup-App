package space.einundzwanzig.meetup.feature.meetups.picker

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.map.LatLng
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import space.einundzwanzig.meetup.data.meetups.location.MeetupLocationService
import space.einundzwanzig.meetup.feature.meetups.R
import space.einundzwanzig.meetup.feature.meetups.common.UiMessage
import space.einundzwanzig.meetup.feature.meetups.common.UiText
import javax.inject.Inject

/** State of "Ort des Events" (Dart `LocationPickerScreen`, spec 09 §2.6). */
@Immutable
data class LocationPickerUiState(
    val pick: LatLng? = null,
    val locating: Boolean = false,
)

sealed interface LocationPickerAction {
    data object Back : LocationPickerAction
    data class Pick(val point: LatLng) : LocationPickerAction
    data object JumpToMe : LocationPickerAction
    data object Confirm : LocationPickerAction
}

@HiltViewModel
class LocationPickerViewModel @Inject constructor(
    private val location: MeetupLocationService,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LocationPickerUiState(
            pick = savedState.get<DoubleArray>(KEY_PICK)?.let { LatLng(it[0], it[1]) },
        ),
    )
    val state: StateFlow<LocationPickerUiState> = _state.asStateFlow()

    /** Camera targets for "Zu meinem Standort" (moves the map only, never the pick). */
    private val _jumps = Channel<LatLng>(Channel.CONFLATED)
    val jumps: Flow<LatLng> = _jumps.receiveAsFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    private var initialized = savedState.contains(KEY_PICK)

    /** Presets the pick from the NavKey once (both values set and not both 0). */
    fun init(initial: LatLng?) {
        if (initialized) return
        initialized = true
        if (initial != null) setPick(initial)
    }

    fun onAction(action: LocationPickerAction) {
        when (action) {
            is LocationPickerAction.Pick -> setPick(action.point)
            LocationPickerAction.JumpToMe -> jumpToMe()
            LocationPickerAction.Back, LocationPickerAction.Confirm -> Unit
        }
    }

    private fun setPick(p: LatLng) {
        savedState[KEY_PICK] = doubleArrayOf(p.latitude, p.longitude)
        _state.update { it.copy(pick = p) }
    }

    /**
     * Dart `_jumpToMe`: asks the location provider directly (Flutter also downloaded the meetup
     * list here, spec 09 §2.6). A failure shows a short hint instead of staying silent.
     */
    private fun jumpToMe() {
        if (_state.value.locating) return
        _state.update { it.copy(locating = true) }
        viewModelScope.launch {
            val result = location.getCurrentLocation()
            _state.update { it.copy(locating = false) }
            val pos = result.position
            if (result.status == LocationStatus.OK && pos != null && !(pos.latitude == 0.0 && pos.longitude == 0.0)) {
                _jumps.send(LatLng(pos.latitude, pos.longitude))
            } else {
                _messages.send(UiMessage(UiText.Res(R.string.native_loc_pick_no_fix), Tone.Warning))
            }
        }
    }

    private companion object {
        const val KEY_PICK = "pick"
    }
}
