package space.einundzwanzig.meetup.feature.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkContact
import java.time.Instant
import java.time.ZoneOffset

class NetworkPresentationTest {

    private val labels = object : NetworkPresentation.Labels {
        override val you = "Du"
        override val and = "und"
        override val oneSharedMeetup = "1 gemeinsames Meetup"
        override fun sharedMeetups(count: Int) = "$count gemeinsame Meetups"
        override fun lastAt(place: String) = "zuletzt $place"
        override fun via(names: String) = "über $names"
    }

    private val net = sampleNetwork()

    @Test
    fun `short keeps short npubs and shortens long ones`() {
        assertEquals("npub1c1", NetworkPresentation.short("npub1c1"))
        val long = "npub1" + "a".repeat(58)
        assertEquals("npub1aaaaa…aaaaaaaa", NetworkPresentation.short(long))
    }

    @Test
    fun `initial is the upper-cased first code point of the trimmed name`() {
        assertEquals("A", NetworkPresentation.initialOf("  anna"))
        assertEquals("", NetworkPresentation.initialOf("   "))
        assertEquals("", NetworkPresentation.initialOf(null))
        assertEquals("😀", NetworkPresentation.initialOf("😀 smile"))
    }

    @Test
    fun `stand time is HH mm today and dd MM HH mm otherwise`() {
        val now = Instant.parse("2026-10-21T18:00:00Z").toEpochMilli()
        assertEquals("08:05", NetworkPresentation.standTime(Instant.parse("2026-10-21T08:05:00Z"), now, ZoneOffset.UTC))
        assertEquals("20.10. 23:59", NetworkPresentation.standTime(Instant.parse("2026-10-20T23:59:00Z"), now, ZoneOffset.UTC))
    }

    @Test
    fun `updating degree is clamped to 1 to 3`() {
        assertEquals(1, NetworkPresentation.updatingDegree(0))
        assertEquals(3, NetworkPresentation.updatingDegree(2))
        assertEquals(3, NetworkPresentation.updatingDegree(3))
    }

    @Test
    fun `sections honour filter, search on npub and name, and the preview limit`() {
        val all = NetworkPresentation.sections(net, emptyMap(), 0, "", emptySet())
        assertEquals(listOf(1, 2, 3), all.map { it.degree })
        assertEquals(listOf(2, 2, 1), all.map { it.total })

        val only2 = NetworkPresentation.sections(net, emptyMap(), 2, "", emptySet())
        assertEquals(listOf(2), only2.map { it.degree })

        val byName = NetworkPresentation.sections(net, mapOf(np(4) to "Doris"), 0, "  DOR ", emptySet())
        assertEquals(listOf(np(4)), byName.flatMap { s -> s.shown.map { it.npub } })

        val byNpub = NetworkPresentation.sections(net, emptyMap(), 0, "C5", emptySet())
        assertEquals(listOf(np(5)), byNpub.flatMap { s -> s.shown.map { it.npub } })

        assertTrue(NetworkPresentation.sections(net, emptyMap(), 0, "zzz", emptySet()).isEmpty())

        val big = bigNetwork(25)
        val preview = NetworkPresentation.sections(big, emptyMap(), 0, "", emptySet()).single()
        assertEquals(20, preview.shown.size)
        assertTrue(preview.hasMore)
        val expanded = NetworkPresentation.sections(big, emptyMap(), 0, "", setOf(1)).single()
        assertEquals(25, expanded.shown.size)
        assertFalse(expanded.hasMore)
        val searched = NetworkPresentation.sections(big, emptyMap(), 0, "npub1c", emptySet()).single()
        assertEquals(25, searched.shown.size)
    }

    @Test
    fun `detail line for direct contacts counts shared meetups and names the newest`() {
        val c1 = net.contactsByNpub.getValue(np(1))
        assertEquals("2 gemeinsame Meetups · zuletzt Wien, 12.09.", NetworkPresentation.detailLine(net, c1, emptyMap(), labels))
        val c2 = net.contactsByNpub.getValue(np(2))
        assertEquals("1 gemeinsames Meetup · zuletzt Wien, 12.09.", NetworkPresentation.detailLine(net, c2, emptyMap(), labels))
        val none = NetworkContact("npub1x", 1, emptySet(), emptySet(), ME)
        assertEquals("", NetworkPresentation.detailLine(net, none, emptyMap(), labels))
        val undated = NetworkContact("npub1y", 1, setOf("stammtisch@abc"), emptySet(), ME)
        assertEquals("1 gemeinsames Meetup · zuletzt Stammtisch", NetworkPresentation.detailLine(net, undated, emptyMap(), labels))
    }

    @Test
    fun `detail line for second degree lists bridges by label`() {
        val names = mapOf(np(1) to "Zoe", np(2) to "Anna")
        assertEquals("über Anna und Zoe", NetworkPresentation.detailLine(net, net.contactsByNpub.getValue(np(3)), names, labels))
        assertEquals("über Zoe", NetworkPresentation.detailLine(net, net.contactsByNpub.getValue(np(4)), names, labels))
        val many = NetworkContact("npub1m", 2, emptySet(), setOf(np(1), np(2), np(9)), np(1))
        assertEquals("über Anna, Zoe +1", NetworkPresentation.detailLine(net, many, names, labels))
        val lonely = NetworkContact("npub1l", 2, emptySet(), emptySet(), null)
        assertEquals("", NetworkPresentation.detailLine(net, lonely, names, labels))
    }

    @Test
    fun `detail line for third degree shows the path`() {
        val names = mapOf(np(1) to "Anna")
        assertEquals("über Anna → npub1c3", NetworkPresentation.detailLine(net, net.contactsByNpub.getValue(np(5)), names, labels))
        // Broken path falls back to the bridge rule.
        val orphan = NetworkContact("npub1o", 3, emptySet(), setOf(np(1)), "npub1missing")
        val withOrphan = net.copy(byDegree = net.byDegree + (3 to net.byDegree.getValue(3) + orphan))
        assertEquals("über Anna", NetworkPresentation.detailLine(withOrphan, orphan, names, labels))
    }

    @Test
    fun `label uses you, then name, then short npub`() {
        assertEquals("Du", NetworkPresentation.label(ME, ME, emptyMap(), "Du"))
        assertEquals("Anna", NetworkPresentation.label(np(1), ME, mapOf(np(1) to "Anna"), "Du"))
        assertEquals(np(2), NetworkPresentation.label(np(2), ME, emptyMap(), "Du"))
    }

    @Test
    fun `detail path falls back to me and npub for direct contacts`() {
        assertEquals(listOf(ME, np(1), np(3), np(5)), NetworkPresentation.detailPath(net, np(5)))
        val noParent = MyNetwork(
            ME,
            linkedMapOf(1 to listOf(NetworkContact(np(1), 1, emptySet(), emptySet(), null)), 2 to emptyList(), 3 to emptyList()),
            0,
        )
        assertEquals(listOf(ME, np(1)), NetworkPresentation.detailPath(noParent, np(1)))
        assertEquals(emptyList<String>(), NetworkPresentation.detailPath(noParent, "npub1unknown"))
    }

    @Test
    fun `also via excludes path members and sorts by label`() {
        val names = mapOf(np(1) to "Zoe", np(2) to "Anna")
        val path = NetworkPresentation.detailPath(net, np(3))
        assertEquals(listOf(np(2)), NetworkPresentation.alsoVia(net, np(3), path, names, "Du"))
        assertEquals(listOf(np(2), np(1)), NetworkPresentation.alsoVia(net, np(3), emptyList(), names, "Du"))
        assertEquals(emptyList<String>(), NetworkPresentation.alsoVia(net, "npub1none", emptyList(), names, "Du"))
    }

    @Test
    fun `names to ask skips asked and undecodable npubs and stops at 400`() {
        val asked = setOf(np(1))
        val out = NetworkPresentation.namesToAsk(net, asked) { if (it == np(4)) null else "h$it" }
        assertEquals(listOf(np(2), np(3), np(5)), out.keys.toList())
        assertEquals("h${np(2)}", out[np(2)])
        val big = bigNetwork(450)
        assertEquals(400, NetworkPresentation.namesToAsk(big, emptySet()) { it }.size)
    }

    @Test
    fun `tree layout places me in the centre and at most 8 direct contacts`() {
        val nodes = NetworkPresentation.layoutTree(net, mapOf(np(1) to "anna"), 300f, 300f, 1f)
        val me = nodes.first()
        assertEquals(0, me.degree)
        assertEquals(150f, me.x, 0.001f)
        assertEquals(19f, me.radius, 0.001f)
        // c1 at the top (angle -pi/2), r1 = 0.48 * 150.
        val c1 = nodes.first { it.npub == np(1) }
        assertEquals(150f, c1.x, 0.01f)
        assertEquals(150f - 72f, c1.y, 0.01f)
        assertEquals("A", c1.initial)
        assertEquals(15f, c1.radius, 0.001f)
        // c3, c4 are kids of c1; c5 is a grandkid of c3.
        assertEquals(setOf(np(1), np(2), np(3), np(4), np(5)), nodes.drop(1).map { it.npub }.toSet())
        val c5 = nodes.first { it.npub == np(5) }
        val c3 = nodes.first { it.npub == np(3) }
        assertEquals(c3.x, c5.fromX)
        assertEquals(3.5f, c5.radius, 0.001f)

        assertEquals(1 + 8, NetworkPresentation.layoutTree(bigNetwork(20), emptyMap(), 300f, 300f, 1f).size)
        assertEquals(1, NetworkPresentation.layoutTree(MyNetwork.empty(ME), emptyMap(), 300f, 300f, 1f).size)
    }

    @Test
    fun `hit test finds nearest node except me within tolerance`() {
        val nodes = NetworkPresentation.layoutTree(net, emptyMap(), 300f, 300f, 1f)
        assertNull(NetworkPresentation.hitTest(nodes, 150f, 150f, 24f))
        val c1 = nodes.first { it.npub == np(1) }
        assertEquals(np(1), NetworkPresentation.hitTest(nodes, c1.x + 5f, c1.y + 5f, 24f)?.npub)
        assertNull(NetworkPresentation.hitTest(nodes, 0f, 299f, 24f))
    }

    @Test
    fun `target is the deepest highlighted node`() {
        val nodes = NetworkPresentation.layoutTree(net, emptyMap(), 300f, 300f, 1f)
        assertNull(NetworkPresentation.targetNpub(nodes, emptySet()))
        val path = net.pathTo(np(5)).toSet()
        assertEquals(np(5), NetworkPresentation.targetNpub(nodes, path))
        assertEquals(np(1), NetworkPresentation.targetNpub(nodes, setOf(ME, np(1))))
    }
}
