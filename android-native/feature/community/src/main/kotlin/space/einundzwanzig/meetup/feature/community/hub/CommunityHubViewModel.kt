package space.einundzwanzig.meetup.feature.community.hub

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
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.data.community.CommunityLinks
import space.einundzwanzig.meetup.data.proofs.satoshiduell.DuellStatus
import space.einundzwanzig.meetup.data.proofs.satoshiduell.SatoshiDuellClient
import space.einundzwanzig.meetup.feature.community.di.NpubSource
import javax.inject.Inject

/** Community hub state (spec 11 §2.5). [duell] is null while the first status request runs. */
data class CommunityHubUiState(val duell: DuellStatus? = null) {
    /** Status shown on the card: Flutter renders `snap.data ?? DuellStatus.empty`. */
    val shownDuell: DuellStatus get() = duell ?: DuellStatus.EMPTY
}

/** User actions of the hub. */
sealed interface CommunityHubAction {
    data object OpenPortal : CommunityHubAction
    data object OpenNews : CommunityHubAction
    data object OpenNostr : CommunityHubAction
    data object OpenShoutout : CommunityHubAction
    data object OpenPodcast : CommunityHubAction
    data object OpenSatoshiDuell : CommunityHubAction
    data object OpenPlebrap : CommunityHubAction
}

@HiltViewModel
class CommunityHubViewModel @Inject constructor(
    private val duellClient: SatoshiDuellClient,
    private val npubSource: NpubSource,
) : ViewModel() {

    private val _state = MutableStateFlow(CommunityHubUiState())
    val state: StateFlow<CommunityHubUiState> = _state.asStateFlow()

    private val _urls = Channel<String>(Channel.BUFFERED)

    /** External URLs to open (SatoshiDuell link, resolved with the active npub). */
    val urls: Flow<String> = _urls.receiveAsFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    /**
     * (Re)loads the SatoshiDuell status. Flutter's `FutureBuilder` calls `fetchStatus()` on every
     * rebuild; the client caches for 2 min, so calling this on every resume is cheap.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val status = runSuspendCatching { duellClient.fetchStatus() }.getOrElse { DuellStatus.EMPTY }
            _state.update { it.copy(duell = status) }
        }
    }

    /** SatoshiDuell card tap: `https://satoshiduell.de/?npub=<npub>` or the plain URL. */
    fun openSatoshiDuell() {
        viewModelScope.launch {
            val npub = runSuspendCatching { npubSource.npub() }
                .onFailure { AppLogger.warn(TAG, "npub für SatoshiDuell nicht lesbar", it) }
                .getOrNull()
            _urls.send(CommunityLinks.satoshiDuell(npub))
        }
    }

    private companion object {
        const val TAG = "CommunityHub"
    }
}
