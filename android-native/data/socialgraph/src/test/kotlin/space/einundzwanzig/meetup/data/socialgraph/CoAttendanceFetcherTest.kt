package space.einundzwanzig.meetup.data.socialgraph

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.data.socialgraph.internal.GraphCrawler
import space.einundzwanzig.meetup.data.socialgraph.internal.RelayAnswer
import space.einundzwanzig.meetup.data.socialgraph.internal.RelayCoAttendanceFetcher

/** Spec 05 §6.2 filters, §6.3 validation and §8.2.6 per-relay result semantics on fake sockets. */
class CoAttendanceFetcherTest {

    private val url = "wss://relay.test"
    private val alice = KeyPair.generate()
    private val now = System.currentTimeMillis() / 1000

    private fun attendance(author: KeyPair, key: String, content: String = """{"event":"$key","meetup":"X","t":1780000000}""") =
        EventIds.sign(author, now, 30079, listOf(listOf("d", key), listOf("client", "einundzwanzig-meetup-app")), content)

    @Test
    fun `filters in Dart key order`() {
        assertEquals(
            """{"kinds":[30079],"authors":["aa","bb"],"limit":5000,"since":17}""",
            DartJson.encode(RelayCoAttendanceFetcher.buildFilter(null, listOf("aa", "bb"), 17)),
        )
        assertEquals(
            """{"kinds":[30079],"#d":["k-2026-01-01@X","k-2026-01-01","m"],"limit":1000}""",
            DartJson.encode(RelayCoAttendanceFetcher.buildFilter(linkedSetOf("k-2026-01-01@X", "m"), null, 0)),
        )
        assertEquals("""{"kinds":[30079]}""", DartJson.encode(RelayCoAttendanceFetcher.buildFilter(null, null, null)))
    }

    @Test
    fun `EOSE answer is complete, bad content and empty event are skipped`() = runTest {
        val scripted = ScriptedRelays()
        val log = RecordingLog()
        scripted.onReq[url] = { sub, _, s ->
            s.pushEvent(sub, attendance(alice, "k-2026-01-01@X"))
            s.pushEvent(sub, attendance(alice, "k-2026-02-01", content = "kein json"))
            s.pushEvent(sub, attendance(alice, "k-2026-03-01", content = """{"meetup":"X"}"""))
            s.pushEvent(sub, attendance(alice, "k-2026-04-01", content = """{"event":5,"t":"x"}"""))
            // Different kind: rejected by the filter check.
            s.pushEvent(sub, EventIds.sign(alice, now, 1, emptyList(), """{"event":"zzz"}"""))
            s.push(listOf("EOSE", sub))
        }
        val answer = RelayCoAttendanceFetcher(RelayClient(scripted.connector), log)
            .fetch(url, null, listOf(alice.publicKeyHex), null)!!
        assertTrue(answer.complete)
        assertEquals(listOf("k-2026-01-01@X", "5"), answer.records.map { it.meetupEventId })
        assertEquals(listOf(1_780_000_000L, 0L), answer.records.map { it.attendedAt })
        assertEquals(alice.npub, answer.records[0].npub)
        assertTrue(log.lines.any { it.contains("1 von") && it.contains("nicht auswertbar") })
        val req = DartJson.decode(scripted.frames.first().second).jsonArray
        assertTrue(req[1].jsonPrimitive.content.matches(Regex("coatt-[0-9a-f]{16}")))
    }

    @Test
    fun `closed connection before EOSE gives an incomplete answer, unreachable gives null`() = runTest {
        val scripted = ScriptedRelays()
        scripted.onReq[url] = { sub, _, s ->
            s.pushEvent(sub, attendance(alice, "k-2026-01-01@X"))
            s.finish()
        }
        val fetcher = RelayCoAttendanceFetcher(RelayClient(scripted.connector), RecordingLog())
        val answer = fetcher.fetch(url, setOf("k-2026-01-01@X"), null, null)!!
        assertFalse(answer.complete)
        assertEquals(1, answer.records.size)
        assertNull(fetcher.fetch("wss://down.test", setOf("k"), null, null))
    }

    @Test
    fun `timeout after partial events keeps the records as incomplete, without events gives null`() = runTest {
        val scripted = ScriptedRelays()
        val log = RecordingLog()
        scripted.onReq[url] = { sub, _, s ->
            s.pushEvent(sub, attendance(alice, "k-2026-01-01@X"))
            s.pushEvent(sub, attendance(alice, "k-2026-02-01@X"))
            // No EOSE: the 8 s response timeout ends the query.
        }
        val client = RelayClient(scripted.connector, UnconfinedTestDispatcher(testScheduler))
        val answer = RelayCoAttendanceFetcher(client, log).fetch(url, null, listOf(alice.publicKeyHex), null)!!
        assertFalse(answer.complete)
        assertEquals(listOf("k-2026-01-01@X", "k-2026-02-01@X"), answer.records.map { it.meetupEventId })
        assertTrue(log.lines.any { it.contains("Zeit abgelaufen, 2 Eintraege") })

        val silent = ScriptedRelays()
        silent.onReq[url] = { _, _, _ -> }
        val silentClient = RelayClient(silent.connector, UnconfinedTestDispatcher(testScheduler))
        assertNull(RelayCoAttendanceFetcher(silentClient, RecordingLog()).fetch(url, setOf("k"), null, null))
    }

    @Test
    fun `CLOSED after events gives an incomplete answer with the reason logged, without events gives null`() = runTest {
        val scripted = ScriptedRelays()
        val log = RecordingLog()
        scripted.onReq[url] = { sub, _, s ->
            s.pushEvent(sub, attendance(alice, "k-2026-01-01@X"))
            s.push(listOf("CLOSED", sub, "rate-limited"))
        }
        val answer = RelayCoAttendanceFetcher(RelayClient(scripted.connector), log)
            .fetch(url, null, listOf(alice.publicKeyHex), null)!!
        assertFalse(answer.complete)
        assertEquals(1, answer.records.size)
        assertTrue(log.lines.any { it.contains("CLOSED: rate-limited") && it.contains("1 Eintraege") })
        assertFalse(log.lines.any { it.contains("Zeit abgelaufen") })

        val closed = ScriptedRelays()
        closed.onReq[url] = { sub, _, s -> s.push(listOf("CLOSED", sub, "auth-required: nope")) }
        assertNull(RelayCoAttendanceFetcher(RelayClient(closed.connector), RecordingLog()).fetch(url, setOf("k"), null, null))
    }

    @Test
    fun `reaching the limit before EOSE gives an incomplete answer`() = runTest {
        val key = "k-2026-01-01@X"
        val limit = RelayCoAttendanceFetcher.KEY_LIMIT
        val events = (0 until limit).map {
            attendance(alice, key, content = """{"event":"$key","meetup":"X","t":${1_780_000_000 + it}}""")
        }
        val scripted = ScriptedRelays()
        val log = RecordingLog()
        scripted.onReq[url] = { sub, _, s ->
            for (e in events) s.pushEvent(sub, e)
            s.push(listOf("EOSE", sub))
        }
        val answer = RelayCoAttendanceFetcher(RelayClient(scripted.connector), log).fetch(url, setOf(key), null, null)!!
        assertEquals(limit, answer.records.size)
        assertFalse(answer.complete)
        assertTrue(log.lines.any { it.contains("Limit von $limit erreicht") })

        // One below the limit with EOSE is complete.
        val below = ScriptedRelays()
        below.onReq[url] = { sub, _, s ->
            for (e in events.dropLast(1)) s.pushEvent(sub, e)
            s.push(listOf("EOSE", sub))
        }
        val complete = RelayCoAttendanceFetcher(RelayClient(below.connector), RecordingLog()).fetch(url, setOf(key), null, null)!!
        assertEquals(limit - 1, complete.records.size)
        assertTrue(complete.complete)
    }

    @Test
    fun `batch aggregation - any, all, dedupe`() {
        val r1 = rec(npubOf(1), "a")
        val r2 = rec(npubOf(2), "a")
        val agg = GraphCrawler.aggregate(
            true,
            listOf(RelayAnswer(listOf(r1, r2), true), null, RelayAnswer(listOf(r1), false)),
        )
        assertEquals(listOf(r1, r2), agg.records)
        assertTrue(agg.anyComplete)
        assertFalse(agg.allComplete)
        val all = GraphCrawler.aggregate(true, listOf(RelayAnswer(emptyList(), true), RelayAnswer(listOf(r1), true)))
        assertTrue(all.allComplete)
        assertFalse(GraphCrawler.aggregate(false, emptyList()).allComplete)
        assertFalse(GraphCrawler.aggregate(true, listOf(RelayAnswer(emptyList(), false))).anyComplete)
    }
}
