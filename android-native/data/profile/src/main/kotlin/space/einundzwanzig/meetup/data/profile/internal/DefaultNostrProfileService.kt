package space.einundzwanzig.meetup.data.profile.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.json.jsonArrayOf
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.core.nostr.relay.RelayFanOut
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.SubscriptionIds
import space.einundzwanzig.meetup.data.profile.NostrProfileLookup
import space.einundzwanzig.meetup.data.profile.NostrProfileService
import space.einundzwanzig.meetup.data.profile.NostrProfileService.Companion.NAMES_BATCH
import space.einundzwanzig.meetup.data.profile.NostrProfileService.Companion.NAMES_MAX_AGE
import space.einundzwanzig.meetup.data.profile.NostrProfileService.Companion.NAME_TIMEOUT
import space.einundzwanzig.meetup.data.profile.NostrProfileService.Companion.REPUTATION_D_TAG
import space.einundzwanzig.meetup.data.profile.NostrProfileService.Companion.REPUTATION_KIND
import space.einundzwanzig.meetup.data.profile.ProfileCacheKeys
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Port of `NostrProfileService` (spec 07 §8.4) on [RelayClient] and the shared [KeyValueStore].
 * Single-name lookups run in [scope] (application scope) and are coalesced per pubkey.
 */
internal class DefaultNostrProfileService @Inject constructor(
    private val lookup: NostrProfileLookup,
    private val relayConfig: RelayConfig,
    private val relayClient: RelayClient,
    private val store: KeyValueStore,
    private val time: TimeSource,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
    private val log: DiagnosticLog = AppLogger,
) : NostrProfileService {

    /** Session cache of [fetchDisplayName]; [Name.value] `null` = asked, nothing found. */
    private val nameCache = ConcurrentHashMap<String, Name>()

    private class Name(val value: String?)

    /** `(created_at, name)` of one answer. */
    private data class Hit(val at: Long, val name: String)

    override suspend fun fetchProfilePicture(pubkeyHex: String): String? = lookup.fetchPicture(pubkeyHex)

    // ---------------------------------------------------------------- single name

    /**
     * One in-flight [fetchDisplayName] lookup. [awaiters] counts the callers currently waiting for
     * it; [started] turns true once the lookup holds a [nameLimiter] permit. Both guarded by [namesLock].
     */
    private class PendingName {
        lateinit var job: Deferred<String?>
        var awaiters = 0
        var started = false
    }

    /** In-flight [fetchDisplayName] lookups by pubkey, guarded by [namesLock]. */
    private val namesLock = Any()
    private val pendingNames = HashMap<String, PendingName>()

    /** Bounds concurrent single-name lookups (each fans out to every relay) across all chips. */
    private val nameLimiter = Semaphore(MAX_PARALLEL_NAME_LOOKUPS)

    override suspend fun fetchDisplayName(pubkeyHex: String): String? {
        if (pubkeyHex.isEmpty()) return null
        nameCache[pubkeyHex]?.let { return it.value }
        // Several chips with the same npub share one lookup. The body runs in the application
        // scope so a cancelled caller does not cancel the lookup of the others.
        val pending = synchronized(namesLock) {
            nameCache[pubkeyHex]?.let { return it.value }
            (pendingNames[pubkeyHex] ?: newPendingName(pubkeyHex)).also { it.awaiters++ }
        }
        try {
            return pending.job.await()
        } finally {
            synchronized(namesLock) {
                pending.awaiters--
                // Nobody waits any more (e.g. the chip scrolled away) and the lookup is still
                // queued for a permit: drop it so chips on screen are not stuck behind it.
                // A cancelled lookup writes no nameCache entry; the next caller starts afresh.
                if (pending.awaiters == 0 && !pending.started) {
                    if (pendingNames[pubkeyHex] === pending) pendingNames.remove(pubkeyHex)
                    pending.job.cancel()
                }
            }
        }
    }

    /** Creates and registers the lookup for [pubkeyHex]; caller holds [namesLock]. */
    private fun newPendingName(pubkeyHex: String): PendingName {
        val pending = PendingName()
        pendingNames[pubkeyHex] = pending
        pending.job = scope.async {
            lookupDisplayName(pubkeyHex) { synchronized(namesLock) { pending.started = true } }
        }
        pending.job.invokeOnCompletion {
            synchronized(namesLock) { if (pendingNames[pubkeyHex] === pending) pendingNames.remove(pubkeyHex) }
        }
        return pending
    }

    /**
     * Dart `fetchDisplayName` body; writes [nameCache] before the pending entry is removed.
     * [onStart] runs once the [nameLimiter] permit is held.
     */
    private suspend fun lookupDisplayName(pubkeyHex: String, onStart: () -> Unit): String? = nameLimiter.withPermit {
        onStart()
        nameCache[pubkeyHex]?.let { return@withPermit it.value }
        var found: String? = null
        try {
            val relays = relayConfig.getActiveRelays()
            // 1. kind 0: newest across relays (strictly newer; first relay wins ties).
            //    A newer profile without a name clears the name; no nickname fallback then.
            var bestAt = -1L
            var sawProfile = false
            val profileFilter = jsonObjectOf("kinds" to listOf(0), "authors" to listOf(pubkeyHex), "limit" to 1)
            for (hit in perRelayNewest(relays, profileFilter, "nam", ::profileNameOrEmpty)) {
                if (hit != null && hit.at > bestAt) {
                    bestAt = hit.at
                    found = hit.name
                    sawProfile = true
                }
            }
            if (sawProfile && found.isNullOrEmpty()) found = null

            // 2. The app publishes no kind 0: the nickname of the reputation event.
            if (!sawProfile) {
                bestAt = -1L
                found = null
                val nickFilter = jsonObjectOf(
                    "kinds" to listOf(REPUTATION_KIND),
                    "authors" to listOf(pubkeyHex),
                    "#d" to listOf(REPUTATION_D_TAG),
                    "limit" to 1,
                )
                for (hit in perRelayNewest(relays, nickFilter, "nick") { usableNickname(it) ?: "" }) {
                    if (hit != null && hit.at > bestAt) {
                        bestAt = hit.at
                        found = hit.name
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Without a name the shortened npub stays — no reason to fail.
        }
        nameCache[pubkeyHex] = Name(found)
        found
    }

    /**
     * Dart `_fetchNameFromRelay` / `_fetchNicknameFromRelay` for every relay: the newest usable
     * answer per relay (`created_at >= best` replaces, i.e. later arrival wins ties), `null` for
     * relays without one. Relays are queried in parallel; the list is in relay order, so folding
     * it reproduces Dart's sequential loop exactly.
     */
    private suspend fun perRelayNewest(
        relays: List<String>,
        filter: JsonObject,
        prefix: String,
        parse: (String) -> String,
    ): List<Hit?> {
        val results = relayClient.queryAll(
            relays,
            listOf(filter),
            RelayQueryOptions(
                connectTimeout = NAME_TIMEOUT,
                responseTimeout = NAME_TIMEOUT,
                subscriptionId = SubscriptionIds.secure(prefix),
                logTag = TAG,
            ),
            RelayFanOut.PARALLEL,
        )
        return results.map { result ->
            var best: Hit? = null
            for (event in result.events) {
                val at = EventGuard.replaceableCreatedAt(event, time) ?: continue
                if (best != null && at < best.at) continue
                val name = try {
                    parse(event["content"].dartStringOrNull() ?: "")
                } catch (_: Exception) {
                    continue // unparsable content: event ignored, as in Dart
                }
                best = Hit(at, name)
            }
            best
        }
    }

    // ---------------------------------------------------------------- batch names

    override suspend fun cachedDisplayNames(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val raw = store.getString(ProfileCacheKeys.DISPLAY_NAMES)
            if (raw != null) {
                val map = DartJson.decode(raw) as JsonObject
                for ((hex, v) in map) {
                    val n = (v as? JsonObject)?.get("n").dartStringOrNull() ?: continue
                    out[hex] = n
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        // The single lookup (NpubChip) benefits too.
        for ((hex, name) in out) nameCache.putIfAbsent(hex, Name(name))
        return out
    }

    override suspend fun fetchDisplayNames(pubkeysHex: Collection<String>): Map<String, String> {
        val wanted = pubkeysHex.filterTo(LinkedHashSet()) { it.isNotEmpty() }
        val result = LinkedHashMap<String, String>()
        if (wanted.isEmpty()) return result

        val stored = readStoredNames()

        val now = time.nowMillis()
        val missing = ArrayList<String>()
        for (hex in wanted) {
            val s = stored[hex]
            val name = s?.get("n").dartStringOrNull()
            val at = s?.get("t").dartIntOrNull()
            if (!name.isNullOrEmpty()) {
                result[hex] = name
                nameCache.putIfAbsent(hex, Name(name))
                // Fresh enough: do not ask again.
                if (at != null && now - at < NAMES_MAX_AGE.inWholeMilliseconds) continue
            } else {
                val known = nameCache[hex]
                if (known != null) {
                    // Already asked in this session without a result.
                    if (!known.value.isNullOrEmpty()) result[hex] = known.value
                    continue
                }
            }
            missing += hex
        }
        if (missing.isEmpty()) return result

        val relays = try {
            relayConfig.getActiveRelays()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (relays.isEmpty()) return result

        // 1. kind 0 profiles, 2. reputation nicknames for everyone without a profile event.
        val profiles = LinkedHashMap<String, String>()
        for (part in missing.chunked(NAMES_BATCH)) {
            mergeNewest(batchAll(relays, part, listOf(0), null) { profileNameOrNull(it) }, profiles)
        }
        val noProfile = missing.filter { it !in profiles }
        val fresh = LinkedHashMap<String, String>()
        for (hex in missing) {
            val name = profiles[hex]
            if (!name.isNullOrEmpty()) fresh[hex] = name
        }
        for (part in noProfile.chunked(NAMES_BATCH)) {
            mergeNewest(batchAll(relays, part, listOf(REPUTATION_KIND), REPUTATION_D_TAG, ::usableNickname), fresh)
        }

        // Only this call's changes; they are applied to a fresh read of the stored map below.
        val updates = LinkedHashMap<String, JsonObject>()
        val removals = HashSet<String>()
        for (hex in missing) {
            val name = fresh[hex]
            if (!name.isNullOrEmpty()) {
                result[hex] = name
                nameCache[hex] = Name(name)
                updates[hex] = jsonObjectOf("n" to name, "t" to now)
            } else if (profiles[hex]?.isEmpty() == true) {
                // The newest profile removed the name: the stored old one must go.
                result.remove(hex)
                nameCache[hex] = Name(null)
                removals += hex
            } else {
                // Nothing found: a stored name stays (a silent relay deletes nothing).
                nameCache.putIfAbsent(hex, Name(null))
            }
        }
        // Read-merge-write under a lock so overlapping calls do not drop each other's names.
        // A write failure is swallowed like Dart's `try { prefs.setString } catch (_) {}`.
        try {
            namesStoreLock.withLock {
                val current = readStoredNames()
                current.putAll(updates)
                for (hex in removals) current.remove(hex)
                store.setString(ProfileCacheKeys.DISPLAY_NAMES, DartJson.encode(JsonObject(current)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        log.diag(TAG, "Namen: ${missing.size} abgefragt, ${fresh.size} gefunden.")
        return result
    }

    /** Serializes the read-merge-write of [ProfileCacheKeys.DISPLAY_NAMES] in [fetchDisplayNames]. */
    private val namesStoreLock = Mutex()

    /** The stored `nostr_display_names_v1` map (non-object entries dropped); empty if absent or unreadable. */
    private suspend fun readStoredNames(): LinkedHashMap<String, JsonObject> {
        val stored = LinkedHashMap<String, JsonObject>()
        try {
            store.getString(ProfileCacheKeys.DISPLAY_NAMES)?.let { raw ->
                for ((k, v) in DartJson.decode(raw) as JsonObject) {
                    if (v is JsonObject) stored[k] = v
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            stored.clear()
        }
        return stored
    }

    /** `_batchNames` on every relay in parallel; answers in relay order. */
    private suspend fun batchAll(
        relays: List<String>,
        authors: List<String>,
        kinds: List<Int>,
        dTag: String?,
        parse: (String) -> String?,
    ): List<Map<String, Hit>> {
        val filter = if (dTag == null) {
            jsonObjectOf("kinds" to kinds, "authors" to authors, "limit" to authors.size * 2)
        } else {
            jsonObjectOf("kinds" to kinds, "authors" to authors, "limit" to authors.size * 2, "#d" to jsonArrayOf(dTag))
        }
        val results = relayClient.queryAll(
            relays,
            listOf(filter),
            RelayQueryOptions(
                connectTimeout = NAME_TIMEOUT,
                responseTimeout = NAME_TIMEOUT,
                subscriptionId = SubscriptionIds.secure("names"),
                logTag = TAG,
            ),
            RelayFanOut.PARALLEL,
        )
        return results.map { result ->
            val out = LinkedHashMap<String, Hit>()
            for (event in result.events) {
                val at = EventGuard.replaceableCreatedAt(event, time) ?: continue
                val hex = event["pubkey"].dartStringOrNull().orEmpty()
                if (hex.isEmpty()) continue
                // An empty name counts too: otherwise an older event would revive a removed name.
                val name = try {
                    parse(event["content"].dartStringOrNull() ?: "") ?: ""
                } catch (_: Exception) {
                    continue
                }
                val cur = out[hex]
                if (cur == null || at > cur.at) out[hex] = Hit(at, name)
            }
            out
        }
    }

    /** Per person the strictly newest answer across relays (first answer wins ties), then `putIfAbsent`. */
    private fun mergeNewest(answers: List<Map<String, Hit>>, into: MutableMap<String, String>) {
        val best = LinkedHashMap<String, Hit>()
        for (answer in answers) {
            for ((hex, hit) in answer) {
                val cur = best[hex]
                if (cur == null || hit.at > cur.at) best[hex] = hit
            }
        }
        for ((hex, hit) in best) into.putIfAbsent(hex, hit.name)
    }

    // ---------------------------------------------------------------- local picture / reset

    override suspend fun setLocalPicture(path: String) {
        store.setString(ProfileCacheKeys.LOCAL_PICTURE, path)
    }

    override suspend fun getLocalPicture(): String? = store.getString(ProfileCacheKeys.LOCAL_PICTURE)

    override suspend fun getExistingLocalPicture(): String? {
        val path = getLocalPicture()?.takeIf { it.isNotEmpty() } ?: return null
        return withContext(ioDispatcher) { path.takeIf { File(it).isFile } }
    }

    override fun observeLocalPicture(): Flow<String?> = store.observeString(ProfileCacheKeys.LOCAL_PICTURE)

    override suspend fun clearCache() {
        lookup.invalidate()
        store.edit {
            removeByPrefix(ProfileCacheKeys.LEGACY_PICTURE_PREFIX)
            removeByPrefix(ProfileCacheKeys.METADATA_PREFIX)
            removeByPrefix(ProfileCacheKeys.RELAYS_PREFIX)
            remove(ProfileCacheKeys.LOCAL_PICTURE)
        }
    }

    companion object {
        const val TAG: String = "NostrProfile"

        /**
         * At most this many single-name lookups query the relays at the same time. High enough
         * that a typical screen of chips resolves in one or two lookup rounds (Dart ran every
         * chip at once); list screens should still prefetch via `fetchDisplayNames`.
         */
        internal const val MAX_PARALLEL_NAME_LOOKUPS = 10

        /** Dart `profile['x'] as String?`: absent/null → null, string → value, else throws. */
        private fun optString(o: JsonObject, key: String): String? {
            val v: JsonElement? = o[key]
            if (v == null || v is JsonNull) return null
            return v.dartStringOrNull() ?: throw IllegalArgumentException("$key is not a String")
        }

        private fun contentObject(content: String): JsonObject =
            DartJson.decode(content) as? JsonObject ?: throw IllegalArgumentException("content is not an object")

        /** `_fetchNameFromRelay`: trimmed `display_name` if non-empty, else trimmed `name` or `""`. Throws on bad content. */
        internal fun profileNameOrEmpty(content: String): String {
            val profile = contentObject(content)
            val n = optString(profile, "display_name")?.dartTrim()
            val alt = optString(profile, "name")?.dartTrim()
            return if (!n.isNullOrEmpty()) n else alt ?: ""
        }

        /** `_nameFromProfile`: like [profileNameOrEmpty] but `null` instead of `""`. Throws on bad content. */
        internal fun profileNameOrNull(content: String): String? {
            val profile = contentObject(content)
            val n = optString(profile, "display_name")?.dartTrim()
            val alt = optString(profile, "name")?.dartTrim()
            if (!n.isNullOrEmpty()) return n
            if (!alt.isNullOrEmpty()) return alt
            return null
        }

        /**
         * `_nameFromReputation`: trimmed `identity.nickname` unless empty or "anon"
         * (case-insensitive), else `null`. Throws on bad content.
         */
        internal fun usableNickname(content: String): String? {
            val body = contentObject(content)
            val identity = body["identity"]
            val nick = if (identity is JsonObject) optString(identity, "nickname")?.dartTrim() else null
            if (nick.isNullOrEmpty() || nick.lowercase() == "anon") return null
            return nick
        }
    }
}
