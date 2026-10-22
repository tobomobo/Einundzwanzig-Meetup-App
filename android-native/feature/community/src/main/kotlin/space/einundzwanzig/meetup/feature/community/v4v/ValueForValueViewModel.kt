package space.einundzwanzig.meetup.feature.community.v4v

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
import space.einundzwanzig.meetup.data.community.v4v.V4V_DEFAULT_COMMENT
import space.einundzwanzig.meetup.data.community.v4v.V4vErrorKind
import space.einundzwanzig.meetup.data.community.v4v.V4vResult
import space.einundzwanzig.meetup.data.community.v4v.V4vService
import space.einundzwanzig.meetup.data.community.v4v.parseV4vAmount
import javax.inject.Inject

/**
 * V4V screen state (spec 11 §2.10). [error] is the inline red message, [noWalletInvoice] the
 * invoice shown in the copy fallback sheet when no wallet could be opened.
 */
data class ValueForValueUiState(
    val amount: String = "",
    val loading: Boolean = false,
    val error: V4vErrorKind? = null,
    val noWalletInvoice: String? = null,
)

sealed interface ValueForValueAction {
    data class AmountChanged(val text: String) : ValueForValueAction
    data object Donate : ValueForValueAction

    /** The wallet chooser could not be opened for [invoice] → copy fallback sheet. */
    data class WalletUnavailable(val invoice: String) : ValueForValueAction
    data object CopyInvoice : ValueForValueAction
    data object DismissSheet : ValueForValueAction
}

/** One-shot effects the screen executes. */
sealed interface ValueForValueEvent {
    /** Open `lightning:<invoice>` through the wallet chooser. */
    data class OpenInvoice(val invoice: String) : ValueForValueEvent

    /** Copy [invoice] to the clipboard and show `v4vCopied`. */
    data class CopyInvoice(val invoice: String) : ValueForValueEvent
}

@HiltViewModel
class ValueForValueViewModel @Inject constructor(
    private val v4v: V4vService,
) : ViewModel() {

    private val _state = MutableStateFlow(ValueForValueUiState())
    val state: StateFlow<ValueForValueUiState> = _state.asStateFlow()

    private val _events = Channel<ValueForValueEvent>(Channel.BUFFERED)
    val events: Flow<ValueForValueEvent> = _events.receiveAsFlow()

    fun onAction(action: ValueForValueAction) {
        when (action) {
            // Digits-only input filter; typing clears the error.
            is ValueForValueAction.AmountChanged ->
                _state.update { it.copy(amount = action.text.filter { c -> c in '0'..'9' }, error = null) }
            ValueForValueAction.Donate -> donate()
            is ValueForValueAction.WalletUnavailable -> _state.update { it.copy(noWalletInvoice = action.invoice) }
            ValueForValueAction.CopyInvoice -> {
                val invoice = _state.value.noWalletInvoice ?: return
                _state.update { it.copy(noWalletInvoice = null) }
                _events.trySend(ValueForValueEvent.CopyInvoice(invoice))
            }
            ValueForValueAction.DismissSheet -> _state.update { it.copy(noWalletInvoice = null) }
        }
    }

    /** `_donate()`. */
    private fun donate() {
        if (_state.value.loading) return
        val amount = parseV4vAmount(_state.value.amount)
        if (amount <= 0) {
            _state.update { it.copy(error = V4vErrorKind.InvalidAmount) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = v4v.createInvoice(amountSats = amount, comment = V4V_DEFAULT_COMMENT)
            when (result) {
                is V4vResult.Success -> {
                    _state.update { it.copy(loading = false) }
                    _events.send(ValueForValueEvent.OpenInvoice(result.invoice))
                }
                is V4vResult.Failure -> _state.update { it.copy(loading = false, error = result.kind) }
            }
        }
    }
}
