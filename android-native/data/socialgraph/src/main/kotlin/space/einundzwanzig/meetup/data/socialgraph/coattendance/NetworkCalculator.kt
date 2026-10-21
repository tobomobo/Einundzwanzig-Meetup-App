package space.einundzwanzig.meetup.data.socialgraph.coattendance

import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.badges.util.DartSort
import java.time.Instant

/**
 * Pure graph algorithms of the co-attendance network (spec 05 §8.2.8–§8.2.12): adjacency, BFS
 * degrees with bridges and parents, the stage merge and the presence-check path search.
 * Ports of `_adjacency`, `_networkFromGraph`, `_mergeStage`, the BFS of `verifyPerson` and
 * `analyze` in `coattendance_service.dart`. Sorting uses [DartSort] (Dart `List.sort` order).
 */
object NetworkCalculator {

    private const val LOG_TAG = "Netzwerk"

    /**
     * Undirected adjacency: A–B if they share a meetup key under [AttendanceKeys.keysMatch].
     * Only meetups (never events) create edges. Neighbor sets keep insertion order.
     */
    fun adjacency(nodes: Map<String, CoAttNode>): Map<String, Set<String>> {
        val groups = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for ((npub, node) in nodes) {
            for (k in node.meetups) {
                groups.getOrPut(AttendanceKeys.base(k)) { mutableListOf() }.add(npub to k)
            }
        }
        val adj = LinkedHashMap<String, MutableSet<String>>()
        for (g in groups.values) {
            for (i in g.indices) {
                for (j in i + 1 until g.size) {
                    val a = g[i]
                    val b = g[j]
                    if (a.first == b.first) continue
                    if (!AttendanceKeys.keysMatch(a.second, b.second)) continue
                    adj.getOrPut(a.first) { LinkedHashSet() }.add(b.first)
                    adj.getOrPut(b.first) { LinkedHashSet() }.add(a.first)
                }
            }
        }
        return adj
    }

    /**
     * Dart `_networkFromGraph`: BFS from [myNpub] up to [maxDepth]; direct neighbors are visited
     * strongest first (most shared meetups), degree-2 bridges are the degree-1 parents, deeper
     * bridges are inherited. With [log], writes the Dart diagnostic lines to [logger].
     */
    fun networkFromGraph(
        myNpub: String,
        nodes: Map<String, CoAttNode>,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        updatedAt: Instant? = null,
        log: Boolean = false,
        logger: DiagnosticLog = AppLogger,
    ): MyNetwork {
        val adj = adjacency(nodes)
        val myMeetups: Set<String> = nodes[myNpub]?.meetups ?: emptySet()

        val degree = LinkedHashMap<String, Int>().apply { put(myNpub, 0) }
        val bridges = HashMap<String, MutableSet<String>>()
        val parent = HashMap<String, String>()
        val queue = ArrayDeque<String>().apply { add(myNpub) }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val curDeg = degree.getValue(current)
            if (curDeg >= maxDepth) continue
            var neighbors: Collection<String> = adj[current] ?: emptySet()
            if (curDeg == 0) {
                val strength = neighbors.associateWith {
                    AttendanceKeys.sharedKeys(nodes[it]?.meetups ?: emptySet(), myMeetups).size
                }
                neighbors = DartSort.sorted(neighbors) { a, b -> strength.getValue(b).compareTo(strength.getValue(a)) }
                    .toCollection(LinkedHashSet())
            }
            for (neighbor in neighbors) {
                if (!degree.containsKey(neighbor)) {
                    degree[neighbor] = curDeg + 1
                    parent[neighbor] = current
                    queue.add(neighbor)
                }
                if (curDeg == 0) {
                    // A direct neighbor is its own bridge.
                } else if (degree[neighbor] == curDeg + 1) {
                    if (curDeg == 1) {
                        bridges.getOrPut(neighbor) { LinkedHashSet() }.add(current)
                    } else {
                        val inherited = bridges[current]
                        if (inherited != null) bridges.getOrPut(neighbor) { LinkedHashSet() }.addAll(inherited)
                    }
                }
            }
        }

        val byDegree = LinkedHashMap<Int, MutableList<NetworkContact>>()
        for (d in 1..3) byDegree[d] = mutableListOf()
        for ((npub, deg) in degree) {
            if (deg == 0 || deg > maxDepth) continue
            val shared = if (deg == 1) AttendanceKeys.sharedKeys(nodes[npub]?.meetups ?: emptySet(), myMeetups) else emptySet()
            byDegree.getOrPut(deg) { mutableListOf() }.add(
                NetworkContact(
                    npub = npub,
                    degree = deg,
                    sharedMeetupsWithMe = shared,
                    bridges = if (deg == 1) emptySet() else bridges[npub]?.toCollection(LinkedHashSet()) ?: emptySet(),
                    parent = parent[npub],
                ),
            )
        }

        if (log) logDiagnostics(logger, myNpub, nodes, myMeetups, byDegree)

        byDegree[1]?.let { list ->
            DartSort.sort(list) { a, b ->
                val c = b.sharedMeetupsWithMe.size.compareTo(a.sharedMeetupsWithMe.size)
                if (c != 0) {
                    c
                } else {
                    AttendanceKeyLabel.newestOrYearZero(b.sharedMeetupsWithMe)
                        .compareTo(AttendanceKeyLabel.newestOrYearZero(a.sharedMeetupsWithMe))
                }
            }
        }
        byDegree[2]?.let { list -> DartSort.sort(list) { a, b -> b.bridges.size.compareTo(a.bridges.size) } }
        byDegree[3]?.let { list -> DartSort.sort(list) { a, b -> b.bridges.size.compareTo(a.bridges.size) } }

        val meetupsOf = LinkedHashMap<String, Set<String>>()
        for ((n, d) in degree) {
            if (d <= maxDepth) meetupsOf[n] = LinkedHashSet(nodes[n]?.meetups ?: emptySet())
        }

        return MyNetwork(
            myNpub = myNpub,
            byDegree = byDegree.mapValuesTo(LinkedHashMap()) { it.value.toList() },
            myMeetupCount = myMeetups.size,
            updatedAt = updatedAt,
            meetupsOf = meetupsOf,
        )
    }

    private fun logDiagnostics(
        logger: DiagnosticLog,
        myNpub: String,
        nodes: Map<String, CoAttNode>,
        myMeetups: Set<String>,
        byDegree: Map<Int, List<NetworkContact>>,
    ) {
        logger.diag(LOG_TAG, "Eigene Meetup-Kennungen (${myMeetups.size}): ${myMeetups.joinToString(", ")}")
        val d1 = byDegree[1].orEmpty()
        logger.diag(
            LOG_TAG,
            "${nodes.size} Teilnehmer im Graphen, davon ${d1.size} im 1. Grad, " +
                "${byDegree[2].orEmpty().size} im 2. Grad, " +
                "${byDegree[3].orEmpty().size} im 3. Grad.",
        )
        if (d1.isEmpty() && nodes.isNotEmpty()) {
            val foreign = LinkedHashSet<String>()
            for ((npub, node) in nodes) {
                if (npub == myNpub) continue
                foreign.addAll(node.meetups)
                if (foreign.size >= 15) break
            }
            logger.diag(LOG_TAG, "Keine Treffer. Fremde Kennungen (Stichprobe): ${foreign.take(15).joinToString(", ")}")
        }
        for (c in d1) {
            logger.diag(
                LOG_TAG,
                "1. Grad ${c.npub.substring(0, minOf(16, c.npub.length))}… ueber: ${c.sharedMeetupsWithMe.joinToString(", ")}",
            )
        }
    }

    /**
     * Dart `_mergeStage`: degrees up to [doneDepth] from [fresh], deeper ones from [previous]
     * without anyone already placed. `updatedAt` stays the previous one; `meetupsOf` is merged
     * (fresh wins).
     */
    fun mergeStage(fresh: MyNetwork, previous: MyNetwork?, doneDepth: Int): MyNetwork {
        if (previous == null) return fresh
        val placed = HashSet<String>()
        val out = LinkedHashMap<Int, List<NetworkContact>>()
        for (d in 1..3) {
            if (d > doneDepth) continue
            val list = fresh.byDegree[d] ?: emptyList()
            out[d] = list
            list.mapTo(placed) { it.npub }
        }
        for (d in 1..3) {
            if (d <= doneDepth) continue
            val list = (previous.byDegree[d] ?: emptyList()).filter { it.npub !in placed }
            out[d] = list
            list.mapTo(placed) { it.npub }
        }
        val sorted = LinkedHashMap<Int, List<NetworkContact>>()
        for (d in out.keys.sorted()) sorted[d] = out.getValue(d)
        return MyNetwork(
            myNpub = fresh.myNpub,
            byDegree = sorted,
            myMeetupCount = fresh.myMeetupCount,
            updatedAt = previous.updatedAt,
            meetupsOf = LinkedHashMap(previous.meetupsOf).apply { putAll(fresh.meetupsOf) },
        )
    }

    /**
     * Shortest path `[from, …, to]` over [adj] by BFS on paths (Dart `verifyPerson`); paths longer
     * than [maxDepth] hops are not expanded. `null` if [from] has no edges or [to] is unreachable.
     */
    fun shortestPath(adj: Map<String, Set<String>>, from: String, to: String, maxDepth: Int): List<String>? {
        if (!adj.containsKey(from)) return null
        val visited = hashSetOf(from)
        val queue = ArrayDeque<List<String>>().apply { add(listOf(from)) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur.size - 1 > maxDepth) continue
            val last = cur.last()
            if (last == to) return cur
            for (n in adj[last] ?: emptySet()) {
                if (visited.add(n)) queue.add(cur + n)
            }
        }
        return null
    }

    /** Dart `verifyPerson` on an already loaded graph. */
    fun presenceCheck(nodes: Map<String, CoAttNode>, myNpub: String, targetNpub: String, maxDepth: Int): PresenceCheck {
        val myMeetups = nodes[myNpub]?.meetups ?: emptySet()
        val targetMeetups = nodes[targetNpub]?.meetups ?: emptySet()
        val shared = AttendanceKeys.sharedKeys(myMeetups, targetMeetups)
        val targetInNetwork = nodes.containsKey(targetNpub)
        if (myNpub == targetNpub) {
            return PresenceCheck(targetNpub, 0, listOf(myNpub), shared, targetInNetwork, targetMeetups.size)
        }
        val path = shortestPath(adjacency(nodes), myNpub, targetNpub, maxDepth)
        return PresenceCheck(
            targetNpub = targetNpub,
            degree = if (path == null) -1 else path.size - 1,
            path = path ?: emptyList(),
            sharedMeetups = shared,
            targetInNetwork = targetInNetwork,
            targetTotalMeetups = targetMeetups.size,
        )
    }

    /** Dart `analyze` on an already loaded graph. */
    fun analyze(nodes: Map<String, CoAttNode>, myNpub: String, targetNpub: String): CoAttNetwork {
        val myMeetups = nodes[myNpub]?.meetups ?: emptySet()
        val targetMeetups = nodes[targetNpub]?.meetups ?: emptySet()
        val shared = AttendanceKeys.sharedKeys(myMeetups, targetMeetups)
        val mutual = mutableListOf<String>()
        for ((npub, node) in nodes) {
            if (npub == myNpub || npub == targetNpub) continue
            val withMe = AttendanceKeys.sharedKeys(node.meetups, myMeetups).isNotEmpty()
            val withTarget = AttendanceKeys.sharedKeys(node.meetups, targetMeetups).isNotEmpty()
            if (withMe && withTarget) mutual.add(npub)
        }
        val targetContacts = LinkedHashSet<String>()
        for ((npub, node) in nodes) {
            if (npub == targetNpub) continue
            if (AttendanceKeys.sharedKeys(node.meetups, targetMeetups).isNotEmpty()) targetContacts.add(npub)
        }
        return CoAttNetwork(
            myNpub = myNpub,
            targetNpub = targetNpub,
            sharedMeetups = shared,
            mutualContacts = mutual,
            nodes = nodes,
            targetTotalMeetups = targetMeetups.size,
            targetTotalContacts = targetContacts.size,
        )
    }

    /** Counts of new / closer / farther / gone people between two states (Dart `_logChanges`). */
    internal fun logChanges(before: MyNetwork?, after: MyNetwork, logger: DiagnosticLog) {
        if (before == null) return
        val b = before.degreeOf
        val a = after.degreeOf
        var added = 0
        var closer = 0
        var farther = 0
        var gone = 0
        for ((npub, d) in a) {
            val old = b[npub]
            when {
                old == null -> added++
                d < old -> closer++
                d > old -> farther++
            }
        }
        for (npub in b.keys) if (!a.containsKey(npub)) gone++
        logger.diag(
            LOG_TAG,
            "Gegenueber Cache: $added neu, $closer naeher gerueckt, $farther weiter weg, $gone nicht mehr im Netzwerk.",
        )
    }

    /** Default crawl / BFS depth of the own network. */
    const val DEFAULT_MAX_DEPTH = 3

    /** BFS depth of [presenceCheck] (the graph is still loaded to depth 3). */
    const val VERIFY_MAX_DEPTH = 6
}
