package space.einundzwanzig.meetup.data.socialgraph.internal

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.socialgraph.coattendance.AttendanceKeys
import space.einundzwanzig.meetup.data.socialgraph.coattendance.CoAttNode
import space.einundzwanzig.meetup.data.socialgraph.coattendance.CoAttendanceRecord
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceConstants.AUTHOR_BATCH
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceConstants.KEY_BATCH
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceConstants.MAX_MEETUPS_PER_STAGE
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceConstants.MAX_PEOPLE_PER_STAGE
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceConstants.PARALLEL_BATCHES
import space.einundzwanzig.meetup.data.socialgraph.internal.CoAttendanceConstants.SINCE_OVERLAP_S
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** All relays' answer to ONE batch (Dart `_BatchAnswer`). */
internal data class BatchAnswer(
    val records: List<CoAttendanceRecord>,
    val anyComplete: Boolean,
    val allComplete: Boolean,
)

/** Result of a crawl (Dart `_GraphRun`); [answered] = at least one batch was answered completely. */
internal data class GraphRun(
    val graph: Map<String, CoAttNode>,
    val knownAuthors: Set<String>,
    val knownKeys: Set<String>,
    val answered: Boolean,
)

/**
 * Staged co-attendance crawl (Dart `_loadGraph`, spec 05 §8.2.7): own attendances → people at
 * those meetups (degree 1) → their other meetups → … up to `maxDepth` stages, incremental with
 * `since` when started from a cache. Batches run with at most [PARALLEL_BATCHES] in flight, each
 * asking all relays in parallel; graph mutations are serialized by a mutex.
 */
internal class GraphCrawler(
    private val relays: suspend () -> List<String>,
    private val fetcher: CoAttendanceFetcher,
    private val log: DiagnosticLog,
) {

    suspend fun loadGraph(
        myNpub: String,
        extraNpubs: List<String> = emptyList(),
        maxDepth: Int = 3,
        cache: GraphCache? = null,
        fullSync: Boolean = true,
        onStage: ((graph: Map<String, CoAttNode>, depth: Int) -> Unit)? = null,
    ): GraphRun {
        val relayList = relays()
        val start = if (cache != null && !fullSync) cache else null
        val incremental = start != null

        val graph = LinkedHashMap<String, CoAttNode>()
        val knownAuthors = LinkedHashSet<String>()
        val knownKeys = LinkedHashSet<String>()
        var since: Long? = null
        if (start != null) {
            for ((k, v) in start.nodes) graph[k] = v.copy()
            knownAuthors.addAll(start.knownAuthors)
            knownKeys.addAll(start.knownKeys)
            since = start.syncAt / 1000 - SINCE_OVERLAP_S
        }

        val mutex = Mutex()
        var batchesTotal = 0
        var batchesAnswered = 0
        var recordsFetched = 0

        fun node(npub: String): CoAttNode = graph.getOrPut(npub) { CoAttNode(npub) }

        fun add(r: CoAttendanceRecord) {
            if (AttendanceKeys.isDegenerateEventId(r.meetupEventId)) return
            val n = node(r.npub)
            if (AttendanceKeys.isEventKey(r.meetupEventId)) n.events.add(r.meetupEventId) else n.meetups.add(r.meetupEventId)
        }

        // NOTE: uses `cache` (not `start`), i.e. also in full-sync mode (Dart).
        fun keepCachedAuthors(npubs: Iterable<String>) {
            if (cache == null) return
            for (n in npubs) {
                val c = cache.nodes[n] ?: continue
                val target = node(n)
                target.meetups.addAll(c.meetups)
                target.events.addAll(c.events)
            }
        }

        fun keepCachedKeys(wanted: Set<String>) {
            if (cache == null) return
            for ((npub, c) in cache.nodes) {
                val hits = c.meetups.filter { it in wanted }
                if (hits.isEmpty()) continue
                node(npub).meetups.addAll(hits)
            }
        }

        suspend fun fetchAuthors(npubs: Iterable<String>) {
            val known = mutableListOf<String>()
            val fresh = mutableListOf<String>()
            for (n in npubs) (if (n in knownAuthors) known else fresh).add(n)
            val tasks = mutableListOf<suspend () -> Unit>()
            fun plan(group: List<String>, sinceArg: Long?) {
                for (part in group.chunked(AUTHOR_BATCH)) {
                    val hexes = part.mapNotNull(::toHex)
                    if (hexes.isEmpty()) continue
                    tasks.add {
                        mutex.withLock { batchesTotal++ }
                        val res = queryRelays(relayList, keys = null, authorsHex = hexes, since = sinceArg)
                        mutex.withLock {
                            res.records.forEach(::add)
                            recordsFetched += res.records.size
                            if (res.anyComplete) batchesAnswered++
                            if (!res.allComplete) keepCachedAuthors(part)
                            if (res.anyComplete) knownAuthors.addAll(part) else knownAuthors.removeAll(part.toSet())
                        }
                    }
                }
            }
            plan(known, since)
            plan(fresh, null)
            runLimited(tasks, PARALLEL_BATCHES)
        }

        suspend fun fetchKeys(keys: Set<String>) {
            val known = mutableListOf<String>()
            val fresh = mutableListOf<String>()
            for (k in keys) (if (k in knownKeys) known else fresh).add(k)
            val tasks = mutableListOf<suspend () -> Unit>()
            fun plan(group: List<String>, sinceArg: Long?) {
                for (chunk in group.chunked(KEY_BATCH)) {
                    val part = chunk.toCollection(LinkedHashSet())
                    tasks.add {
                        mutex.withLock { batchesTotal++ }
                        val res = queryRelays(relayList, keys = part, authorsHex = null, since = sinceArg)
                        mutex.withLock {
                            res.records.forEach(::add)
                            recordsFetched += res.records.size
                            if (res.anyComplete) batchesAnswered++
                            if (!res.allComplete) keepCachedKeys(AttendanceKeys.wantedKeys(part))
                            if (res.anyComplete) knownKeys.addAll(part) else knownKeys.removeAll(part)
                        }
                    }
                }
            }
            plan(known, since)
            plan(fresh, null)
            runLimited(tasks, PARALLEL_BATCHES)
        }

        fun peopleAt(keys: Set<String>): Set<String> {
            val byBase = LinkedHashMap<String, MutableList<String>>()
            for (q in keys) byBase.getOrPut(AttendanceKeys.base(q)) { mutableListOf() }.add(q)
            val out = LinkedHashSet<String>()
            for ((npub, n) in graph) {
                for (k in n.meetups) {
                    val qs = byBase[AttendanceKeys.base(k)]
                    if (qs != null && qs.any { AttendanceKeys.keysMatch(k, it) }) {
                        out.add(npub)
                        break
                    }
                }
            }
            return out
        }

        fun meetupKeysOf(npubs: Iterable<String>): Set<String> {
            val out = LinkedHashSet<String>()
            for (n in npubs) graph[n]?.meetups?.let(out::addAll)
            return out
        }

        val seeds = LinkedHashSet<String>().apply {
            add(myNpub)
            addAll(extraNpubs)
        }.filter { toHex(it) != null }
        if (seeds.isEmpty() || relayList.isEmpty()) {
            return GraphRun(graph, knownAuthors, knownKeys, answered = false)
        }

        fetchAuthors(seeds)

        val seenPeople = LinkedHashSet<String>(seeds)
        val seenKeys = LinkedHashSet<String>()
        var frontierKeys: Set<String> = meetupKeysOf(seeds)

        log.diag(
            LOG_TAG,
            "Stufe 0: ${frontierKeys.size} eigene Meetups (${if (incremental) "nur Neues seit letztem Abruf" else "vollstaendig"}).",
        )

        for (depth in 1..maxDepth) {
            frontierKeys = frontierKeys.filterTo(LinkedHashSet()) { it !in seenKeys }
            if (frontierKeys.isEmpty()) break

            var keys = frontierKeys
            if (keys.size > MAX_MEETUPS_PER_STAGE) {
                log.warn(LOG_TAG, "Grad $depth: ${keys.size} Meetups, begrenzt auf $MAX_MEETUPS_PER_STAGE.")
                keys = keys.take(MAX_MEETUPS_PER_STAGE).toCollection(LinkedHashSet())
            }
            seenKeys.addAll(keys)

            fetchKeys(keys)

            var newPeople: Set<String> = peopleAt(keys).filterTo(LinkedHashSet()) { it !in seenPeople }
            if (newPeople.size > MAX_PEOPLE_PER_STAGE) {
                log.warn(LOG_TAG, "Grad $depth: ${newPeople.size} Personen, begrenzt auf $MAX_PEOPLE_PER_STAGE.")
                newPeople = newPeople.take(MAX_PEOPLE_PER_STAGE).toCollection(LinkedHashSet())
            }
            seenPeople.addAll(newPeople)

            log.diag(LOG_TAG, "Grad $depth: ${keys.size} Meetups abgefragt, ${newPeople.size} neue Personen.")

            onStage?.invoke(graph, depth)

            if (depth == maxDepth || newPeople.isEmpty()) break

            fetchAuthors(newPeople)
            frontierKeys = meetupKeysOf(newPeople)
        }

        log.diag(
            LOG_TAG,
            "Abruf: $batchesAnswered von $batchesTotal Paketen beantwortet, " +
                "$recordsFetched Eintraege geladen, ${graph.size} Personen im Graphen.",
        )
        return GraphRun(graph, knownAuthors, knownKeys, answered = batchesAnswered > 0)
    }

    /** Dart `_queryRelays`: all relays in parallel, de-duplicated by `npub|key` (first wins). */
    private suspend fun queryRelays(
        relays: List<String>,
        keys: Set<String>?,
        authorsHex: List<String>?,
        since: Long?,
    ): BatchAnswer {
        val answers = coroutineScope {
            relays.map { url ->
                async {
                    try {
                        fetcher.fetch(url, keys, authorsHex, since)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(RelayCoAttendanceFetcher.TAG, "Fetch-Fehler $url: ${describeError(e)}")
                        null
                    }
                }
            }.awaitAll()
        }
        return aggregate(relays.isNotEmpty(), answers)
    }

    companion object {
        private const val LOG_TAG = "Netzwerk"

        /** Merges per-relay answers (Dart `_queryRelays` tail). */
        fun aggregate(hasRelays: Boolean, answers: List<RelayAnswer?>): BatchAnswer {
            val seen = HashSet<String>()
            val out = mutableListOf<CoAttendanceRecord>()
            var any = false
            var all = hasRelays
            for (a in answers) {
                if (a == null) {
                    all = false
                    continue
                }
                if (a.complete) any = true else all = false
                for (r in a.records) if (seen.add("${r.npub}|${r.meetupEventId}")) out.add(r)
            }
            return BatchAnswer(out, anyComplete = any, allComplete = all)
        }

        /** npub → hex; `null` if it does not decode (or is not an npub). */
        fun toHex(npub: String): String? = try {
            Nip19.decodeNpub(npub).ifEmpty { null }
        } catch (_: Exception) {
            null
        }

        /** Dart `_runLimited`: up to [max] workers take tasks in order. */
        suspend fun runLimited(tasks: List<suspend () -> Unit>, max: Int) {
            if (tasks.isEmpty()) return
            val next = AtomicInteger(0)
            coroutineScope {
                repeat(minOf(max, tasks.size)) {
                    launch {
                        while (true) {
                            val i = next.getAndIncrement()
                            if (i >= tasks.size) break
                            tasks[i]()
                        }
                    }
                }
            }
        }
    }
}
