package space.einundzwanzig.meetup.feature.reputation.verify

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.data.proofs.platform.ProofVerifyResult
import space.einundzwanzig.meetup.data.proofs.platform.VerifyLevel
import space.einundzwanzig.meetup.data.reputation.event.ReputationEvent
import space.einundzwanzig.meetup.data.trust.layers.LayersInput
import space.einundzwanzig.meetup.feature.reputation.common.LiveLayers
import space.einundzwanzig.meetup.feature.reputation.common.loadLiveLayers
import space.einundzwanzig.meetup.feature.reputation.data.ReputationDataSource

/** UI state of the "REPUTATION PRÜFEN" verify-string screen (spec 04 §2.4). */
@Immutable
internal data class ReputationVerifyUiState(
    val input: String = "",
    val isVerifying: Boolean = false,
    val result: ProofVerifyResult<ReputationEvent>? = null,
    val layers: LiveLayers = LiveLayers(),
) {
    /** The layers widget shows once reputation, social or zaps exist (spec 04 §2.4 step 4). */
    val layersInput: LayersInput?
        get() {
            val rep = result?.reputation
            if (rep == null && layers.social == null && layers.zaps == null) return null
            return LayersMapping.input(rep, layers.zaps, layers.social, layers.nip05)
        }
}

internal sealed interface ReputationVerifyAction {
    data class InputChanged(val text: String) : ReputationVerifyAction
    data object Verify : ReputationVerifyAction

    /** Clipboard text from the paste button: set it and verify at once (non-empty only). */
    data class Paste(val text: String?) : ReputationVerifyAction
}

@HiltViewModel(assistedFactory = ReputationVerifyViewModel.Factory::class)
internal class ReputationVerifyViewModel @AssistedInject constructor(
    @Assisted initialInput: String?,
    private val source: ReputationDataSource,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(initialInput: String?): ReputationVerifyViewModel
    }

    // The typed text survives process death (e.g. while the user copies a string elsewhere).
    private val _state = MutableStateFlow(ReputationVerifyUiState(input = savedStateHandle[KEY_INPUT] ?: ""))
    val state: StateFlow<ReputationVerifyUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        if (!initialInput.isNullOrEmpty()) {
            // After process death the restored text wins over the navigation argument.
            if (savedStateHandle.get<String>(KEY_INPUT) == null) setInput(initialInput)
            verify()
        }
    }

    private fun setInput(text: String) {
        savedStateHandle[KEY_INPUT] = text
        _state.update { it.copy(input = text) }
    }

    fun onAction(action: ReputationVerifyAction) {
        when (action) {
            is ReputationVerifyAction.InputChanged -> setInput(action.text)
            ReputationVerifyAction.Verify -> verify()
            is ReputationVerifyAction.Paste -> if (!action.text.isNullOrEmpty()) {
                setInput(action.text)
                verify()
            }
        }
    }

    private var generation = 0

    /** Starts a verification; a running one is cancelled and replaced (e.g. by a paste). */
    private fun verify() {
        val input = _state.value.input.dartTrim()
        if (input.isEmpty()) return
        job?.cancel()
        val gen = ++generation
        _state.update { it.copy(isVerifying = true, result = null, layers = LiveLayers()) }
        job = viewModelScope.launch {
            val result = try {
                source.verifyProofString(input)
            } finally {
                // A superseded run must not clear the flag of the one that replaced it.
                if (gen == generation) _state.update { it.copy(isVerifying = false) }
            }
            _state.update { it.copy(result = result) }
            val npub = result.npub
            if (npub != null && result.level != VerifyLevel.INVALID) {
                val hex = source.npubToHex(npub) ?: return@launch
                loadLiveLayers(source, hex) { change -> _state.update { it.copy(layers = change(it.layers)) } }
            }
        }
    }

    internal companion object {
        const val KEY_INPUT = "input"
    }
}
