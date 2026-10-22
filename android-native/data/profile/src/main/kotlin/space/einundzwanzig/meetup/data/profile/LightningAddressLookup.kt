package space.einundzwanzig.meetup.data.profile

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Lightning address (LUD-16) of a pubkey from its newest kind-0 profile (Dart
 * `NewsZapService.fetchLightningAddress`, spec 11 §6.9, spec 06 §5.5). Used by news zaps and by
 * the zap-receipt provider check of the proofs domain.
 */
interface LightningAddressLookup {

    /**
     * Queries kind 0 of [pubkeyHex] on [readTargets] in parallel (sub id `zapmeta`, connect
     * [CONNECT_TIMEOUT] per relay, [OVERALL_TIMEOUT] in total, earlier when every relay is done).
     * The newest profile wins (later arrival on equal `created_at`; a parse failure leaves the
     * current best unchanged).
     *
     * @return the trimmed `lud16` if it contains `@`; [LUD06_MARKER] if only `lud06` is set
     *   (unsupported, but distinguishable from "none"); `null` if the newest profile has no
     *   address, nothing was found, or [pubkeyHex] is empty.
     */
    suspend fun fetchLightningAddress(pubkeyHex: String): String?

    /**
     * Dart `NewsReactionsService.readTargets()`: [NEWS_RELAYS] ∪ [READ_ONLY_RELAYS] ∪ the user's
     * active relays, in this order, without duplicates (active relays that fail to load are
     * skipped). `:data:news` should use this instead of keeping its own copy.
     */
    suspend fun readTargets(): List<String>

    companion object {
        /** Relays the news articles come from (read and write). */
        val NEWS_RELAYS: List<String> = listOf(
            "wss://nostr.einundzwanzig.space",
            "wss://relay.damus.io",
            "wss://nos.lol",
        )

        /** Read-only aggregator relays (never written to). */
        val READ_ONLY_RELAYS: List<String> = listOf("wss://relay.nostr.band")

        /** Returned when the newest profile only has a `lud06` (bech32 LNURL), which the app does not support. */
        const val LUD06_MARKER: String = "lud06:"

        /** Fixed subscription id of the lookup. */
        const val SUBSCRIPTION_ID: String = "zapmeta"

        /** Per-relay connect timeout. */
        val CONNECT_TIMEOUT: Duration = 4.seconds

        /** Total budget of the lookup. */
        val OVERALL_TIMEOUT: Duration = 6.seconds
    }
}
