package space.einundzwanzig.meetup.feature.profile.relay

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.nostr.relay.RelayAddResult
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.feature.profile.common.SnackbarMessage
import space.einundzwanzig.meetup.feature.profile.common.UiText

/** One relay row (spec 07 §2.1 items 5 and 7). */
@Immutable
data class RelayRow(val url: String, val enabled: Boolean)

/** State of the relay settings screen (Dart `_RelaySettingsScreenState`). */
@Immutable
data class RelaySettingsUiState(
    val isLoading: Boolean = true,
    val defaultRelays: List<RelayRow> = emptyList(),
    val customRelays: List<String> = emptyList(),
    val activeCount: Int = 0,
    val addDialogOpen: Boolean = false,
    val isTestingRelay: Boolean = false,
)

/** User actions of [RelaySettingsScreen]. */
sealed interface RelaySettingsAction {
    data class ToggleDefault(val url: String, val enabled: Boolean) : RelaySettingsAction
    data class RemoveCustom(val url: String) : RelaySettingsAction
    data object OpenAddDialog : RelaySettingsAction
    data object DismissAddDialog : RelaySettingsAction
    data class AddRelay(val url: String) : RelaySettingsAction
    data object Back : RelaySettingsAction
}

@HiltViewModel
class RelaySettingsViewModel @Inject constructor(
    private val relayConfig: RelayConfig,
) : ViewModel() {

    private val _state = MutableStateFlow(RelaySettingsUiState())
    val state: StateFlow<RelaySettingsUiState> = _state.asStateFlow()

    private val snackbarChannel = Channel<SnackbarMessage>(Channel.BUFFERED)
    val snackbars: Flow<SnackbarMessage> = snackbarChannel.receiveAsFlow()

    /**
     * Bumped whenever the add dialog opens or closes. A relay test only closes the dialog / clears
     * [RelaySettingsUiState.isTestingRelay] if the dialog it was started from is still the open one;
     * its snackbar and the list reload always happen.
     */
    private var dialogGeneration = 0

    init {
        viewModelScope.launch { reload() }
    }

    fun onAction(action: RelaySettingsAction) {
        when (action) {
            is RelaySettingsAction.ToggleDefault -> viewModelScope.launch {
                guarded { relayConfig.setDefaultRelayEnabled(action.url, action.enabled) }
                reload()
            }
            is RelaySettingsAction.RemoveCustom -> viewModelScope.launch {
                guarded { relayConfig.removeCustomRelay(action.url) }
                reload()
            }
            RelaySettingsAction.OpenAddDialog -> {
                dialogGeneration++
                _state.update { it.copy(addDialogOpen = true, isTestingRelay = false) }
            }
            // Closing during a test resets the flag; the running test still reports its result.
            RelaySettingsAction.DismissAddDialog -> {
                dialogGeneration++
                _state.update { it.copy(addDialogOpen = false, isTestingRelay = false) }
            }
            is RelaySettingsAction.AddRelay -> addRelay(action.url)
            RelaySettingsAction.Back -> Unit
        }
    }

    private fun addRelay(url: String) {
        if (_state.value.isTestingRelay) return
        _state.update { it.copy(isTestingRelay = true) }
        val generation = dialogGeneration
        snackbarChannel.trySend(
            SnackbarMessage(UiText.res(L10nR.string.rsTesting), Tone.Neutral, AppSnackbarDurations.Long),
        )
        viewModelScope.launch {
            val result = try {
                relayConfig.addCustomRelay(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Relay-Test fehlgeschlagen", e)
                RelayAddResult.UNREACHABLE
            }
            val current = generation == dialogGeneration
            if (current) _state.update { it.copy(isTestingRelay = false) }
            when (result) {
                RelayAddResult.ADDED -> {
                    if (current) _state.update { it.copy(addDialogOpen = false) }
                    reload()
                    snackbarChannel.send(SnackbarMessage(UiText.res(L10nR.string.rsRelayAdded), Tone.Success, replaceCurrent = true))
                }
                RelayAddResult.INVALID_URL ->
                    snackbarChannel.send(SnackbarMessage(UiText.res(L10nR.string.rsInvalidUrl), Tone.Error, replaceCurrent = true))
                RelayAddResult.UNREACHABLE ->
                    snackbarChannel.send(SnackbarMessage(UiText.res(L10nR.string.rsRelayUnreachable), Tone.Error, replaceCurrent = true))
                RelayAddResult.ALREADY_PRESENT -> {
                    if (current) _state.update { it.copy(addDialogOpen = false) }
                    snackbarChannel.send(SnackbarMessage(UiText.res(L10nR.string.rsRelayAlreadyAdded), Tone.Neutral, replaceCurrent = true))
                }
            }
        }
    }

    private suspend fun reload() {
        try {
            val status = relayConfig.getRelayStatus()
            val custom = relayConfig.getCustomRelays()
            val active = relayConfig.getActiveRelays()
            _state.update {
                it.copy(
                    isLoading = false,
                    defaultRelays = RelayConfig.DEFAULT_RELAYS.map { url -> RelayRow(url, status[url] ?: true) },
                    customRelays = custom,
                    activeCount = active.size,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Relay-Liste nicht lesbar", e)
            _state.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Relay-Einstellung nicht gespeichert", e)
            snackbarChannel.send(SnackbarMessage.error(UiText.res(L10nR.string.errorGeneric, e.message ?: e.toString())))
        }
    }

    private companion object {
        const val TAG = "RelaySettings"
    }
}
