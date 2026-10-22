package space.einundzwanzig.meetup.data.meetups.nearby

import java.time.LocalDate
import java.time.ZoneId

/**
 * Nearby meetup search (Dart `NearbyMeetupService.preload/search`, spec 09 §8.8). Location
 * acquisition lives in `MeetupLocationService.getCurrentLocation`.
 */
interface NearbyMeetupService {

    /**
     * Loads meetups and dates concurrently and caches them for the process lifetime. Without
     * [forceReload] it returns immediately when both caches hold real answers. An empty directory
     * falls back to the bundled list; meetups are deduplicated by `city.lowercase().trim()` (first
     * wins, empty keys always kept) — parity with Dart (spec 09 §14 item 12). Native deviation:
     * the fallback list and an empty date list are not final, so the next call retries only the
     * missing part (Dart kept them until the process ended).
     */
    suspend fun preload(forceReload: Boolean = false)

    /**
     * Meetups with coordinates within [radiusKm] of the center, each with its matching dates
     * (legacy substring city matcher, spec 09 §8.8):
     * - [DateMode.ANY]: dates after now − 12 h; meetups without dates are included.
     * - [DateMode.SINGLE_DAY]: dates on [dayFrom] (local day in [zone]); meetups without a match are skipped.
     * - [DateMode.RANGE]: dates from [dayFrom] 00:00:00 to [dayTo] 23:59:59 inclusive; same skip rule.
     *
     * Sorted by ascending distance.
     */
    suspend fun search(
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double,
        dateMode: DateMode,
        dayFrom: LocalDate? = null,
        dayTo: LocalDate? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<NearbyMeetup>

    companion object {
        /** Default radius of the nearby screen, km. */
        const val DEFAULT_RADIUS_KM = 25.0

        /** Radius slider minimum, km. */
        const val MIN_RADIUS_KM = 5.0

        /** Radius slider maximum, km. */
        const val MAX_RADIUS_KM = 100.0

        /** Radius slider step, km (19 intervals; Compose `Slider(steps = 18)`). */
        const val RADIUS_STEP_KM = 5.0

        /** ANY mode shows dates that started at most this many hours ago. */
        const val ANY_WINDOW_PAST_HOURS = 12L
    }
}
