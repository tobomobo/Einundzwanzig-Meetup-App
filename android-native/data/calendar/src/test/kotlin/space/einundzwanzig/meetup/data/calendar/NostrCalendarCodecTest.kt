package space.einundzwanzig.meetup.data.calendar

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.json.toJsonElement
import space.einundzwanzig.meetup.data.calendar.model.CalendarEventDraft
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.calendar.nostr.NostrCalendarCodec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Spec 10 §10 "new vectors": tag layouts, parse rules, issuer rules, badge window. */
class NostrCalendarCodecTest {

    private val utc = ZoneOffset.UTC
    private val A = "a".repeat(64)
    private val B = "b".repeat(64)
    private val C = "c".repeat(64)
    private val P = "1".repeat(64)
    private val D = "0123456789abcdef0123456789abcdef"

    private fun raw(kind: Int, tags: List<List<String>>, content: String = "", pubkey: String = P): JsonObject =
        jsonObjectOf(
            "id" to "e".repeat(64),
            "pubkey" to pubkey,
            "created_at" to 1_700_000_000L,
            "kind" to kind,
            "tags" to tags.toJsonElement(),
            "content" to content,
            "sig" to "f".repeat(128),
        )

    private fun timeEvent(vararg extra: List<String>) = raw(
        31923,
        listOf(listOf("d", "x"), listOf("title", "T"), listOf("start", "1781856000")) + extra.toList(),
    )

    @Test
    fun `publish time-based event with badge - exact tag layout`() {
        val draft = CalendarEventDraft(
            title = "BTC Prag",
            description = " desc ",
            location = " Prag ",
            start = Instant.parse("2026-06-19T08:00:00Z").atZone(ZoneId.of("Europe/Prague")),
            allDay = false,
            badgeEnabled = true,
            badgeImageUrl = " https://x/y.png ",
            lat = 50.087451,
            lng = 14.420671,
            issuers = listOf(A),
            formerIssuers = mapOf(B to 1_700_000_000L),
        )
        val (kind, tags) = NostrCalendarCodec.buildTags(draft, D)
        assertEquals(31923, kind)
        assertEquals(
            listOf(
                listOf("d", D), listOf("title", "BTC Prag"), listOf("start", "1781856000"), listOf("location", "Prag"),
                listOf("client", "einundzwanzig-meetup-app"), listOf("badge", "true"), listOf("badge_image", "https://x/y.png"),
                listOf("g", "50.087451", "14.420671"), listOf("p", A, "", "issuer"),
                listOf("p", B, "", "former_issuer", "1700000000"),
            ),
            tags,
        )
    }

    @Test
    fun `location and badge image use Dart trim`() {
        val draft = CalendarEventDraft(
            title = "T",
            description = "",
            location = "\uFEFFPrag\u001F",
            start = Instant.parse("2026-06-19T08:00:00Z").atZone(ZoneId.of("Europe/Prague")),
            allDay = false,
            badgeEnabled = true,
            badgeImageUrl = "\uFEFFhttps://x\u0085",
        )
        val tags = NostrCalendarCodec.buildTags(draft, D).second
        assertTrue(listOf("location", "Prag\u001F") in tags)
        assertTrue(listOf("badge_image", "https://x") in tags)
        // Dart `trim()` empties a BOM-only location, so no location tag is written.
        val bomOnly = NostrCalendarCodec.buildTags(draft.copy(location = "\uFEFF\u0085"), D).second
        assertTrue(bomOnly.none { it[0] == "location" })
    }

    @Test
    fun `badge off writes no badge, g or p tags`() {
        val draft = CalendarEventDraft(
            title = "T", description = "", location = "", start = Instant.parse("2026-06-19T08:00:00Z").atZone(utc),
            badgeEnabled = false, badgeImageUrl = "https://x", lat = 1.0, lng = 2.0, issuers = listOf(A),
            formerIssuers = mapOf(B to 1L),
        )
        val (_, tags) = NostrCalendarCodec.buildTags(draft, D)
        assertEquals(listOf("d", "title", "start", "client"), tags.map { it[0] })
    }

    @Test
    fun `badge on - short issuer keys, empty image and zero coordinates are skipped`() {
        val draft = CalendarEventDraft(
            title = "T", description = "", location = "  ", start = Instant.parse("2026-06-19T08:00:00Z").atZone(utc),
            end = Instant.parse("2026-06-19T10:00:00Z").atZone(utc),
            badgeEnabled = true, badgeImageUrl = "  ", issuers = listOf("a".repeat(63), A),
        )
        val (_, tags) = NostrCalendarCodec.buildTags(draft, D)
        assertEquals(
            listOf(
                listOf("d", D), listOf("title", "T"), listOf("start", "1781856000"), listOf("end", "1781863200"),
                listOf("client", "einundzwanzig-meetup-app"), listOf("badge", "true"), listOf("p", A, "", "issuer"),
            ),
            tags,
        )
    }

    @Test
    fun `publish all-day event`() {
        val draft = CalendarEventDraft(
            title = "T", description = "", location = "",
            start = LocalDate.of(2026, 6, 19).atStartOfDay(ZoneId.of("Europe/Berlin")),
            end = LocalDate.of(2026, 6, 21).atStartOfDay(ZoneId.of("Europe/Berlin")),
            allDay = true,
        )
        val (kind, tags) = NostrCalendarCodec.buildTags(draft, D)
        assertEquals(31922, kind)
        assertEquals(
            listOf(
                listOf("d", D), listOf("title", "T"), listOf("start", "2026-06-19"), listOf("end", "2026-06-21"),
                listOf("client", "einundzwanzig-meetup-app"),
            ),
            tags,
        )
    }

    @Test
    fun `g tag uses Dart toStringAsFixed(6) vectors`() {
        val vectors = mapOf(
            0.1234565 to "0.123456", 47.0000005 to "47.000000", 16.3738185 to "16.373818", 5e-7 to "0.000000",
            0.0078125 to "0.007813", -0.0078125 to "-0.007813", -1e-7 to "-0.000000", 48.2082 to "48.208200",
            -122.4194 to "-122.419400",
        )
        for ((v, expected) in vectors) {
            val draft = CalendarEventDraft(
                title = "T", description = "", location = "", start = Instant.EPOCH.atZone(utc),
                badgeEnabled = true, lat = v, lng = 1.0,
            )
            val g = NostrCalendarCodec.buildTags(draft, D).second.first { it[0] == "g" }
            assertEquals("lat $v", expected, g[1])
        }
        // -0.0 alone counts as "no coordinates" but keeps its sign next to a real value.
        val draft = CalendarEventDraft(
            title = "T", description = "", location = "", start = Instant.EPOCH.atZone(utc),
            badgeEnabled = true, lat = -0.0, lng = 1.0,
        )
        assertEquals("-0.000000", NostrCalendarCodec.buildTags(draft, D).second.first { it[0] == "g" }[1])
        val none = draft.copy(lng = 0.0)
        assertTrue(NostrCalendarCodec.buildTags(none, D).second.none { it[0] == "g" })
    }

    @Test
    fun `cancel tags and deletion request`() {
        val ev = NostrCalendarCodec.parse(
            raw(31923, listOf(listOf("d", D), listOf("title", "T"), listOf("start", "1781884800"), listOf("location", "L"))),
            utc,
        )!!
        assertEquals(
            listOf(
                listOf("d", D), listOf("title", "T"), listOf("start", "1781884800"), listOf("status", "cancelled"),
                listOf("client", "einundzwanzig-meetup-app"),
            ),
            NostrCalendarCodec.cancelTags(ev, utc),
        )
        assertEquals(
            listOf(listOf("e", "e".repeat(64)), listOf("a", "31923:$P:$D"), listOf("k", "31923")),
            NostrCalendarCodec.deletionTags(ev),
        )
        val allDay = NostrCalendarCodec.parse(raw(31922, listOf(listOf("d", D), listOf("title", "T"), listOf("start", "2026-06-19"))), utc)!!
        assertEquals(listOf("start", "2026-06-19"), NostrCalendarCodec.cancelTags(allDay, ZoneId.of("America/New_York"))[2])
    }

    @Test
    fun `fromEvent rules`() {
        assertTrue(NostrCalendarCodec.parse(timeEvent(listOf("badge", "TRUE")), utc)!!.badgeEnabled)
        assertTrue(NostrCalendarCodec.parse(timeEvent(listOf("p", "a".repeat(63), "", "issuer")), utc)!!.issuers.isEmpty())
        assertTrue(NostrCalendarCodec.parse(timeEvent(listOf("p", A)), utc)!!.issuers.isEmpty())
        assertTrue(NostrCalendarCodec.parse(timeEvent(listOf("p", B, "", "former_issuer", "x")), utc)!!.formerIssuers.isEmpty())
        assertEquals(
            mapOf(B to 1_700_000_000L),
            NostrCalendarCodec.parse(timeEvent(listOf("p", B, "", "former_issuer", "1700000000")), utc)!!.formerIssuers,
        )
        assertEquals(listOf(A), NostrCalendarCodec.parse(timeEvent(listOf("p", A, "", "issuer")), utc)!!.issuers)
        val named = raw(31923, listOf(listOf("d", "x"), listOf("name", "Old"), listOf("start", "1781856000")))
        assertEquals("Old", NostrCalendarCodec.parse(named, utc)!!.title)
        assertNull(NostrCalendarCodec.parse(raw(1, listOf(listOf("title", "T"), listOf("start", "1"))), utc))
        assertNull(NostrCalendarCodec.parse(raw(31923, listOf(listOf("title", "T"), listOf("start", "abc"))), utc))
        assertNull(NostrCalendarCodec.parse(raw(31923, listOf(listOf("start", "1781856000"))), utc))
        assertTrue(NostrCalendarCodec.parse(timeEvent(listOf("status", "cancelled")), utc)!!.isCancelled)
    }

    @Test
    fun `fromEvent fields`() {
        val ev = NostrCalendarCodec.parse(
            timeEvent(
                listOf("end", "1781863200"), listOf("location", "Prag"), listOf("client", "einundzwanzig-meetup-app"),
                listOf("badge_image", "https://x"), listOf("g", "50.1", "abc"), listOf("g", "1", "2"),
            ),
            utc,
        )!!
        assertEquals(Instant.ofEpochSecond(1781856000), ev.start)
        assertEquals(Instant.ofEpochSecond(1781863200), ev.end)
        assertEquals("Prag", ev.location)
        assertTrue(ev.fromApp)
        assertEquals(50.1, ev.lat, 0.0)
        assertEquals(0.0, ev.lng, 0.0) // first g tag wins, unparsable → 0
        assertEquals("31923:$P:x", ev.address)
        assertFalse(ev.allDay)
        assertEquals(1_700_000_000L, ev.createdAt)

        val allDay = NostrCalendarCodec.parse(
            raw(31922, listOf(listOf("d", "y"), listOf("title", "T"), listOf("start", "2026-06-19"), listOf("end", "bad"))),
            ZoneId.of("Europe/Berlin"),
        )!!
        assertTrue(allDay.allDay)
        assertEquals(LocalDate.of(2026, 6, 19), allDay.startDate)
        assertEquals(Instant.parse("2026-06-18T22:00:00Z"), allDay.start)
        assertNull(allDay.end)
    }

    @Test
    fun `fromEvent rejects malformed structures without throwing`() {
        assertNull(NostrCalendarCodec.parse(jsonObjectOf("kind" to 31923.0, "tags" to listOf(listOf("title", "T")).toJsonElement())))
        assertNull(NostrCalendarCodec.parse(jsonObjectOf("kind" to 31923, "tags" to "x")))
        assertNull(NostrCalendarCodec.parse(jsonObjectOf("kind" to 31923, "tags" to listOf("x").toJsonElement())))
        assertNull(NostrCalendarCodec.parse(timeEvent().let { JsonObject(it + ("tags" to listOf(listOf("title", "T"), listOf("start", "99999999999999")).toJsonElement())) }))
    }

    @Test
    fun `parse address`() {
        val upper = "ABC" + "D".repeat(61)
        assertEquals(
            NostrCalendarCodec.Address(31923, upper.lowercase(), "my:d:tag"),
            NostrCalendarCodec.parseAddress("31923:$upper:my:d:tag"),
        )
        assertNull(NostrCalendarCodec.parseAddress("31923:short:x"))
        assertNull(NostrCalendarCodec.parseAddress("x:$A:d"))
        assertNull(NostrCalendarCodec.parseAddress("31923:$A:"))
        assertNull(NostrCalendarCodec.parseAddress("31923:$A"))
    }

    @Test
    fun `isIssuer vectors`() {
        val ev = NostrCalendarCodec.parse(
            raw(
                31923,
                listOf(
                    listOf("d", "x"), listOf("title", "T"), listOf("start", "1781856000"),
                    listOf("p", A, "", "issuer"), listOf("p", B, "", "former_issuer", "1700000000"),
                ),
                pubkey = "abcdef" + "1".repeat(58),
            ),
            utc,
        )!!
        val creator = ev.pubkey
        assertTrue(ev.isIssuer(creator))
        assertTrue(ev.isIssuer(creator.uppercase()))
        assertTrue(ev.isIssuer(A))
        assertTrue(ev.isIssuer(A.uppercase()))
        assertFalse(ev.isIssuer(B))
        assertTrue(ev.isIssuer(B, atEpoch = 1_700_000_000L))
        assertFalse(ev.isIssuer(B, atEpoch = 1_700_000_001L))
        assertFalse(ev.isIssuer(""))
        assertFalse(ev.isIssuer(C, atEpoch = 0))
    }

    @Test
    fun `badge window covers the local event days`() {
        val zone = ZoneId.of("Europe/Berlin")
        val base = NostrCalendarEvent(
            id = "", pubkey = P, dTag = "d", title = "T", description = "", location = "",
            start = Instant.parse("2026-06-19T17:00:00Z"), end = null, allDay = false, kind = 31923, badgeEnabled = true,
        )
        assertFalse(base.isBadgeWindowOpen(Instant.parse("2026-06-18T21:59:59Z"), zone))
        assertTrue(base.isBadgeWindowOpen(Instant.parse("2026-06-18T22:00:00Z"), zone))
        assertTrue(base.isBadgeWindowOpen(Instant.parse("2026-06-19T21:59:59Z"), zone))
        assertFalse(base.isBadgeWindowOpen(Instant.parse("2026-06-19T22:00:00Z"), zone))
        val multi = base.copy(end = Instant.parse("2026-06-21T10:00:00Z"))
        assertTrue(multi.isBadgeWindowOpen(Instant.parse("2026-06-21T21:00:00Z"), zone))
        assertFalse(multi.isBadgeWindowOpen(Instant.parse("2026-06-21T22:00:00Z"), zone))
        assertFalse(base.copy(badgeEnabled = false).isBadgeWindowOpen(Instant.parse("2026-06-19T12:00:00Z"), zone))
    }

    @Test
    fun `draft from event keeps the all-day dates in any zone`() {
        val ev = NostrCalendarCodec.parse(
            raw(31922, listOf(listOf("d", D), listOf("title", "T"), listOf("start", "2026-06-19"), listOf("end", "2026-06-21"))),
            ZoneId.of("Pacific/Auckland"),
        )!!
        val draft = CalendarEventDraft.of(ev, zone = ZoneId.of("America/Los_Angeles"))
        val tags = NostrCalendarCodec.buildTags(draft, ev.dTag).second
        assertEquals(listOf("start", "2026-06-19"), tags[2])
        assertEquals(listOf("end", "2026-06-21"), tags[3])
        assertNotNull(NostrCalendarCodec.newDTag().takeIf { it.matches(Regex("[0-9a-f]{32}")) })
    }
}
