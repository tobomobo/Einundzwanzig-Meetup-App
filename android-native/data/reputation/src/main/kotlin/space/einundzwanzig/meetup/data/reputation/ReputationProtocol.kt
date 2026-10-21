package space.einundzwanzig.meetup.data.reputation

/**
 * Wire constants of the kind-30078 reputation event (Dart `ReputationPublisher`, spec 04 §5, §6).
 * Changing any of them breaks compatibility with Flutter users.
 */
object ReputationProtocol {
    /** NIP-78 application data, parameterized replaceable. */
    const val EVENT_KIND: Int = 30078

    /** The `d` tag value. Always filter by it: the admin registry uses kind 30078 too. */
    const val D_TAG: String = "einundzwanzig-reputation"

    /** `content.version` and the `v` tag. */
    const val PROTOCOL_VERSION: Int = 2

    /** The `client` tag value. */
    const val CLIENT: String = "einundzwanzig-meetup-app"

    /** Prefix of the fetch subscription id (`rep-fetch-<16 hex>` from a CSPRNG, Audit M4). */
    const val FETCH_SUBSCRIPTION_PREFIX: String = "rep-fetch"

    /** Tags in their exact order: `[["d",…],["v","2"],["client",…]]`. */
    val TAGS: List<List<String>> = listOf(
        listOf("d", D_TAG),
        listOf("v", PROTOCOL_VERSION.toString()),
        listOf("client", CLIENT),
    )

    /** Shared-prefs key (Long, ms since epoch) of the last publish (Flutter name). */
    const val KEY_LAST_PUBLISH: String = "reputation_last_publish"

    /** Shared-prefs key (String, 16 hex) of the last publish change hash (Flutter name). */
    const val KEY_LAST_HASH: String = "reputation_last_hash"

    /**
     * Native-only key (String, 64 hex): pubkey of the identity that made the last publish. A
     * different (or missing) value disables the hash check, so an identity switch is never
     * suppressed by counts that happen to match (spec 04 §4 FIX).
     */
    const val KEY_LAST_PUBKEY: String = "reputation_last_pubkey"

    /** Dart `_minPublishInterval` (5 minutes). */
    const val MIN_PUBLISH_INTERVAL_MS: Long = 5 * 60 * 1000L
}
