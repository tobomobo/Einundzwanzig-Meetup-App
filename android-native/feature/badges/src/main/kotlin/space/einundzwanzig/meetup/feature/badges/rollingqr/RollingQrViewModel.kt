package space.einundzwanzig.meetup.feature.badges.rollingqr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.issuance.session.MeetupSession
import space.einundzwanzig.meetup.data.issuance.session.RollingQr
import space.einundzwanzig.meetup.data.issuance.session.RollingQrService
import space.einundzwanzig.meetup.data.issuance.session.SessionRemaining
import javax.inject.Inject

/**
 * State of the organizer's rolling QR screen (Dart `RollingQRScreen`, spec 02 §2.3).
 *
 * @property session the running session; `null` after loading = "KEINE AKTIVE SESSION".
 * @property qr the QR string of the current 10-second step (`null` while generating).
 * @property secondsLeft countdown 1…10 until the next code.
 * @property npub the organizer's npub (Dart `NostrService.getNpub()`), `""` if unknown.
 */
data class RollingQrUiState(
    val loading: Boolean = true,
    val session: MeetupSession? = null,
    val qr: String? = null,
    val secondsLeft: Int = RollingQr.INTERVAL_SECONDS,
    val remaining: SessionRemaining = SessionRemaining.Expired,
    val npub: String = "",
    val confirmEnd: Boolean = false,
    val ending: Boolean = false,
) {
    /** Session bar text without the pin: `"name, country"` or `"name"`. */
    val meetupInfo: String
        get() = session?.let { if (it.meetupCountry.isNotEmpty()) "${it.meetupName}, ${it.meetupCountry}" else it.meetupName }
            .orEmpty()
}

sealed interface RollingQrAction {
    data object Resumed : RollingQrAction
    data object Paused : RollingQrAction
    data object EndClicked : RollingQrAction
    data object EndDismissed : RollingQrAction
    data object EndConfirmed : RollingQrAction
    data object Back : RollingQrAction
}

sealed interface RollingQrEvent {
    data object Back : RollingQrEvent
}

/**
 * Shows the current session's rolling QR. The code regenerates on every 10-second step
 * boundary (aligned, a compatible enhancement over Flutter's screen-relative timer); the
 * countdown ticks every second; an expired session only stops the UI (no wipe, like Dart).
 * Timers pause in the background and restart on resume.
 */
@HiltViewModel
class RollingQrViewModel @Inject constructor(
    private val service: RollingQrService,
    private val profiles: UserProfileRepository,
    private val time: TimeSource,
) : ViewModel() {

    internal var log: DiagnosticLog = AppLogger

    private val _state = MutableStateFlow(RollingQrUiState())
    val state: StateFlow<RollingQrUiState> = _state.asStateFlow()

    private val _events = Channel<RollingQrEvent>(Channel.BUFFERED)
    val events: Flow<RollingQrEvent> = _events.receiveAsFlow()

    private var ticker: Job? = null
    private var refreshJob: Job? = null
    private var lastStep = Long.MIN_VALUE
    private var resumed = false

    init {
        viewModelScope.launch { load() }
    }

    fun onAction(action: RollingQrAction) {
        when (action) {
            RollingQrAction.Resumed -> {
                resumed = true
                restartTicker()
            }
            RollingQrAction.Paused -> {
                resumed = false
                ticker?.cancel()
            }
            RollingQrAction.EndClicked -> if (_state.value.session != null) _state.update { it.copy(confirmEnd = true) }
            RollingQrAction.EndDismissed -> _state.update { it.copy(confirmEnd = false) }
            RollingQrAction.EndConfirmed -> endSession()
            RollingQrAction.Back -> _events.trySend(RollingQrEvent.Back)
        }
    }

    private suspend fun load() {
        val npub = try {
            profiles.load().nostrNpub
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "npub nicht lesbar", e)
            ""
        }
        val session = try {
            service.loadSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(TAG, "Session laden fehlgeschlagen", e)
            null
        }
        val now = time.nowSeconds()
        val active = session?.takeIf { !it.isExpired(now) }
        _state.update {
            it.copy(
                loading = false,
                session = active,
                npub = npub.ifEmpty { active?.pubkey.orEmpty() },
                remaining = active?.remaining(now) ?: SessionRemaining.Expired,
                secondsLeft = secondsLeft(now),
            )
        }
        if (active != null && resumed) restartTicker()
    }

    private fun restartTicker() {
        ticker?.cancel()
        if (_state.value.session == null) return
        lastStep = Long.MIN_VALUE
        ticker = viewModelScope.launch {
            while (true) {
                val session = _state.value.session ?: break
                val now = time.nowSeconds()
                if (session.isExpired(now)) {
                    // Dart: stop the UI only; the service ends the session on the next load.
                    _state.update { it.copy(session = null, qr = null, confirmEnd = false) }
                    break
                }
                _state.update { it.copy(secondsLeft = secondsLeft(now), remaining = session.remaining(now)) }
                val step = RollingQr.timeStep(now)
                if (step != lastStep) {
                    lastStep = step
                    refresh(session)
                }
                delay(MILLIS_PER_SECOND - Math.floorMod(time.nowMillis(), MILLIS_PER_SECOND))
            }
        }
    }

    /**
     * Generates the code of the current step. A slow generation (re-signing via Amber/bunker,
     * signer prompt, slow secure storage) can span step boundaries whose ticks were skipped
     * while it ran; then the code is regenerated right away instead of showing a stale step
     * until the next boundary.
     */
    private fun refresh(session: MeetupSession) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            var current = session
            while (true) {
                val step = RollingQr.timeStep(time.nowSeconds())
                try {
                    val qr = service.generateQrString(current)
                    _state.update { if (it.session != null) it.copy(qr = qr) else it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Dart: errors are only logged; the last code stays (the next boundary retries).
                    log.error(TAG, "QR-Generierung fehlgeschlagen", e)
                    break
                }
                if (RollingQr.timeStep(time.nowSeconds()) == step) break
                current = _state.value.session ?: break
            }
        }
    }

    private fun endSession() {
        if (_state.value.ending) return
        _state.update { it.copy(ending = true, confirmEnd = false) }
        viewModelScope.launch {
            ticker?.cancel()
            try {
                service.endSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(TAG, "Session beenden fehlgeschlagen", e)
            }
            _state.update { it.copy(ending = false, session = null, qr = null) }
            _events.trySend(RollingQrEvent.Back)
        }
    }

    private fun secondsLeft(nowSeconds: Long): Int =
        RollingQr.INTERVAL_SECONDS - Math.floorMod(nowSeconds, RollingQr.INTERVAL_SECONDS.toLong()).toInt()

    private companion object {
        const val TAG = "RollingQR"
        const val MILLIS_PER_SECOND = 1000L
    }
}
