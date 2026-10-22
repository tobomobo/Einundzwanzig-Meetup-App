package space.einundzwanzig.meetup.feature.proofs.humanity

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityOutcome
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityStatus
import space.einundzwanzig.meetup.data.proofs.humanity.LastChecked
import space.einundzwanzig.meetup.feature.proofs.republish.ReputationRepublisher
import java.time.ZoneId
import javax.inject.Inject

/** UI state of the humanity proof screen (spec 06 §2.2). */
@Immutable
data class HumanityProofUiState(
    val isLoading: Boolean = true,
    val status: HumanityStatus? = null,
    val isChecking: Boolean = false,
    /** Outcome of the last check on this screen; `null` before the first one. */
    val result: HumanityOutcome? = null,
    /** `d.M.yyyy` of the accepted receipt (only meaningful when verified). */
    val firstZapDate: String = "",
    /** Relative time of the last relay check; `null` when it never ran (`lastCheckedAt == 0`). */
    val lastChecked: LastChecked? = null,
) {
    /** `needsReverification` already forces `verified = false` in [HumanityStatus]. */
    val verified: Boolean get() = status?.verified ?: false

    /** Dart `resultSuccess`. */
    val resultSuccess: Boolean get() = result == HumanityOutcome.FOUND
}

sealed interface HumanityProofAction {
    data object Check : HumanityProofAction
}

@HiltViewModel
internal class HumanityProofViewModel @Inject constructor(
    private val repository: HumanityProofRepository,
    private val republisher: ReputationRepublisher,
    private val timeSource: TimeSource,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val _state = MutableStateFlow(HumanityProofUiState())
    val state: StateFlow<HumanityProofUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.status
                .catch { e ->
                    AppLogger.warn(TAG, "Humanity-Status nicht lesbar", e)
                    emit(HumanityStatus(verified = false))
                }
                .collect { status ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            status = status,
                            firstZapDate = status.firstZapDateStr(zone),
                            lastChecked = status.lastCheckedLabel(),
                        )
                    }
                }
        }
    }

    fun onAction(action: HumanityProofAction) {
        when (action) {
            HumanityProofAction.Check -> check()
        }
    }

    /**
     * Recomputes the relative "last check" label against the current clock (called on resume,
     * so a screen left in the background does not keep showing a stale "Gerade eben").
     */
    fun refreshClock() {
        _state.update { s ->
            val status = s.status ?: return@update s
            s.copy(lastChecked = status.lastCheckedLabel())
        }
    }

    private fun HumanityStatus.lastCheckedLabel(): LastChecked? =
        if (lastCheckedAt > 0) lastChecked(timeSource.nowMillis(), zone) else null

    private fun check() {
        if (_state.value.isChecking) return
        _state.update { it.copy(isChecking = true, result = null) }
        viewModelScope.launch {
            val result = try {
                repository.checkForZaps()
            } finally {
                _state.update { it.copy(isChecking = false) }
            }
            _state.update { it.copy(result = result.outcome) }
            if (result.found) {
                // Status reloads through repository.status. Spec 06 §14 R5: publish forced
                // (Flutter's publishInBackground([]) was a no-op).
                refreshStatus()
                republisher.republishForced()
            }
        }
    }

    private suspend fun refreshStatus() {
        val status = runCatching { repository.getStatus() }.getOrNull() ?: return
        _state.update {
            it.copy(
                status = status,
                firstZapDate = status.firstZapDateStr(zone),
                lastChecked = status.lastCheckedLabel(),
            )
        }
    }

    private companion object {
        const val TAG = "Humanity"
    }
}
