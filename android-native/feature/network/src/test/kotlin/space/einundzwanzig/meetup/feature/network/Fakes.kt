package space.einundzwanzig.meetup.feature.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkBuildEvent
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkContact
import space.einundzwanzig.meetup.feature.network.data.NetworkDataSource
import java.time.Instant

internal class FakeTime(var millis: Long) : TimeSource {
    override fun nowMillis(): Long = millis
}

internal class FakeNetworkSource : NetworkDataSource {
    var npub: String = ME
    var cached: MyNetwork? = null
    var stages: List<NetworkBuildEvent> = emptyList()
    var buildError: Exception? = null

    /** If set, the build suspends on this gate before emitting anything. */
    var gate: CompletableDeferred<Unit>? = null
    var failed: Int = 0
    var fixed: Int = 0
    var cachedNames: Map<String, String> = emptyMap()
    var fetchedNames: Map<String, String> = emptyMap()

    /** If set, `fetchDisplayNames` suspends on this gate (a slow relay round-trip). */
    var namesGate: CompletableDeferred<Unit>? = null

    val builds = mutableListOf<Boolean>()
    val fetchRequests = mutableListOf<List<String>>()
    var retries = 0

    override suspend fun myNpub(): String = npub
    override suspend fun cachedNetwork(myNpub: String): MyNetwork? = cached

    override fun buildNetwork(myNpub: String, forceFull: Boolean): Flow<NetworkBuildEvent> = flow {
        builds += forceFull
        gate?.await()
        buildError?.let { throw it }
        stages.forEach { emit(it) }
    }

    override suspend fun failedCount(): Int = failed

    override suspend fun retryFailed(): Int {
        retries++
        return fixed
    }

    override suspend fun cachedDisplayNames(): Map<String, String> = cachedNames

    override suspend fun fetchDisplayNames(hexes: Collection<String>): Map<String, String> {
        fetchRequests += hexes.toList()
        namesGate?.await()
        return fetchedNames
    }

    override fun npubToHex(npub: String): String? = if (npub.startsWith("npub1")) "hex:" + npub.removePrefix("npub1") else null
}

internal const val ME = "npub1me"
internal fun np(i: Int) = "npub1c$i"
internal fun hex(i: Int) = "hex:c$i"

/**
 * me — c1 (shared wien-2026-09-12, graz-2026-05-01), c2 (shared wien-2026-09-12)
 * c3 (2nd, via c1 and c2, parent c1), c4 (2nd, via c1, parent c1)
 * c5 (3rd, parent c3, bridges c1)
 */
internal fun sampleNetwork(updatedAt: Instant? = Instant.parse("2026-10-21T08:15:00Z"), stale: Boolean = false): MyNetwork {
    val wien = "wien-2026-09-12@abc"
    val graz = "graz-2026-05-01@abc"
    val linz = "linz-2026-07-03@abc"
    val d1 = listOf(
        NetworkContact(np(1), 1, setOf(graz, wien), emptySet(), ME),
        NetworkContact(np(2), 1, setOf(wien), emptySet(), ME),
    )
    val d2 = listOf(
        NetworkContact(np(3), 2, emptySet(), setOf(np(1), np(2)), np(1)),
        NetworkContact(np(4), 2, emptySet(), setOf(np(1)), np(1)),
    )
    val d3 = listOf(NetworkContact(np(5), 3, emptySet(), setOf(np(1)), np(3)))
    return MyNetwork(
        ME,
        linkedMapOf(1 to d1, 2 to d2, 3 to d3),
        myMeetupCount = 2,
        updatedAt = updatedAt,
        stale = stale,
        meetupsOf = mapOf(
            ME to setOf(wien, graz),
            np(1) to setOf(wien, graz, linz),
            np(2) to setOf(wien),
            np(3) to setOf(linz),
            np(4) to setOf(linz),
            np(5) to emptySet(),
        ),
    )
}

internal fun bigNetwork(direct: Int): MyNetwork {
    val d1 = (1..direct).map { NetworkContact(np(it), 1, emptySet(), emptySet(), ME) }
    return MyNetwork(ME, linkedMapOf(1 to d1, 2 to emptyList(), 3 to emptyList()), 1)
}
