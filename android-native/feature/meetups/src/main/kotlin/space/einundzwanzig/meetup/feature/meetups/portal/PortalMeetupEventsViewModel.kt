package space.einundzwanzig.meetup.feature.meetups.portal

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.meetups.MeetupScreenRules
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalTime
import space.einundzwanzig.meetup.feature.meetups.common.MeetupFormats
import space.einundzwanzig.meetup.feature.meetups.common.UiMessage
import space.einundzwanzig.meetup.feature.meetups.common.UiText
import java.time.ZoneId
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * One row of "BESTEHENDE TERMINE".
 *
 * @property eventId portal id (`null` for dates without id; they still open the editor).
 * @property index position in this meetup's dates sorted by start (the editor's `eventIndex`).
 * @property whenText `dd.MM.yyyy  HH:mm` local, or the raw `start` when unparsable.
 * @property location the date's location, `null`/empty → "Zum Bearbeiten antippen".
 */
@Immutable
data class PortalEventRow(
    val eventId: Int?,
    val index: Int,
    val whenText: String,
    val location: String?,
)

@Immutable
data class PortalMeetupEventsUiState(
    val meetupName: String,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val rows: List<PortalEventRow> = emptyList(),
)

sealed interface PortalMeetupEventsAction {
    data object Back : PortalMeetupEventsAction
    data object Refresh : PortalMeetupEventsAction
    data object Create : PortalMeetupEventsAction
    data class Edit(val row: PortalEventRow) : PortalMeetupEventsAction
}

/** `ManageEventsScreen` (spec 09 §2.1): the portal dates of one meetup. */
@HiltViewModel(assistedFactory = PortalMeetupEventsViewModel.Factory::class)
class PortalMeetupEventsViewModel @AssistedInject constructor(
    @Assisted("meetupId") private val meetupId: Int,
    @Assisted("meetupName") meetupName: String,
    private val portal: PortalApiService,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("meetupId") meetupId: Int, @Assisted("meetupName") meetupName: String): PortalMeetupEventsViewModel
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val _state = MutableStateFlow(PortalMeetupEventsUiState(meetupName))
    val state: StateFlow<PortalMeetupEventsUiState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        load(pull = false)
    }

    fun onAction(action: PortalMeetupEventsAction) {
        when (action) {
            PortalMeetupEventsAction.Refresh -> {
                _state.update { it.copy(refreshing = true) }
                load(pull = true)
            }
            else -> Unit
        }
    }

    /** The editor returned `pop(true)`: green snackbar, then reload (Dart `_open`). */
    fun onSaved(created: Boolean) {
        viewModelScope.launch {
            _messages.send(
                UiMessage(
                    UiText.Res(if (created) L10nR.string.portalCreatedOk else L10nR.string.portalUpdatedOk),
                    Tone.Success,
                ),
            )
        }
        load(pull = false)
    }

    private fun load(pull: Boolean) {
        loadJob?.cancel()
        if (!pull) _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            val events = MeetupScreenRules.eventsOfMeetup(portal.getMyMeetupEvents(), meetupId)
            val rows = events.mapIndexed { i, ev ->
                PortalEventRow(
                    eventId = ev.id,
                    index = i,
                    whenText = MeetupFormats.portalStart(PortalTime.portalStartUtc(ev.start, zone)) ?: ev.start,
                    location = ev.location,
                )
            }
            _state.update { it.copy(loading = false, refreshing = false, rows = rows) }
        }
    }
}
