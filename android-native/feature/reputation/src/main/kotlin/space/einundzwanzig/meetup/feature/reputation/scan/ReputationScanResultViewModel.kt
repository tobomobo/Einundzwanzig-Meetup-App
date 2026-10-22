package space.einundzwanzig.meetup.feature.reputation.scan

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.data.reputation.qr.QrParseResult
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrCodec
import space.einundzwanzig.meetup.feature.reputation.common.LiveLayers
import space.einundzwanzig.meetup.feature.reputation.common.loadLiveLayers
import space.einundzwanzig.meetup.feature.reputation.data.ReputationDataSource

/** UI state of the "ERGEBNIS" screen (spec 04 §2.3). */
@Immutable
internal data class ReputationScanResultUiState(
    val result: QrParseResult,
    val layers: LiveLayers = LiveLayers(),
)

/**
 * Re-runs the pure parse/verify of spec 04 §7.7 on the scanned [code] (ROUTES "Arguments instead
 * of models") and, for a verified QR, loads the live layers of [QrParseResult.Verified.layersNpub]
 * (the signer on an S-8 mismatch).
 */
@HiltViewModel(assistedFactory = ReputationScanResultViewModel.Factory::class)
internal class ReputationScanResultViewModel @AssistedInject constructor(
    @Assisted code: String,
    private val source: ReputationDataSource,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(code: String): ReputationScanResultViewModel
    }

    private val _state = MutableStateFlow(ReputationScanResultUiState(ReputationQrCodec.parse(code)))
    val state: StateFlow<ReputationScanResultUiState> = _state.asStateFlow()

    init {
        val verified = _state.value.result as? QrParseResult.Verified
        val npub = verified?.layersNpub?.takeIf { it.isNotEmpty() }
        val hex = npub?.let(source::npubToHex)
        if (hex != null) {
            viewModelScope.launch {
                loadLiveLayers(source, hex) { change -> _state.update { it.copy(layers = change(it.layers)) } }
            }
        }
    }
}
