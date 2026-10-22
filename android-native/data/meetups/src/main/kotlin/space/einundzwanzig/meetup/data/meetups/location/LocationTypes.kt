package space.einundzwanzig.meetup.data.meetups.location

import space.einundzwanzig.meetup.data.meetups.model.Meetup
import kotlin.math.abs

/** Coarse location status for the badge flows (Dart `GpsStatus`). */
enum class GpsStatus { OK, DENIED, SERVICE_DISABLED, ERROR }

/**
 * Fine-grained location status (Dart `LocationStatus` of `NearbyMeetupService`).
 *
 * [DENIED_FOREVER] needs the app settings, [NO_FIX] (service on, permission granted, no fix)
 * needs a step towards a window — the UI gives different advice for each.
 */
enum class LocationStatus {
    OK, DENIED, DENIED_FOREVER, SERVICE_DISABLED, NO_FIX, ERROR;

    /** Mapping to the coarse [GpsStatus] (DENIED_FOREVER → DENIED, NO_FIX → ERROR). */
    fun toGpsStatus(): GpsStatus = when (this) {
        OK -> GpsStatus.OK
        DENIED, DENIED_FOREVER -> GpsStatus.DENIED
        SERVICE_DISABLED -> GpsStatus.SERVICE_DISABLED
        NO_FIX, ERROR -> GpsStatus.ERROR
    }
}

/** Location permission state as reported by [MeetupLocationService.permissionState]. */
enum class LocationPermission {
    /** Fine or coarse (approximate) location is granted; approximate is sufficient. */
    GRANTED,

    /** Not granted; a request may still show the system dialog. */
    DENIED,

    /**
     * Not granted and the system no longer showed the dialog at the last prompt: only the app
     * settings help. Remembered from that prompt, so it can be stale after a settings reset.
     */
    DENIED_FOREVER,
}

/**
 * A location fix (the parts of geolocator's `Position` the app uses).
 *
 * @property accuracyMeters horizontal accuracy (0 if unknown).
 * @property timeMillis wall-clock time of the fix (epoch ms), used for the last-known age check.
 */
data class Position(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double = 0.0,
    val timeMillis: Long,
)

/** Outcome of [MeetupLocationService.getCurrentLocation]; [position] is set only for [LocationStatus.OK]. */
data class LocationResult(val status: LocationStatus, val position: Position? = null)

/** A meetup with its distance to the current position. */
data class MeetupCandidate(val meetup: Meetup, val distanceKm: Double)

/**
 * Result of [MeetupLocationService.resolveLocation] (Dart `LocationMeetupResult`).
 *
 * @property candidates meetups with coordinates, sorted by ascending distance.
 */
data class LocationMeetupResult(
    val status: GpsStatus,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val candidates: List<MeetupCandidate> = emptyList(),
) {
    /** The nearest candidate, if any. */
    val nearest: MeetupCandidate? get() = candidates.firstOrNull()

    /** Candidates within [MeetupLocationService.CREATION_RADIUS_KM] (inclusive). */
    val withinCreationRadius: List<MeetupCandidate>
        get() = candidates.filter { it.distanceKm <= MeetupLocationService.CREATION_RADIUS_KM }

    /** `true` when the two nearest candidates differ by less than [AMBIGUITY_THRESHOLD_KM]. */
    val isAmbiguous: Boolean
        get() = candidates.size >= 2 &&
            abs(candidates[1].distanceKm - candidates[0].distanceKm) < AMBIGUITY_THRESHOLD_KM

    companion object {
        /** Distance difference below which the two nearest meetups count as ambiguous. */
        const val AMBIGUITY_THRESHOLD_KM = 2.0
    }
}
