package space.einundzwanzig.meetup.feature.badges.writer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.issuance.nfc.NfcBadgePayload
import space.einundzwanzig.meetup.data.issuance.nfc.NfcWriteError
import space.einundzwanzig.meetup.data.issuance.session.RollingQrService
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.feature.badges.nfc.NfcAvailability
import space.einundzwanzig.meetup.feature.badges.nfc.NfcStatusSource
import space.einundzwanzig.meetup.feature.badges.nfc.NfcTagHandle
import space.einundzwanzig.meetup.feature.badges.nfc.NfcWriteResult
import javax.inject.Inject

/** The writer's home-meetup box (display only; the tag gets the session payload). */
sealed interface HomeMeetupInfo {
    /** Still loading. */
    data object Loading : HomeMeetupInfo

    /** No home meetup set: `writerSelectHomeFirst`. */
    data object NoHome : HomeMeetupInfo

    /** Set but not in the directory: `"Meetup: $id"`. */
    data class NotFound(val id: String) : HomeMeetupInfo

    /** `"📍 $city, $country"`. */
    data class Found(val city: String, val country: String) : HomeMeetupInfo
}

/** Status line of the writer (spec 02 §2.4). */
sealed interface WriterStatus {
    data object Ready : WriterStatus
    data object NoHomeMeetup : WriterStatus
    data object HomeNotFound : WriterStatus
    data object LoadingSession : WriterStatus
    data object NoActiveSession : WriterStatus
    data object NoNfcDevice : WriterStatus
    data object NfcDisabledHint : WriterStatus
    data object HoldTag : WriterStatus
    data object Formatting : WriterStatus
    data object FormatFailed : WriterStatus
    data object NoNdef : WriterStatus
    data object ReadOnly : WriterStatus
    data class TooSmall(val actual: Int, val max: Int) : WriterStatus
    data class WriteFailed(val error: NfcWriteError, val raw: String) : WriterStatus
    data class StartError(val detail: String) : WriterStatus

    /** Success text: `writerTagWritten + writerCompactSize(size) + writerValidHours(4) + writerJumpToQr`. */
    data class Written(val size: Int) : WriterStatus
}

enum class WriterDialog { NFC_UNAVAILABLE, NFC_DISABLED }

data class NfcTagWriterUiState(
    val home: HomeMeetupInfo = HomeMeetupInfo.Loading,
    val estimatedSize: Int = 0,
    val status: WriterStatus = WriterStatus.Ready,
    val waitingForTag: Boolean = false,
    val success: Boolean = false,
    val dialog: WriterDialog? = null,
)

sealed interface NfcTagWriterAction {
    data object CreateTag : NfcTagWriterAction
    data class TagDiscovered(val tag: NfcTagHandle) : NfcTagWriterAction
    data class SessionFailed(val detail: String) : NfcTagWriterAction
    data object DismissDialog : NfcTagWriterAction
    data object OpenNfcSettings : NfcTagWriterAction
    data object Back : NfcTagWriterAction
}

sealed interface NfcTagWriterEvent {
    /** Red snackbar `nwSelectHomeMeetup`. */
    data object SelectHomeMeetup : NfcTagWriterEvent

    /** 3 s after success: `navigateFromRoot(RollingQr)`. */
    data object OpenRollingQr : NfcTagWriterEvent
    data object Back : NfcTagWriterEvent

    /** Open the system NFC settings from the activity (same task). */
    data object OpenNfcSettings : NfcTagWriterEvent
}

/**
 * NFC tag writer (Dart `NFCWriterScreen`, spec 02 §2.4, flag off): writes the running
 * session's signed base payload as one NDEF Text record, with the size check and formatting
 * of blank tags; on success it opens the rolling QR after 3 s.
 */
@HiltViewModel
class NfcTagWriterViewModel @Inject constructor(
    private val sessions: RollingQrService,
    private val profiles: UserProfileRepository,
    private val meetups: MeetupService,
    private val nfc: NfcStatusSource,
) : ViewModel() {

    internal var log: DiagnosticLog = AppLogger

    private val _state = MutableStateFlow(NfcTagWriterUiState())
    val state: StateFlow<NfcTagWriterUiState> = _state.asStateFlow()

    private val _events = Channel<NfcTagWriterEvent>(Channel.BUFFERED)
    val events: Flow<NfcTagWriterEvent> = _events.receiveAsFlow()

    private var tagJson: String? = null
    private var processingTag = false

    init {
        viewModelScope.launch { loadHomeMeetup() }
    }

    fun onAction(action: NfcTagWriterAction) {
        when (action) {
            NfcTagWriterAction.CreateTag -> viewModelScope.launch { startWrite() }
            is NfcTagWriterAction.TagDiscovered -> onTag(action.tag)
            is NfcTagWriterAction.SessionFailed -> _state.update {
                it.copy(waitingForTag = false, status = WriterStatus.StartError(action.detail))
            }
            NfcTagWriterAction.DismissDialog -> _state.update { it.copy(dialog = null) }
            NfcTagWriterAction.OpenNfcSettings -> {
                _state.update { it.copy(dialog = null) }
                _events.trySend(NfcTagWriterEvent.OpenNfcSettings)
            }
            NfcTagWriterAction.Back -> _events.trySend(NfcTagWriterEvent.Back)
        }
    }

    private suspend fun loadHomeMeetup() {
        val homeId = try {
            profiles.load().homeMeetupId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Profil nicht lesbar", e)
            ""
        }
        if (homeId.isEmpty()) {
            _state.update { it.copy(home = HomeMeetupInfo.NoHome, status = WriterStatus.NoHomeMeetup) }
            return
        }
        val list = try {
            meetups.fetchMeetups()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Meetups nicht ladbar", e)
            emptyList()
        }.ifEmpty { meetups.fallbackMeetups }
        val meetup = meetups.resolveFavorite(homeId) ?: findIn(list, homeId)
        if (meetup == null) {
            _state.update { it.copy(home = HomeMeetupInfo.NotFound(homeId), status = WriterStatus.HomeNotFound) }
        } else {
            _state.update {
                it.copy(
                    home = HomeMeetupInfo.Found(meetup.city, meetup.country),
                    estimatedSize = NfcBadgePayload.estimatedSize(meetup.city, meetup.country),
                )
            }
        }
    }

    private fun findIn(list: List<Meetup>, stored: String): Meetup? =
        list.firstOrNull { it.id == stored } ?: list.firstOrNull { DartString.lower(it.city) == DartString.lower(stored) }

    private suspend fun startWrite() {
        val current = _state.value
        if (current.waitingForTag || current.success) return
        // Flutter: `_homeMeetup == null` blocks, which includes a home id missing from the directory.
        if (current.home !is HomeMeetupInfo.Found) {
            _events.trySend(NfcTagWriterEvent.SelectHomeMeetup)
            return
        }
        _state.update { it.copy(status = WriterStatus.LoadingSession) }
        val payload = try {
            sessions.getBasePayload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(TAG, "Base-Payload nicht ladbar", e)
            null
        }
        if (payload == null) {
            _state.update { it.copy(status = WriterStatus.NoActiveSession) }
            return
        }
        when (nfc.availability()) {
            NfcAvailability.NOT_SUPPORTED -> _state.update {
                it.copy(status = WriterStatus.NoNfcDevice, dialog = WriterDialog.NFC_UNAVAILABLE)
            }
            NfcAvailability.DISABLED -> _state.update {
                it.copy(status = WriterStatus.NfcDisabledHint, dialog = WriterDialog.NFC_DISABLED)
            }
            NfcAvailability.ENABLED -> {
                tagJson = NfcBadgePayload.tagJson(payload)
                _state.update { it.copy(status = WriterStatus.HoldTag, waitingForTag = true) }
            }
        }
    }

    private fun onTag(tag: NfcTagHandle) {
        val json = tagJson
        if (processingTag || json == null || !_state.value.waitingForTag) return
        processingTag = true
        viewModelScope.launch {
            val result = try {
                tag.writeTextRecord(
                    textPayload = NfcBadgePayload.encodeTextPayload(json),
                    actualSize = NfcBadgePayload.actualSize(json),
                    onFormatting = { _state.update { it.copy(status = WriterStatus.Formatting) } },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NfcWriteResult.Failed(describeError(e))
            }
            val status = when (result) {
                NfcWriteResult.Written, NfcWriteResult.Formatted -> WriterStatus.Written(json.length)
                NfcWriteResult.FormatFailed -> WriterStatus.FormatFailed
                NfcWriteResult.NoNdef -> WriterStatus.NoNdef
                NfcWriteResult.ReadOnly -> WriterStatus.ReadOnly
                is NfcWriteResult.TooSmall -> WriterStatus.TooSmall(result.actual, result.max)
                is NfcWriteResult.Failed -> {
                    log.debug(TAG, "[ERROR] Write Error: ${result.message}")
                    WriterStatus.WriteFailed(NfcBadgePayload.classifyWriteError(result.message), result.message)
                }
            }
            val success = status is WriterStatus.Written
            _state.update { it.copy(status = status, waitingForTag = false, success = success) }
            processingTag = false
            if (success) {
                delay(JUMP_DELAY_MILLIS)
                _events.trySend(NfcTagWriterEvent.OpenRollingQr)
            }
        }
    }

    private companion object {
        const val TAG = "NfcWriter"
        const val JUMP_DELAY_MILLIS = 3000L
    }
}
