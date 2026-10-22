package space.einundzwanzig.meetup.feature.badges.scanner

import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.issuance.nfc.NfcPayloadError
import space.einundzwanzig.meetup.data.issuance.scan.FoundInfo
import space.einundzwanzig.meetup.data.issuance.scan.PendingBadge
import space.einundzwanzig.meetup.data.issuance.session.NonceValidation
import space.einundzwanzig.meetup.feature.badges.nfc.NfcTagHandle

/** Localized labels the scanner logic needs (the slug of `verifyUnknownMeetup`). */
interface ScannerLabels {
    /** The localized `verifyUnknownMeetup`. */
    fun unknownMeetup(): String
}

/**
 * State of the participant badge scanner (Dart `MeetupVerificationScreen`, spec 02 §2.1).
 *
 * @property status the live status line of the scanner view (`_statusText`).
 * @property processing a scan is being verified (block height, registry, claim signing).
 * @property readingNfc NFC reader mode is on.
 * @property result non-null = confirmation view (`_success == true`).
 * @property saving the blocking "locating" indicator of `_confirmSaveBadge`.
 */
data class BadgeScannerUiState(
    val nfcEnabled: Boolean = false,
    val status: ScanStatus = ScanStatus.Ready,
    val processing: Boolean = false,
    val readingNfc: Boolean = false,
    val result: ScanResultUi? = null,
    val saving: Boolean = false,
    val dialog: ScannerDialog? = null,
)

/** The scanner view's status line. */
sealed interface ScanStatus {
    /** `verifyReadyToScan`. */
    data object Ready : ScanStatus

    /** `verifyCheckingNfc`. */
    data object CheckingNfc : ScanStatus

    /** `verifyWaitingNfc`. */
    data object WaitingNfc : ScanStatus

    /** `verifyNoNfcDevice`. */
    data object NoNfcDevice : ScanStatus

    /** `nfcDisabledHint`. */
    data object NfcDisabledHint : ScanStatus

    /** `verifyErrQrExpired(message)`. */
    data class NonceExpired(val validation: NonceValidation) : ScanStatus

    /** `verifyErrPrefix(message)` (signature, format, expiry, malformed fields). */
    data class Rejected(val message: String) : ScanStatus

    /** NFC record could not be read (`verifyErr*`). */
    data class NfcRejected(val error: NfcPayloadError) : ScanStatus

    /** Reader session could not start (`verifyErrNfcError(detail)`). */
    data class NfcSessionError(val detail: String) : ScanStatus

    /** `"✗ " + verifyCantSelfBadge + verifyAskScan`. */
    data object SelfScan : ScanStatus
}

/** The confirmation view. */
sealed interface ScanResultUi {
    /** "BADGE GEFUNDEN": green, or orange for an unknown signer. */
    data class Found(val pending: PendingBadge, val info: FoundInfo) : ScanResultUi

    /** "BEREITS GESAMMELT" (grey) with "ZUR WALLET". */
    data class AlreadyCollected(val fullName: String, val isEvent: Boolean) : ScanResultUi

    /**
     * The wallet was unreadable and the badge went to the encrypted pending slot: Flutter set
     * `_pendingBadge = null` without a rebuild; native shows the "already/aside" layout with
     * the `badgesHeldAside` text and "ZUR WALLET".
     */
    data class KeptAside(val fullName: String) : ScanResultUi
}

/** Dialogs above the scanner. */
sealed interface ScannerDialog {
    /** App-review demo payload: English "Demo scan OK". */
    data object ReviewDemo : ScannerDialog

    /** `gpsTooFar` / `gpsTooFarSub`. */
    data class TooFar(val distanceKm: Double, val allowedKm: Double) : ScannerDialog

    /** Co-attendance opt-in (`caOptIn*`). */
    data class CoAttendanceOptIn(val badge: MeetupBadge) : ScannerDialog

    /** NFC hardware missing. */
    data object NfcUnavailable : ScannerDialog

    /** NFC off. */
    data object NfcDisabled : ScannerDialog
}

/** User and system input of the scanner. */
sealed interface BadgeScannerAction {
    data object ScanQrClicked : BadgeScannerAction
    data class QrScanned(val raw: String) : BadgeScannerAction
    data object ScanNfcClicked : BadgeScannerAction
    data class NfcTagDiscovered(val tag: NfcTagHandle) : BadgeScannerAction
    data class NfcSessionFailed(val detail: String) : BadgeScannerAction
    data object NfcDialogScanQr : BadgeScannerAction
    data object NfcOpenSettings : BadgeScannerAction
    data object AddToWallet : BadgeScannerAction
    data object OpenWallet : BadgeScannerAction
    data object Close : BadgeScannerAction
    data object DismissDialog : BadgeScannerAction
    data class CoAttendanceAnswered(val publish: Boolean) : BadgeScannerAction
}

/** One-shot effects. */
sealed interface BadgeScannerEvent {
    /** Push `BadgeQrCamera`. */
    data object OpenCamera : BadgeScannerEvent

    /** Replace the scanner with the wallet. */
    data object OpenWallet : BadgeScannerEvent

    /** Pop the scanner (Flutter `pop(false)`). */
    data object Close : BadgeScannerEvent

    /** Show a snackbar on the app host (it outlives the screen). */
    data class Snackbar(val kind: ScannerSnackbar) : BadgeScannerEvent

    /** Haptic feedback for a scan result. */
    data class Haptic(val success: Boolean) : BadgeScannerEvent
}

/** Snackbars of `_confirmSaveBadge` (spec 02 §2.1 steps 8–14). */
enum class ScannerSnackbar {
    /** `badgeUnverifiedInfo`, 5 s, surface. */
    UNVERIFIED_PRESENCE,

    /** `badgesHeldAside`, 8 s. */
    HELD_ASIDE,

    /** `badgeSaveFailed`, 8 s, red. */
    SAVE_FAILED,

    /** `verifyBadgeDuplicate`, orange. */
    DUPLICATE,

    /** `verifyBadgeSaved`, green. */
    SAVED,

    /** `caPublished`, green, 3 s. */
    CO_ATTENDANCE_PUBLISHED,

    /** `caPublishFailed`, red, 6 s. */
    CO_ATTENDANCE_FAILED,
}
