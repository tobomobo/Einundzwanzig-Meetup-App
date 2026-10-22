package space.einundzwanzig.meetup.data.socialgraph

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.socialgraph.internal.GraphCache

/** Spec 05 §4 graph cache format and §10.2 round-trip. */
class GraphCacheTest {

    private val a = npubOf(1)
    private val b = npubOf(2)

    private fun sample() = GraphCache(
        owner = a,
        fullSyncAt = 1_700_000_000_000,
        syncAt = 1_700_000_500_000,
        knownAuthors = linkedSetOf(a, b),
        knownKeys = linkedSetOf("k-2026-01-01@X"),
        nodes = graphOf(a to listOf("k-2026-01-01@X", "ev|e-2026-01-01"), b to listOf("k-2026-01-01")),
    )

    @Test
    fun `exact JSON with e omitted when empty`() {
        assertEquals(
            """{"v":1,"owner":"$a","full":1700000000000,"sync":1700000500000,"ka":["$a","$b"],"kk":["k-2026-01-01@X"],""" +
                """"n":{"$a":{"m":["k-2026-01-01@X"],"e":["ev|e-2026-01-01"]},"$b":{"m":["k-2026-01-01"]}}}""",
            sample().encode(),
        )
    }

    @Test
    fun `round trip`() {
        val back = GraphCache.fromJson(DartJson.decode(sample().encode()))!!
        assertEquals(a, back.owner)
        assertEquals(1_700_000_000_000, back.fullSyncAt)
        assertEquals(1_700_000_500_000, back.syncAt)
        assertEquals(listOf(a, b), back.knownAuthors.toList())
        assertEquals(listOf("k-2026-01-01@X"), back.knownKeys.toList())
        assertEquals(listOf("k-2026-01-01@X"), back.nodes.getValue(a).meetups.toList())
        assertEquals(listOf("ev|e-2026-01-01"), back.nodes.getValue(a).events.toList())
        assertTrue(back.nodes.getValue(b).events.isEmpty())
    }

    @Test
    fun `version and type rules`() {
        assertNull(GraphCache.fromJson(DartJson.decode("""{"v":2,"owner":"x"}""")))
        assertNull(GraphCache.fromJson(DartJson.decode("""{"owner":"x"}""")))
        // Dart: 1.0 == 1.
        assertNotNull(GraphCache.fromJson(DartJson.decode("""{"v":1.0}""")))
        val lenient = GraphCache.fromJson(
            DartJson.decode("""{"v":1,"owner":5,"full":1.5,"sync":"x","ka":[1,"a",null],"kk":"no","n":{"p":3,"q":{"m":[2.0]}}}"""),
        )!!
        assertEquals("5", lenient.owner)
        assertEquals(0L, lenient.fullSyncAt)
        assertEquals(0L, lenient.syncAt)
        assertEquals(listOf("1", "a", "null"), lenient.knownAuthors.toList())
        assertTrue(lenient.knownKeys.isEmpty())
        assertTrue(lenient.nodes.getValue("p").meetups.isEmpty())
        assertEquals(listOf("2.0"), lenient.nodes.getValue("q").meetups.toList())
    }

    @Test
    fun `read - missing, unreadable, valid`() = runTest {
        val log = RecordingLog()
        val store = InMemoryKeyValueStore()
        assertNull(GraphCache.read(store, log))
        store.setString(GraphCache.KEY, "{kaputt")
        assertNull(GraphCache.read(store, log))
        assertTrue(log.lines.any { it.startsWith("WARN Netzwerk Cache nicht lesbar") })
        store.setString(GraphCache.KEY, "[]")
        assertNull(GraphCache.read(store, log))
        GraphCache.write(store, sample(), log)
        assertEquals(a, GraphCache.read(store, log)!!.owner)
    }
}
