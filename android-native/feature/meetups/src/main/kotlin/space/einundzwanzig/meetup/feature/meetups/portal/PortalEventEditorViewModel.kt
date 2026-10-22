package space.einundzwanzig.meetup.feature.meetups.portal

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.core.navigation.PortalEventEditor
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.meetups.MeetupScreenRules
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetupEvent
import space.einundzwanzig.meetup.data.meetups.portal.PortalTime
import space.einundzwanzig.meetup.feature.meetups.R
import space.einundzwanzig.meetup.feature.meetups.common.UiMessage
import space.einundzwanzig.meetup.feature.meetups.common.UiText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** State of the portal date editor (Dart `_EventEditor`, spec 09 §2.1). */
@Immutable
data class PortalEventEditorUiState(
    val meetupName: String,
    val isEdit: Boolean,
    /** Edit mode: the date is being resolved (cache, then one `getMyMeetupEvents` call). */
    val loading: Boolean = false,
    /** Edit mode: the date was not found, also not after the network attempt. */
    val notFound: Boolean = false,
    val start: ZonedDateTime? = null,
    val location: String = "",
    val description: String = "",
    val link: String = "",
    val saving: Boolean = false,
    /** Today in the device zone; the date picker allows today − 1 … today + 1095 days. */
    val today: LocalDate,
    /** The picker's initial date/time when nothing is set yet (Dart `DateTime.now()`). */
    val now: ZonedDateTime,
) {
    val firstDate: LocalDate get() = today.minusDays(1)
    val lastDate: LocalDate get() = today.plusDays(MAX_DAYS_AHEAD)

    companion object {
        /** Dart `now.add(const Duration(days: 365 * 3))`. */
        const val MAX_DAYS_AHEAD = 365L * 3
    }
}

sealed interface PortalEventEditorAction {
    data object Back : PortalEventEditorAction

    /** Date picked; [time] `null` = the time dialog was dismissed → 19:00. */
    data class StartPicked(val date: LocalDate, val time: LocalTime?) : PortalEventEditorAction
    data class LocationChanged(val value: String) : PortalEventEditorAction
    data class DescriptionChanged(val value: String) : PortalEventEditorAction
    data class LinkChanged(val value: String) : PortalEventEditorAction
    data object Save : PortalEventEditorAction
}

/** One-off results of the editor. */
sealed interface PortalEventEditorEvent {
    /** Saved: send `PortalEventSaved(created)` and pop. */
    data class Saved(val created: Boolean) : PortalEventEditorEvent
}

@HiltViewModel(assistedFactory = PortalEventEditorViewModel.Factory::class)
class PortalEventEditorViewModel @AssistedInject constructor(
    @Assisted private val key: PortalEventEditor,
    private val portal: PortalApiService,
    private val time: TimeSource,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: PortalEventEditor): PortalEventEditorViewModel
    }

    private val zone: ZoneId = ZoneId.systemDefault()

    /** The edited portal date; resolved from the cache or, after process death, from the network. */
    private var existing: PortalMeetupEvent? = null

    private val _state: MutableStateFlow<PortalEventEditorUiState>
    val state: StateFlow<PortalEventEditorUiState>

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    private val _events = Channel<PortalEventEditorEvent>(Channel.BUFFERED)
    val events: Flow<PortalEventEditorEvent> = _events.receiveAsFlow()

    init {
        val now = Instant.ofEpochMilli(time.nowMillis()).atZone(zone)
        val cached = if (key.isEdit) resolve(portal.lastMyMeetupEvents) else null
        existing = cached
        val pending = key.isEdit && cached == null
        _state = MutableStateFlow(
            withSaved(
                withExisting(
                    PortalEventEditorUiState(
                        meetupName = key.meetupName,
                        isEdit = key.isEdit,
                        loading = pending,
                        today = now.toLocalDate(),
                        now = now,
                    ),
                    cached,
                ),
            ),
        )
        state = _state.asStateFlow()
        if (pending) {
            // The in-memory cache is empty after process death (or when opened directly):
            // ask the portal once before showing "not found".
            viewModelScope.launch {
                val fetched = runSuspendCatching { portal.getMyMeetupEvents() }.getOrNull().orEmpty()
                val ex = resolve(fetched)
                existing = ex
                _state.update { withSaved(withExisting(it.copy(loading = false, notFound = ex == null), ex)) }
            }
        }
    }

    private fun resolve(events: List<PortalMeetupEvent>): PortalMeetupEvent? =
        MeetupScreenRules.resolveEditedEvent(events, key.meetupId, key.eventId, key.eventIndex)

    /** Prefill from the portal date. */
    private fun withExisting(s: PortalEventEditorUiState, ex: PortalMeetupEvent?): PortalEventEditorUiState {
        if (ex == null) return s
        // Native fix of the Flutter prefill bug (spec 09 §8.12, §14 item 3): the raw portal value
        // is UTC without zone, so prefill with portalStartUtc; a save without changes keeps the time.
        val start = PortalTime.portalStartUtc(ex.start, zone)
        AppLogger.diag(TAG, "Editor: start roh=${ex.start} → lokal=$start")
        return s.copy(
            start = start,
            location = ex.location.orEmpty(),
            description = ex.description.orEmpty(),
            link = ex.link.orEmpty(),
        )
    }

    /** Values typed before process death win over the prefill. */
    private fun withSaved(s: PortalEventEditorUiState): PortalEventEditorUiState = s.copy(
        start = savedState.get<String>(KEY_START)
            ?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() }
            ?: s.start,
        location = savedState.get<String>(KEY_LOCATION) ?: s.location,
        description = savedState.get<String>(KEY_DESCRIPTION) ?: s.description,
        link = savedState.get<String>(KEY_LINK) ?: s.link,
    )

    fun onAction(action: PortalEventEditorAction) {
        when (action) {
            is PortalEventEditorAction.StartPicked -> {
                val start = action.date.atTime(action.time ?: DEFAULT_TIME).atZone(zone)
                savedState[KEY_START] = start.toOffsetDateTime().toString()
                _state.update { it.copy(start = start) }
            }
            is PortalEventEditorAction.LocationChanged -> {
                savedState[KEY_LOCATION] = action.value
                _state.update { it.copy(location = action.value) }
            }
            is PortalEventEditorAction.DescriptionChanged -> {
                savedState[KEY_DESCRIPTION] = action.value
                _state.update { it.copy(description = action.value) }
            }
            is PortalEventEditorAction.LinkChanged -> {
                savedState[KEY_LINK] = action.value
                _state.update { it.copy(link = action.value) }
            }
            PortalEventEditorAction.Save -> save()
            PortalEventEditorAction.Back -> Unit
        }
    }

    private fun save() {
        val s = _state.value
        if (s.saving || s.notFound || s.loading) return
        viewModelScope.launch {
            val start = s.start
            if (start == null) {
                _messages.send(UiMessage(UiText.Res(L10nR.string.portalNeedStart), Tone.Error))
                return@launch
            }
            val iso = PortalTime.toPortalIso(start)
            val location = s.location.trim().ifEmpty { null }
            val description = s.description.trim().ifEmpty { null }
            val link = s.link.trim().ifEmpty { null }
            val ex = existing
            if (s.isEdit && ex?.id == null) {
                _messages.send(UiMessage(UiText.Res(R.string.native_portal_event_without_id), Tone.Error))
                return@launch
            }
            _state.update { it.copy(saving = true) }
            val res = if (ex?.id != null) {
                portal.updateMeetupEvent(ex.id!!, start = iso, location = location, description = description, link = link)
            } else {
                portal.createMeetupEvent(key.meetupId, iso, location, description, link)
            }
            _state.update { it.copy(saving = false) }
            if (res.ok) {
                _events.send(PortalEventEditorEvent.Saved(created = !s.isEdit))
            } else {
                _messages.send(UiMessage(UiText.portal(res), Tone.Error))
            }
        }
    }

    private companion object {
        const val TAG = "Portal"
        const val KEY_START = "editor_start"
        const val KEY_LOCATION = "editor_location"
        const val KEY_DESCRIPTION = "editor_description"
        const val KEY_LINK = "editor_link"

        /** Dart: time dialog dismissed → 19:00. */
        val DEFAULT_TIME: LocalTime = LocalTime.of(19, 0)
    }
}
