package space.einundzwanzig.meetup.feature.admin

import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.data.meetups.location.GpsStatus
import space.einundzwanzig.meetup.data.meetups.location.MeetupCandidate
import space.einundzwanzig.meetup.data.meetups.model.Meetup

/** Everything the organizer panel shows (spec 05 §2.2). */
@Immutable
data class AdminPanelUiState(
    /** Own npub; the status row shows an `NpubChip` only when it is non-empty. */
    val adminNpub: String = "",
    /** `promotionSource == "trust_score"` → `apViaTrustScore`, else `apOrganizer`. */
    val viaTrustScore: Boolean = false,
    /** Own npub is the bootstrap super admin (Flutter: only an extra 16 dp spacer). */
    val isSuperAdmin: Boolean = false,
    /** `false` until the first session check finished (avoids flashing the start tile). */
    val sessionChecked: Boolean = false,
    /** The running session, `null` when none. */
    val session: ActiveSessionUi? = null,
    /** The blocking, non-dismissible spinner (GPS lookup, loading the picker, creating the session). */
    val busy: Boolean = false,
    /** The dialog or sheet currently shown. */
    val dialog: AdminDialog? = null,
)

/** A running session with its live countdown `Hh MMm SSs`. */
@Immutable
data class ActiveSessionUi(
    val meetupName: String,
    val expiresAtSeconds: Long,
    val timeLeft: String,
)

/** The dialog catalogue of spec 05 §2.2.2 plus the two confirmations of §2.2.1. */
@Immutable
sealed interface AdminDialog {
    /** `apNewMeetupQ` / `apNewMeetupBody`. */
    data object ConfirmStart : AdminDialog

    /** `apSessionEndQ` / `apSessionEndBody`. */
    data object ConfirmEnd : AdminDialog

    /** `_askContinueWithoutGps(status)`. */
    data class ContinueWithoutGps(val status: GpsStatus) : AdminDialog

    /** `_showCustomNeedsLocation()`. */
    data object CustomNeedsLocation : AdminDialog

    /** `_confirmNoReference(city)`. */
    data class NoReference(val city: String) : AdminDialog

    /**
     * `_pickAnyPortalMeetup()` with its nested `_confirmPickedMeetup(m)` ([confirming] non-null).
     */
    data class PortalPicker(
        val rows: List<PickerMeetup>,
        val query: String = "",
        val confirming: Meetup? = null,
    ) : AdminDialog {
        val visibleRows: List<PickerMeetup> get() = PortalMeetupPicker.filter(rows, query)
    }

    /** `_askMeetupName()`. */
    data class ManualName(val text: String = "", val showError: Boolean = false) : AdminDialog

    /** `_pickMeetup(candidates)`: bottom sheet with up to 5 rows. */
    data class PickNearby(val candidates: List<MeetupCandidate>) : AdminDialog
}

/** User intents of [AdminPanelScreen]. */
sealed interface AdminPanelAction {
    data object Back : AdminPanelAction
    data object StartClicked : AdminPanelAction
    data object EndClicked : AdminPanelAction
    data object OpenActiveMeetup : AdminPanelAction

    /** Barrier tap / back on the current dialog (each dialog maps it to its Flutter result). */
    data object DismissDialog : AdminPanelAction
    data object ConfirmStart : AdminPanelAction
    data object ConfirmEnd : AdminPanelAction

    /** `gpsOpenLocationSettings` / `gpsOpenAppSettings` (the dialog stays open). */
    data object OpenLocationSettings : AdminPanelAction
    data object OpenAppSettings : AdminPanelAction

    /** `gpsRetry`: closes the dialog and restarts the start flow. */
    data object GpsRetry : AdminPanelAction

    /** `orgGpsSoftContinue`. */
    data object GpsContinue : AdminPanelAction

    /** `apNoRefContinue`. */
    data object NoReferenceContinue : AdminPanelAction

    data class PickerQueryChanged(val query: String) : AdminPanelAction
    data class PickerRowClicked(val meetup: Meetup) : AdminPanelAction

    /** `apConfirmPickYes` in the nested confirmation. */
    data object PickerConfirmYes : AdminPanelAction

    /** `dialogCancel` in the nested confirmation (back to the list). */
    data object PickerConfirmCancel : AdminPanelAction

    /** `apEnterManually`. */
    data object PickerEnterManually : AdminPanelAction

    data class ManualNameChanged(val text: String) : AdminPanelAction
    data object ManualNameSubmit : AdminPanelAction

    data class NearbyPicked(val candidate: MeetupCandidate) : AdminPanelAction

    /** The screen became visible again (e.g. back from the rolling QR). */
    data object Resumed : AdminPanelAction
}

/** One-shot effects. */
sealed interface AdminPanelEvent {
    data class Navigate(val key: NavKey) : AdminPanelEvent
    data object NavigateBack : AdminPanelEvent

    /** Red snackbar `wotErrorShort(message)`. */
    data class Error(val message: String) : AdminPanelEvent
}
