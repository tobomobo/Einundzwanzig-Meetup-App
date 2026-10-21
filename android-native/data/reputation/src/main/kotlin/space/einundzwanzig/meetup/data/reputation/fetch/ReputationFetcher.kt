package space.einundzwanzig.meetup.data.reputation.fetch

import space.einundzwanzig.meetup.data.reputation.event.ReputationEvent

/**
 * Reads someone's kind-30078 reputation event from the active relays (Dart
 * `ReputationPublisher.fetchByNpub` / `fetchByPubkeyHex`, spec 04 §5.3). Inject the `@Singleton`.
 *
 * Request per relay: `["REQ","rep-fetch-<16 hex>",{"kinds":[30078],"authors":[hex],
 * "#d":["einundzwanzig-reputation"],"limit":1}]` (connect 8 s, answer 8 s). Every event is
 * verified (id + BIP-340, 10-digit `created_at`) and must answer that filter (kind, exact
 * author, `d` tag — spec 04 §5.3 FIX); its content must be a JSON object.
 *
 * Deviation (spec 04 R-9 FIX): all relays are asked in parallel and the **newest** valid event
 * wins (`created_at` ≤ now + 600 s; ties keep relay order), instead of Flutter's
 * first-relay-that-answers. Total time is bounded by one relay timeout instead of one per relay.
 */
interface ReputationFetcher {

    /** `null` for an empty or undecodable npub, or when no relay has a valid event. */
    suspend fun fetchByNpub(npub: String): ReputationEvent?

    /** `null` for an empty pubkey or when no relay has a valid event. */
    suspend fun fetchByPubkeyHex(pubkeyHex: String): ReputationEvent?
}
