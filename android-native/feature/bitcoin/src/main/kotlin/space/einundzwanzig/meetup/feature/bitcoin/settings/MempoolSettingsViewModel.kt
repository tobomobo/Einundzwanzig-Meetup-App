package space.einundzwanzig.meetup.feature.bitcoin.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.mempool.HostTestFailure
import space.einundzwanzig.meetup.data.mempool.MempoolConfig
import space.einundzwanzig.meetup.data.mempool.MempoolHosts
import space.einundzwanzig.meetup.data.mempool.MempoolMode
import space.einundzwanzig.meetup.data.mempool.MempoolService
import javax.inject.Inject

/** Result hint below the "Verbindung testen" button (spec 13 §2.3). */
sealed interface MempoolTestResult {
    /** Green: `"${mempoolTestOk} · Block $height"` (height not grouped). */
    data class Ok(val height: Int) : MempoolTestResult

    /** Red: a classified connection failure. */
    data class Failed(val failure: HostTestFailure) : MempoolTestResult

    /** Red: `mempoolInvalidUrl` after saving an invalid custom URL. */
    data object InvalidUrl : MempoolTestResult
}

/**
 * State of the Mempool settings.
 *
 * @property mode selected option card (UI selection; persisted only when valid).
 * @property customText custom URL field; [customRevision] changes when the ViewModel rewrites it.
 * @property activeHost "Aktive Quelle" footer (effective host).
 */
@Immutable
data class MempoolSettingsUiState(
    val mode: MempoolMode = MempoolMode.CLEARNET,
    val customText: String = "",
    val customRevision: Int = 0,
    val testing: Boolean = false,
    val testResult: MempoolTestResult? = null,
    val activeHost: String = MempoolHosts.CLEARNET,
)

/** User actions of the Mempool settings. */
sealed interface MempoolSettingsAction {
    /** Option card tapped. */
    data class Select(val mode: MempoolMode) : MempoolSettingsAction

    /** Custom URL edited. */
    data class CustomChanged(val text: String) : MempoolSettingsAction

    /** "Speichern" button or IME action. */
    data object SaveCustom : MempoolSettingsAction

    /** "Verbindung testen". */
    data object Test : MempoolSettingsAction

    /** Back arrow. */
    data object Back : MempoolSettingsAction
}

/** One-shot events of the Mempool settings. */
sealed interface MempoolSettingsEvent {
    /** Custom URL saved: show the `mempoolSaved` snackbar. */
    data object Saved : MempoolSettingsEvent
}

/**
 * Mempool server settings (Flutter `_MempoolSettingsScreenState`, spec 13 §2.3).
 * Uses the persisted host for the custom field (the Flutter screen could show a session
 * fallback mirror there, spec 13 §14 item 7).
 */
@HiltViewModel
class MempoolSettingsViewModel @Inject constructor(
    private val config: MempoolConfig,
    private val mempool: MempoolService,
    @ApplicationScope private val appScope: CoroutineScope,
    private val log: DiagnosticLog,
) : ViewModel() {

    // Seeded synchronously (the config is normally loaded at app start), so the persisted card is
    // selected from the first frame.
    private val local = MutableStateFlow(
        MempoolSettingsUiState(
            mode = config.mode,
            customText = if (config.mode == MempoolMode.CUSTOM) config.persistedHost else "",
            activeHost = config.host,
        ),
    )

    /** Set by the first user edit; the late init refresh must not overwrite it. */
    private var userTouched = false
    private val events = Channel<MempoolSettingsEvent>(Channel.BUFFERED)
    private var testJob: Job? = null

    /** UI state; the footer follows the effective host. */
    val uiState: StateFlow<MempoolSettingsUiState> = combine(local, config.hostFlow) { s, host ->
        s.copy(activeHost = host)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    /** One-shot UI events (snackbar). */
    val uiEvents: Flow<MempoolSettingsEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            config.ensureLoaded()
            if (userTouched) return@launch
            val mode = config.mode
            local.update { s ->
                val text = if (mode == MempoolMode.CUSTOM) config.persistedHost else s.customText
                val revision = if (text != s.customText) s.customRevision + 1 else s.customRevision
                s.copy(mode = mode, customText = text, customRevision = revision)
            }
        }
    }

    /** Host the connection test uses (not necessarily saved). */
    internal fun selectedHost(s: MempoolSettingsUiState = local.value): String = when (s.mode) {
        MempoolMode.CLEARNET -> MempoolHosts.CLEARNET
        MempoolMode.TOR -> MempoolHosts.TOR
        MempoolMode.CUSTOM -> MempoolHosts.normalize(s.customText)
    }

    /** Handles [action]; navigation is handled by the route. */
    fun onAction(action: MempoolSettingsAction) {
        when (action) {
            is MempoolSettingsAction.Select -> select(action.mode)
            is MempoolSettingsAction.CustomChanged -> {
                userTouched = true
                local.update { it.copy(customText = action.text) }
            }
            MempoolSettingsAction.SaveCustom -> saveCustom()
            MempoolSettingsAction.Test -> test()
            MempoolSettingsAction.Back -> Unit
        }
    }

    private fun select(mode: MempoolMode) {
        userTouched = true
        // A running test belongs to the previous host: drop it so its result cannot appear under
        // the newly selected card.
        testJob?.cancel()
        testJob = null
        local.update { it.copy(mode = mode, testing = false, testResult = null) }
        val custom = local.value.customText
        if (mode == MempoolMode.CUSTOM && !MempoolHosts.looksValid(custom)) return
        appScope.launch(start = CoroutineStart.UNDISPATCHED) { persist(mode, custom) }
    }

    /**
     * Persists [mode] and forgets the dashboard of the old source. Runs in the application scope so
     * popping the screen right after a tap cannot cancel it half-way. Callers start it
     * [CoroutineStart.UNDISPATCHED], so each write reaches the config's FIFO write lock
     * synchronously in tap order (the last tapped card is the one that is saved).
     *
     * @return `true` when the write went through.
     */
    private suspend fun persist(mode: MempoolMode, custom: String): Boolean = try {
        config.setMode(mode, custom)
        mempool.clearLastDashboard()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG, "Mempool-Quelle nicht speicherbar", e)
        false
    }

    private fun saveCustom() {
        userTouched = true
        val custom = local.value.customText
        if (!MempoolHosts.looksValid(custom)) {
            local.update { it.copy(testResult = MempoolTestResult.InvalidUrl) }
            return
        }
        val write = appScope.async(start = CoroutineStart.UNDISPATCHED) { persist(MempoolMode.CUSTOM, custom) }
        viewModelScope.launch {
            if (!write.await()) return@launch
            local.update {
                it.copy(customText = config.persistedHost, customRevision = it.customRevision + 1, testResult = null)
            }
            events.send(MempoolSettingsEvent.Saved)
        }
    }

    private fun test() {
        if (testJob?.isActive == true) return
        val host = selectedHost()
        local.update { it.copy(testing = true, testResult = null) }
        testJob = viewModelScope.launch {
            val result = try {
                MempoolTestResult.Ok(mempool.testHost(host))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MempoolTestResult.Failed(HostTestFailure.of(e, host))
            }
            local.update { it.copy(testing = false, testResult = result) }
        }
    }
}

private const val TAG = "Mempool"
