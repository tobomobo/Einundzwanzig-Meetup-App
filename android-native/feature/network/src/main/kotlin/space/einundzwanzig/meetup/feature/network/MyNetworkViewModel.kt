package space.einundzwanzig.meetup.feature.network

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkBuildEvent
import space.einundzwanzig.meetup.feature.network.data.NetworkDataSource
import java.time.ZoneId
import javax.inject.Inject

/** What "Meine Vernetzung" shows (Dart `_MyNetworkScreenState` fields, spec 05 §2.1). */
@Immutable
internal data class MyNetworkUiState(
    val loading: Boolean = true,
    val network: MyNetwork? = null,
    val failedCount: Int = 0,
    val retrying: Boolean = false,
    val updating: Boolean = false,
    val doneDepth: Int = 0,
    val updateFailed: Boolean = false,
    /** Pull-to-refresh indicator (a forced full re-sync is running). */
    val refreshing: Boolean = false,
    /** `mnStandAt` time text of [MyNetwork.updatedAt], `null` without one. */
    val standTime: String? = null,
    val names: Map<String, String> = emptyMap(),
    val filter: Int = 0,
    val query: String = "",
    val expanded: Set<Int> = emptySet(),
    /** npub whose detail sheet is open (its path is highlighted in the graph). */
    val highlight: String? = null,
    /** Network as it was when the detail sheet opened (Dart `_openDetail(net)`), so it stays stable. */
    val detailNetwork: MyNetwork? = null,
    val infoSheetOpen: Boolean = false,
) {
    /** Dart body selection. */
    val body: Body
        get() = when {
            loading || ((network == null || network.isEmpty) && updating) -> Body.FirstLoad
            network == null || network.isEmpty -> Body.Empty
            else -> Body.Content
        }

    enum class Body { FirstLoad, Empty, Content }
}

internal sealed interface MyNetworkAction {
    data object Refresh : MyNetworkAction
    data object Retry : MyNetworkAction
    data class SetFilter(val filter: Int) : MyNetworkAction
    data class SetQuery(val query: String) : MyNetworkAction
    data class Expand(val degree: Int) : MyNetworkAction
    data class OpenDetail(val npub: String) : MyNetworkAction
    data object CloseDetail : MyNetworkAction
    data object OpenInfo : MyNetworkAction
    data object CloseInfo : MyNetworkAction
    data object CheckPerson : MyNetworkAction
    data class OpenInNostr(val npub: String) : MyNetworkAction
}

/** One-shot effects for the screen. */
internal sealed interface MyNetworkEvent {
    /** `mnRetryResult(fixed, total)`; success colour if [fixed] > 0, else error. */
    data class RetryResult(val fixed: Int, val total: Int) : MyNetworkEvent
}

@HiltViewModel
internal class MyNetworkViewModel @Inject constructor(
    private val source: NetworkDataSource,
    private val time: TimeSource,
) : ViewModel() {

    private val _state = MutableStateFlow(MyNetworkUiState())
    val state: StateFlow<MyNetworkUiState> = _state.asStateFlow()

    private val _events = Channel<MyNetworkEvent>(Channel.BUFFERED)
    val events: Flow<MyNetworkEvent> = _events.receiveAsFlow()

    /** Dart `_namesAsked`: each person is asked for once per screen session. */
    private val namesAsked = HashSet<String>()
    private var loadJob: Job? = null

    /** Generation of the latest [load]; only that load may clear [MyNetworkUiState.refreshing]. */
    private var loadGen = 0

    /** Zone for the `mnStandAt` time; overridable in tests. */
    internal var zone: ZoneId = ZoneId.systemDefault()

    init {
        load(forceFull = false)
    }

    fun onAction(action: MyNetworkAction) {
        when (action) {
            MyNetworkAction.Refresh -> load(forceFull = true)
            MyNetworkAction.Retry -> retry()
            is MyNetworkAction.SetFilter -> _state.update { it.copy(filter = action.filter) }
            is MyNetworkAction.SetQuery -> _state.update { it.copy(query = action.query) }
            is MyNetworkAction.Expand -> _state.update { it.copy(expanded = it.expanded + action.degree) }
            is MyNetworkAction.OpenDetail ->
                _state.update { it.copy(highlight = action.npub, detailNetwork = it.network) }
            MyNetworkAction.CloseDetail -> _state.update { it.copy(highlight = null, detailNetwork = null) }
            MyNetworkAction.OpenInfo -> _state.update { it.copy(infoSheetOpen = true) }
            MyNetworkAction.CloseInfo -> _state.update { it.copy(infoSheetOpen = false) }
            // Navigation / intents are handled by the route.
            MyNetworkAction.CheckPerson, is MyNetworkAction.OpenInNostr -> Unit
        }
    }

    /** Dart `_load({forceFull})`. A new load replaces the collector of a running one. */
    fun load(forceFull: Boolean) {
        loadJob?.cancel()
        val gen = ++loadGen
        if (forceFull) _state.update { it.copy(refreshing = true) }
        loadJob = viewModelScope.launch {
            try {
                runLoad(forceFull)
            } finally {
                if (forceFull && gen == loadGen) _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private suspend fun runLoad(forceFull: Boolean) {
        val myNpub = try {
            source.myNpub()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Profil nicht lesbar", e)
            ""
        }
        if (myNpub.isEmpty()) {
            _state.update { it.copy(loading = false, network = null, updating = false) }
            return
        }
        if (_state.value.network == null) {
            val cached = try {
                source.cachedNetwork(myNpub)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Netzwerk-Cache nicht lesbar", e)
                null
            }
            if (cached != null) {
                showNetwork(cached)
                _state.update { it.copy(loading = false) }
                launchLoadNames(cached)
            }
        }
        _state.update { it.copy(updating = true, doneDepth = 0, updateFailed = false) }
        try {
            kotlinx.coroutines.coroutineScope {
                val failed = async {
                    try {
                        source.failedCount()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.warn(TAG, "Fehlgeschlagene Teilnahmen nicht lesbar", e)
                        0
                    }
                }
                var final: MyNetwork? = null
                source.buildNetwork(myNpub, forceFull).collect { ev ->
                    when (ev) {
                        is NetworkBuildEvent.Stage -> {
                            showNetwork(ev.network)
                            _state.update { it.copy(doneDepth = ev.depth, loading = false) }
                        }
                        is NetworkBuildEvent.Done -> final = ev.network
                    }
                }
                val net = final ?: error("Netzwerkaufbau ohne Ergebnis")
                val failedCount = failed.await()
                val shown = _state.value.network
                if (!(net.stale && net.isEmpty && shown != null)) showNetwork(net)
                _state.update {
                    it.copy(failedCount = failedCount, updating = false, updateFailed = net.stale, loading = false)
                }
                _state.value.network?.let { launchLoadNames(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Netzwerk konnte nicht geladen werden", e)
            _state.update {
                it.copy(loading = false, updating = false, updateFailed = it.network != null)
            }
        }
    }

    private fun showNetwork(net: MyNetwork) {
        val stand = net.updatedAt?.let { NetworkPresentation.standTime(it, time.nowMillis(), zone) }
        _state.update { it.copy(network = net, standTime = stand) }
    }

    /**
     * Dart calls `_loadNames` without awaiting it: the lookup runs outside the load job, so the
     * staged build (and the pull-to-refresh indicator) never waits for the relay round-trip, and a
     * cancelled load cannot leave npubs marked as asked without a fetch.
     */
    private fun launchLoadNames(net: MyNetwork) {
        viewModelScope.launch { loadNames(net) }
    }

    /** Dart `_loadNames`: cached names first, then one batch fetch. */
    private suspend fun loadNames(net: MyNetwork) {
        val hexOf = NetworkPresentation.namesToAsk(net, namesAsked, source::npubToHex)
        if (hexOf.isEmpty()) return
        namesAsked.addAll(hexOf.keys)
        fun apply(byHex: Map<String, String>) {
            val found = LinkedHashMap<String, String>()
            for ((npub, hex) in hexOf) {
                val n = byHex[hex]
                if (!n.isNullOrEmpty()) found[npub] = n
            }
            if (found.isNotEmpty()) _state.update { it.copy(names = it.names + found) }
        }
        try {
            apply(source.cachedDisplayNames())
            apply(source.fetchDisplayNames(hexOf.values))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Anzeigenamen nicht ladbar", e)
        }
    }

    /** Dart `_retry`. */
    private fun retry() {
        if (_state.value.retrying) return
        _state.update { it.copy(retrying = true) }
        viewModelScope.launch {
            val fixed = try {
                source.retryFailed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Erneutes Senden fehlgeschlagen", e)
                0
            }
            val total = _state.value.failedCount
            _state.update { it.copy(retrying = false) }
            _events.send(MyNetworkEvent.RetryResult(fixed, total))
            load(forceFull = false)
        }
    }

    private companion object {
        const val TAG = "MyNetwork"
    }
}
