package space.einundzwanzig.meetup.data.meetups.portal

/**
 * Per-meetup organizer authority from the public `GET /api/meetup-leaders` list (Dart
 * `PortalLeaderService`, spec 09 §8.10, §9 items 1–3).
 *
 * SECURITY: anyone can create a portal meetup and become its leader, so a signer is accepted as
 * organizer only for the meetup it leads. There is deliberately no API returning all leader npubs.
 * "Unknown" (`null`) and "No" (`false`) stay distinct, and an empty list is never adopted.
 */
interface PortalLeaderService {

    /**
     * Loads the list when needed: returns early if a non-empty list is younger than 24 h (unless
     * [force]); otherwise loads the prefs cache when nothing is in memory, then ALWAYS tries the
     * network (8 s). Non-200, errors and empty answers keep the old state.
     */
    suspend fun ensureLoaded(force: Boolean = false)

    /**
     * Is [npub] (trimmed, exact) a leader of portal meetup [meetupId]? `null` = unknown (empty
     * [npub] or no data could be loaded), `false` = data present but the npub is not listed or the
     * meetup has no leaders.
     */
    suspend fun isLeaderOf(meetupId: Int, npub: String): Boolean?

    /** Number of meetups [npub] leads, from the loaded data. Display/diagnostics only — never an authority check. */
    fun meetupCountFor(npub: String): Int

    companion object {
        /** Prefs key of the raw JSON body. */
        const val CACHE_KEY = "portal_leaders_cache"

        /** Prefs key of the cache time (epoch ms). */
        const val CACHE_TS_KEY = "portal_leaders_cache_ts"

        /** Cache lifetime, ms (24 h). */
        const val TTL_MS = 24L * 60 * 60 * 1000
    }
}
