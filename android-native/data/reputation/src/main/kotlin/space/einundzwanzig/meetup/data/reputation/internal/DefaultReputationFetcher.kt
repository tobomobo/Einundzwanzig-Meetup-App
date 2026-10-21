package space.einundzwanzig.meetup.data.reputation.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.NostrFilter
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayFanOut
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryStatus
import space.einundzwanzig.meetup.core.nostr.relay.SubscriptionIds
import space.einundzwanzig.meetup.data.reputation.ReputationProtocol
import space.einundzwanzig.meetup.data.reputation.event.ReputationEvent
import space.einundzwanzig.meetup.data.reputation.event.ReputationEventParser
import space.einundzwanzig.meetup.data.reputation.fetch.ReputationFetcher
import javax.inject.Inject

/**
 * [ReputationFetcher] over [RelayClient] (which verifies every event and matches it against the
 * filter). See the interface for the newest-wins deviation.
 */
internal class DefaultReputationFetcher @Inject constructor(
    private val relayClient: RelayClient,
    private val relayConfig: RelayConfig,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : ReputationFetcher {

    override suspend fun fetchByNpub(npub: String): ReputationEvent? {
        if (npub.isEmpty()) return null
        val hex = try {
            Nip19.decodeNpub(npub)
        } catch (_: Exception) {
            return null
        }
        return fetchByPubkeyHex(hex)
    }

    override suspend fun fetchByPubkeyHex(pubkeyHex: String): ReputationEvent? {
        if (pubkeyHex.isEmpty()) return null
        val relays = relayConfig.getActiveRelays()
        if (relays.isEmpty()) return null

        val filter = filterFor(pubkeyHex)
        val results = relayClient.queryAll(
            relays,
            listOf(filter),
            RelayQueryOptions(
                subscriptionId = SubscriptionIds.secure(ReputationProtocol.FETCH_SUBSCRIPTION_PREFIX),
                logTag = TAG,
            ),
            RelayFanOut.PARALLEL,
        )
        for (r in results) {
            if (r.status == RelayQueryStatus.CONNECT_FAILED || r.error != null) {
                log.debug(TAG, "${r.relay} Fetch fehlgeschlagen: ${r.error ?: r.status}")
            }
        }
        return withContext(cpu) { newest(results.flatMap { it.events }, pubkeyHex, time.nowSeconds()) }
    }

    companion object {
        private const val TAG = "ReputationPublisher"

        /**
         * `{"kinds":[30078],"authors":[hex],"#d":["einundzwanzig-reputation"],"limit":1}` (Dart
         * key order).
         */
        fun filterFor(pubkeyHex: String): JsonObject = NostrFilter(
            kinds = listOf(ReputationProtocol.EVENT_KIND),
            authors = listOf(pubkeyHex),
            tags = mapOf("d" to listOf(ReputationProtocol.D_TAG)),
            limit = 1,
        ).toJson()

        /**
         * The newest parseable event of [pubkeyHex] among already verified [events] (exact author
         * compare, `created_at` ≤ now + 600 s, JSON-object content); ties keep the first.
         */
        fun newest(events: List<JsonObject>, pubkeyHex: String, nowSeconds: Long): ReputationEvent? {
            var best: ReputationEvent? = null
            var bestAt = Long.MIN_VALUE
            for (event in events) {
                if (event["pubkey"].dartStringOrNull() != pubkeyHex) continue
                val at = EventGuard.replaceableCreatedAt(event, nowSeconds) ?: continue
                if (best != null && at <= bestAt) continue
                val parsed = ReputationEventParser.parseRaw(event) ?: continue
                best = parsed
                bestAt = at
            }
            return best
        }
    }
}
