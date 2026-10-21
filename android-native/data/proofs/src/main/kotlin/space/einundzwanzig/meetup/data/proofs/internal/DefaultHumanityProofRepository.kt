package space.einundzwanzig.meetup.data.proofs.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import space.einundzwanzig.meetup.core.data.prefs.KeyValueEditor
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
import java.util.concurrent.atomic.AtomicLong
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

    private val searchMutex = Mutex()

    /** Searches finished under [searchMutex] (written under it, read before locking). */
    @Volatile
    private var completedSearches = 0L

    /**
     * The last finished search if it was not discarded (guarded by [searchMutex]); `null` after a
     * discarded (STALE) run, so a waiter never takes over an older result.
     */
    private var lastSearch: SearchRun? = null

    /** Bumped by [clearForIdentitySwitch]; a search started under another epoch must not write. */
    private val identityEpoch = AtomicLong()

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
        withContext(dispatcher) { searchSingleFlight() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Dart let this escape and the screen spun forever (spec 06 §14 R23).
        log.warn(TAG, "Humanity-Prüfung fehlgeschlagen: $e")
        HumanityCheckResult(found = false, outcome = HumanityOutcome.ERROR)
    }

    /**
     * One search at a time (startup re-verification and a manual "JETZT PRÜFEN" must not race on
     * the `humanity_*` keys or double the relay load). A caller that had to wait for a search
     * which completed meanwhile takes over its result instead of searching again, but only if
     * that search was not discarded, the npub is the same and no `humanity_*` key changed since it
     * finished (e.g. a backup restore in between must get a fresh relay check, Audit 2 #4). The
     * lock is held by the running caller, so cancelling it cancels the search (no detached work);
     * a waiting caller then runs its own search. Once [search] returned, the bookkeeping runs
     * non-cancellable, so a search whose own write cancels its caller (the startup task watches
     * the flag that the write removes) is still shared.
     */
    private suspend fun searchSingleFlight(): HumanityCheckResult {
        val seen = completedSearches
        return searchMutex.withLock {
            val last = lastSearch
            if (completedSearches != seen && last != null && last.npub == identity.npub() && last.endValues == humanityValues()) {
                return@withLock last.result
            }
            val run = search()
            withContext(NonCancellable) {
                lastSearch = if (run.reusable) run.copy(endValues = humanityValues()) else null
                completedSearches++
            }
            run.result
        }
    }

    private suspend fun humanityValues(): Map<String, PrefValue?> = HumanityKeys.ALL.associateWith { store.get(it) }

    /**
     * Result of one [search]; [reusable] is `false` if it was discarded (identity or humanity
     * keys changed). [endValues] are the `humanity_*` values right after it finished.
     */
    private data class SearchRun(
        val npub: String?,
        val result: HumanityCheckResult,
        val reusable: Boolean = true,
        val endValues: Map<String, PrefValue?> = emptyMap(),
    )

    /**
     * The humanity state when a search started. The search writes only if the identity is still
     * the same and no one else changed the `humanity_*` keys meanwhile (spec 06 §14 R20: an
     * identity switch — [HumanityKeys.clearIdentityProofs], whoever calls it — must not be undone
     * by a search that started for the previous key).
     */
    private class Snapshot(val npub: String, val epoch: Long, val values: Map<String, PrefValue?>)

    private suspend fun search(): SearchRun {
        val npub = identity.npub()
        if (npub.isNullOrEmpty()) return SearchRun(npub, HumanityCheckResult(found = false, outcome = HumanityOutcome.NO_KEY))
        val pubkeyHex = try {
            Nip19.decodeNpub(npub) // another HRP gives "" without throwing (Dart quirk, kept)
        } catch (_: Exception) {
            return SearchRun(npub, HumanityCheckResult(found = false, outcome = HumanityOutcome.INVALID_NPUB))
        }
        val snapshot = Snapshot(npub, identityEpoch.get(), humanityValues())

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
            return when (saveProof(snapshot, hit.receiptEventId, hit.zapTimestamp)) {
                Write.STALE -> staleRun(npub)
                Write.FAILED -> {
                    // Not persisted: do not report "verified" (Dart's failing write threw → no success).
                    log.warn(TAG, "Humanity-Proof konnte nicht gespeichert werden")
                    SearchRun(npub, HumanityCheckResult(found = false, outcome = HumanityOutcome.ERROR))
                }
                Write.OK -> {
                    if (snapshot.values[HumanityKeys.NEEDS_REVERIFICATION].asBool == true) {
                        log.debug(TAG, "Reverifikation erfolgreich: Zap-Receipt bestätigt")
                    }
                    SearchRun(
                        npub,
                        HumanityCheckResult(
                            found = true,
                            outcome = HumanityOutcome.FOUND,
                            receiptEventId = hit.receiptEventId,
                            zapTimestamp = hit.zapTimestamp,
                        ),
                    )
                }
            }
        }
        val now = time.nowSeconds()
        if (writeIfUnchanged(snapshot) { setInt(HumanityKeys.CHECKED_AT, now) } == Write.STALE) return staleRun(npub)
        return SearchRun(npub, HumanityCheckResult(found = false, outcome = HumanityOutcome.NOT_FOUND))
    }

    private fun staleRun(npub: String): SearchRun {
        log.warn(TAG, "Identität oder Humanity-Status hat sich während der Suche geändert — Ergebnis verworfen")
        return SearchRun(npub, HumanityCheckResult(found = false, outcome = HumanityOutcome.ERROR), reusable = false)
    }

    private enum class Write { OK, FAILED, STALE }

    /**
     * Runs [block] in one transaction if the identity of [snapshot] is still current, no
     * [clearForIdentitySwitch] happened and every `humanity_*` key still has its snapshot value.
     * The transaction runs non-cancellable: once started, its result is always reported (a
     * cancellation right after a committed write would otherwise hide that it was written).
     */
    private suspend fun writeIfUnchanged(snapshot: Snapshot, block: KeyValueEditor.() -> Unit): Write {
        if (identity.npub() != snapshot.npub || identityEpoch.get() != snapshot.epoch) return Write.STALE
        var stale = false
        val ok = withContext(NonCancellable) {
            store.edit {
                stale = HumanityKeys.ALL.any { get(it) != snapshot.values[it] }
                if (!stale) block()
            }
        }
        return when {
            stale -> Write.STALE
            !ok -> Write.FAILED
            else -> Write.OK
        }
    }

    override suspend fun reverifyIfNeeded(): Boolean = withContext(dispatcher) { reverify() }

    private suspend fun reverify(): Boolean {
        if (store.getBool(HumanityKeys.NEEDS_REVERIFICATION) != true) return false
        val npub = identity.npub()
        val result = checkForZaps()
        if (!result.found) {
            log.debug(TAG, "Reverifikation fehlgeschlagen: Kein Zap-Receipt gefunden (${result.outcome}). Nächster Versuch später.")
            return false
        }
        if (identity.npub() != npub) {
            log.warn(TAG, "Reverifikation verworfen: Identität hat gewechselt")
            return false
        }
        // The search's own write already cleared the flag (R6). Only if something set it again
        // in between is it confirmed here; a flag cleared by an identity switch stays cleared.
        // (The search logged the success when its write cleared the flag.)
        var confirmed = false
        val ok = withContext(NonCancellable) {
            store.edit {
                if (getBool(HumanityKeys.NEEDS_REVERIFICATION) == true) {
                    setBool(HumanityKeys.VERIFIED, true)
                    remove(HumanityKeys.NEEDS_REVERIFICATION)
                    confirmed = true
                }
            }
        }
        if (ok && confirmed) log.debug(TAG, "Reverifikation erfolgreich: Zap-Receipt bestätigt")
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

    override suspend fun clearForIdentitySwitch(): Boolean {
        identityEpoch.incrementAndGet() // before the wipe: a running search can no longer write
        return store.edit { HumanityKeys.clearIdentityProofs(this) }
    }

    /**
     * Dart `_saveProof`, guarded by [writeIfUnchanged]. Unlike Dart it also removes
     * `humanity_needs_reverification`, so a manual check after a backup restore shows "verified"
     * at once (spec 06 §14 R6).
     */
    private suspend fun saveProof(snapshot: Snapshot, receiptEventId: String, zapTimestamp: Long): Write {
        val now = time.nowSeconds()
        return writeIfUnchanged(snapshot) {
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
