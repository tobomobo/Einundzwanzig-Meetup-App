package space.einundzwanzig.meetup.data.issuance.event

import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.issuance.session.MeetupSession

/** Outcome of the event-badge trust chain (Dart `EventChainStatus`, spec 02 §8.8). */
enum class EventChainStatus {
    /** Creator is a registered organizer and the signer is an issuer. */
    VERIFIED,

    /** Event found, but the signer is not (or no longer) in its issuer list. */
    SIGNER_NOT_LISTED,

    /** Event found, but its creator is not a registered organizer (or the registry failed). */
    CREATOR_NOT_AUTHORIZED,

    /** The event does not issue badges. */
    EVENT_HAS_NO_BADGE,

    /** Not found, cancelled or no relay reachable: "unverified", not "forged". */
    EVENT_NOT_FOUND,
}

/**
 * Result of [EventBadgeChainService.verify] (Dart `EventChainResult`).
 *
 * @property creatorName registry name (or meetup) of the creator, only for [EventChainStatus.VERIFIED].
 * @property badgeImageUrl the event's `badge_image`, copied into the scanned badge as cover.
 */
data class EventChainResult(
    val status: EventChainStatus,
    val eventTitle: String = "",
    val creatorPubkey: String = "",
    val creatorName: String? = null,
    val badgeImageUrl: String = "",
) {
    /** `status == VERIFIED`. */
    val ok: Boolean get() = status == EventChainStatus.VERIFIED
}

/**
 * Trust chain of an event badge when scanning (Dart `EventBadgeChainService`): badge `m` → calendar
 * event (newest version) → creator in the admin registry, signer in the issuer list.
 */
interface EventBadgeChainService {
    /**
     * @param issuedAt the **scanner's** current time (helpers' clocks are untrusted); former
     *   issuers count up to their removal time. `null`: only current issuers count.
     */
    suspend fun verify(eventAddress: String, signerPubkey: String, issuedAt: Long?): EventChainResult
}

/** Where the right to create an event with a badge comes from (Dart `EventBadgeRight`). */
enum class EventBadgeRight { NONE, ADMIN_REGISTRY, PORTAL_LEADER }

/**
 * Who may create an event **with a badge** (Dart `EventBadgeAuthService`, spec 02 §8.10):
 * admin registry → membership of any portal meetup → the stored admin status of the profile.
 * Who may *issue* for an existing event is `NostrCalendarEvent.isIssuer`.
 */
interface EventBadgeAuthService {
    /** The first source that grants the right; [EventBadgeRight.NONE] without identity. Never throws. */
    suspend fun myRight(): EventBadgeRight

    /** `myRight() != NONE`: enables the badge toggle of the event editor. */
    suspend fun mayCreate(): Boolean = myRight() != EventBadgeRight.NONE
}

/** Why an event-badge session may not start (Dart `EventSessionError`). */
enum class EventSessionError {
    /** No identity (`evSessionNoIdentity`). */
    NO_IDENTITY,

    /** Not creator or issuer of the event (`evSessionNotIssuer`). */
    NOT_ISSUER,

    /** Not the event day(s) or badges disabled (`evSessionOutsideWindow`). */
    OUTSIDE_WINDOW,

    /** The event has no map location (`evSessionNoEventLocation`). */
    NO_EVENT_LOCATION,

    /** Location failed for another reason (`evSessionNoLocation`). */
    LOCATION_UNAVAILABLE,

    /** Location service off (`evSessionLocationOff`, action: location settings). */
    LOCATION_SERVICE_OFF,

    /** Permission denied (`evSessionLocationDenied`). */
    LOCATION_DENIED,

    /** Permission denied forever (`evSessionLocationDeniedForever`, action: app settings). */
    LOCATION_DENIED_FOREVER,

    /** No fix, e.g. indoors without network location (`evSessionLocationNoFix`, 10 s snackbar). */
    LOCATION_NO_FIX,

    /** More than [EventBadgeSessionService.ISSUER_RADIUS_KM] from the venue (`evSessionTooFar(km)`). */
    TOO_FAR_AWAY,

    /** Signing or storage failed (`evSessionFailed`). */
    SESSION_FAILED,
}

/**
 * Result of [EventBadgeSessionService.start] (Dart `EventSessionResult`).
 *
 * @property distanceKm distance to the venue, only for [EventSessionError.TOO_FAR_AWAY]
 *   (format with `toStringAsFixed(1)`).
 */
data class EventSessionResult(
    val session: MeetupSession? = null,
    val error: EventSessionError? = null,
    val distanceKm: Double? = null,
) {
    /** `session != null`. */
    val ok: Boolean get() = session != null
}

/**
 * A helper (issuer) starts the rolling-QR session for an event badge (Dart
 * `EventBadgeSessionService.start`, spec 02 §8.9): issuer check, badge window, venue location,
 * own position within 5 km, then a session until local midnight after the last event day, and
 * the organizer attendance in the background.
 */
interface EventBadgeSessionService {
    /** Never throws (except cancellation). */
    suspend fun start(event: NostrCalendarEvent): EventSessionResult

    companion object {
        /** Helper ↔ venue radius (the creator set the venue point on a map, so it is precise). */
        const val ISSUER_RADIUS_KM: Double = 5.0
    }
}
