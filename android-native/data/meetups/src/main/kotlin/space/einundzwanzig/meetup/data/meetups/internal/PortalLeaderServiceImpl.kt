package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.data.meetups.portal.PortalLeaderService
import space.einundzwanzig.meetup.data.meetups.portal.PortalLeaderService.Companion.CACHE_KEY
import space.einundzwanzig.meetup.data.meetups.portal.PortalLeaderService.Companion.CACHE_TS_KEY
import space.einundzwanzig.meetup.data.meetups.portal.PortalLeaderService.Companion.TTL_MS
import space.einundzwanzig.meetup.data.meetups.portal.PortalUrls
import javax.inject.Inject
import javax.inject.Singleton

/** Port of Dart `PortalLeaderService` (spec 09 §8.10). */
@Singleton
internal class PortalLeaderServiceImpl @Inject constructor(
    private val client: OkHttpClient,
    private val prefs: KeyValueStore,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    @IoDispatcher private val io: CoroutineDispatcher = Dispatchers.IO,
) : PortalLeaderService {

    private val mutex = Mutex()

    @Volatile
    private var leaders: Map<Int, Set<String>> = emptyMap()

    @Volatile
    private var loadedAt: Long? = null

    /**
     * Number of finished network attempts. A caller that queued on [mutex] while another caller
     * fetched skips its own fetch (Dart ran concurrent calls in parallel, so the worst case was one
     * timeout, not one per queued caller).
     */
    @Volatile
    private var attempts: Long = 0

    override suspend fun ensureLoaded(force: Boolean) {
        val seen = attempts
        mutex.withLock {
            if (!force && attempts != seen) return
            try {
                load(force)
            } finally {
                attempts++
            }
        }
    }

    private suspend fun load(force: Boolean) {
        val at = loadedAt
        if (!force && leaders.isNotEmpty() && at != null && time.nowMillis() - at < TTL_MS) return
        if (leaders.isEmpty()) loadFromCache()

        val r = client.getCappedOn(io, PortalUrls.LEADERS_URL, maxBytes = MAX_BYTES, timeout = PortalUrls.LEADERS_TIMEOUT)
        if (r !is HttpResult.Response) {
            log.warn(TAG, "Leader-Liste nicht abrufbar: ${(r as? HttpResult.Failure)?.cause ?: r}")
            return
        }
        if (r.status != 200) {
            log.warn(TAG, "Portal antwortete mit HTTP ${r.status}.")
            return
        }
        val body = r.text()
        val parsed = runCatching { parse(body, normalize = true) }.getOrElse {
            log.warn(TAG, "Leader-Liste nicht abrufbar: $it")
            return
        }
        // Never adopt an empty answer: it can be an outage, and then every organizer would be unknown.
        if (parsed.isEmpty()) {
            log.warn(TAG, "Leere Leader-Liste — alter Stand bleibt.")
            return
        }
        val now = time.nowMillis()
        leaders = parsed
        loadedAt = now
        prefs.edit {
            setString(CACHE_KEY, body)
            setInt(CACHE_TS_KEY, now)
        }
        log.info(TAG, "${parsed.size} Meetups mit Leadern geladen (${parsed.values.sumOf { it.size }} Eintraege).")
    }

    private suspend fun loadFromCache() {
        val raw = prefs.getString(CACHE_KEY) ?: return
        val ts = prefs.getInt(CACHE_TS_KEY) ?: return
        val parsed = runCatching { parse(raw, normalize = false) }.getOrNull() ?: return
        if (parsed.isEmpty()) return
        leaders = parsed
        loadedAt = ts
        log.debug(TAG, "${parsed.size} Meetups aus dem Speicher.")
    }

    override suspend fun isLeaderOf(meetupId: Int, npub: String): Boolean? {
        if (npub.isEmpty()) return null
        ensureLoaded()
        val current = leaders
        if (current.isEmpty()) return null
        // Meetups without leaders are not listed by the portal: a missing entry is a clear "no".
        val set = current[meetupId] ?: return false
        return npub.dartTrim() in set
    }

    override fun meetupCountFor(npub: String): Int {
        val current = leaders
        if (npub.isEmpty() || current.isEmpty()) return 0
        val n = npub.dartTrim()
        return current.values.count { n in it }
    }

    companion object {
        private const val TAG = "PortalLeader"
        private const val MAX_BYTES = 8L * 1024 * 1024

        /**
         * Parses `[{meetup_id:int, npubs:[String]}]`. The network path ([normalize]) trims npubs,
         * drops empty ones and skips empty sets; the cache path keeps the strings as stored.
         * Entries with a non-int id or non-list npubs are skipped; a non-list body throws.
         */
        internal fun parse(body: String, normalize: Boolean): Map<Int, Set<String>> {
            val data = DartJson.decode(body) as? JsonArray ?: throw IllegalStateException("keine Liste")
            val out = LinkedHashMap<Int, Set<String>>()
            for (e in data) {
                if (e !is JsonObject) continue
                val id = e["meetup_id"].dartInt() ?: continue
                val npubs = e["npubs"] as? JsonArray ?: continue
                val strings = npubs.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (normalize) {
                    val set = strings.map { it.dartTrim() }.filter { it.isNotEmpty() }.toSet()
                    if (set.isNotEmpty()) out[id] = set
                } else {
                    out[id] = strings.toSet()
                }
            }
            return out
        }
    }
}
