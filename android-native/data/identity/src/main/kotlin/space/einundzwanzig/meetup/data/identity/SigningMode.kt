package space.einundzwanzig.meetup.data.identity

/**
 * Who holds the private key (Dart `SigningMode`). Persisted under the pref `signing_mode` as
 * [stored] (the lowercase Dart enum name).
 */
enum class SigningMode(
    /** The value written to the `signing_mode` pref. */
    val stored: String,
) {
    /** The app holds the key (generated or imported) in the secure store. */
    LOCAL("local"),

    /** Amber via NIP-55; the key never leaves Amber. */
    AMBER("amber"),

    /**
     * Browser extension (NIP-07). Web only: on Android it can only appear after restoring a web
     * backup. The identity is shown, [SigningService.canSign] is `false` and the user is offered
     * to connect another signer.
     */
    NIP07("nip07"),

    /** Remote signer ("bunker") via NIP-46. */
    NIP46("nip46"),
    ;

    /** `true` for every mode in which the app does not hold the private key. */
    val isExternal: Boolean get() = this != LOCAL

    companion object {
        /** Dart `getMode()`: unknown or absent values read as [LOCAL]. */
        fun fromStored(value: String?): SigningMode = entries.firstOrNull { it.stored == value } ?: LOCAL
    }
}

/** The primary "I already use Nostr" path on this device (Dart `RecommendedExistingPath`). */
enum class RecommendedExistingPath {
    /** Browser extension; never returned on Android. */
    NIP07,

    /** Amber is installed. */
    AMBER,

    /** Remote signer (bunker:// or nostrconnect://). */
    BUNKER,
}
