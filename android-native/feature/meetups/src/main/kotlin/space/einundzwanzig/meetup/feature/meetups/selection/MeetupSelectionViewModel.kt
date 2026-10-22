package space.einundzwanzig.meetup.feature.meetups.selection

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.meetups.FavoriteMigration
import space.einundzwanzig.meetup.data.meetups.MeetupScreenRules
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.feature.meetups.R
import space.einundzwanzig.meetup.feature.meetups.common.UiMessage
import space.einundzwanzig.meetup.feature.meetups.common.UiText
import javax.inject.Inject

/** State of "MEETUP AUSWÄHLEN" (Dart `MeetupSelectionScreen`, spec 09 §2.4). */
@Immutable
data class MeetupSelectionUiState(
    val loading: Boolean = true,
    /** The directory failed to load (spec 09 §14 item 11): retry, saving disabled. */
    val loadFailed: Boolean = false,
    val meetups: List<Meetup> = emptyList(),
    val query: String = "",
    /** Selected portal IDs in insertion order (Dart `LinkedHashSet`). */
    val selected: List<String> = emptyList(),
    val saving: Boolean = false,
) {
    val visible: List<Meetup> get() = MeetupScreenRules.selectionFilter(meetups, query)
    val canSave: Boolean get() = !loading && !loadFailed && !saving
}

sealed interface MeetupSelectionAction {
    data object Back : MeetupSelectionAction
    data object Retry : MeetupSelectionAction
    data class QueryChanged(val query: String) : MeetupSelectionAction
    data class Toggle(val meetupId: String) : MeetupSelectionAction
    data object Save : MeetupSelectionAction
}

@HiltViewModel
class MeetupSelectionViewModel @Inject constructor(
    private val profiles: UserProfileRepository,
    private val meetupService: MeetupService,
) : ViewModel() {

    private val _state = MutableStateFlow(MeetupSelectionUiState())
    val state: StateFlow<MeetupSelectionUiState> = _state.asStateFlow()

    /** Emits once after a successful save; the route pops (Dart `pop(true)`). */
    private val _saved = Channel<Unit>(Channel.CONFLATED)
    val saved: Flow<Unit> = _saved.receiveAsFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    init {
        load()
    }

    fun onAction(action: MeetupSelectionAction) {
        when (action) {
            MeetupSelectionAction.Retry -> load()
            is MeetupSelectionAction.QueryChanged -> _state.update { it.copy(query = action.query) }
            is MeetupSelectionAction.Toggle -> _state.update { s ->
                val ids = LinkedHashSet(s.selected)
                if (!ids.remove(action.meetupId)) ids.add(action.meetupId)
                s.copy(selected = ids.toList())
            }
            MeetupSelectionAction.Save -> save()
            MeetupSelectionAction.Back -> Unit
        }
    }

    private fun load() {
        _state.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            val profile = async { runSuspendCatching { profiles.load() }.getOrNull() }
            val list = runSuspendCatching { meetupService.fetchMeetups() }.getOrDefault(emptyList())
            val p = profile.await()
            if (p == null || list.isEmpty()) {
                // No offline fallback here (Dart); never let an empty list or an unreadable
                // profile (nothing preselected) wipe the stored favorites (§14 item 11).
                _state.update { it.copy(loading = false, loadFailed = true, meetups = emptyList()) }
                return@launch
            }
            val migration = FavoriteMigration.migrate(p.favoriteMeetupIds, list)
            if (migration.ambiguous.isNotEmpty()) {
                AppLogger.warn(TAG, "${migration.ambiguous.size} Favorit(en) mehrdeutig — bitte neu waehlen")
            }
            _state.update {
                it.copy(
                    loading = false,
                    loadFailed = false,
                    meetups = list,
                    // Keep taps made while a retry was loading.
                    selected = if (it.selected.isEmpty()) migration.ids else it.selected,
                )
            }
        }
    }

    private fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val ok = runSuspendCatching {
                profiles.update { p ->
                    p.copy(favoriteMeetupIds = s.selected, homeMeetupId = s.selected.firstOrNull().orEmpty())
                }
            }.onFailure { AppLogger.warn(TAG, "Favoriten nicht gespeichert", it) }.isSuccess
            _state.update { it.copy(saving = false) }
            if (ok) {
                _saved.send(Unit)
            } else {
                _messages.send(UiMessage(UiText.Res(R.string.native_meetups_save_failed), Tone.Error))
            }
        }
    }

    private companion object {
        const val TAG = "Meetups"
    }
}
