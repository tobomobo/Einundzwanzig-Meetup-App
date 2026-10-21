package space.einundzwanzig.meetup.data.profile.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.NostrEvents
import space.einundzwanzig.meetup.core.nostr.relay.ProfileRelayQuery
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.data.profile.NostrProfileLookup
import space.einundzwanzig.meetup.data.profile.NostrProfileLookup.Companion.INDEX_RELAY
import space.einundzwanzig.meetup.data.profile.NostrProfileLookup.Companion.MAX_AUTHOR_RELAYS
import space.einundzwanzig.meetup.data.profile.NostrProfileLookup.Companion.MAX_EVENTS_PER_RELAY
import space.einundzwanzig.meetup.data.profile.NostrProfileLookup.Companion.MAX_INITIAL_RELAYS
import space.einundzwanzig.meetup.data.profile.NostrProfileLookup.Companion.pictureUrl
import space.einundzwanzig.meetup.data.profile.NostrProfileLookup.Companion.relayUrl
import space.einundzwanzig.meetup.data.profile.ProfileCacheKeys

/**
 * Port of `NostrProfileLookup._fetch` (spec 07 §8.3). The shared lookups run in [scope]
 * (application scope) so a cancelled caller does not cancel the lookup of a coalesced caller.
 */
internal class DefaultNostrProfileLookup(
    private val relays: suspend () -> List<String>,
    private val query: ProfileRelayQuery,
    private val time: TimeSource,
    private val store: KeyValueStore,
    private val scope: CoroutineScope,
) : NostrProfileLookup {

    private val lock = Any()
    private val pending = HashMap<String, Deferred<String?>>()
    private var generation = 0

    override suspend fun fetchPicture(pubkeyHex: String): String? {
        if (!HEX64.matches(pubkeyHex)) return null
        val deferred = synchronized(lock) {
            pending[pubkeyHex] ?: run {
                val gen = generation
                scope.async { fetch(pubkeyHex, gen) }.also { job ->
                    pending[pubkeyHex] = job
                    job.invokeOnCompletion {
                        synchronized(lock) {
                            if (gen == generation && pending[pubkeyHex] === job) pending.remove(pubkeyHex)
                        }
                    }
                }
            }
        }
        return deferred.await()
    }

    override fun invalidate() {
        synchronized(lock) {
            generation++
            pending.clear()
        }
    }

    private fun isCurrent(gen: Int): Boolean = synchronized(lock) { gen == generation }

    private suspend fun fetch(pubkey: String, gen: Int): String? {
        val key = ProfileCacheKeys.metadata(pubkey)
        var cached: NostrEvent? = null
        var fetchedAt = 0L
        run {
            val value = decodeOrNull(store.getString(key)) as? JsonObject ?: return@run
            cached = validEvent(value["event"], pubkey, time.nowMillis())?.takeIf { it.kind == 0 }
            // Dart `value['fetchedAt'] as int` throws for other types: fetchedAt stays 0.
            fetchedAt = value["fetchedAt"].dartIntOrNull() ?: return@run
        }
        val now = time.nowMillis()
        cached?.let { if (isFresh(fetchedAt, now)) return picture(it) }

        val legacy = pictureUrl(store.getString(ProfileCacheKeys.legacyPicture(pubkey)))
        val legacyAt = store.getInt(ProfileCacheKeys.legacyPictureTime(pubkey)) ?: 0L
        if (cached == null && legacy != null && isFresh(legacyAt, now)) return legacy

        val configured = try {
            relays()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RelayConfig.DEFAULT_RELAYS
        }
        // Keep one slot for the discovery index even with many app relays.
        val initial = LinkedHashSet<String>().apply {
            configured.mapNotNull { relayUrl(it) }.filter { it != INDEX_RELAY }.distinct()
                .take(MAX_INITIAL_RELAYS - 1).forEach(::add)
            add(INDEX_RELAY)
        }

        val relayKey = ProfileCacheKeys.relays(pubkey)
        val cachedRelays = validEvent(decodeOrNull(store.getString(relayKey)), pubkey, now)?.takeIf { it.kind == 10002 }

        val events = queryAll(initial, pubkey, now).toMutableList()
        val relayList = newest(listOfNotNull(cachedRelays) + events.filter { it.kind == 10002 })
        if (relayList != null) {
            val authorRelays = LinkedHashSet<String>()
            for (tag in relayList.tags) {
                if (tag.size < 2 || tag[0] != "r") continue
                if (tag.size > 2 && tag[2] != "write") continue
                val relay = relayUrl(tag[1], discovered = true)
                if (relay != null && relay !in initial) authorRelays += relay
                if (authorRelays.size == MAX_AUTHOR_RELAYS) break
            }
            events += queryAll(authorRelays, pubkey, now)
        }
        // Newer hints from author relays are kept for the next lookup (no recursion here).
        val latestRelays = newest(listOfNotNull(cachedRelays) + events.filter { it.kind == 10002 })
        if (latestRelays != null && isCurrent(gen)) {
            store.setString(relayKey, latestRelays.toJson())
        }

        val latest = newest(listOfNotNull(cached) + events.filter { it.kind == 0 })
        val receivedProfile = events.any { it.kind == 0 }
        if (latest != null && receivedProfile && isCurrent(gen)) {
            val json = DartJson.encode(linkedMapOf("event" to latest.toMap(), "fetchedAt" to time.nowMillis()))
            store.edit {
                setString(key, json)
                // A known signed profile must never revive an old URL.
                remove(ProfileCacheKeys.legacyPicture(pubkey))
                remove(ProfileCacheKeys.legacyPictureTime(pubkey))
            }
        }
        return if (latest != null) picture(latest) else legacy
    }

    private suspend fun queryAll(relays: Collection<String>, pubkey: String, now: Long): List<NostrEvent> =
        coroutineScope {
            relays.map { relay ->
                async {
                    try {
                        query.query(relay, pubkey).take(MAX_EVENTS_PER_RELAY).mapNotNull { validEvent(it, pubkey, now) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

    private fun isFresh(at: Long, now: Long): Boolean {
        val age = now - at
        return at > 0 && age >= 0 && age < NostrProfileLookup.CACHE_DURATION.inWholeMilliseconds
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        private fun decodeOrNull(text: String?): JsonElement? =
            if (text == null) null else runCatching { DartJson.decode(text) }.getOrNull()

        /**
         * Dart `_validEvent`: an object by [pubkey] (exact) of kind 0 or 10002 with an int
         * `created_at` in `0..now`, valid id and BIP-340 signature (all fields typed), and for
         * kind 0 a content that decodes to a JSON object.
         */
        internal fun validEvent(raw: JsonElement?, pubkey: String, nowMillis: Long): NostrEvent? {
            if (raw !is JsonObject) return null
            if (raw["pubkey"].dartStringOrNull() != pubkey) return null
            val kind = raw["kind"].dartIntOrNull()
            if (kind != 0L && kind != 10002L) return null
            val at = raw["created_at"].dartIntOrNull() ?: return null
            if (at < 0 || at > Math.floorDiv(nowMillis, 1000L)) return null
            val event = NostrEvents.fromJsonOrNull(raw) ?: return null
            if (!event.isValid()) return null
            if (event.kind == 0 && decodeOrNull(event.content) !is JsonObject) return null
            return event
        }

        /** NIP-01: highest `created_at`; equal → lexicographically smallest id. */
        internal fun newest(events: Iterable<NostrEvent>): NostrEvent? {
            var best: NostrEvent? = null
            for (event in events) {
                val b = best
                if (b == null || event.createdAt > b.createdAt || (event.createdAt == b.createdAt && event.id < b.id)) {
                    best = event
                }
            }
            return best
        }

        private fun picture(event: NostrEvent): String? =
            (decodeOrNull(event.content) as? JsonObject)?.get("picture")?.let { pictureUrl(it) }
    }
}
