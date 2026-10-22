package space.einundzwanzig.meetup.data.meetups.location

/**
 * Location acquisition, radius rules and nearest-meetup lookup (Dart `MeetupLocationService` plus
 * `NearbyMeetupService.getCurrentLocation`, spec 09 §8.5–§8.7).
 *
 * Works without Google Play services: fixes come from the platform `LocationManager` (fused
 * provider where the OS has one, then GPS/network), then a last-known fix of at most 10 minutes.
 * The location never leaves the device.
 */
interface MeetupLocationService {

    /** Whether the system location service is switched on. */
    fun isLocationServiceEnabled(): Boolean

    /** Current permission state without prompting (fine OR coarse counts as granted). */
    fun permissionState(): LocationPermission

    /**
     * Three-stage acquisition (spec 09 §8.5): service off → [LocationStatus.SERVICE_DISABLED];
     * permission not granted → one request via [LocationPermissionBridge], also when it was denied
     * forever (the flag is only remembered from the last prompt: Android answers instantly if it
     * still holds, and shows the dialog if the user reset the permission in the settings; Dart
     * skipped the request); then a balanced fix (25 s), a high-accuracy `LocationManager` fix (20 s), and finally
     * the last known fix if it is younger than 11 minutes (`age.inMinutes <= 10`), else
     * [LocationStatus.NO_FIX]. Unexpected failures give [LocationStatus.ERROR].
     */
    suspend fun getCurrentLocation(): LocationResult

    /**
     * [getCurrentLocation] + `fetchMeetups()`; returns all meetups with coordinates sorted by
     * distance. Does NOT fall back to the bundled list (callers do that when needed).
     */
    suspend fun resolveLocation(): LocationMeetupResult

    /** Haversine distance in km ([Geo.distanceKm]). */
    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double = Geo.distanceKm(lat1, lng1, lat2, lng2)

    /**
     * `true` if the reference ([lat2],[lng2]) is (0,0) (no organizer coordinates → no check), else
     * `distanceKm <= PARTICIPANT_RADIUS_KM`.
     */
    fun isWithinParticipantRadius(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Boolean =
        (lat2 == 0.0 && lng2 == 0.0) || distanceKm(lat1, lng1, lat2, lng2) <= PARTICIPANT_RADIUS_KM

    /** Opens the system location settings (`ACTION_LOCATION_SOURCE_SETTINGS`); `false` if impossible. */
    fun openLocationSettings(): Boolean

    /** Opens this app's details page (`ACTION_APPLICATION_DETAILS_SETTINGS`); `false` if impossible. */
    fun openAppSettings(): Boolean

    companion object {
        /** Participant ↔ organizer-MEASURED reference (QR `la`/`lo`). */
        const val PARTICIPANT_RADIUS_KM = 5.0

        /** Organizer: meetups within this radius count as "here". */
        const val CREATION_RADIUS_KM = 10.0

        /** Participant ↔ DERIVED reference (portal coordinates; regions can be wide). */
        const val DERIVED_RADIUS_KM = 50.0

        /** See [LocationMeetupResult.AMBIGUITY_THRESHOLD_KM]. */
        const val AMBIGUITY_THRESHOLD_KM = LocationMeetupResult.AMBIGUITY_THRESHOLD_KM

        /** First fix attempt (balanced power), seconds. */
        const val FIRST_FIX_TIMEOUT_S = 25L

        /** Second fix attempt (LocationManager, high accuracy), seconds. */
        const val SECOND_FIX_TIMEOUT_S = 20L

        /** Last-known fixes are accepted while `age.inMinutes <= MAX_LAST_KNOWN_AGE_MIN`. */
        const val MAX_LAST_KNOWN_AGE_MIN = 10L
    }
}
