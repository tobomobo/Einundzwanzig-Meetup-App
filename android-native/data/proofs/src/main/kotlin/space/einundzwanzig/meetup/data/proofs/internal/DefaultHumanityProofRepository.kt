package space.einundzwanzig.meetup.data.proofs.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.RelayParseTally
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.prefs.asBool
import space.einundzwanzig.meetup.core.data.prefs.asInt
import space.einundzwanzig.meetup.core.data.prefs.asString
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelaySubscriptionRequest
import space.einundzwanzig.meetup.core.nostr.relay.SubscriptionIds
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityCheckResult
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityKeys
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityOutcome
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityStatus
import space.einundzwanzig.meetup.data.proofs.zap.ZapProviderResolver
import space.einundzwanzig.meetup.data.proofs.zap.ZapReceiptValidator
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Port of `HumanityProofService`. */
internal class DefaultHumanityProofRepository @Inject constructor(
    private val store: KeyValueStore,
    private val relayConfig: RelayConfig,
    private val relayClient: RelayClient,
    private val providers: ZapProviderResolver,
    private val identity: ProofsIdentity,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : HumanityProofRepository {

    override val status: Flow<HumanityStatus> = combine(HumanityKeys.ALL.map { store.observe(it) }) { values ->
        statusOf(HumanityKeys.ALL.zip(values.toList()).toMap())
    }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getStatus(): HumanityStatus = statusOf(HumanityKeys.ALL.associateWith { store.get(it) })

    private fun statusOf(values: Map<String, PrefValue?>): HumanityStatus {
        val pending = values[HumanityKeys.NEEDS_REVERIFICATION].asBool == true
        return HumanityStatus(
            verified = !pending && values[HumanityKeys.VERIFIED].asBool == true,
            firstZapAt = values[HumanityKeys.FIRST_ZAP_AT].asInt ?: 0,
            receiptEventId = values[HumanityKeys.RECEIPT_ID].asString ?: "",
            lastCheckedAt = values[HumanityKeys.CHECKED_AT].asInt ?: 0,
            needsReverification = pending,
        )
    }

    override suspend fun checkForZaps(): HumanityCheckResult = try {
        withContext(dispatcher) { search() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Dart let this escape and the screen spun forever (spec 06 §14 R23).
        log.warn(TAG, "Humanity-Prüfung fehlgeschlagen: $e")
        HumanityCheckResult(found = false, outcome = HumanityOutcome.ERROR)
    }

    private suspend fun search(): HumanityCheckResult {
        val npub = identity.npub()
        if (npub.isNullOrEmpty()) return HumanityCheckResult(found = false, outcome = HumanityOutcome.NO_KEY)
        val pubkeyHex = try {
            Nip19.decodeNpub(npub) // another HRP gives "" without throwing (Dart quirk, kept)
        } catch (_: Exception) {
            return HumanityCheckResult(found = false, outcome = HumanityOutcome.INVALID_NPUB)
        }

        val zapRelays = LinkedHashSet<String>().apply {
            addAll(relayConfig.getActiveRelays())
            addAll(EXTRA_ZAP_RELAYS)
        }
        for (relay in zapRelays) {
            val hit = try {
                searchZapActivity(relay, pubkeyHex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG, "Zap-Suche auf $relay fehlgeschlagen: $e")
                null
            } ?: continue
            if (!saveProof(hit.receiptEventId, hit.zapTimestamp)) {
                // Not persisted: do not report "verified" (Dart's failing write threw → no success).
                log.warn(TAG, "Humanity-Proof konnte nicht gespeichert werden")
                return HumanityCheckResult(found = false, outcome = HumanityOutcome.ERROR)
            }
            return HumanityCheckResult(
                found = true,
                outcome = HumanityOutcome.FOUND,
                receiptEventId = hit.receiptEventId,
                zapTimestamp = hit.zapTimestamp,
            )
        }
        store.setInt(HumanityKeys.CHECKED_AT, time.nowSeconds())
        return HumanityCheckResult(found = false, outcome = HumanityOutcome.NOT_FOUND)
    }

    override suspend fun reverifyIfNeeded(): Boolean = withContext(dispatcher) { reverify() }

    private suspend fun reverify(): Boolean {
        if (store.getBool(HumanityKeys.NEEDS_REVERIFICATION) != true) return false
        val result = checkForZaps()
        if (!result.found) {
            log.debug(TAG, "Reverifikation fehlgeschlagen: Kein Zap-Receipt gefunden. Nächster Versuch beim nächsten App-Start.")
            return false
        }
        val ok = store.edit {
            setBool(HumanityKeys.VERIFIED, true)
            remove(HumanityKeys.NEEDS_REVERIFICATION)
        }
        if (ok) log.debug(TAG, "Reverifikation erfolgreich: Zap-Receipt bestätigt")
        return ok
    }

    override suspend fun reset() {
        store.edit {
            remove(HumanityKeys.VERIFIED)
            remove(HumanityKeys.RECEIPT_ID)
            remove(HumanityKeys.FIRST_ZAP_AT)
            remove(HumanityKeys.CHECKED_AT)
        }
    }

    override suspend fun getProofForPublishing(): JsonObject? {
        val s = getStatus()
        if (!s.verified) return null
        return jsonObjectOf("verified" to true, "method" to "lightning_zap", "first_zap_at" to s.firstZapAt)
    }

    override suspend fun checkForZapsByPubkey(pubkeyHex: String): Boolean = withContext(dispatcher) {
        searchByPubkey(pubkeyHex)
    }

    private suspend fun searchByPubkey(pubkeyHex: String): Boolean {
        val relays = LinkedHashSet<String>().apply {
            addAll(relayConfig.getActiveRelays().take(2))
            addAll(BY_PUBKEY_RELAYS)
        }
        for (relay in relays) {
            try {
                if (searchZapActivity(relay, pubkeyHex) != null) return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG, "Zap-Suche auf $relay fehlgeschlagen: $e")
            }
        }
        return false
    }

    override suspend fun verifyReceiptExists(receiptEventId: String): Boolean = withContext(dispatcher) {
        receiptExists(receiptEventId)
    }

    private suspend fun receiptExists(receiptEventId: String): Boolean {
        if (receiptEventId.isEmpty()) return false
        for (relay in relayConfig.getActiveRelays().take(3)) {
            val filter = jsonObjectOf("ids" to listOf(receiptEventId), "kinds" to listOf(ZapReceiptValidator.KIND_ZAP_RECEIPT), "limit" to 1)
            val tally = RelayParseTally(TAG, "Beleg-Pruefung von $relay", log)
            try {
                val result = relayClient.query(
                    relay,
                    listOf(filter),
                    RelayQueryOptions(
                        subscriptionId = SubscriptionIds.secure("verify"),
                        matchFilters = false,
                        logTag = TAG,
                        parseTally = tally,
                    ),
                )
                val exists = result.events.any { ev ->
                    ev["id"].dartStringOrNull() == receiptEventId && ev["kind"].dartIntOrNull() == ZapReceiptValidator.KIND_ZAP_RECEIPT.toLong()
                }
                if (exists) return true
            } finally {
                tally.report()
            }
        }
        return false
    }

    override suspend fun restoreFromBackup(backupProof: JsonObject?, additive: Boolean): Boolean {
        val already = additive && store.getBool(HumanityKeys.VERIFIED) == true
        if (already || backupProof == null) return false
        val verified = (backupProof["verified"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false
        if (!verified) return false
        val receiptId = backupProof["receipt_event_id"].dartStringOrNull() ?: ""
        val firstZapAt = backupProof["first_zap_at"].dartIntOrNull() ?: 0L
        val checkedAt = backupProof["checked_at"].dartIntOrNull() ?: 0L
        val ok = store.edit {
            setBool(HumanityKeys.VERIFIED, false)
            setBool(HumanityKeys.NEEDS_REVERIFICATION, true)
            if (receiptId.isNotEmpty()) setString(HumanityKeys.RECEIPT_ID, receiptId)
            if (firstZapAt > 0) setInt(HumanityKeys.FIRST_ZAP_AT, firstZapAt)
            if (checkedAt > 0) setInt(HumanityKeys.CHECKED_AT, checkedAt)
        }
        log.debug(
            "Backup",
            "Humanity-Proof als pending_reverification markiert. Receipt: ${if (receiptId.isNotEmpty()) "vorhanden" else "fehlt"}",
        )
        return ok
    }

    override suspend fun clearForIdentitySwitch(): Boolean = store.edit { HumanityKeys.clearIdentityProofs(this) }

    /**
     * Dart `_saveProof`. Unlike Dart it also removes `humanity_needs_reverification`, so a manual
     * check after a backup restore shows "verified" at once (spec 06 §14 R6).
     * @return `false` if the write failed.
     */
    private suspend fun saveProof(receiptEventId: String, zapTimestamp: Long): Boolean {
        val now = time.nowSeconds()
        return store.edit {
            setBool(HumanityKeys.VERIFIED, true)
            setString(HumanityKeys.RECEIPT_ID, receiptEventId)
            setInt(HumanityKeys.FIRST_ZAP_AT, zapTimestamp)
            setInt(HumanityKeys.CHECKED_AT, now)
            remove(HumanityKeys.NEEDS_REVERIFICATION)
        }
    }

    private class Candidate(
        val receiptEventId: String,
        val zapTimestamp: Long,
        val receiptPubkey: String,
        val recipientPubkey: String,
        val senderPubkey: String,
    )

    private class Hit(val receiptEventId: String, val zapTimestamp: Long)

    /**
     * Dart `_searchZapActivity`: one socket, three REQs (`zap-recv` `#p`, `zap-req` kind 9734
     * authors, `zap-sent` `#P`), `verifiedEvent` only, until every subscription sent EOSE or
     * [SEARCH_TIMEOUT]. Kind-9734 events are ignored (they prove nothing). Up to
     * [MAX_CANDIDATES] locally valid receipts in which [pubkeyHex] is recipient or sender are
     * checked against the recipient's provider in arrival order; the first match wins.
     */
    private suspend fun searchZapActivity(relay: String, pubkeyHex: String): Hit? {
        val tally = RelayParseTally(TAG, "Zap-Suche von $relay", log)
        try {
            val requests = listOf(
                RelaySubscriptionRequest(
                    SubscriptionIds.secure("zap-recv"),
                    listOf(jsonObjectOf("kinds" to listOf(9735), "#p" to listOf(pubkeyHex), "limit" to 3)),
                ),
                RelaySubscriptionRequest(
                    SubscriptionIds.secure("zap-req"),
                    listOf(jsonObjectOf("kinds" to listOf(9734), "authors" to listOf(pubkeyHex), "limit" to 3)),
                ),
                RelaySubscriptionRequest(
                    SubscriptionIds.secure("zap-sent"),
                    listOf(jsonObjectOf("kinds" to listOf(9735), "#P" to listOf(pubkeyHex), "limit" to 3)),
                ),
            )
            val result = relayClient.queryMulti(
                relay,
                requests,
                RelayQueryOptions(
                    connectTimeout = RelayConfig.RELAY_TIMEOUT,
                    responseTimeout = SEARCH_TIMEOUT,
                    matchFilters = false,
                    logTag = TAG,
                    parseTally = tally,
                ),
            )
            val candidates = ArrayList<Candidate>()
            for (tagged in result.events) {
                val event = tagged.event
                if (event["kind"].dartIntOrNull() != ZapReceiptValidator.KIND_ZAP_RECEIPT.toLong()) continue
                val check = ZapReceiptValidator.checkLocal(event)
                if (!check.ok) {
                    tally.failed("zap_receipt:${check.reason}")
                    continue
                }
                val mine = check.recipientPubkey == pubkeyHex || check.senderPubkey == pubkeyHex
                if (!mine) continue
                if (candidates.size < MAX_CANDIDATES) {
                    candidates += Candidate(
                        receiptEventId = event["id"].dartStringOrNull() ?: "",
                        zapTimestamp = event["created_at"].dartIntOrNull() ?: 0L,
                        receiptPubkey = check.receiptPubkey,
                        recipientPubkey = check.recipientPubkey,
                        senderPubkey = check.senderPubkey,
                    )
                }
            }
            for (c in candidates) {
                if (providers.isFromRecipientProvider(c.receiptPubkey, c.recipientPubkey, c.senderPubkey)) {
                    return Hit(c.receiptEventId, c.zapTimestamp)
                }
                log.debug(TAG, "Quittung ${c.receiptEventId.take(8)}… nicht vom Provider des Empfängers — ignoriert")
            }
            return null
        } finally {
            tally.report()
        }
    }

    companion object {
        private const val TAG = "HumanityProof"

        /** Relays added after the active relays for the own check (order matters). */
        val EXTRA_ZAP_RELAYS: List<String> = listOf(
            "wss://relay.damus.io",
            "wss://relay.nostr.band",
            "wss://nos.lol",
            "wss://relay.snort.social",
            "wss://relay.primal.net",
            "wss://nostr.wine",
        )

        /** Relays added after the first two active relays for [checkForZapsByPubkey]. */
        val BY_PUBKEY_RELAYS: List<String> = listOf("wss://relay.damus.io", "wss://relay.nostr.band", "wss://nos.lol")

        /** Wait for the three EOSEs at most this long. */
        val SEARCH_TIMEOUT: Duration = 12.seconds

        /** At most this many locally valid receipts per relay get a provider check. */
        const val MAX_CANDIDATES: Int = 6
    }
}
