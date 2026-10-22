package space.einundzwanzig.meetup.feature.onboarding.bunker

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.data.identity.identityErrorMessage
import space.einundzwanzig.meetup.data.identity.Nip46ConnectResult
import space.einundzwanzig.meetup.data.identity.Nip46Pairing
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.feature.onboarding.R
import space.einundzwanzig.meetup.feature.onboarding.common.UiText

/** Steps of the BunkerConnectSheet (spec 01 §2.5). */
enum class BunkerStep { CHOOSE, SIGNER_APP, PASTE }

/** User actions inside the BunkerConnectSheet. */
sealed interface BunkerAction {
    data object StartSignerApp : BunkerAction
    data object ShowPaste : BunkerAction
    data object BackToChoose : BunkerAction
    data class PasteChanged(val value: String) : BunkerAction
    data object ConnectPasted : BunkerAction
    data object Cancel : BunkerAction

    /** The pairing URI was copied (plain clipboard) → snackbar `bunkerCopied`. */
    data object UriCopied : BunkerAction

    /** `ACTION_VIEW` of the pairing URI found no app → snackbar `bunkerNoSignerApp`. */
    data object NoSignerApp : BunkerAction

    /** A QR code was scanned in the paste step (native addition): fills the field if it is a `bunker://` URI. */
    data class Scanned(val raw: String) : BunkerAction
}

/** State of the BunkerConnectSheet. */
@Immutable
data class BunkerSheetState(
    val step: BunkerStep = BunkerStep.CHOOSE,
    val busy: Boolean = false,
    val error: UiText? = null,
    /** `nostrconnect://` URI of the running pairing (QR / copy / open). */
    val pairingUri: String? = null,
    /** `auth_url` of the signer, shown inline as a button while the sheet is open. */
    val authUrl: String? = null,
    val pasteInput: String = "",
)

/**
 * Logic of the BunkerConnectSheet (Dart `_BunkerConnectSheetState`), owned by the screen's
 * ViewModel. [open] starts a session, which ends with [onFinished] (success or `null`).
 *
 * While open, `auth_url`s of the signer are intercepted and shown inline; closing restores the
 * previous handler (never `null`, spec 01 §2.5 lifecycle). An unfinished pairing is cancelled on
 * close; a successful one became the live session and is left alone.
 */
class BunkerConnectController(
    private val signing: SigningService,
    private val scope: CoroutineScope,
    private val onFinished: (Nip46ConnectResult.Success?) -> Unit,
) {
    private val _state = MutableStateFlow(BunkerSheetState())
    val state: StateFlow<BunkerSheetState> = _state.asStateFlow()

    private var interceptor: AutoCloseable? = null
    private var pairing: Nip46Pairing? = null
    private var job: Job? = null
    private var isOpen = false

    fun open() {
        if (isOpen) return
        isOpen = true
        _state.value = BunkerSheetState()
        // Runs on the relay transport thread: only touch the thread-safe StateFlow.
        interceptor = signing.interceptAuthUrls { url -> _state.update { it.copy(authUrl = url) } }
    }

    /** Option "Signer-App" (QR / nostrconnect://). */
    fun startSignerApp() {
        if (!isOpen || _state.value.busy) return
        _state.update { it.copy(busy = true, error = null, authUrl = null, step = BunkerStep.SIGNER_APP) }
        job = scope.launch {
            try {
                val p = signing.startNip46Pairing()
                if (!isOpen) {
                    p.cancel()
                    return@launch
                }
                pairing = p
                _state.update { it.copy(pairingUri = p.uri) }
                handle(signing.completeNip46Pairing(p))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Kopplung fehlgeschlagen", e)
                _state.update { it.copy(busy = false, error = UiText.res(e.identityErrorMessage)) }
            }
        }
    }

    fun showPaste() {
        if (_state.value.busy) return
        _state.update { it.copy(step = BunkerStep.PASTE, error = null) }
    }

    fun backToChoose() {
        if (_state.value.busy) return
        _state.update { it.copy(step = BunkerStep.CHOOSE, error = null) }
    }

    fun onPasteChanged(value: String) = _state.update { it.copy(pasteInput = value) }

    /** Scanned QR text: a `bunker://` URI fills the paste field, anything else shows an error. */
    fun onScanned(raw: String) {
        if (_state.value.busy) return
        val text = raw.trim()
        if (isBunkerUri(text)) {
            _state.update { it.copy(pasteInput = text, error = null, step = BunkerStep.PASTE) }
        } else {
            _state.update { it.copy(error = UiText.res(R.string.native_onboarding_bunker_scan_invalid)) }
        }
    }

    /** "Verbinden" with a pasted `bunker://` URI. Disabled while busy (no second pairing). */
    fun connectPasted() {
        val input = _state.value.pasteInput.trim()
        if (!isOpen || _state.value.busy || input.isEmpty()) return
        _state.update { it.copy(busy = true, error = null, authUrl = null) }
        job = scope.launch {
            try {
                handle(signing.connectNip46Bunker(input))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Bunker-Verbindung fehlgeschlagen", e)
                _state.update { it.copy(busy = false, error = UiText.res(e.identityErrorMessage)) }
            }
        }
    }

    private fun handle(result: Nip46ConnectResult) {
        when (result) {
            is Nip46ConnectResult.Success -> {
                // The service adopted this client as the live session: never cancel it.
                pairing = null
                finish(result)
            }
            Nip46ConnectResult.Timeout ->
                _state.update { it.copy(busy = false, error = UiText.res(L10nR.string.bunkerTimeout)) }
            is Nip46ConnectResult.Error ->
                _state.update { it.copy(busy = false, error = UiText.res(result.cause?.identityErrorMessage ?: space.einundzwanzig.meetup.data.identity.R.string.native_identity_bunker_failure)) }
        }
    }

    /** "Abbrechen" or the sheet was dismissed (only possible while not busy, or via the cancel button). */
    fun cancel() {
        if (!isOpen) return
        job?.cancel()
        finish(null)
    }

    private fun finish(result: Nip46ConnectResult.Success?) {
        if (!isOpen) return
        isOpen = false
        interceptor?.close()
        interceptor = null
        val p = pairing
        pairing = null
        if (p != null) scope.launch { withContext(NonCancellable) { p.cancel() } }
        _state.value = BunkerSheetState()
        onFinished(result)
    }

    /** ViewModel cleared: release the handler and close the pairing sockets in [cleanupScope]. */
    fun dispose(cleanupScope: CoroutineScope) {
        interceptor?.close()
        interceptor = null
        val p = pairing
        pairing = null
        isOpen = false
        if (p != null) cleanupScope.launch { p.cancel() }
    }

    companion object {
        private const val TAG = "BunkerConnect"

        /** `bunker://` prefix, case-insensitive (the service parses and validates the rest). */
        fun isBunkerUri(text: String): Boolean = text.startsWith("bunker://", ignoreCase = true)
    }
}
