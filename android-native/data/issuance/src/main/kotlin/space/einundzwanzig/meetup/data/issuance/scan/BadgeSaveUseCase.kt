package space.einundzwanzig.meetup.data.issuance.scan

import kotlinx.coroutines.flow.SharedFlow
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge

/**
 * The scanner's "ZUR WALLET HINZUFÜGEN" (Dart `_confirmSaveBadge`, spec 02 §2.1): soft presence
 * check, save-time duplicate guard, atomic save (or keep-aside while the wallet is unreadable),
 * the reputation hook data ([SaveOutcome.Saved.collection]) and the co-attendance opt-in publish.
 */
interface BadgeSaveUseCase {

    /**
     * Optional notification after every successful save (for `:app`-level observers only). Not
     * replayed and dropped without a collector, so it is **not** the reputation hook: use
     * [SaveOutcome.Saved.collection] for that.
     */
    val badgeSaved: SharedFlow<BadgeSavedEvent>

    /**
     * Runs the save flow for [pending] (a [ScanOutcome.Found]). Show a blocking loading
     * indicator while it runs: location takes up to ~45 s.
     *
     * Reputation hook (Dart step 12): on [SaveOutcome.Saved] the caller (the scanner ViewModel in
     * `:feature:badges`) must call `ReputationPublisher.publishInBackground(outcome.collection)`.
     * This module does not depend on `:data:reputation`.
     *
     * @param openedForMeetupLat latitude of the meetup the scanner was opened for (Dart
     *   `widget.meetup.lat`; 0 for the global scan button). The second presence reference. Never
     *   the device position: that would make every presence check pass.
     * @param openedForMeetupLng longitude of that meetup (Dart `widget.meetup.lng`).
     */
    suspend fun confirm(
        pending: PendingBadge,
        openedForMeetupLat: Double = 0.0,
        openedForMeetupLng: Double = 0.0,
    ): SaveOutcome

    /**
     * After the user agreed in the co-attendance opt-in dialog (asked every time, only when
     * [SaveOutcome.Saved.askCoAttendance]): publishes kind 30079. Returns the number of relays
     * that accepted it: > 0 → `caPublished`, 0 → `caPublishFailed`.
     */
    suspend fun publishCoAttendance(badge: MeetupBadge): Int
}

/** Emitted on [BadgeSaveUseCase.badgeSaved]. */
data class BadgeSavedEvent(val badge: MeetupBadge, val collection: List<MeetupBadge>)

/** Result of [BadgeSaveUseCase.confirm]. */
sealed interface SaveOutcome {

    /**
     * Measurably too far: dialog `gpsTooFar` / `gpsTooFarSub(distanceKm 1 decimal, allowedKm 0
     * decimals)`. The badge stays pending (retry possible). Native shows the applied radius
     * (Flutter always showed 5).
     */
    data class TooFar(val distanceKm: Double, val allowedKm: Double) : SaveOutcome

    /** Common to every outcome after the presence check. `false` → snackbar `badgeUnverifiedInfo` (5 s). */
    sealed interface AfterPresence : SaveOutcome {
        val presenceVerified: Boolean
    }

    /**
     * Stored. Snackbar `verifyBadgeSaved`; call
     * `ReputationPublisher.publishInBackground(collection)` (fire and forget, Dart step 12); if
     * [askCoAttendance] show the opt-in dialog, then replace the scanner with the wallet.
     *
     * @property collection the stored wallet after the save (includes [badge]).
     */
    data class Saved(
        val badge: MeetupBadge,
        override val presenceVerified: Boolean,
        val askCoAttendance: Boolean,
        val collection: List<MeetupBadge>,
    ) : AfterPresence

    /** The same event is already in the wallet: snackbar `verifyBadgeDuplicate`, open the wallet. */
    data class Duplicate(val badge: MeetupBadge, override val presenceVerified: Boolean) : AfterPresence

    /** Wallet unreadable or save failed, badge kept aside (encrypted): snackbar `badgesHeldAside` (8 s). */
    data class KeptAside(val badge: MeetupBadge, override val presenceVerified: Boolean) : AfterPresence

    /**
     * Stored nowhere: red snackbar `badgeSaveFailed` (8 s). [pending] (with the measured
     * position) stays available for a retry.
     */
    data class SaveFailed(val pending: PendingBadge, override val presenceVerified: Boolean) : AfterPresence
}
