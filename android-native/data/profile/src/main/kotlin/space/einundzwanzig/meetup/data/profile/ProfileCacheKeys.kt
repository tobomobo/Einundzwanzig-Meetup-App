package space.einundzwanzig.meetup.data.profile

/**
 * Flutter preference keys of the profile domain (spec 07 §4), stored in the shared
 * `KeyValueStore` under exactly these names.
 */
object ProfileCacheKeys {

    /** Prefix of the signed kind-0 cache: `{"event": <kind-0 event>, "fetchedAt": <ms>}`. */
    const val METADATA_PREFIX: String = "nostr_profile_metadata_v2_"

    /** Prefix of the cached NIP-65 relay list (the kind-10002 event JSON itself). */
    const val RELAYS_PREFIX: String = "nostr_profile_relays_v2_"

    /** Prefix of every legacy (v1) picture key, also old global ones. Only ever removed. */
    const val LEGACY_PICTURE_PREFIX: String = "nostr_profile_picture"

    /** Legacy v1 picture URL key prefix (`nostr_profile_picture_<hex>`). */
    const val LEGACY_PICTURE: String = "nostr_profile_picture_"

    /** Legacy v1 picture timestamp key prefix (`nostr_profile_picture_time_<hex>`, int ms). */
    const val LEGACY_PICTURE_TIME: String = "nostr_profile_picture_time_"

    /** Persistent display-name cache: `{"<hex>": {"n": "<name>", "t": <ms>}, …}`. */
    const val DISPLAY_NAMES: String = "nostr_display_names_v1"

    /** Absolute path of the gallery picture picked on the home screen. */
    const val LOCAL_PICTURE: String = "local_profile_picture"

    /** `nostr_profile_metadata_v2_<hex>`. */
    fun metadata(pubkeyHex: String): String = METADATA_PREFIX + pubkeyHex

    /** `nostr_profile_relays_v2_<hex>`. */
    fun relays(pubkeyHex: String): String = RELAYS_PREFIX + pubkeyHex

    /** `nostr_profile_picture_<hex>`. */
    fun legacyPicture(pubkeyHex: String): String = LEGACY_PICTURE + pubkeyHex

    /** `nostr_profile_picture_time_<hex>`. */
    fun legacyPictureTime(pubkeyHex: String): String = LEGACY_PICTURE_TIME + pubkeyHex
}
