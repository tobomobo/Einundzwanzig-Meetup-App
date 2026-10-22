package space.einundzwanzig.meetup.feature.reputation.qr

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.reputation.publish.PublishResult
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQr
import space.einundzwanzig.meetup.feature.reputation.data.ReputationDataSource
import javax.inject.Inject

/** Everything the content view of the REPUTATION screen shows (spec 04 §2.1). */
@Immutable
internal data class ReputationQrContent(
    val nickname: String,
    val score: Double,
    /** Local level wire string (`trustScore.level`). */
    val level: String,
    val qrData: String,
    /** Real signature state (R-8 FIX), drives the card label. */
    val isSigned: Boolean,
    /** `countVerifiedBadges(all)`: card badge count and proof block. */
    val verifiedBadgeCount: Int,
    /** `trustScore.totalBadges`: stats tile "Badges". */
    val totalBadges: Int,
    val uniqueMeetups: Int,
    val uniqueSigners: Int,
    val boundBadgeCount: Int,
    /** `isNostrSigned && !isOrganizer` count, the proof block's total. */
    val countedBadgeCount: Int,
    val organizerMeetupCount: Int,
    val proofStatus: ReputationQr.ProofStatus,
)

/** UI state of the REPUTATION screen. `content == null` after loading means the empty view. */
@Immutable
internal data class ReputationQrUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val badgesUnreadable: Boolean = false,
    val hasIdentity: Boolean = false,
    val platformProofCount: Int = 0,
    val content: ReputationQrContent? = null,
    val isPublishing: Boolean = false,
    /** Result of the last publish on this screen (shown under the button). */
    val lastPublish: PublishResult? = null,
) {
    val isEmpty: Boolean get() = !isLoading && !loadFailed && content == null

    /** The AppBar share action is disabled while the badges are unreadable (spec 04 §2.1). */
    val canOpenCard: Boolean get() = !badgesUnreadable
}

internal sealed interface ReputationQrAction {
    data object OpenCard : ReputationQrAction
    data object OpenScanner : ReputationQrAction
    data object ShareImage : ReputationQrAction
    data object Publish : ReputationQrAction
    data object Retry : ReputationQrAction
}

/** One-shot effects of the REPUTATION screen. */
internal sealed interface ReputationQrEvent {
    /** Snackbar with the publish result, green if successful, 3 s (spec 04 §2.1.2). */
    data class Published(val result: PublishResult) : ReputationQrEvent

    /** The signer failed; the QR fell back to the unsigned `21:` code (spec 04 §2.1 step 4 FIX). */
    data object SignFailed : ReputationQrEvent
}

@HiltViewModel
internal class ReputationQrViewModel @Inject constructor(
    private val source: ReputationDataSource,
) : ViewModel() {

    private val _state = MutableStateFlow(ReputationQrUiState())
    val state: StateFlow<ReputationQrUiState> = _state.asStateFlow()

    private val _events = Channel<ReputationQrEvent>(Channel.BUFFERED)
    val events: Flow<ReputationQrEvent> = _events.receiveAsFlow()

    init {
        load()
    }

    fun onAction(action: ReputationQrAction) {
        when (action) {
            ReputationQrAction.Publish -> publish()
            ReputationQrAction.Retry -> load()
            // Navigation and sharing are handled by the route.
            ReputationQrAction.OpenCard, ReputationQrAction.OpenScanner, ReputationQrAction.ShareImage -> Unit
        }
    }

    /** Runs once per screen (no refresh when coming back, spec 04 §2.1 "Load"). */
    private fun load() {
        _state.update { it.copy(isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            val qr = try {
                source.generateQr()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Reputation-QR konnte nicht erstellt werden", e)
                _state.update { it.copy(isLoading = false, loadFailed = true) }
                return@launch
            }
            val unreadable = source.badgesUnreadable()
            _state.update {
                it.copy(
                    isLoading = false,
                    loadFailed = false,
                    badgesUnreadable = unreadable,
                    hasIdentity = qr.hasIdentity,
                    platformProofCount = qr.platformProofCount,
                    content = (qr as? ReputationQr.Content)?.toContent(),
                )
            }
            if (qr is ReputationQr.Content && qr.signError != null) {
                AppLogger.warn(TAG, "QR-Signatur fehlgeschlagen, unsignierter Code", qr.signError)
                _events.send(ReputationQrEvent.SignFailed)
            }
        }
    }

    private fun publish() {
        if (_state.value.isPublishing) return
        _state.update { it.copy(isPublishing = true, lastPublish = null) }
        viewModelScope.launch {
            val result = try {
                source.publishForced()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PublishResult.Error(e)
            }
            _state.update { it.copy(isPublishing = false, lastPublish = result) }
            _events.send(ReputationQrEvent.Published(result))
        }
    }

    private fun ReputationQr.Content.toContent() = ReputationQrContent(
        nickname = user.nickname.ifEmpty { UserProfile.DEFAULT_NICKNAME },
        score = score.totalScore,
        level = score.level,
        qrData = qrData,
        isSigned = isSigned,
        verifiedBadgeCount = verifiedBadgeCount,
        totalBadges = score.totalBadges,
        uniqueMeetups = score.uniqueMeetups,
        uniqueSigners = score.uniqueSigners,
        boundBadgeCount = boundBadgeCount,
        countedBadgeCount = countedBadgeCount,
        organizerMeetupCount = organizerMeetupCount,
        proofStatus = proofStatus,
    )

    private companion object {
        const val TAG = "ReputationQr"
    }
}
