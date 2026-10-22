package space.einundzwanzig.meetup.feature.meetups.portal

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetup
import space.einundzwanzig.meetup.feature.meetups.common.UiMessage
import space.einundzwanzig.meetup.feature.meetups.common.UiText
import javax.inject.Inject
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * One co-leader row of a meetup card (`/api/meetup/{id}/leaders` entry, spec 09 §3.4).
 *
 * @property userId portal user id; only rows with an Int id can be removed.
 */
@Immutable
data class LeaderRow(
    val userId: Int?,
    val name: String,
    val npub: String,
    val isCreator: Boolean,
) {
    companion object {
        /** Dart `(a['name'] ?? '').toString()` etc. */
        fun fromJson(j: JsonObject): LeaderRow = LeaderRow(
            userId = j["id"]?.dartIntOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt(),
            name = j["name"].dartToString(),
            npub = j["nostr"].dartToString(),
            isCreator = (j["is_creator"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true,
        )

        private fun JsonElement?.dartToString(): String = when (this) {
            null, JsonNull -> ""
            is JsonPrimitive -> content
            else -> toString()
        }
    }
}

/** One `MeetupAdminCard`. */
@Immutable
data class PortalMeetupCard(
    val meetup: PortalMeetup,
    val leadersLoading: Boolean = false,
    val leaders: List<LeaderRow> = emptyList(),
    val busy: Boolean = false,
)

/** Open dialog of "Meine Meetups". */
sealed interface PortalDialog {
    data class AddLeader(val meetupId: Int) : PortalDialog
    data class RemoveLeader(val meetupId: Int, val userId: Int) : PortalDialog
}

/** State of "Meine Meetups" (spec 09 §2.1). */
@Immutable
data class PortalMeetupsUiState(
    val checking: Boolean = true,
    val connected: Boolean = false,
    val loggingIn: Boolean = false,
    val loadingMeetups: Boolean = false,
    val refreshing: Boolean = false,
    val cards: List<PortalMeetupCard> = emptyList(),
    val dialog: PortalDialog? = null,
)

sealed interface PortalMeetupsAction {
    data object Login : PortalMeetupsAction
    data object Logout : PortalMeetupsAction
    data object Refresh : PortalMeetupsAction
    data class ManageEvents(val meetup: PortalMeetup) : PortalMeetupsAction
    data class AskAddLeader(val meetupId: Int) : PortalMeetupsAction
    data class ConfirmAddLeader(val input: String) : PortalMeetupsAction
    data class AskRemoveLeader(val meetupId: Int, val leader: LeaderRow) : PortalMeetupsAction
    data object ConfirmRemoveLeader : PortalMeetupsAction
    data object DismissDialog : PortalMeetupsAction
    data object Back : PortalMeetupsAction
}

/**
 * "Meine Meetups" (Dart `PortalMeetupsScreen` + `MeetupAdminCard`, spec 09 §2.1).
 *
 * Deviation (spec 09 §14 item 8): before anything is loaded the token is checked with
 * `tokenMatchesCurrentKey()`; a token of another identity is deleted and the login view returns.
 */
@HiltViewModel
class PortalMeetupsViewModel @Inject constructor(
    private val portal: PortalApiService,
) : ViewModel() {

    private val _state = MutableStateFlow(PortalMeetupsUiState())
    val state: StateFlow<PortalMeetupsUiState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val connected = portal.hasToken()
            // Loading phase until the token is known to belong to this identity.
            _state.update { it.copy(checking = false, connected = connected, loadingMeetups = connected) }
            if (connected) {
                if (!portal.tokenMatchesCurrentKey()) {
                    // Never load (or send authenticated requests) with a foreign identity's token.
                    portal.deleteToken()
                    _state.update { it.copy(connected = false, loadingMeetups = false, cards = emptyList()) }
                    return@launch
                }
                loadMeetups()
            }
        }
    }

    fun onAction(action: PortalMeetupsAction) {
        when (action) {
            PortalMeetupsAction.Login -> login()
            PortalMeetupsAction.Logout -> logout()
            PortalMeetupsAction.Refresh -> refresh()
            is PortalMeetupsAction.AskAddLeader -> _state.update { it.copy(dialog = PortalDialog.AddLeader(action.meetupId)) }
            is PortalMeetupsAction.ConfirmAddLeader -> addLeader(action.input)
            is PortalMeetupsAction.AskRemoveLeader -> {
                // Dart: `admin['id'] is! int` → silently return.
                val id = action.leader.userId ?: return
                _state.update { it.copy(dialog = PortalDialog.RemoveLeader(action.meetupId, id)) }
            }
            PortalMeetupsAction.ConfirmRemoveLeader -> removeLeader()
            PortalMeetupsAction.DismissDialog -> _state.update { it.copy(dialog = null) }
            is PortalMeetupsAction.ManageEvents, PortalMeetupsAction.Back -> Unit
        }
    }

    private fun login() {
        if (_state.value.loggingIn) return
        _state.update { it.copy(loggingIn = true) }
        viewModelScope.launch {
            val res = portal.loginWithNostr()
            if (res.ok) {
                _state.update { it.copy(loggingIn = false, connected = true) }
                loadMeetups()
            } else {
                _state.update { it.copy(loggingIn = false) }
                _messages.send(
                    UiMessage(UiText.Prefixed(UiText.Res(L10nR.string.portalLoginFailed), UiText.portal(res)), Tone.Error),
                )
            }
        }
    }

    private fun logout() {
        loadJob?.cancel()
        viewModelScope.launch {
            portal.logout()
            _state.update { it.copy(connected = false, cards = emptyList(), loadingMeetups = false, refreshing = false) }
        }
    }

    private fun refresh() {
        if (!_state.value.connected) return
        _state.update { it.copy(refreshing = true) }
        loadMeetups(pull = true)
    }

    private fun loadMeetups(pull: Boolean = false) {
        loadJob?.cancel()
        if (!pull) _state.update { it.copy(loadingMeetups = true) }
        loadJob = viewModelScope.launch {
            val meetups = portal.getMyMeetups()
            _state.update { s ->
                s.copy(
                    loadingMeetups = false,
                    refreshing = false,
                    cards = meetups.map { PortalMeetupCard(it, leadersLoading = it.isLeader) },
                )
            }
            meetups.filter { it.isLeader }.forEach { m -> launch { loadLeaders(m.id) } }
        }
    }

    private suspend fun loadLeaders(meetupId: Int) {
        updateCard(meetupId) { it.copy(leadersLoading = true) }
        val rows = portal.getMeetupLeaders(meetupId).map(LeaderRow::fromJson)
        updateCard(meetupId) { it.copy(leadersLoading = false, leaders = rows) }
    }

    private fun updateCard(meetupId: Int, transform: (PortalMeetupCard) -> PortalMeetupCard) {
        _state.update { s -> s.copy(cards = s.cards.map { if (it.meetup.id == meetupId) transform(it) else it }) }
    }

    private fun addLeader(input: String) {
        val dialog = _state.value.dialog as? PortalDialog.AddLeader ?: return
        _state.update { it.copy(dialog = null) }
        val npub = input.trim()
        if (npub.isEmpty()) return
        viewModelScope.launch {
            if (!PortalApiService.looksLikeNpub(npub)) {
                _messages.send(UiMessage(UiText.Res(L10nR.string.ldNpubInvalid), Tone.Error))
                return@launch
            }
            updateCard(dialog.meetupId) { it.copy(busy = true) }
            val res = portal.addMeetupLeader(dialog.meetupId, npub)
            updateCard(dialog.meetupId) { it.copy(busy = false) }
            if (res.ok) {
                _messages.send(UiMessage(UiText.Res(L10nR.string.ldAdded), Tone.Success))
                loadLeaders(dialog.meetupId)
            } else {
                _messages.send(UiMessage(UiText.Prefixed(UiText.Res(L10nR.string.ldFailed), UiText.portal(res)), Tone.Error))
            }
        }
    }

    private fun removeLeader() {
        val dialog = _state.value.dialog as? PortalDialog.RemoveLeader ?: return
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            updateCard(dialog.meetupId) { it.copy(busy = true) }
            val res = portal.removeMeetupLeader(dialog.meetupId, dialog.userId)
            updateCard(dialog.meetupId) { it.copy(busy = false) }
            if (res.ok) {
                _messages.send(UiMessage(UiText.Res(L10nR.string.ldRemoved), Tone.Success))
                loadLeaders(dialog.meetupId)
            } else {
                _messages.send(UiMessage(UiText.Prefixed(UiText.Res(L10nR.string.ldFailed), UiText.portal(res)), Tone.Error))
            }
        }
    }
}
