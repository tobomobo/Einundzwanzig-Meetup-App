package space.einundzwanzig.meetup.feature.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.navigation.MeetupSessionWizard
import space.einundzwanzig.meetup.core.navigation.RollingQr
import space.einundzwanzig.meetup.data.issuance.nfc.IssuanceFeatures
import space.einundzwanzig.meetup.data.issuance.session.MeetupSession
import space.einundzwanzig.meetup.data.meetups.location.GpsStatus
import space.einundzwanzig.meetup.data.meetups.location.MeetupCandidate
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import javax.inject.Inject

/**
 * Organizer panel (Dart `AdminPanelScreen`, spec 05 §2.2): session status with live countdown,
 * end/start confirmations and the start-session decision tree of §2.2.3. Dialogs are modelled as
 * [AdminPanelUiState.dialog]; the start flow suspends until the user answers each one.
 */
@HiltViewModel
class AdminPanelViewModel internal constructor(
    private val repository: AdminPanelRepository,
    private val time: TimeSource,
    private val nfcEnabled: Boolean,
    private val log: DiagnosticLog,
) : ViewModel() {

    @Inject
    constructor(repository: AdminPanelRepository, time: TimeSource) :
        this(repository, time, IssuanceFeatures.NFC_ENABLED, AppLogger)

    private val _state = MutableStateFlow(AdminPanelUiState())
    val state: StateFlow<AdminPanelUiState> = _state.asStateFlow()

    private val _events = Channel<AdminPanelEvent>(Channel.BUFFERED)
    val events: Flow<AdminPanelEvent> = _events.receiveAsFlow()

    private var tickerJob: Job? = null
    private var flowJob: Job? = null
    private var checkJob: Job? = null
    private var pending: CompletableDeferred<Any?>? = null

    init {
        viewModelScope.launch {
            val info = try {
                repository.loadAdminInfo()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(TAG, "Organisator-Info nicht ladbar", e)
                return@launch
            }
            _state.update {
                it.copy(
                    adminNpub = info.npub,
                    viaTrustScore = info.promotionSource == PROMOTION_TRUST_SCORE,
                    isSuperAdmin = info.isSuperAdmin,
                )
            }
        }
    }

    fun onAction(action: AdminPanelAction) {
        when (action) {
            AdminPanelAction.Back -> _events.trySend(AdminPanelEvent.NavigateBack)
            // The first resume happens right after composition, so this also covers the initial
            // check; a running check is replaced so two never race on the same session.
            AdminPanelAction.Resumed -> {
                checkJob?.cancel()
                checkJob = viewModelScope.launch { checkSession() }
            }
            AdminPanelAction.StartClicked -> if (!flowRunning()) showDialog(AdminDialog.ConfirmStart)
            AdminPanelAction.EndClicked -> showDialog(AdminDialog.ConfirmEnd)
            AdminPanelAction.OpenActiveMeetup -> _events.trySend(AdminPanelEvent.Navigate(RollingQr))
            AdminPanelAction.ConfirmStart -> {
                showDialog(null)
                startFlow()
            }
            AdminPanelAction.ConfirmEnd -> {
                showDialog(null)
                viewModelScope.launch { endSessionEarly() }
            }
            AdminPanelAction.DismissDialog -> dismissDialog()
            AdminPanelAction.OpenLocationSettings -> repository.openLocationSettings()
            AdminPanelAction.OpenAppSettings -> repository.openAppSettings()
            AdminPanelAction.GpsRetry -> answer(Answer.RETRY)
            AdminPanelAction.GpsContinue -> answer(Answer.CONTINUE)
            AdminPanelAction.NoReferenceContinue -> answer(true)
            is AdminPanelAction.PickerQueryChanged -> updatePicker { it.copy(query = action.query) }
            is AdminPanelAction.PickerRowClicked -> updatePicker { it.copy(confirming = action.meetup) }
            AdminPanelAction.PickerConfirmCancel -> updatePicker { it.copy(confirming = null) }
            AdminPanelAction.PickerConfirmYes -> {
                val picker = _state.value.dialog as? AdminDialog.PortalPicker
                picker?.confirming?.let { answer(PickerResult.Picked(it)) }
            }
            AdminPanelAction.PickerEnterManually -> answer(PickerResult.Manual)
            is AdminPanelAction.ManualNameChanged -> updateManualName { it.copy(text = action.text, showError = false) }
            AdminPanelAction.ManualNameSubmit -> {
                val dialog = _state.value.dialog as? AdminDialog.ManualName ?: return
                val name = dialog.text.trim()
                if (name.isEmpty()) updateManualName { it.copy(showError = true) } else answer(name)
            }
            is AdminPanelAction.NearbyPicked -> answer(action.candidate)
        }
    }

    // ---- Session state (Dart `_checkSession` + timer) ----

    private suspend fun checkSession() {
        val session = try {
            repository.loadSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Session nicht ladbar", e)
            null
        }
        applySession(session)
    }

    private suspend fun applySession(session: MeetupSession?) {
        val now = time.nowMillis()
        if (session != null && now < session.expiresAt * 1000) {
            _state.update {
                it.copy(
                    sessionChecked = true,
                    session = ActiveSessionUi(session.meetupName, session.expiresAt, timeLeft(session.expiresAt, now)),
                )
            }
            startTicker()
        } else {
            if (session != null) endSessionSafely()
            tickerJob?.cancel()
            _state.update { it.copy(sessionChecked = true, session = null) }
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val current = _state.value.session ?: break
                val now = time.nowMillis()
                if (now > current.expiresAtSeconds * 1000) {
                    tickerJob = null
                    checkSession()
                    break
                }
                _state.update { s ->
                    s.copy(session = s.session?.copy(timeLeft = timeLeft(current.expiresAtSeconds, now)))
                }
            }
        }
    }

    private suspend fun endSessionEarly() {
        endSessionSafely()
        tickerJob?.cancel()
        _state.update { it.copy(session = null) }
    }

    /**
     * `RollingQrService.endSession` must never crash the app (viewModelScope has no handler): a
     * failed wipe is logged and surfaced as the error snackbar; callers still clear their state.
     */
    private suspend fun endSessionSafely() {
        try {
            repository.endSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Session nicht beendbar", e)
            _events.send(AdminPanelEvent.Error(e.message ?: e.toString()))
        }
    }

    // ---- Dialog plumbing ----

    private fun showDialog(dialog: AdminDialog?) = _state.update { it.copy(dialog = dialog) }

    private fun flowRunning(): Boolean = flowJob?.isActive == true

    private fun answer(value: Any?) {
        val deferred = pending ?: return
        pending = null
        deferred.complete(value)
    }

    private fun dismissDialog() {
        when (val dialog = _state.value.dialog) {
            null -> Unit
            AdminDialog.ConfirmStart, AdminDialog.ConfirmEnd -> showDialog(null)
            is AdminDialog.ContinueWithoutGps, AdminDialog.CustomNeedsLocation -> answer(Answer.ABORT)
            is AdminDialog.NoReference -> answer(false)
            // Barrier dismiss of the picker means "enter manually" (Dart returns null); with the
            // nested confirmation open it only closes the confirmation.
            is AdminDialog.PortalPicker ->
                if (dialog.confirming != null) updatePicker { it.copy(confirming = null) } else answer(PickerResult.Manual)
            is AdminDialog.ManualName -> answer(null)
            is AdminDialog.PickNearby -> answer(null)
        }
    }

    private suspend fun <T> ask(dialog: AdminDialog): T {
        val deferred = CompletableDeferred<Any?>()
        pending = deferred
        showDialog(dialog)
        try {
            @Suppress("UNCHECKED_CAST")
            return deferred.await() as T
        } finally {
            if (pending === deferred) pending = null
            showDialog(null)
        }
    }

    private inline fun updatePicker(crossinline f: (AdminDialog.PortalPicker) -> AdminDialog.PortalPicker) =
        _state.update { s -> (s.dialog as? AdminDialog.PortalPicker)?.let { s.copy(dialog = f(it)) } ?: s }

    private inline fun updateManualName(crossinline f: (AdminDialog.ManualName) -> AdminDialog.ManualName) =
        _state.update { s -> (s.dialog as? AdminDialog.ManualName)?.let { s.copy(dialog = f(it)) } ?: s }

    private fun setBusy(busy: Boolean) = _state.update { it.copy(busy = busy) }

    // ---- Start flow (Dart `_startNewSession`, spec 05 §2.2.3) ----

    private fun startFlow() {
        if (flowRunning()) return
        flowJob = viewModelScope.launch {
            try {
                while (runStartFlow() == FlowEnd.RESTART) Unit
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything before the guarded session creation (e.g. the location lookup or the
                // portal directory) ends the flow with the error snackbar instead of a crash.
                setBusy(false)
                log.warn(TAG, "Meetup-Start abgebrochen", e)
                _events.send(AdminPanelEvent.Error(e.message ?: e.toString()))
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun runStartFlow(): FlowEnd {
        setBusy(true)
        val loc = try {
            repository.resolveLocation()
        } finally {
            setBusy(false)
        }
        if (loc.status != GpsStatus.OK) {
            log.warn(TAG, "Standort fuer Meetup-Erstellung NICHT ermittelbar: ${loc.status.dartName()}", null)
            when (ask<Answer>(AdminDialog.ContinueWithoutGps(loc.status))) {
                Answer.CONTINUE -> Unit
                Answer.RETRY -> return FlowEnd.RESTART
                Answer.ABORT -> return FlowEnd.DONE
            }
        } else {
            log.diag(
                TAG,
                "Standort erfasst — ${loc.candidates.size} Meetup(s) im Umkreis bekannt, " +
                    "${loc.withinCreationRadius.size} davon im Erstellungs-Radius.",
            )
        }
        val nearby = loc.withinCreationRadius
        val orgLat = loc.lat
        val orgLng = loc.lng
        val meetupName: String
        val meetupCountry: String
        if (nearby.isEmpty()) {
            val hasMeasuredGps = !(orgLat == 0.0 && orgLng == 0.0)
            val fromPortal = pickAnyPortalMeetup()
            if (fromPortal is PickerResult.Manual) {
                if (!hasMeasuredGps) {
                    log.warn(TAG, "Freitext-Meetup ohne eigenen Standort abgelehnt — kein Bezugspunkt moeglich.", null)
                    return when (ask<Answer>(AdminDialog.CustomNeedsLocation)) {
                        Answer.RETRY -> FlowEnd.RESTART
                        else -> FlowEnd.DONE
                    }
                }
                val name = ask<String?>(AdminDialog.ManualName())
                if (name.isNullOrBlank()) return FlowEnd.DONE
                meetupName = name.trim()
                meetupCountry = ""
            } else {
                val meetup = (fromPortal as PickerResult.Picked).meetup
                if (!hasMeasuredGps && meetup.lat == 0.0 && meetup.lng == 0.0) {
                    log.warn(
                        TAG,
                        "Meetup \"${meetup.city}\" hat keine Portal-Koordinaten und es gibt " +
                            "keinen eigenen Standort — Rueckfrage an den Organisator.",
                        null,
                    )
                    if (ask<Boolean?>(AdminDialog.NoReference(meetup.city)) != true) return FlowEnd.DONE
                }
                meetupName = meetup.city
                meetupCountry = meetup.country
                // Portal coordinates are deliberately NOT used as the organizer position.
                log.info(
                    TAG,
                    "Kein eigener Standort — Meetup \"${meetup.city}\" aus dem Portal " +
                        "gewaehlt. Teilnehmer leiten die Referenz daraus ab.",
                )
            }
        } else {
            val chosen = if (nearby.size == 1) {
                nearby.first()
            } else {
                ask<MeetupCandidate?>(AdminDialog.PickNearby(nearby.take(MAX_NEARBY))) ?: return FlowEnd.DONE
            }
            meetupName = chosen.meetup.city
            meetupCountry = chosen.meetup.country
        }

        setBusy(true)
        try {
            val session = repository.startSession(meetupName, meetupCountry, orgLat, orgLng)
            applySession(session)
            setBusy(false)
            _events.send(AdminPanelEvent.Navigate(if (nfcEnabled) MeetupSessionWizard else RollingQr))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setBusy(false)
            log.warn(TAG, "Meetup-Start fehlgeschlagen", e)
            _events.send(AdminPanelEvent.Error(e.message ?: e.toString()))
        }
        return FlowEnd.DONE
    }

    /**
     * Dart `_pickAnyPortalMeetup`: [PickerResult.Manual] for "enter manually", a dismissed picker
     * and an empty directory (Dart returns `null` in all three cases).
     */
    private suspend fun pickAnyPortalMeetup(): PickerResult {
        setBusy(true)
        val rows = try {
            val all = repository.portalMeetups()
            val mine = repository.myMeetupNames()
            PortalMeetupPicker.order(all, mine).also { ordered ->
                log.diag(
                    TAG,
                    "Meetup-Auswahl: ${ordered.count { it.isMine }} eigene(s) oben, " +
                        "${ordered.count { !it.isMine }} weitere alphabetisch.",
                )
            }
        } finally {
            setBusy(false)
        }
        if (rows.isEmpty()) return PickerResult.Manual
        return ask<PickerResult>(AdminDialog.PortalPicker(rows))
    }

    private enum class Answer { CONTINUE, RETRY, ABORT }

    private enum class FlowEnd { DONE, RESTART }

    private sealed interface PickerResult {
        data object Manual : PickerResult
        data class Picked(val meetup: Meetup) : PickerResult
    }

    internal companion object {
        const val TAG = "Organisator"
        const val PROMOTION_TRUST_SCORE = "trust_score"
        const val MAX_NEARBY = 5

        /** Dart: `"${d.inHours}h ${mm}m ${ss}s"` with zero-padded minutes and seconds. */
        fun timeLeft(expiresAtSeconds: Long, nowMillis: Long): String {
            val diff = (expiresAtSeconds * 1000 - nowMillis).coerceAtLeast(0)
            val hours = diff / 3_600_000
            val minutes = (diff / 60_000) % 60
            val seconds = (diff / 1_000) % 60
            return "${hours}h ${minutes.toString().padStart(2, '0')}m ${seconds.toString().padStart(2, '0')}s"
        }

        private fun GpsStatus.dartName(): String = when (this) {
            GpsStatus.OK -> "ok"
            GpsStatus.DENIED -> "denied"
            GpsStatus.SERVICE_DISABLED -> "serviceDisabled"
            GpsStatus.ERROR -> "error"
        }
    }
}
