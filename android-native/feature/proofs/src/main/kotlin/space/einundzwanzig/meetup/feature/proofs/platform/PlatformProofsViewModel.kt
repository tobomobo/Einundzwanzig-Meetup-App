package space.einundzwanzig.meetup.feature.proofs.platform

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.data.proofs.platform.PlatformCatalog
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofRepository
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateError
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateResult
import space.einundzwanzig.meetup.feature.proofs.republish.ReputationRepublisher
import javax.inject.Inject

/**
 * Local state of the create sheet (spec 06 §2.1 "Create sheet").
 *
 * The two text fields are [TextFieldState]s (synchronous snapshot state) rather than strings in
 * the [StateFlow], so fast typing and IME composition never desync from an asynchronously
 * collected value. [canSubmit] reads them, so composition re-evaluates it on every edit.
 */
@Stable
data class CreateSheetState(
    val selectedPlatform: String? = null,
    val customPlatformName: TextFieldState = TextFieldState(),
    val username: TextFieldState = TextFieldState(),
) {
    /** Flutter's silent validation: nothing happens while this is false. */
    val canSubmit: Boolean
        get() = selectedPlatform != null &&
            username.text.isNotBlank() &&
            (selectedPlatform != PlatformCatalog.OTHER || customPlatformName.text.isNotBlank())
}

/** UI state of the platform proof screen. */
@Stable
data class PlatformProofsUiState(
    val isLoading: Boolean = true,
    val proofs: List<PlatformProof> = emptyList(),
    /** Non-null while the create sheet is open. */
    val sheet: CreateSheetState? = null,
    /** True while the signer signs kind 21003 (external signers can take seconds). */
    val isCreating: Boolean = false,
    /** Non-null while the revoke dialog is shown. */
    val pendingRevoke: PlatformProof? = null,
) {
    /** Platform ids that already have a proof (chip check mark). */
    val linkedPlatforms: Set<String> get() = proofs.mapTo(HashSet()) { it.platform }
}

/** User actions of the platform proof screen. */
sealed interface PlatformProofsAction {
    data object OpenSheet : PlatformProofsAction
    data object DismissSheet : PlatformProofsAction
    data class SelectPlatform(val platformId: String) : PlatformProofsAction
    data object Create : PlatformProofsAction
    data class RequestRevoke(val proof: PlatformProof) : PlatformProofsAction
    data object DismissRevoke : PlatformProofsAction
    data object ConfirmRevoke : PlatformProofsAction
}

/** One-shot effects for the route. */
sealed interface PlatformProofsEvent {
    /** Show `ppLinkSaved` (green, 3 s) and close the screen (Flutter pops, spec 06 §14 R3). */
    data object LinkSaved : PlatformProofsEvent

    /** Show the failure (red, 10 s, close icon). */
    data class CreateFailed(val error: ProofCreateError, val detail: String?) : PlatformProofsEvent

    /** Native: the revoke write failed (Flutter had no failure path). */
    data object RevokeFailed : PlatformProofsEvent
}

@HiltViewModel
internal class PlatformProofsViewModel @Inject constructor(
    private val repository: PlatformProofRepository,
    private val republisher: ReputationRepublisher,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(PlatformProofsUiState())
    val state: StateFlow<PlatformProofsUiState> = _state.asStateFlow()

    private val _events = Channel<PlatformProofsEvent>(Channel.BUFFERED)
    val events: Flow<PlatformProofsEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.proofs
                .catch { e ->
                    AppLogger.warn(TAG, "Plattform-Proofs nicht lesbar", e)
                    emit(emptyList())
                }
                .collect { proofs -> _state.update { it.copy(isLoading = false, proofs = proofs) } }
        }
    }

    fun onAction(action: PlatformProofsAction) {
        when (action) {
            PlatformProofsAction.OpenSheet -> _state.update { it.copy(sheet = CreateSheetState()) }
            PlatformProofsAction.DismissSheet -> _state.update { it.copy(sheet = null) }
            is PlatformProofsAction.SelectPlatform -> updateSheet {
                // Changing the chip clears the username; the custom name stays (Flutter).
                it.username.clearText()
                it.copy(selectedPlatform = action.platformId)
            }
            PlatformProofsAction.Create -> create()
            is PlatformProofsAction.RequestRevoke -> _state.update { it.copy(pendingRevoke = action.proof) }
            PlatformProofsAction.DismissRevoke -> _state.update { it.copy(pendingRevoke = null) }
            PlatformProofsAction.ConfirmRevoke -> revoke()
        }
    }

    /**
     * Shows `ppLinkSaved` (success, 3 s) on the app-level [host] from the application scope, so
     * the snackbar outlives this screen (Flutter's root messenger). Only call it with the app
     * host: [AppSnackbarHost][space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost]
     * enforces the duration, a host that leaves composition would suspend forever.
     */
    fun showLinkSavedOnAppHost(host: SnackbarHostState, message: String) {
        appScope.launch(Dispatchers.Main.immediate) {
            host.showAppSnackbar(message, Tone.Success, AppSnackbarDurations.Medium)
        }
    }

    private inline fun updateSheet(transform: (CreateSheetState) -> CreateSheetState) {
        val sheet = _state.value.sheet ?: return
        val updated = transform(sheet)
        _state.update { it.copy(sheet = updated) }
    }

    fun create(reportResult: ((ProofCreateResult) -> Unit)? = null) {
        val current = _state.value
        val sheet = current.sheet ?: return
        if (current.isCreating || !sheet.canSubmit) return
        val platform = sheet.selectedPlatform ?: return
        val username = sheet.username.text.toString().trim()
        val customName = if (platform == PlatformCatalog.OTHER) sheet.customPlatformName.text.toString().trim() else null
        _state.update { it.copy(sheet = null, isCreating = true) }
        // Sign + store + republish run in the application scope: with Amber or a NIP-46 bunker
        // the user may leave while the signer is open, and a proof they already approved must
        // still be stored and published (Flutter's future keeps running after the pop).
        appScope.launch {
            val result = try {
                repository.createProof(platform, username, customName).also {
                    if (it is ProofCreateResult.Success) republisher.republishForced()
                }
            } finally {
                _state.update { it.copy(isCreating = false) }
            }
            // The reporter captures only localized resources and the app-lifetime queue.
            // It runs even if Nav3 has already cleared the ViewModel and route collector.
            reportResult?.invoke(result)
            when (result) {
                is ProofCreateResult.Success -> _events.trySend(PlatformProofsEvent.LinkSaved)
                is ProofCreateResult.Failure -> if (reportResult == null) {
                    _events.trySend(PlatformProofsEvent.CreateFailed(result.error, result.detail))
                }
            }
        }
    }

    private fun revoke() {
        val proof = _state.value.pendingRevoke ?: return
        // Close the dialog before the suspend calls (Flutter captured the navigator first).
        _state.update { it.copy(pendingRevoke = null) }
        // Application scope: leaving right after confirming must not cancel the write/republish.
        appScope.launch {
            if (repository.removeProof(proof.platform)) {
                republisher.republishForced()
            } else {
                _events.trySend(PlatformProofsEvent.RevokeFailed)
            }
        }
    }

    private companion object {
        const val TAG = "PlatformProof"
    }
}
