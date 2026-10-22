package space.einundzwanzig.meetup.feature.badges.scanner

import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.data.issuance.nfc.IssuanceFeatures
import space.einundzwanzig.meetup.data.issuance.nfc.NfcPayloadError
import space.einundzwanzig.meetup.data.issuance.scan.BadgeSaveUseCase
import space.einundzwanzig.meetup.data.issuance.scan.BadgeScanProcessor
import space.einundzwanzig.meetup.data.issuance.scan.SaveOutcome
import space.einundzwanzig.meetup.data.issuance.scan.ScanOutcome
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher
import space.einundzwanzig.meetup.feature.badges.nfc.NfcAvailability
import space.einundzwanzig.meetup.feature.badges.nfc.NfcReadResult
import space.einundzwanzig.meetup.feature.badges.nfc.NfcStatusSource
import space.einundzwanzig.meetup.feature.badges.nfc.NfcTagHandle
import javax.inject.Inject

/**
 * Participant badge scanner (Dart `MeetupVerificationScreen`, spec 02 §2.1): QR (via
 * `BadgeQrCamera`) or NFC (flag), verification and claim through [BadgeScanProcessor], the
 * save flow through [BadgeSaveUseCase], reputation publish and the co-attendance opt-in.
 *
 * The scanner is opened for the global placeholder meetup, so the "opened for" presence
 * reference is always 0/0 (ROUTES.md: `MeetupVerificationScreen(meetup: "global")`).
 */
@HiltViewModel
class BadgeScannerViewModel internal constructor(
    private val processor: BadgeScanProcessor,
    private val saveUseCase: BadgeSaveUseCase,
    private val reputationPublisher: ReputationPublisher,
    private val nfc: NfcStatusSource,
    private val labels: ScannerLabels,
    private val appScope: CoroutineScope,
    nfcEnabled: Boolean,
    private val log: DiagnosticLog,
) : ViewModel() {

    @Inject
    constructor(
        processor: BadgeScanProcessor,
        saveUseCase: BadgeSaveUseCase,
        reputationPublisher: ReputationPublisher,
        nfc: NfcStatusSource,
        labels: ScannerLabels,
        @ApplicationScope appScope: CoroutineScope,
    ) : this(processor, saveUseCase, reputationPublisher, nfc, labels, appScope, IssuanceFeatures.NFC_ENABLED, AppLogger)

    private val _state = MutableStateFlow(BadgeScannerUiState(nfcEnabled = nfcEnabled))
    val state: StateFlow<BadgeScannerUiState> = _state.asStateFlow()

    private val _events = Channel<BadgeScannerEvent>(Channel.BUFFERED)
    val events: Flow<BadgeScannerEvent> = _events.receiveAsFlow()

    /** Dart `_tagProcessing`: NFC debounce, released 1500 ms after processing ends. */
    private var tagProcessing = false

    /** Localized `verifyUnknownMeetup` from the screen (the current locale); falls back to [labels]. */
    var unknownMeetupLabel: String? = null

    fun onAction(action: BadgeScannerAction) {
        when (action) {
            BadgeScannerAction.ScanQrClicked -> startQrScan()
            is BadgeScannerAction.QrScanned -> onQrScanned(action.raw)
            BadgeScannerAction.ScanNfcClicked -> startNfcRead()
            is BadgeScannerAction.NfcTagDiscovered -> onTag(action.tag)
            is BadgeScannerAction.NfcSessionFailed -> _state.update {
                it.copy(readingNfc = false, status = ScanStatus.NfcSessionError(action.detail))
            }
            BadgeScannerAction.NfcDialogScanQr -> {
                _state.update { it.copy(dialog = null) }
                startQrScan()
            }
            BadgeScannerAction.NfcOpenSettings -> {
                _state.update { it.copy(dialog = null) }
                nfc.openNfcSettings()
            }
            BadgeScannerAction.AddToWallet -> confirmSave()
            BadgeScannerAction.OpenWallet -> send(BadgeScannerEvent.OpenWallet)
            BadgeScannerAction.Close -> send(BadgeScannerEvent.Close)
            BadgeScannerAction.DismissDialog -> dismissDialog()
            is BadgeScannerAction.CoAttendanceAnswered -> answerCoAttendance(action.publish)
        }
    }

    /**
     * Shows a snackbar on the app-level [host] from the application scope so it survives the
     * switch to the wallet (Flutter's root messenger). Only call it with the app host.
     */
    fun showOnAppHost(host: SnackbarHostState, message: String, tone: Tone, durationMillis: Long) {
        appScope.launch(Dispatchers.Main.immediate) { host.showAppSnackbar(message, tone, durationMillis) }
    }

    private fun startQrScan() {
        if (_state.value.processing) return
        _state.update { it.copy(readingNfc = false) }
        send(BadgeScannerEvent.OpenCamera)
    }

    private fun label(): String = unknownMeetupLabel ?: labels.unknownMeetup()

    private fun onQrScanned(raw: String) {
        if (_state.value.processing || _state.value.result != null) return
        process { processor.processQrText(raw, label()) }
    }

    private fun startNfcRead() {
        if (!_state.value.nfcEnabled || _state.value.processing) return
        _state.update { it.copy(status = ScanStatus.CheckingNfc) }
        when (nfc.availability()) {
            NfcAvailability.NOT_SUPPORTED -> _state.update {
                it.copy(status = ScanStatus.NoNfcDevice, dialog = ScannerDialog.NfcUnavailable)
            }
            NfcAvailability.DISABLED -> _state.update {
                it.copy(status = ScanStatus.NfcDisabledHint, dialog = ScannerDialog.NfcDisabled)
            }
            NfcAvailability.ENABLED -> _state.update { it.copy(status = ScanStatus.WaitingNfc, readingNfc = true) }
        }
    }

    private fun onTag(tag: NfcTagHandle) {
        if (tagProcessing || !_state.value.readingNfc) return
        tagProcessing = true
        viewModelScope.launch {
            try {
                when (val read = tag.readFirstRecordPayload()) {
                    NfcReadResult.NoNdef -> stopNfc(ScanStatus.NfcRejected(NfcPayloadError.NoNdef))
                    NfcReadResult.Empty -> stopNfc(ScanStatus.NfcRejected(NfcPayloadError.TagEmpty))
                    is NfcReadResult.Failed -> stopNfc(ScanStatus.NfcRejected(NfcPayloadError.ReadError(read.message)))
                    is NfcReadResult.Payload -> {
                        _state.update { it.copy(readingNfc = false) }
                        processNow { processor.processNfcPayload(read.bytes, label()) }
                    }
                }
            } finally {
                delay(NFC_DEBOUNCE_MILLIS)
                tagProcessing = false
            }
        }
    }

    private fun stopNfc(status: ScanStatus) {
        _state.update { it.copy(readingNfc = false, status = status) }
        send(BadgeScannerEvent.Haptic(success = false))
    }

    private fun process(block: suspend () -> ScanOutcome?) {
        viewModelScope.launch { processNow(block) }
    }

    private suspend fun processNow(block: suspend () -> ScanOutcome?) {
        _state.update { it.copy(processing = true) }
        val outcome = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(TAG, "Badge-Scan fehlgeschlagen", e)
            ScanOutcome.Malformed(describeError(e))
        }
        _state.update { it.copy(processing = false) }
        if (outcome != null) apply(outcome)
    }

    private fun apply(outcome: ScanOutcome) {
        when (outcome) {
            ScanOutcome.ReviewDemo -> _state.update { it.copy(dialog = ScannerDialog.ReviewDemo) }
            is ScanOutcome.NonceRejected -> reject(ScanStatus.NonceExpired(outcome.validation))
            is ScanOutcome.VerificationFailed -> reject(ScanStatus.Rejected(outcome.result.message))
            is ScanOutcome.Malformed -> reject(ScanStatus.Rejected(outcome.detail))
            is ScanOutcome.NfcRejected -> reject(ScanStatus.NfcRejected(outcome.error))
            ScanOutcome.SelfScan -> reject(ScanStatus.SelfScan)
            is ScanOutcome.AlreadyCollected -> {
                _state.update { it.copy(result = ScanResultUi.AlreadyCollected(outcome.fullName, outcome.isEvent)) }
                send(BadgeScannerEvent.Haptic(success = true))
            }
            is ScanOutcome.Found -> {
                _state.update { it.copy(result = ScanResultUi.Found(outcome.pending, outcome.info)) }
                send(BadgeScannerEvent.Haptic(success = true))
            }
        }
    }

    private fun reject(status: ScanStatus) {
        _state.update { it.copy(status = status, result = null) }
        send(BadgeScannerEvent.Haptic(success = false))
    }

    /** Dart `_confirmSaveBadge` (spec 02 §2.1). */
    private fun confirmSave() {
        val found = _state.value.result as? ScanResultUi.Found
        if (found == null) {
            send(BadgeScannerEvent.Close)
            return
        }
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val outcome = try {
                saveUseCase.confirm(found.pending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(TAG, "Badge speichern fehlgeschlagen", e)
                _state.update { it.copy(saving = false) }
                snackbar(ScannerSnackbar.SAVE_FAILED)
                return@launch
            }
            _state.update { it.copy(saving = false) }
            onSaveOutcome(found, outcome)
        }
    }

    private fun onSaveOutcome(found: ScanResultUi.Found, outcome: SaveOutcome) {
        if (outcome is SaveOutcome.TooFar) {
            _state.update { it.copy(dialog = ScannerDialog.TooFar(outcome.distanceKm, outcome.allowedKm)) }
            send(BadgeScannerEvent.Haptic(success = false))
            return
        }
        if (outcome is SaveOutcome.AfterPresence && !outcome.presenceVerified) {
            snackbar(ScannerSnackbar.UNVERIFIED_PRESENCE)
        }
        when (outcome) {
            is SaveOutcome.Saved -> {
                reputationPublisher.publishInBackground(outcome.collection)
                snackbar(ScannerSnackbar.SAVED)
                if (outcome.askCoAttendance) {
                    _state.update { it.copy(dialog = ScannerDialog.CoAttendanceOptIn(outcome.badge)) }
                } else {
                    send(BadgeScannerEvent.OpenWallet)
                }
            }
            is SaveOutcome.Duplicate -> {
                _state.update { it.copy(result = null) }
                snackbar(ScannerSnackbar.DUPLICATE)
                send(BadgeScannerEvent.OpenWallet)
            }
            is SaveOutcome.KeptAside -> {
                _state.update { it.copy(result = ScanResultUi.KeptAside(found.info.fullName)) }
                snackbar(ScannerSnackbar.HELD_ASIDE)
            }
            is SaveOutcome.SaveFailed -> {
                _state.update { it.copy(result = found.copy(pending = outcome.pending)) }
                snackbar(ScannerSnackbar.SAVE_FAILED)
            }
            is SaveOutcome.TooFar -> Unit
        }
    }

    private fun dismissDialog() {
        when (_state.value.dialog) {
            // A dismissed opt-in counts as "no" (Dart: dialog result null), then the wallet opens.
            is ScannerDialog.CoAttendanceOptIn -> answerCoAttendance(false)
            else -> _state.update { it.copy(dialog = null) }
        }
    }

    private fun answerCoAttendance(publish: Boolean) {
        val dialog = _state.value.dialog as? ScannerDialog.CoAttendanceOptIn ?: return
        _state.update { it.copy(dialog = null) }
        if (!publish) {
            send(BadgeScannerEvent.OpenWallet)
            return
        }
        // The publish outlives the screen like Flutter's awaited call before the replace; the
        // wallet opens once the relays answered so the result snackbar follows the save one.
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val count = try {
                saveUseCase.publishCoAttendance(dialog.badge)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(TAG, "Co-Attendance-Veröffentlichung fehlgeschlagen", e)
                0
            }
            _state.update { it.copy(saving = false) }
            snackbar(if (count > 0) ScannerSnackbar.CO_ATTENDANCE_PUBLISHED else ScannerSnackbar.CO_ATTENDANCE_FAILED)
            send(BadgeScannerEvent.OpenWallet)
        }
    }

    private fun snackbar(kind: ScannerSnackbar) = send(BadgeScannerEvent.Snackbar(kind))

    private fun send(event: BadgeScannerEvent) {
        _events.trySend(event)
    }

    private companion object {
        const val TAG = "BadgeScanner"
        const val NFC_DEBOUNCE_MILLIS = 1500L
    }
}
