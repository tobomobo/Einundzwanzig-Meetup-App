package space.einundzwanzig.meetup.feature.reputation.person

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.socialgraph.coattendance.PresenceCheck
import space.einundzwanzig.meetup.feature.reputation.data.ReputationDataSource

/** Error box of "Person prüfen" (spec 04 §2.7). */
internal enum class VerifyPersonError {
    /** `vpInvalidNpub`. */
    INVALID_NPUB,

    /** The check threw: Flutter shows `vpNoneTitle`. */
    CHECK_FAILED,
}

@Immutable
internal data class VerifyPersonUiState(
    val input: String = "",
    val checking: Boolean = false,
    val result: PresenceCheck? = null,
    val error: VerifyPersonError? = null,
)

internal sealed interface VerifyPersonAction {
    data class InputChanged(val text: String) : VerifyPersonAction
    data object Check : VerifyPersonAction

    /** An npub from the QR camera: set the text and check (spec 04 §2.7 step 2). */
    data class Scanned(val npub: String) : VerifyPersonAction
    data object OpenScanner : VerifyPersonAction
    data class OpenNostr(val npub: String) : VerifyPersonAction
}

@HiltViewModel(assistedFactory = VerifyPersonViewModel.Factory::class)
internal class VerifyPersonViewModel @AssistedInject constructor(
    @Assisted initialTargetNpub: String?,
    private val source: ReputationDataSource,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(initialTargetNpub: String?): VerifyPersonViewModel
    }

    private val _state = MutableStateFlow(VerifyPersonUiState())
    val state: StateFlow<VerifyPersonUiState> = _state.asStateFlow()

    init {
        if (!initialTargetNpub.isNullOrEmpty()) {
            _state.update { it.copy(input = initialTargetNpub) }
            check()
        }
    }

    fun onAction(action: VerifyPersonAction) {
        when (action) {
            is VerifyPersonAction.InputChanged -> _state.update { it.copy(input = action.text) }
            VerifyPersonAction.Check -> check()
            is VerifyPersonAction.Scanned -> {
                // A scan replaces any running check, so the field and the result never disagree.
                _state.update { it.copy(input = action.npub) }
                check(restart = true)
            }
            // Handled by the route.
            VerifyPersonAction.OpenScanner, is VerifyPersonAction.OpenNostr -> Unit
        }
    }

    private var job: Job? = null
    private var generation = 0

    private fun check(restart: Boolean = false) {
        if (_state.value.checking && !restart) return
        job?.cancel()
        val gen = ++generation
        val target = normalize(_state.value.input)
        _state.update { it.copy(error = null, result = null) }
        if (!source.isValidNpub(target)) {
            _state.update { it.copy(checking = false, error = VerifyPersonError.INVALID_NPUB) }
            return
        }
        _state.update { it.copy(checking = true) }
        job = viewModelScope.launch {
            try {
                val myNpub = source.myNpub()
                val result = source.verifyPerson(myNpub, target)
                if (gen == generation) _state.update { it.copy(result = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Personen-Prüfung fehlgeschlagen", e)
                if (gen == generation) _state.update { it.copy(error = VerifyPersonError.CHECK_FAILED) }
            } finally {
                // A superseded check must not clear the flag of the one that replaced it.
                if (gen == generation) _state.update { it.copy(checking = false) }
            }
        }
    }

    companion object {
        private const val TAG = "VerifyPerson"

        /**
         * Dart `input.trim().toLowerCase().replaceFirst('nostr:', '')`. PARITY: the first
         * occurrence anywhere, not only a prefix.
         */
        fun normalize(input: String): String = DartString.lower(input.dartTrim()).replaceFirst("nostr:", "")
    }
}
