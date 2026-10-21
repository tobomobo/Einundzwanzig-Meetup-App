package space.einundzwanzig.meetup.data.profile

import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.url.SafeUrl
import space.einundzwanzig.meetup.core.nostr.relay.RelayUrls
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Profile picture discovery (Dart `NostrProfileLookup`, spec 07 §8.3).
 *
 * Read-only and without any key: queries the app relays plus the profile index
 * [INDEX_RELAY] for kind 0 and the NIP-65 relay list (kind 10002), then up to
 * [MAX_AUTHOR_RELAYS] of the author's write relays. NIP-01 replacement order applies (newest
 * `created_at`, lowest id on ties), also when a newer profile removes the picture. Results are
 * cached signed for [CACHE_DURATION]; the stale cache survives relay outages.
 *
 * Most callers want [NostrProfileService.fetchProfilePicture], which delegates here.
 */
interface NostrProfileLookup {

    /**
     * The `picture` URL of [pubkeyHex]'s newest signed kind-0 profile, or `null`.
     * Non-hex input (`^[0-9a-f]{64}$`) returns `null` without network. Concurrent calls for
     * the same key share one lookup.
     */
    suspend fun fetchPicture(pubkeyHex: String): String?

    /**
     * Starts a new cache generation: running lookups still answer their callers but can no
     * longer write any cache (app reset).
     */
    fun invalidate()

    companion object {
        /** Signed metadata is fresh for 12 h. */
        val CACHE_DURATION: Duration = 12.hours

        /** The profile index relay that is always queried. */
        const val INDEX_RELAY: String = "wss://purplepag.es"

        /** App relays + index relay queried in the first round. */
        const val MAX_INITIAL_RELAYS: Int = 8

        /** Author write relays (NIP-65) queried in the second round; no recursion. */
        const val MAX_AUTHOR_RELAYS: Int = 4

        /** Events taken per relay answer (= `ProfileRelayClient.maxEvents`). */
        const val MAX_EVENTS_PER_RELAY: Int = 8

        /**
         * Dart `NostrProfileLookup.pictureUrl`: a non-empty string that is an `http`/`https` URL
         * with a host and without user-info, else `null`. Accepts a `String` or a
         * [JsonPrimitive] string; everything else (numbers, `file:`, `data:`, `javascript:`
         * URLs, embedded credentials) is rejected.
         */
        fun pictureUrl(value: Any?): String? {
            val text = when (value) {
                is String -> value
                is JsonPrimitive -> if (value.isString) value.content else return null
                else -> return null
            }
            if (text.isEmpty()) return null
            // Dart Uri.tryParse does not trim: leading whitespace means "no scheme".
            if (text.first().isWhitespace()) return null
            val safe = SafeUrl.httpUrlOrNull(text) ?: return null
            val afterScheme = safe.url.substringAfter("://", "")
            val authority = afterScheme.substring(0, afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) afterScheme.length else it })
            if (authority.substringBeforeLast('@', "").isNotEmpty()) return null
            return safe.url
        }

        /**
         * Dart `NostrProfileLookup.relayUrl`: relay URL normalisation of the lookup. See
         * [RelayUrls.normalize]; with [discovered] (NIP-65 hints of other people) local and
         * private targets are rejected.
         */
        fun relayUrl(value: String, discovered: Boolean = false): String? = RelayUrls.normalize(value, discovered)
    }
}
