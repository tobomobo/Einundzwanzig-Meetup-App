package space.einundzwanzig.meetup.data.socialgraph.coattendance

import java.time.Instant

/**
 * One attendance loaded from a relay (Dart `CoAttendanceRecord`).
 *
 * @property npub author npub.
 * @property meetupEventId the attendance **key** (`content.event`).
 * @property attendedAt `content.t` (unix seconds) if it is an int, else `0`.
 */
data class CoAttendanceRecord(
    val npub: String,
    val meetupEventId: String,
    val attendedAt: Long,
)

/**
 * A person in the co-attendance graph (Dart `CoAttNode`). Mutable while a graph is built;
 * both sets keep insertion order.
 *
 * @property meetups meetup keys (edges are built from these only).
 * @property events event keys (`ev|…`), never used for edges.
 */
class CoAttNode(val npub: String) {
    val meetups: MutableSet<String> = LinkedHashSet()
    val events: MutableSet<String> = LinkedHashSet()

    /** Meetups followed by events. */
    val all: Set<String> get() = LinkedHashSet<String>(meetups).apply { addAll(events) }

    /** Independent deep copy. */
    fun copy(): CoAttNode = CoAttNode(npub).also {
        it.meetups.addAll(meetups)
        it.events.addAll(events)
    }

    override fun toString(): String = "CoAttNode($npub, meetups=$meetups, events=$events)"
}

/**
 * Network between the user and one target (Dart `CoAttNetwork`, result of
 * [CoAttendanceRepository.analyze]; no app callers in Flutter).
 */
data class CoAttNetwork(
    val myNpub: String,
    val targetNpub: String,
    val sharedMeetups: Set<String>,
    val mutualContacts: List<String>,
    val nodes: Map<String, CoAttNode>,
    val targetTotalMeetups: Int,
    val targetTotalContacts: Int,
) {
    val hasDirectOverlap: Boolean get() = sharedMeetups.isNotEmpty()
    val hasAnyConnection: Boolean get() = sharedMeetups.isNotEmpty() || mutualContacts.isNotEmpty()
}

/**
 * A person in the user's own network (Dart `NetworkContact`).
 *
 * @property degree 1..maxDepth.
 * @property sharedMeetupsWithMe only for degree 1: `sharedKeys(their meetups, my meetups)`.
 * @property bridges degree-1 npubs through which this person is reached; empty for degree 1.
 * @property parent predecessor on one shortest path (the user for degree 1).
 */
data class NetworkContact(
    val npub: String,
    val degree: Int,
    val sharedMeetupsWithMe: Set<String>,
    val bridges: Set<String>,
    val parent: String? = null,
)

/**
 * The user's network grouped by degree (Dart `MyNetwork`).
 *
 * @property byDegree keys 1, 2, 3 always present (lists may be empty), ascending.
 * @property myMeetupCount number of the user's meetup keys (events not counted).
 * @property updatedAt start of the fetch the data comes from; `null` for stage results.
 * @property stale the refresh failed and this is the saved state.
 * @property meetupsOf meetup keys per person (the user and every contact within maxDepth).
 */
data class MyNetwork(
    val myNpub: String,
    val byDegree: Map<Int, List<NetworkContact>>,
    val myMeetupCount: Int,
    val updatedAt: Instant? = null,
    val stale: Boolean = false,
    val meetupsOf: Map<String, Set<String>> = emptyMap(),
) {
    /** Copy with [stale] = `true`. */
    fun markStale(): MyNetwork = copy(stale = true)

    /** All contacts by npub, iterated in degree order (1, 2, 3) and list order within a degree. */
    val contactsByNpub: Map<String, NetworkContact> by lazy {
        val out = LinkedHashMap<String, NetworkContact>()
        for (list in byDegree.values) for (c in list) out[c.npub] = c
        out
    }

    /**
     * Path from the user to [npub] along the BFS parents, e.g. `[me, anna, carla]`; empty if it
     * cannot be completed within 4 parent hops.
     */
    fun pathTo(npub: String): List<String> {
        val chain = mutableListOf(npub)
        var cur = npub
        for (i in 0 until 4) {
            val p = contactsByNpub[cur]?.parent ?: break
            chain.add(p)
            if (p == myNpub) break
            cur = p
        }
        if (chain.last() != myNpub) return emptyList()
        return chain.asReversed().toList()
    }

    /** Shared meetups of two people (from [meetupsOf]), newest first. */
    fun sharedBetween(a: String, b: String): List<String> =
        AttendanceKeyLabel.newestFirst(AttendanceKeys.sharedKeys(meetupsOf[a] ?: emptySet(), meetupsOf[b] ?: emptySet()))

    /** Degree per npub. */
    val degreeOf: Map<String, Int>
        get() {
            val out = LinkedHashMap<String, Int>()
            for ((d, list) in byDegree) for (c in list) out[c.npub] = d
            return out
        }

    val degree1Count: Int get() = byDegree[1]?.size ?: 0
    val degree2Count: Int get() = byDegree[2]?.size ?: 0
    val degree3Count: Int get() = byDegree[3]?.size ?: 0
    val totalReach: Int get() = degree1Count + degree2Count + degree3Count
    val isEmpty: Boolean get() = totalReach == 0

    companion object {
        /** An empty network (`{1: [], 2: [], 3: []}`). */
        fun empty(myNpub: String, stale: Boolean = false): MyNetwork =
            MyNetwork(myNpub, linkedMapOf(1 to emptyList(), 2 to emptyList(), 3 to emptyList()), 0, stale = stale)
    }
}

/**
 * Presence check towards one person (Dart `PresenceCheck`, [CoAttendanceRepository.verifyPerson]).
 *
 * @property degree 0 = the user, 1 = direct, 2.. = via a chain, -1 = no connection.
 * @property path `[myNpub, …, targetNpub]`, empty when not found.
 * @property targetInNetwork the target has any co-attendance node at all.
 */
data class PresenceCheck(
    val targetNpub: String,
    val degree: Int,
    val path: List<String>,
    val sharedMeetups: Set<String>,
    val targetInNetwork: Boolean,
    val targetTotalMeetups: Int,
) {
    val found: Boolean get() = degree >= 0
    val isDirect: Boolean get() = degree == 1
    val isSelf: Boolean get() = degree == 0
}

/** Progress of [CoAttendanceRepository.buildMyNetwork]. */
sealed interface NetworkBuildEvent {
    /** Stage [depth] (1..3) finished; [network] is the intermediate network to show. */
    data class Stage(val network: MyNetwork, val depth: Int) : NetworkBuildEvent

    /** The build finished; [network] is final (possibly [MyNetwork.stale]). Always the last event. */
    data class Done(val network: MyNetwork) : NetworkBuildEvent
}
