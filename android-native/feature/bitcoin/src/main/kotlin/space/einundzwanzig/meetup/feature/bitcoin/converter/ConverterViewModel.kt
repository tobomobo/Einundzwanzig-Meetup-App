package space.einundzwanzig.meetup.feature.bitcoin.converter

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.data.mempool.MempoolService
import space.einundzwanzig.meetup.data.mempool.converter.ConverterState
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepository
import javax.inject.Inject

/**
 * State of the converter screen: the pure [ConverterState] of `:data:mempool` plus revision
 * counters. A revision changes whenever the ViewModel rewrites a text field the user did not
 * type into (recalculation, unit swap, slider), so the screen re-seeds that field's cursor while
 * the field being typed in keeps its own `TextFieldValue` (spec 13 §8.8).
 */
@Immutable
data class ConverterUiState(
    val converter: ConverterState,
    val fiatRevision: Int = 0,
    val cryptoRevision: Int = 0,
    val premiumRevision: Int = 0,
)

/** User actions of the converter. */
sealed interface ConverterAction {
    /** Fiat field edited (already filtered to `[0-9.,]`). */
    data class FiatChanged(val text: String) : ConverterAction

    /** Crypto field edited (already filtered to `[0-9.,]`). */
    data class CryptoChanged(val text: String) : ConverterAction

    /** Unit chip tapped (sats ⇄ BTC). */
    data object SwapUnit : ConverterAction

    /** Currency picked in the sheet. */
    data class PickCurrency(val code: String) : ConverterAction

    /** Premium percent field edited. */
    data class PremiumChanged(val text: String) : ConverterAction

    /**
     * +/- button next to the premium field: negates the typed text. Compose has no signed number
     * keyboard type, so keyboards honouring the decimal flag may hide the minus key.
     */
    data object TogglePremiumSign : ConverterAction

    /** Premium slider moved. */
    data class SliderChanged(val value: Float) : ConverterAction

    /** Refresh button (app bar or offline banner). */
    data object Refresh : ConverterAction

    /** Back arrow. */
    data object Back : ConverterAction
}

/**
 * Converter (Flutter `_ConverterScreenState`, spec 13 §2.2). The currency is read once at
 * creation (no observation, as in Flutter); prices come from [MempoolService.getPrices] (current
 * host, no fallback, no cached dashboard prices). Nothing but the currency is persisted.
 */
@HiltViewModel
class ConverterViewModel @Inject constructor(
    private val mempool: MempoolService,
    private val currency: CurrencyRepository,
    private val time: TimeSource,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val state = MutableStateFlow(ConverterUiState(ConverterState(currency = currency.current.value)))

    /** UI state. */
    val uiState: StateFlow<ConverterUiState> = state.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadPrices()
    }

    /** Handles [action]; navigation is handled by the route. */
    fun onAction(action: ConverterAction) {
        when (action) {
            is ConverterAction.FiatChanged -> reduce(Field.FIAT) { it.onFiatChanged(action.text) }
            is ConverterAction.CryptoChanged -> reduce(Field.CRYPTO) { it.onCryptoChanged(action.text) }
            ConverterAction.SwapUnit -> reduce(null) { it.swapUnit() }
            is ConverterAction.PickCurrency -> {
                reduce(null) { it.onCurrencyPicked(action.code) }
                // App scope: the write must survive the screen being popped right after picking.
                appScope.launch { currency.set(action.code) }
            }
            is ConverterAction.PremiumChanged -> reduce(Field.PREMIUM) { it.onPremiumText(action.text) }
            ConverterAction.TogglePremiumSign -> reduce(null) {
                val t = it.premiumText
                it.onPremiumText(if (t.startsWith("-")) t.removePrefix("-") else "-$t")
            }
            is ConverterAction.SliderChanged -> reduce(null) { it.onSlider(action.value) }
            ConverterAction.Refresh -> loadPrices()
            ConverterAction.Back -> Unit
        }
    }

    private fun loadPrices() {
        if (loadJob?.isActive == true) return
        reduce(null) { it.onLoading() }
        loadJob = viewModelScope.launch {
            val prices = mempool.getPrices()
            reduce(null) { it.onPricesLoaded(prices, time.nowMillis()) }
        }
    }

    private enum class Field { FIAT, CRYPTO, PREMIUM }

    /** Applies [block]; bumps the revision of every field that changed but was not [typed]. */
    private fun reduce(typed: Field?, block: (ConverterState) -> ConverterState) {
        state.update { ui ->
            val old = ui.converter
            val new = block(old)
            ui.copy(
                converter = new,
                fiatRevision = ui.fiatRevision + bump(typed != Field.FIAT && old.fiatText != new.fiatText),
                cryptoRevision = ui.cryptoRevision + bump(typed != Field.CRYPTO && old.cryptoText != new.cryptoText),
                premiumRevision = ui.premiumRevision + bump(typed != Field.PREMIUM && old.premiumText != new.premiumText),
            )
        }
    }

    private fun bump(changed: Boolean): Int = if (changed) 1 else 0
}
