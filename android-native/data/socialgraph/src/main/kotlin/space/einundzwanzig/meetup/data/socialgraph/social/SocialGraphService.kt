package space.einundzwanzig.meetup.data.socialgraph.social

/**
 * NIP-02 follow graph (Dart `SocialGraphService`, spec 05 §8.1).
 *
 * Reads kind-3 contact lists from the user's active relays (`RelayConfig.getActiveRelays()`,
 * sequentially, newest event across relays wins, an empty list is a valid newest result).
 * Only aggregated numbers ever leave the device (spec 05 §9 item 12).
 */
interface SocialGraphService {

    /**
     * The user's follows (64-char pubkey hex, case preserved). Served from the
     * `social_my_follows` cache if it is younger than 2 h unless [forceRefresh]; otherwise fetched
     * and cached (an empty result is cached too). `{}` without an own npub (not cached).
     */
    suspend fun getMyFollows(forceRefresh: Boolean = false): Set<String>

    /** The follows of [pubkeyHex] from the newest kind-3 event across all active relays, or `{}`. */
    suspend fun fetchContactList(pubkeyHex: String): Set<String>

    /**
     * Compares the user with [targetPubkeyHex]: follows both ways, common contacts, organizer
     * followers (fresh `AdminRegistry.fetchFromRelays()`, first 10 admins) and hops.
     * [SocialAnalysis.empty] without an own pubkey or for the user themselves.
     */
    suspend fun analyze(targetPubkeyHex: String): SocialAnalysis

    /**
     * Aggregated stats for the reputation event: follow count, mutuals estimated from a sample of
     * the first 20 follows (`round(mutual / sample * follows)`), organizer followers.
     * [SocialStats.empty] without an own pubkey. The caller applies its own timeout (10 s in Dart).
     */
    suspend fun getMyStats(): SocialStats

    companion object {
        /** Pref key: JSON array of followed pubkey hex (not keyed by owner, as in Flutter). */
        const val KEY_MY_FOLLOWS = "social_my_follows"

        /** Pref key: ms epoch of the last follow fetch (int). */
        const val KEY_CACHE_TS = "social_cache_ts"

        /** Follow cache lifetime: 2 h. */
        const val CACHE_DURATION_MS = 7_200_000L

        /** Organizer follow checks look at this many admins at most. */
        const val MAX_ADMINS_CHECKED = 10

        /** Mutual sample size of [getMyStats]. */
        const val MUTUAL_SAMPLE = 20
    }
}
