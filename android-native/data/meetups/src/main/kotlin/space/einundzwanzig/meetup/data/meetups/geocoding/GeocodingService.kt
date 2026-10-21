package space.einundzwanzig.meetup.data.meetups.geocoding

/**
 * A geocoding hit (Dart `GeoPlace`).
 *
 * @property displayName full Nominatim name, e.g. "Hamburg, Deutschland".
 * @property shortName `name`, or the first comma part of [displayName].
 */
data class GeoPlace(
    val displayName: String,
    val shortName: String,
    val lat: Double,
    val lng: Double,
)

/** Free-text place search via OpenStreetMap Nominatim (Dart `GeocodingService`, spec 09 §5.4). */
interface GeocodingService {

    /**
     * Up to [limit] places for [query] (trimmed; fewer than 2 characters → `[]` without a request).
     * Never throws: non-200 and errors give `[]`; places at exactly (0,0) are dropped.
     *
     * Usage policy: at most one request per second is enforced here (Nominatim policy); the UI
     * keeps its 600 ms debounce and cancels the previous search coroutine on new input, which
     * cancels the HTTP call.
     */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<GeoPlace>

    companion object {
        /** Nominatim search endpoint. */
        const val URL = "https://nominatim.openstreetmap.org/search"

        /** User-Agent required by the Nominatim usage policy (Flutter value, byte-exact). */
        const val USER_AGENT = "EinundzwanzigMeetupApp/1.0 (nostr meetup app)"

        /** Default number of results. */
        const val DEFAULT_LIMIT = 5

        /** Minimum query length after trimming. */
        const val MIN_QUERY_LENGTH = 2

        /** UI debounce before searching, ms. */
        const val DEBOUNCE_MS = 600L

        /** Minimum spacing between two requests, ms. */
        const val MIN_INTERVAL_MS = 1000L
    }
}
