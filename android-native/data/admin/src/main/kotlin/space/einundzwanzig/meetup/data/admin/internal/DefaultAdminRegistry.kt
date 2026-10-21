package space.einundzwanzig.meetup.data.admin.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.RelayParseTally
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrFilter
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishStatus
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryStatus
import space.einundzwanzig.meetup.core.nostr.relay.SubscriptionIds
import space.einundzwanzig.meetup.core.nostr.relay.query
import space.einundzwanzig.meetup.data.admin.AdminConstants
import space.einundzwanzig.meetup.data.admin.model.AdminCheckResult
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.admin.model.AdminsBySigner
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistry
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistryException
import space.einundzwanzig.meetup.data.admin.registry.AdminRelayFetch
import space.einundzwanzig.meetup.data.admin.signing.AdminSigner
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Port of `lib/services/admin_registry.dart` (spec 05 §8.4). Pref keys in [AdminConstants].
 *
 * The Dart class was static; this is a `@Singleton` (bound in `AdminModule`). Read-modify-write
 * operations on the cache and the vouch list are serialized with a mutex (Dart relied on the
 * single isolate); the sunset counters are updated in one atomic store transaction.
 */
internal class DefaultAdminRegistry(
    private val store: KeyValueStore,
    private val relayClient: RelayClient,
    private val signer: AdminSigner,
    private val timeSource: TimeSource,
    private val backgroundScope: CoroutineScope,
    private val relayFetchOverride: AdminRelayFetch?,
    private val zone: () -> ZoneId,
    private val log: DiagnosticLog,
) : AdminRegistry {

    private val writeMutex = Mutex()

    // =============================================
    // Sunset
    // =============================================

    override suspend fun isSunsetActive(): Boolean {
        var resetFrom = -1L
        var tampered = -1L
        var activatedWith = -1L
        var result = false
        store.edit {
            // _resetSunsetCountOnce (K1 follow-up): runs once per install.
            if (getBool(AdminConstants.KEY_SUNSET_RESET_DONE) != true) {
                setBool(AdminConstants.KEY_SUNSET_RESET_DONE, true)
                resetFrom = getInt(AdminConstants.KEY_UNIQUE_AUTHORS) ?: 0
                remove(AdminConstants.KEY_UNIQUE_AUTHORS)
                remove(AdminConstants.KEY_SUNSET_AUTHORS_SET)
                setBool(AdminConstants.KEY_SUNSET_FLAG, false)
            }
            val uniqueAuthors = getInt(AdminConstants.KEY_UNIQUE_AUTHORS) ?: 0
            result = if (getBool(AdminConstants.KEY_SUNSET_FLAG) == true) {
                // Audit-2 Fund 6: a flag without the matching count is reset (tampering).
                if (uniqueAuthors < AdminConstants.SUNSET_THRESHOLD) {
                    tampered = uniqueAuthors
                    setBool(AdminConstants.KEY_SUNSET_FLAG, false)
                    false
                } else {
                    true
                }
            } else if (uniqueAuthors >= AdminConstants.SUNSET_THRESHOLD) {
                setBool(AdminConstants.KEY_SUNSET_FLAG, true)
                activatedWith = uniqueAuthors
                true
            } else {
                false
            }
        }
        if (resetFrom > 0) {
            log.security(
                TAG,
                "Sunset-Zählung zurückgesetzt (K1): $resetFrom Autoren waren ungefiltert gezählt und konnten vergiftet sein.",
            )
        }
        if (tampered >= 0) {
            log.security(
                TAG,
                "Sunset-Flag inkonsistent! Flag=true aber uniqueAuthors=$tampered < threshold=" +
                    "${AdminConstants.SUNSET_THRESHOLD}. Flag zurückgesetzt.",
            )
        }
        if (activatedWith >= 0) {
            log.debug(
                TAG,
                "BOOTSTRAP SUNSET AKTIVIERT! $activatedWith verschiedene Admins publizieren. Web of Trust ist nun dezentral.",
            )
        }
        return result
    }

    /** Dart `_recordSunsetAuthors`: unions the signer hex keys into the accumulated set. */
    private suspend fun recordSunsetAuthors(authorPubkeysHex: Set<String>) {
        if (authorPubkeysHex.isEmpty()) return
        store.edit {
            val existing = LinkedHashSet(getStringList(AdminConstants.KEY_SUNSET_AUTHORS_SET).orEmpty())
            val before = existing.size
            existing.addAll(authorPubkeysHex)
            if (existing.size != before) {
                setStringList(AdminConstants.KEY_SUNSET_AUTHORS_SET, existing.toList())
                setInt(AdminConstants.KEY_UNIQUE_AUTHORS, existing.size.toLong())
            }
        }
    }

    // =============================================
    // Check
    // =============================================

    override suspend fun checkAdmin(userNpub: String): AdminCheckResult {
        if (userNpub.isEmpty()) return AdminCheckResult(isAdmin = false, source = AdminCheckResult.SOURCE_NO_KEY)

        val isSunset = isSunsetActive()
        if (!isSunset && userNpub == AdminConstants.SUPER_ADMIN_NPUB) {
            return AdminCheckResult(
                isAdmin = true,
                meetup = "Alle Meetups",
                name = "Super-Admin",
                source = AdminCheckResult.SOURCE_SUPER_ADMIN,
            )
        }

        val cacheHit = loadFromCache()?.firstOrNull { it.npub == userNpub }
        if (cacheHit != null) {
            refreshFromRelaysInBackground()
            return AdminCheckResult(true, cacheHit.meetup, cacheHit.name, AdminCheckResult.SOURCE_LOCAL_CACHE)
        }

        // "Not listed" and "could not look it up" must stay distinguishable (scanner).
        var lookupFailed = false
        try {
            val relayList = fetchFromRelays()
            if (relayList.isNullOrEmpty()) {
                lookupFailed = true
            } else {
                saveToCache(relayList)
                isSunsetActive()
                val entry = relayList.firstOrNull { it.npub == userNpub }
                if (entry != null) {
                    return AdminCheckResult(true, entry.meetup, entry.name, AdminCheckResult.SOURCE_NOSTR_RELAY)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Relay-Fetch Fehler: $e")
            lookupFailed = true
        }

        if (lookupFailed) {
            log.warn(TAG, "Registry nicht abrufbar — Signierer bleibt UNGEPRUEFT, nicht unbekannt.")
            return AdminCheckResult(isAdmin = false, source = AdminCheckResult.SOURCE_UNAVAILABLE)
        }
        return AdminCheckResult(isAdmin = false, source = AdminCheckResult.SOURCE_NOT_FOUND)
    }

    override suspend fun checkAdminByPubkey(pubkeyHex: String): AdminCheckResult {
        if (pubkeyHex.isEmpty()) return AdminCheckResult(isAdmin = false, source = AdminCheckResult.SOURCE_NO_KEY)
        val npub = try {
            Nip19.encodeNpub(pubkeyHex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AdminCheckResult(isAdmin = false, source = AdminCheckResult.SOURCE_INVALID_KEY)
        }
        return checkAdmin(npub)
    }

    // =============================================
    // Relay fetch
    // =============================================

    override suspend fun fetchFromRelays(): List<AdminEntry>? {
        val isSunset = isSunsetActive()
        val authorsToQuery = mutableListOf<String>()

        if (!isSunset) {
            try {
                val superAdminHex = Nip19.decodeNpub(AdminConstants.SUPER_ADMIN_NPUB)
                authorsToQuery += superAdminHex
                log.debug(TAG, "Bootstrap Phase: Frage Relays nach Super-Admin ($superAdminHex)")
            } catch (e: Exception) {
                log.warn(TAG, "Ungültiger Super-Admin npub: $e")
                return null
            }
        } else {
            loadFromCache()?.forEach { admin ->
                // Dart Nip19.decodePubkey: "" for another HRP (kept), throws on bech32 errors (skipped).
                runCatching { Nip19.decodeNpub(admin.npub) }.getOrNull()?.let { authorsToQuery += it }
            }
            if (authorsToQuery.isEmpty()) {
                runCatching { Nip19.decodeNpub(AdminConstants.SUPER_ADMIN_NPUB) }.getOrNull()?.let { authorsToQuery += it }
            }
            log.debug(TAG, "Sunset Phase: Frage Relays nach Updates von ${authorsToQuery.size} bekannten Admins.")
        }

        // Only the first 50 authors are queried (filter limits / timeouts), as in Dart.
        val queryChunk = if (authorsToQuery.size > QUERY_CHUNK) authorsToQuery.subList(0, QUERY_CHUNK).toList() else authorsToQuery.toList()
        val fetch = relayFetchOverride ?: AdminRelayFetch { relay, authors -> fetchFromSingleRelay(relay, authors) }

        for (relayUrl in AdminConstants.RELAYS) {
            try {
                val result = fetch.fetch(relayUrl, queryChunk) ?: continue
                // Organic legacy claims are dropped HERE (K1); only queried signers that keep at
                // least one entry count for the sunset (Audit-2 Fund 6).
                val legitSigners = LinkedHashSet<String>()
                val uniqueMap = LinkedHashMap<String, AdminEntry>()
                var sawQueried = false
                for ((signerHex, entries) in result) {
                    if (signerHex !in queryChunk) continue
                    sawQueried = true
                    val clean = withoutLegacyOrganic(entries)
                    if (clean.isEmpty()) continue
                    legitSigners += signerHex
                    for (e in clean) uniqueMap[e.npub] = e
                }
                if (!sawQueried) continue
                val admins = uniqueMap.values.toList()
                recordSunsetAuthors(legitSigners)
                return if (isSunset) mergeWithCache(admins) else admins
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(TAG, "$relayUrl fehlgeschlagen: $e")
            }
        }
        log.warn(TAG, "Kein Relay erreichbar")
        return null
    }

    /**
     * Dart `_mergeWithCache` (Audit M1): relay data replaces the cache; cache entries missing
     * from the relay answer are dropped (revocation). An empty relay answer keeps the cache.
     */
    private suspend fun mergeWithCache(relayEntries: List<AdminEntry>): List<AdminEntry> {
        val cached = loadFromCache().orEmpty()
        if (relayEntries.isEmpty()) return cached
        val merged = LinkedHashMap<String, AdminEntry>()
        for (entry in relayEntries) merged[entry.npub] = entry
        return merged.values.toList()
    }

    /**
     * Dart `_fetchFromSingleRelay` on top of [RelayClient] (signature + filter checks there):
     * EOSE → collected (may be empty); timeout → collected if non-empty, else `null`;
     * disconnect / connect failure → `null`. Entries are de-duplicated per signer by npub.
     */
    private suspend fun fetchFromSingleRelay(relayUrl: String, authorsHex: List<String>): AdminsBySigner? {
        val tally = RelayParseTally(TAG, "Admin-Liste von $relayUrl", log)
        try {
            val filter = NostrFilter(
                kinds = listOf(AdminConstants.ADMIN_LIST_KIND),
                authors = authorsHex,
                tags = mapOf("d" to listOf(AdminConstants.ADMIN_LIST_D_TAG)),
                limit = QUERY_LIMIT,
            )
            val collected = LinkedHashMap<String, MutableList<AdminEntry>>()
            val result = relayClient.query(
                relayUrl,
                filter,
                RelayQueryOptions(
                    connectTimeout = RELAY_TIMEOUT,
                    responseTimeout = RELAY_TIMEOUT,
                    subscriptionId = SubscriptionIds.secure("admin"),
                    // Dart ignored CLOSED and waited for the timeout.
                    stopOnClosed = false,
                    logTag = TAG,
                    parseTally = tally,
                ),
            ) { event ->
                val pubkey = (event["pubkey"] as? JsonPrimitive)?.content ?: return@query
                try {
                    val admins = parseAdminListContent((event["content"] as? JsonPrimitive)?.content ?: "")
                    if (admins.isNotEmpty()) collected.getOrPut(pubkey) { mutableListOf() }.addAll(admins)
                } catch (e: Exception) {
                    tally.failed(e)
                }
            }
            val answer: Map<String, List<AdminEntry>>? = when (result.status) {
                RelayQueryStatus.EOSE, RelayQueryStatus.LIMIT -> collected
                RelayQueryStatus.TIMEOUT, RelayQueryStatus.CLOSED -> collected.takeIf { it.isNotEmpty() }
                RelayQueryStatus.DISCONNECTED, RelayQueryStatus.CONNECT_FAILED, RelayQueryStatus.AUTH_FAILED -> {
                    if (result.status == RelayQueryStatus.CONNECT_FAILED) {
                        throw result.error as? Exception ?: AdminRegistryException("Verbindung zu $relayUrl fehlgeschlagen")
                    }
                    null
                }
            }
            return answer?.mapValues { (_, entries) ->
                LinkedHashMap<String, AdminEntry>().apply { entries.forEach { put(it.npub, it) } }.values.toList()
            }
        } finally {
            tally.report()
        }
    }

    // =============================================
    // Background refresh
    // =============================================

    private fun refreshFromRelaysInBackground() {
        backgroundScope.launch {
            try {
                val age = cacheAge()
                if (age != null && age.inWholeHours < 1) return@launch
                val relayList = fetchFromRelays()
                if (relayList != null) {
                    saveToCache(relayList)
                    isSunsetActive()
                    log.debug(TAG, "Cache im Hintergrund aktualisiert. Total Admins: ${relayList.size}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent, as in Dart.
            }
        }
    }

    // =============================================
    // Cache
    // =============================================

    private fun withoutLegacyOrganic(admins: List<AdminEntry>): List<AdminEntry> = admins.filterNot { it.isLegacyOrganicClaim }

    /**
     * Dart `_loadFromCache`: the only reader of the network cache. Organic legacy claims are
     * dropped and, if any were present, the list (not the timestamp) is rewritten.
     */
    private suspend fun loadFromCache(): List<AdminEntry>? = try {
        val json = store.getString(AdminConstants.KEY_CACHE)
        if (json == null) {
            null
        } else {
            val raw = AdminEntry.listFromJson(json)
            val clean = withoutLegacyOrganic(raw)
            if (clean.size != raw.size) {
                store.setString(AdminConstants.KEY_CACHE, AdminEntry.listToJson(clean))
                log.debug(TAG, "${raw.size - clean.size} organische Alt-Claims aus dem Admin-Cache entfernt")
            }
            clean
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Dart `_saveToCache`: organic-filtered list plus the current timestamp (ms). */
    private suspend fun saveToCache(admins: List<AdminEntry>) {
        val clean = withoutLegacyOrganic(admins)
        store.edit {
            setString(AdminConstants.KEY_CACHE, AdminEntry.listToJson(clean))
            setInt(AdminConstants.KEY_CACHE_TIMESTAMP, timeSource.nowMillis())
        }
    }

    override suspend fun removeFromCache(npub: String) {
        try {
            writeMutex.withLock {
                val list = loadFromCache()
                if (list.isNullOrEmpty()) return
                val filtered = list.filter { it.npub != npub }
                if (filtered.size != list.size) saveToCache(filtered)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Cache is not critical.
        }
    }

    override suspend fun getAdminList(): List<AdminEntry> = loadFromCache().orEmpty()

    override suspend fun addAdmin(entry: AdminEntry) {
        if (entry.isLegacyOrganicClaim) {
            throw IllegalStateException("Organische Claims werden nicht als Admin übernommen.")
        }
        writeMutex.withLock {
            val list = getAdminList().toMutableList()
            if (list.any { it.npub == entry.npub }) return
            if (!Nip19.isValidNpub(entry.npub)) throw AdminRegistryException("Ungültiger npub.")
            list += entry.copy(addedAt = if (entry.addedAt > 0) entry.addedAt else timeSource.nowSeconds())
            saveToCache(list)
        }
    }

    override suspend fun removeAdmin(npub: String) {
        writeMutex.withLock {
            val list = getAdminList().filter { it.npub != npub }
            saveToCache(list)
        }
    }

    override suspend fun cacheAge(): Duration? {
        val timestamp = store.getInt(AdminConstants.KEY_CACHE_TIMESTAMP) ?: return null
        return (timeSource.nowMillis() - timestamp).milliseconds
    }

    override suspend fun clearCache() {
        store.edit {
            remove(AdminConstants.KEY_CACHE)
            remove(AdminConstants.KEY_CACHE_TIMESTAMP)
        }
    }

    override suspend fun forceRefresh(): Int {
        val relayList = fetchFromRelays() ?: return -1
        saveToCache(relayList)
        isSunsetActive()
        return relayList.size
    }

    // =============================================
    // Personal vouches (legacy)
    // =============================================

    override suspend fun getMyVouches(): List<AdminEntry> = try {
        store.getString(AdminConstants.KEY_MY_VOUCHES)?.let(AdminEntry::listFromJson).orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun saveMyVouches(vouches: List<AdminEntry>) {
        store.setString(AdminConstants.KEY_MY_VOUCHES, AdminEntry.listToJson(vouches))
    }

    override suspend fun addVouch(entry: AdminEntry) {
        writeMutex.withLock {
            val list = getMyVouches().toMutableList()
            if (list.any { it.npub == entry.npub }) throw AdminRegistryException("Du bürgst bereits für diesen npub.")
            if (!Nip19.isValidNpub(entry.npub)) throw AdminRegistryException("Ungültiger npub.")
            if (signer.npub() == entry.npub) throw AdminRegistryException("Du kannst nicht für dich selbst bürgen.")
            list += AdminEntry(entry.npub, entry.meetup, entry.name, timeSource.nowSeconds())
            saveMyVouches(list)
        }
    }

    override suspend fun removeVouch(npub: String) {
        writeMutex.withLock {
            saveMyVouches(getMyVouches().filter { it.npub != npub })
        }
    }

    override suspend fun recoverMyVouchesFromRelays(): Int {
        val myNpub = signer.npub()
        if (myNpub.isNullOrEmpty()) return -1
        val myHex = try {
            Nip19.decodeNpub(myNpub)
        } catch (e: Exception) {
            log.warn(TAG, "Eigener npub nicht dekodierbar: $e")
            return -1
        }
        for (relayUrl in AdminConstants.RELAYS) {
            try {
                // Deliberately the real query, not the test override (as in Dart).
                val result = fetchFromSingleRelay(relayUrl, listOf(myHex)) ?: continue
                val mine = result[myHex].orEmpty()
                // An empty answer never overwrites local, possibly unpublished data.
                if (mine.isNotEmpty()) {
                    writeMutex.withLock { saveMyVouches(mine) }
                    return mine.size
                }
                return 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(TAG, "Recover von $relayUrl fehlgeschlagen: $e")
            }
        }
        return -1
    }

    override suspend fun revokeAllVouches(): String {
        if (!signer.canSign()) throw AdminRegistryException("Kein Nostr-Key vorhanden.")
        writeMutex.withLock { saveMyVouches(emptyList()) }
        return createAndPublishAdminListEvent()
    }

    private suspend fun signAdminList(): space.einundzwanzig.meetup.core.nostr.NostrEvent {
        if (!signer.canSign()) throw AdminRegistryException("Kein Nostr-Key vorhanden.")
        val content = DartJson.encode(
            linkedMapOf(
                "admins" to getMyVouches().map { it.toJsonMap() },
                "updated_at" to DartDateTime.now(timeSource).toIso8601String(zone()),
            ),
        )
        return signer.signEvent(
            AdminConstants.ADMIN_LIST_KIND,
            listOf(listOf("d", AdminConstants.ADMIN_LIST_D_TAG)),
            content,
        )
    }

    override suspend fun createAndPublishAdminListEvent(): String {
        val signed = signAdminList()
        var successCount = 0
        val errors = mutableListOf<String>()
        // Audit M8: wait for the OK (first OK frame, any id; 5 s inactivity), sequentially.
        val options = RelayPublishOptions(
            connectTimeout = 5.seconds,
            ackTimeout = Duration.INFINITE,
            ackInactivityTimeout = 5.seconds,
            matchEventId = false,
        )
        for (relayUrl in AdminConstants.RELAYS) {
            val result = relayClient.publish(relayUrl, signed, options)
            when {
                result.accepted -> {
                    successCount++
                    log.debug(TAG, "Event an $relayUrl bestätigt ✓")
                }
                result.status == RelayPublishStatus.CONNECT_FAILED -> {
                    errors += "$relayUrl: ${result.error ?: result.message}"
                    log.warn(TAG, "$relayUrl fehlgeschlagen: ${result.error ?: result.message}")
                }
                else -> {
                    errors += "$relayUrl: Keine Bestätigung erhalten"
                    log.warn(TAG, "$relayUrl: Event gesendet aber nicht bestätigt")
                }
            }
        }
        if (successCount == 0) {
            throw AdminRegistryException("Event konnte an keinen Relay gesendet werden.\n${errors.joinToString("\n")}")
        }
        return "{\"sent_to\": $successCount, \"event\": ${signed.toJson()}}"
    }

    override suspend fun createAdminListEvent(): String = signAdminList().toJson()

    /** Parses `{"admins":[…]}` like Dart (non-object content, non-list `admins` or a bad entry throws). */
    private fun parseAdminListContent(content: String): List<AdminEntry> {
        val decoded = DartJson.decode(content)
        require(decoded is JsonObject) { "content is not a JSON object" }
        return when (val admins = decoded["admins"]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> admins.map(AdminEntry::fromJson)
            else -> throw IllegalArgumentException("type mismatch: 'admins' is not a List")
        }
    }

    private companion object {
        const val TAG = "AdminRegistry"
        const val QUERY_CHUNK = 50
        const val QUERY_LIMIT = 50
        val RELAY_TIMEOUT = 8.seconds
    }
}
