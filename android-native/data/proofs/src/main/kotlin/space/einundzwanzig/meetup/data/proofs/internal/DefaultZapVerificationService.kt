package space.einundzwanzig.meetup.data.proofs.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.RelayParseTally
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryStatus
import space.einundzwanzig.meetup.core.nostr.relay.SubscriptionIds
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.proofs.zap.ZapProviderResolver
import space.einundzwanzig.meetup.data.proofs.zap.ZapReceipt
import space.einundzwanzig.meetup.data.proofs.zap.ZapReceiptValidator
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService.Companion.ANALYSIS_WINDOW
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService.Companion.CACHE_STATS_PREFIX
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService.Companion.CACHE_TS_PREFIX
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService.Companion.CACHE_TTL
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService.Companion.ENOUGH_RECEIPTS
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService.Companion.MAX_PROVIDER_LOOKUPS
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService.Companion.MAX_RELAYS
import javax.inject.Inject

/** Port of `ZapVerificationService`. */
internal class DefaultZapVerificationService @Inject constructor(
    private val store: KeyValueStore,
    private val relayConfig: RelayConfig,
    private val relayClient: RelayClient,
    private val providers: ZapProviderResolver,
    private val identity: ProofsIdentity,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : ZapVerificationService {

    override suspend fun analyzeZapActivity(pubkeyHex: String, useCache: Boolean): ZapStats =
        withContext(dispatcher) { analyze(pubkeyHex, useCache) }

    private suspend fun analyze(pubkeyHex: String, useCache: Boolean): ZapStats {
        if (useCache) loadCachedStats(pubkeyHex)?.let { return it }
        val relays = relayConfig.getActiveRelays()
        val (received, sent) = coroutineScope {
            val r = async { fetchZapReceipts(relays, pubkeyHex, isReceived = true) }
            val s = async { fetchZapReceipts(relays, pubkeyHex, isReceived = false) }
            r.await() to s.await()
        }
        val stats = ZapStats.compute(received, sent)
        cacheStats(pubkeyHex, stats)
        return stats
    }

    override suspend fun getMyStats(): ZapStats = withContext(dispatcher) { myStats() }

    private suspend fun myStats(): ZapStats {
        val npub = identity.npub()
        if (npub.isNullOrEmpty()) return ZapStats.EMPTY
        val hex = try {
            Nip19.decodeNpub(npub)
        } catch (e: Exception) {
            log.warn(TAG, "Eigener npub nicht dekodierbar: $e")
            return ZapStats.EMPTY
        }
        return analyze(hex, useCache = true)
    }

    private suspend fun fetchZapReceipts(relays: List<String>, pubkeyHex: String, isReceived: Boolean): List<ZapReceipt> {
        val since = (time.nowMillis() - ANALYSIS_WINDOW.inWholeMilliseconds) / 1000
        val all = ArrayList<ZapReceipt>()
        val seen = HashSet<String>()
        for (relay in relays.take(MAX_RELAYS)) {
            try {
                for (receipt in fetchFromRelay(relay, pubkeyHex, since, sent = !isReceived)) {
                    val wanted = if (isReceived) receipt.isReceived else receipt.isSent
                    if (!wanted) continue
                    if (seen.add(receipt.eventId)) all += receipt
                }
                if (all.size >= ENOUGH_RECEIPTS) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG, "Zap-Abruf von $relay fehlgeschlagen: $e")
            }
        }
        return onlyFromProviders(all)
    }

    private suspend fun fetchFromRelay(relay: String, pubkeyHex: String, since: Long, sent: Boolean): List<ZapReceipt> {
        val filter = ZapVerificationService.receiptFilter(pubkeyHex, since, sent)
        val tally = RelayParseTally(TAG, "Zap-Belege von $relay", log)
        try {
            val result = relayClient.query(
                relay,
                listOf(filter),
                RelayQueryOptions(
                    connectTimeout = RelayConfig.RELAY_TIMEOUT,
                    responseTimeout = RelayConfig.RELAY_TIMEOUT,
                    subscriptionId = SubscriptionIds.secure("zaps"),
                    logTag = TAG,
                    parseTally = tally,
                ),
            )
            // Dart onError completed with [] (collected receipts discarded); a normal close kept them.
            if (result.status == RelayQueryStatus.DISCONNECTED && result.error != null) return emptyList()
            return result.events.mapNotNull { parseZapReceipt(it, pubkeyHex) }
        } finally {
            tally.report()
        }
    }

    /** Dart `_onlyFromProviders`. */
    private suspend fun onlyFromProviders(receipts: List<ZapReceipt>): List<ZapReceipt> {
        val byRecipient = LinkedHashMap<String, MutableList<ZapReceipt>>()
        for (r in receipts) byRecipient.getOrPut(r.recipientPubkey) { ArrayList() } += r
        val kept = ArrayList<ZapReceipt>()
        var lookups = 0
        for ((recipient, group) in byRecipient) {
            if (lookups++ >= MAX_PROVIDER_LOOKUPS) break
            val provider = providers.providerPubkeyFor(recipient) ?: continue
            group.filterTo(kept) { r ->
                val issuer = DartString.lower(r.receiptPubkey)
                issuer == provider &&
                    issuer != DartString.lower(r.senderPubkey) &&
                    issuer != DartString.lower(r.recipientPubkey)
            }
        }
        if (kept.size != receipts.size) {
            log.debug(TAG, "${receipts.size - kept.size} von ${receipts.size} Quittungen ohne Provider-Nachweis verworfen")
        }
        return kept
    }

    private suspend fun loadCachedStats(pubkey: String): ZapStats? {
        val ts = store.getInt(CACHE_TS_PREFIX + pubkey) ?: 0L
        if (time.nowMillis() - ts > CACHE_TTL.inWholeMilliseconds) return null
        val json = store.getString(CACHE_STATS_PREFIX + pubkey) ?: return null
        return try {
            ZapStats.fromJson(DartJson.decode(json) as JsonObject)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun cacheStats(pubkey: String, stats: ZapStats) {
        store.setString(CACHE_STATS_PREFIX + pubkey, DartJson.encode(stats.toJson()))
        store.setInt(CACHE_TS_PREFIX + pubkey, time.nowMillis())
    }

    companion object {
        private const val TAG = "ZapVerification"

        /**
         * Dart `_parseZapReceipt(event, contextPubkey)`: [ZapReceiptValidator.checkLocal] must pass;
         * `isSent = sender == pk`, `isReceived = recipient == pk` (exact compare); neither → `null`.
         */
        internal fun parseZapReceipt(event: JsonObject, contextPubkey: String): ZapReceipt? {
            val check = ZapReceiptValidator.checkLocal(event)
            if (!check.ok) return null
            val isSent = check.senderPubkey == contextPubkey
            val isReceived = check.recipientPubkey == contextPubkey
            if (!isSent && !isReceived) return null
            return ZapReceipt(
                eventId = event["id"].dartStringOrNull() ?: "",
                senderPubkey = check.senderPubkey,
                recipientPubkey = check.recipientPubkey,
                createdAt = event["created_at"].dartIntOrNull() ?: 0L,
                isSent = isSent,
                isReceived = isReceived,
                hasBolt11 = true,
                receiptPubkey = check.receiptPubkey,
            )
        }
    }
}
