package space.einundzwanzig.meetup.data.profile

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Profiles of other people and of the user (Dart `NostrProfileService`, spec 07 §8.3/§8.4):
 * profile pictures (signed kind-0 discovery, see [NostrProfileLookup]), display names (kind 0
 * `display_name`/`name`, fallback to the `identity.nickname` of the app's kind-30078 reputation
 * event), the locally picked home-screen picture, and the cache reset.
 *
 * Every function is main-safe and never throws for relay problems.
 */
interface NostrProfileService {

    /** Picture URL of [pubkeyHex] (64 lowercase hex), or `null`. See [NostrProfileLookup.fetchPicture]. */
    suspend fun fetchProfilePicture(pubkeyHex: String): String?

    /**
     * Display name of one person (NpubChip, chat), or `null`.
     *
     * Newest kind 0 across the active relays wins (first relay wins ties); a newer profile
     * without a name yields `null` and the nickname is not consulted. Without any kind 0, the
     * newest kind-30078 `einundzwanzig-reputation` event's `identity.nickname` (trimmed, not
     * "anon") is used; that path may return `""`. Results, also negative ones, are cached in
     * memory for the process lifetime. An empty [pubkeyHex] returns `null`.
     */
    suspend fun fetchDisplayName(pubkeyHex: String): String?

    /**
     * Names stored in `nostr_display_names_v1` (hex → name) without any relay query. Also
     * seeds the in-memory cache of [fetchDisplayName].
     */
    suspend fun cachedDisplayNames(): Map<String, String>

    /**
     * Names for many people at once (trust network): hex → name for everyone who has one.
     * Stored names younger than [NAMES_MAX_AGE] are not re-queried; the rest is fetched in
     * batches of [NAMES_BATCH] per relay, all relays in parallel. A relay outage never deletes
     * a stored name; a newer profile without a name does.
     */
    suspend fun fetchDisplayNames(pubkeysHex: Collection<String>): Map<String, String>

    /** Stores the absolute path of the locally picked profile picture (`local_profile_picture`). */
    suspend fun setLocalPicture(path: String)

    /** The stored local picture path (Dart `getLocalPicture`), whether or not the file exists. */
    suspend fun getLocalPicture(): String?

    /**
     * [getLocalPicture] if it is non-empty and the file still exists, else `null`. A path
     * migrated from the Flutter app may point into a cache directory that no longer exists
     * (spec 07 §4); callers then fall back to the remote picture / letter.
     */
    suspend fun getExistingLocalPicture(): String?

    /** [getLocalPicture] now and after every change. */
    fun observeLocalPicture(): Flow<String?>

    /**
     * App reset: invalidates running lookups and removes the signed metadata and relay caches,
     * all legacy `nostr_profile_picture*` keys and `local_profile_picture`. The display-name
     * cache `nostr_display_names_v1` is kept (as in Dart; the full reset clears all prefs).
     */
    suspend fun clearCache()

    companion object {
        /** Per-relay connect budget and answer budget of the name queries. */
        val NAME_TIMEOUT: Duration = 6.seconds

        /** Stored batch names are refreshed after one day. */
        val NAMES_MAX_AGE: Duration = 1.days

        /** Authors per batch REQ. */
        const val NAMES_BATCH: Int = 100

        /** `d` tag of the reputation event that carries `identity.nickname`. */
        const val REPUTATION_D_TAG: String = "einundzwanzig-reputation"

        /** Kind of the reputation event (NIP-78 app data). */
        const val REPUTATION_KIND: Int = 30078
    }
}
