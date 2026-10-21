package space.einundzwanzig.meetup.data.admin

/** Wire and storage constants of the admin domain (spec 05 §4, §6, §8.4.1). */
object AdminConstants {
    /** The hard-coded genesis/super admin (Dart `AdminRegistry.superAdminNpub`). */
    const val SUPER_ADMIN_NPUB = "npub1lf0rga7j66uj6enae2mxezamz5nsz3vechhvmh25tcarn4u8qf5q534jzc"

    /** Distinct admin-list **signers** needed before the super admin loses its special status. */
    const val SUNSET_THRESHOLD = 20

    /**
     * Relays of the admin registry and the promotion claims, in this order. Hard-coded on purpose:
     * they ignore the user's relay configuration (spec 05 §5.1).
     */
    val RELAYS: List<String> = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
        "wss://nostr.einundzwanzig.space",
    )

    /** Kind of the admin list (NIP-78 app data). */
    const val ADMIN_LIST_KIND = 30078

    /** `d` tag of the admin list. */
    const val ADMIN_LIST_D_TAG = "einundzwanzig-admins"

    /** Kind of the admin claim. */
    const val CLAIM_KIND = 30021

    /** `d` tag of the admin claim. */
    const val CLAIM_D_TAG = "einundzwanzig-admin-claim"

    /** Minimum verified proofs (and distinct sessions) of a valid claim. */
    const val MIN_VERIFIED_BADGES = 3

    /** Minimum distinct known signers of a valid claim. */
    const val MIN_UNIQUE_SIGNERS = 1

    /** Claims older than this (event `created_at`) are rejected (Audit-2 Fund 5). */
    const val MAX_CLAIM_AGE_DAYS = 90

    // Pref keys (shared KeyValueStore, Flutter names).
    const val KEY_CACHE = "admin_registry_cache"
    const val KEY_CACHE_TIMESTAMP = "admin_registry_timestamp"
    const val KEY_SUNSET_FLAG = "bootstrap_permanently_sunset"
    const val KEY_MY_VOUCHES = "my_personal_vouches"
    const val KEY_UNIQUE_AUTHORS = "admin_unique_authors_count"
    const val KEY_SUNSET_RESET_DONE = "sunset_count_reset_v1_done"
    const val KEY_SUNSET_AUTHORS_SET = "sunset_authors_set_v1"
    const val KEY_ORGANIC_CLAIMS = "organic_admin_claims"

    /** Every pref key this domain writes (for diagnostics / reset checks). */
    val ALL_KEYS: List<String> = listOf(
        KEY_CACHE, KEY_CACHE_TIMESTAMP, KEY_SUNSET_FLAG, KEY_MY_VOUCHES, KEY_UNIQUE_AUTHORS,
        KEY_SUNSET_RESET_DONE, KEY_SUNSET_AUTHORS_SET, KEY_ORGANIC_CLAIMS,
    )
}
