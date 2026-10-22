package space.einundzwanzig.meetup.data.socialgraph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkCalculator
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkContact
import java.time.Instant

/** Spec 05 §10.2 BFS network vector, pathTo, sharedBetween, mergeStage, presence check. */
class NetworkCalculatorTest {

    private val me = npubOf(1)
    private val anna = npubOf(2)
    private val ben = npubOf(3)
    private val carla = npubOf(4)
    private val dora = npubOf(5)
    private val emil = npubOf(6)
    private val fred = npubOf(7)
    private val gus = npubOf(8)

    private fun vectorGraph() = graphOf(
        me to listOf("a-2026-01-01@S1", "b-2026-02-01@S1"),
        anna to listOf("a-2026-01-01@S1", "b-2026-02-01", "c-2026-03-01@S2"),
        ben to listOf("a-2026-01-01@S2", "d-2026-04-01@S3"),
        carla to listOf("c-2026-03-01@S2"),
        dora to listOf("c-2026-03-01", "e-2026-05-01@S4"),
        emil to listOf("e-2026-05-01@S4"),
        fred to listOf("e-2026-05-01@S4", "f-2026-06-01@S5"),
        gus to listOf("f-2026-06-01@S5"),
    )

    @Test
    fun `BFS vector - degrees, bridges, parents`() {
        val net = NetworkCalculator.networkFromGraph(me, vectorGraph(), 3)

        assertEquals(listOf(anna), net.byDegree[1]!!.map { it.npub })
        val a = net.byDegree[1]!!.single()
        assertEquals(listOf("a-2026-01-01@S1", "b-2026-02-01"), a.sharedMeetupsWithMe.toList())
        assertEquals(me, a.parent)
        assertTrue(a.bridges.isEmpty())

        assertEquals(listOf(carla, dora), net.byDegree[2]!!.map { it.npub })
        for (c in net.byDegree[2]!!) {
            assertEquals(setOf(anna), c.bridges)
            assertEquals(anna, c.parent)
            assertTrue(c.sharedMeetupsWithMe.isEmpty())
        }
        assertEquals(listOf(emil, fred), net.byDegree[3]!!.map { it.npub })
        for (c in net.byDegree[3]!!) {
            assertEquals(setOf(anna), c.bridges)
            assertEquals(dora, c.parent)
        }

        assertFalse(net.contactsByNpub.containsKey(ben))
        assertFalse(net.contactsByNpub.containsKey(gus))
        assertEquals(2, net.myMeetupCount)
        assertEquals(5, net.totalReach)
        assertEquals(listOf(me, anna, dora, emil), net.pathTo(emil))
        assertEquals(listOf("c-2026-03-01"), net.sharedBetween(anna, dora))
        assertEquals(listOf(1, 2, 3), net.byDegree.keys.toList())
        assertEquals(listOf(me, anna, carla, dora, emil, fred), net.meetupsOf.keys.toList())
        assertEquals(mapOf(anna to 1, carla to 2, dora to 2, emil to 3, fred to 3), net.degreeOf)
    }

    @Test
    fun `BFS with smaller maxDepth and an empty graph`() {
        val net = NetworkCalculator.networkFromGraph(me, vectorGraph(), 2)
        assertEquals(3, net.totalReach)
        assertTrue(net.byDegree[3]!!.isEmpty())

        val empty = NetworkCalculator.networkFromGraph(me, emptyMap(), 3)
        assertTrue(empty.isEmpty)
        assertEquals(listOf(1, 2, 3), empty.byDegree.keys.toList())
        assertEquals(setOf(me), empty.meetupsOf.keys)
    }

    @Test
    fun `degree 1 sorted by shared count, then newest shared meetup`() {
        val x = npubOf(20)
        val y = npubOf(21)
        val z = npubOf(22)
        val graph = graphOf(
            me to listOf("a-2026-01-01", "b-2026-05-01", "c-2026-03-01"),
            x to listOf("a-2026-01-01"),
            y to listOf("b-2026-05-01"),
            z to listOf("a-2026-01-01", "c-2026-03-01"),
        )
        val net = NetworkCalculator.networkFromGraph(me, graph, 3)
        assertEquals(listOf(z, y, x), net.byDegree[1]!!.map { it.npub })
        // The strongest neighbor is visited first and becomes the parent of shared people.
        assertEquals(me, net.contactsByNpub.getValue(z).parent)
    }

    @Test
    fun `events never create edges`() {
        val x = npubOf(30)
        val graph = graphOf(me to listOf("ev|big-2026-01-01"), x to listOf("ev|big-2026-01-01"))
        assertTrue(NetworkCalculator.networkFromGraph(me, graph, 3).isEmpty)
        assertTrue(NetworkCalculator.adjacency(graph).isEmpty())
    }

    @Test
    fun `pathTo is capped at four parent hops`() {
        val c = (40..45).map(::npubOf)
        // c0 <- c1 <- … <- c5, c0's parent is me: c5 needs 6 hops.
        val contacts = c.mapIndexed { i, n -> NetworkContact(n, minOf(i + 1, 3), emptySet(), emptySet(), if (i == 0) me else c[i - 1]) }
        val net = MyNetwork(me, linkedMapOf(1 to contacts.take(1), 2 to contacts.drop(1).take(1), 3 to contacts.drop(2)), 0)
        assertEquals(listOf(me, c[0], c[1], c[2], c[3]), net.pathTo(c[3]))
        assertEquals(emptyList<String>(), net.pathTo(c[4]))
        assertEquals(emptyList<String>(), net.pathTo(npubOf(99)))
    }

    @Test
    fun `mergeStage - a person moved up appears only in the fresh degree`() {
        val x = npubOf(50)
        val y = npubOf(51)
        val before = Instant.ofEpochMilli(1_700_000_000_000)
        val previous = MyNetwork(
            me,
            linkedMapOf(
                1 to emptyList(),
                2 to listOf(NetworkContact(x, 2, emptySet(), setOf(y), y), NetworkContact(y, 2, emptySet(), emptySet(), me)),
                3 to emptyList(),
            ),
            1,
            updatedAt = before,
            meetupsOf = mapOf(x to setOf("old"), y to setOf("y")),
        )
        val fresh = MyNetwork(
            me,
            linkedMapOf(1 to listOf(NetworkContact(x, 1, setOf("k"), emptySet(), me)), 2 to emptyList(), 3 to emptyList()),
            2,
            meetupsOf = mapOf(x to setOf("new")),
        )
        val merged = NetworkCalculator.mergeStage(fresh, previous, 1)
        assertEquals(listOf(x), merged.byDegree[1]!!.map { it.npub })
        assertEquals(listOf(y), merged.byDegree[2]!!.map { it.npub })
        assertEquals(before, merged.updatedAt)
        assertEquals(2, merged.myMeetupCount)
        assertEquals(setOf("new"), merged.meetupsOf[x])
        assertEquals(setOf("y"), merged.meetupsOf[y])
        assertEquals(listOf(1, 2, 3), merged.byDegree.keys.toList())
        assertEquals(fresh, NetworkCalculator.mergeStage(fresh, null, 1))
    }

    @Test
    fun `presence check on the vector graph`() {
        val g = vectorGraph()
        val toEmil = NetworkCalculator.presenceCheck(g, me, emil, 6)
        assertEquals(3, toEmil.degree)
        assertEquals(listOf(me, anna, dora, emil), toEmil.path)
        val toGus = NetworkCalculator.presenceCheck(g, me, gus, 6)
        assertEquals(4, toGus.degree)
        assertEquals(listOf(me, anna, dora, fred, gus), toGus.path)
        assertEquals(-1, NetworkCalculator.presenceCheck(g, me, gus, 3).degree)
        val toBen = NetworkCalculator.presenceCheck(g, me, ben, 6)
        assertEquals(-1, toBen.degree)
        assertTrue(toBen.path.isEmpty())
        assertTrue(toBen.targetInNetwork)
        assertEquals(2, toBen.targetTotalMeetups)
        val toAnna = NetworkCalculator.presenceCheck(g, me, anna, 6)
        assertTrue(toAnna.isDirect)
        assertEquals(setOf("a-2026-01-01@S1", "b-2026-02-01"), toAnna.sharedMeetups)
        val self = NetworkCalculator.presenceCheck(g, me, me, 6)
        assertTrue(self.isSelf)
        assertEquals(listOf(me), self.path)
        assertNull(NetworkCalculator.shortestPath(emptyMap(), me, anna, 6))
    }

    @Test
    fun `analyze counts mutual contacts and target reach including me`() {
        val g = vectorGraph()
        val res = NetworkCalculator.analyze(g, me, carla)
        assertTrue(res.sharedMeetups.isEmpty())
        assertEquals(listOf(anna), res.mutualContacts)
        assertTrue(res.hasAnyConnection)
        assertFalse(res.hasDirectOverlap)
        assertEquals(1, res.targetTotalMeetups)
        assertEquals(2, res.targetTotalContacts) // anna, dora
        val withMe = NetworkCalculator.analyze(g, me, anna)
        assertEquals(1 + 2, withMe.targetTotalContacts) // me, carla, dora
    }
}
