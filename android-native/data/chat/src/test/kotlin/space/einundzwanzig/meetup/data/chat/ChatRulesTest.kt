package space.einundzwanzig.meetup.data.chat

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.chat.internal.DefaultChatReadMarkStore
import java.time.Instant
import java.time.ZoneId

/** Spec 11 §10.4 vectors: slugify, city match, read marks, ChatRoom/ChatMessage parsing, duplicates, tags. */
class ChatRulesTest {

    private fun obj(json: String) = DartJson.decode(json) as JsonObject

    @Test
    fun slugify() {
        val vectors = mapOf(
            "Saarbrücken" to "saarbruecken",
            "Würzburg" to "wuerzburg",
            "St. Gallen" to "st-gallen",
            "  Köln  " to "koeln",
            "Gießen" to "giessen",
            "Zürich" to "zuerich",
            "München (Ost)" to "muenchen-ost",
            "Bad Kissingen" to "bad-kissingen",
        )
        vectors.forEach { (input, slug) -> assertEquals(input, slug, ChatRules.slugify(input)) }
        // Decomposed umlaut (u + U+0308) is not normalised: the mark becomes a dash.
        assertEquals("mu-nchen", ChatRules.slugify("München"))
        assertEquals("", ChatRules.slugify("—"))
    }

    @Test
    fun `city match by slug suffix, exact slug, then name`() {
        val saar = ChatRoom(h = "r1", name = "Saarbrücken", meetupSlug = "einundzwanzig-saarbruecken")
        val wue = ChatRoom(h = "r2", name = "Würzburg Meetup", meetupSlug = "WUERZBURG")
        val named = ChatRoom(h = "r3", name = "Bad Kissingen", meetupSlug = "bk-21")
        val rooms = listOf(saar, wue, named)
        assertEquals(saar, ChatRules.matchCity(rooms, "Saarbrücken"))
        assertEquals(wue, ChatRules.matchCity(rooms, "Würzburg"))
        assertEquals(named, ChatRules.matchCity(rooms, "Bad Kissingen"))
        assertNull(ChatRules.matchCity(rooms, "Berlin"))
        assertNull(ChatRules.matchCity(rooms, "  "))
        // Suffix needs the dash: "xsaarbruecken" does not match.
        assertNull(ChatRules.matchCity(listOf(ChatRoom(h = "x", meetupSlug = "xsaarbruecken")), "Saarbrücken"))
    }

    @Test
    fun `read marks parse with the last separator and skip bad entries`() {
        val parsed = ChatReadMarkStore.parse(listOf("abc|100", "evt|31923:pk:d|200", "bad", "|5", "x|notint"))
        assertEquals(mapOf("abc" to 100L, "evt|31923:pk:d" to 200L), parsed)
        // Dart int.tryParse: whitespace, sign and hex are accepted.
        assertEquals(mapOf("a" to 16L, "b" to -3L, "c" to 7L), ChatReadMarkStore.parse(listOf("a|0x10", "b|-3", "c| 7 ")))
    }

    @Test
    fun `markRead never moves backwards and rewrites in previous order`() = runTest {
        val prefs = InMemoryKeyValueStore(
            mapOf(ChatReadMarkStore.KEY to PrefValue.StringListValue(listOf("b|50", "bad", "a|100"))),
        )
        val store = DefaultChatReadMarkStore(prefs)
        store.markRead("a", Instant.ofEpochSecond(90))
        assertEquals(listOf("b|50", "bad", "a|100"), prefs.getStringList(ChatReadMarkStore.KEY))
        assertEquals(100L, store.lastReadFor("a"))
        store.markRead("b", Instant.ofEpochMilli(60_999))
        assertEquals(listOf("b|60", "a|100"), prefs.getStringList(ChatReadMarkStore.KEY))
        store.markRead("evt|31923:pk:d", Instant.ofEpochSecond(70))
        assertEquals(listOf("b|60", "a|100", "evt|31923:pk:d|70"), prefs.getStringList(ChatReadMarkStore.KEY))
        assertEquals(0L, store.lastReadFor("missing"))
    }

    @Test
    fun `ChatRoom fromEvent`() {
        val room = ChatRoom.fromEvent(
            obj(
                """{"tags":[["d","r1"],["name","Saarbrücken"],["meetup_slug","einundzwanzig-saarbruecken"],""" +
                    """["i","meetup:42"],["i","event:31923:pk:d"]]}""",
            ),
        )!!
        assertEquals("r1", room.h)
        assertEquals("Saarbrücken", room.name)
        assertEquals("einundzwanzig-saarbruecken", room.meetupSlug)
        assertEquals("31923:pk:d", room.eventAddress)
        assertTrue(room.isMeetup)
        assertTrue(room.isEvent)
        assertNull(ChatRoom.fromEvent(obj("""{"tags":[["name","x"]]}""")))
        assertNull(ChatRoom.fromEvent(obj("""{"tags":[["d",""]]}""")))
        // First matching tag wins; the event binding is the last one.
        val multi = ChatRoom.fromEvent(
            obj("""{"tags":[["d"],["d","h1"],["name","A"],["name","B"],["i","event:x"],["i","meetup:1"],["i","event:y"]]}"""),
        )!!
        assertEquals("h1", multi.h)
        assertEquals("A", multi.name)
        assertEquals("y", multi.eventAddress)
        assertEquals("h1", ChatRoom(h = "h1").displayName)
    }

    @Test
    fun `ChatMessage fromEvent`() {
        val m = ChatMessage.fromEvent(obj("""{"id":"i1","pubkey":"pk","created_at":1792144000,"content":"hi"}"""))!!
        assertEquals("i1", m.id)
        assertEquals(Instant.ofEpochSecond(1792144000), m.createdAt)
        assertEquals("", ChatMessage.fromEvent(obj("""{"id":"i1","pubkey":"pk","created_at":1}"""))!!.content)
        assertNull(ChatMessage.fromEvent(obj("""{"id":"","pubkey":"pk","created_at":1}""")))
        assertNull(ChatMessage.fromEvent(obj("""{"id":"i","pubkey":"","created_at":1}""")))
        assertNull(ChatMessage.fromEvent(obj("""{"id":"i","pubkey":"pk","created_at":1.0}""")))
        assertNull(ChatMessage.fromEvent(obj("""{"id":"i","pubkey":"pk","created_at":"1"}""")))
        // Outside the Dart DateTime range: null instead of an overflow.
        assertNull(ChatMessage.fromEvent(obj("""{"id":"i","pubkey":"pk","created_at":9223372036854775807}""")))
        assertNull(ChatMessage.fromEvent(obj("""{"id":"i","pubkey":"pk","created_at":8640000000001}""")))
        assertEquals(
            Instant.ofEpochSecond(8_640_000_000_000L),
            ChatMessage.fromEvent(obj("""{"id":"i","pubkey":"pk","created_at":8640000000000}"""))!!.createdAt,
        )
    }

    @Test
    fun `local message and duplicate window`() {
        val me = "me"
        val local = ChatMessage.local(me, "hallo", nowMicros = 1_792_144_000_123_456L)
        assertEquals("lokal-1792144000123456", local.id)
        assertTrue(local.isLocal)
        assertEquals(Instant.ofEpochSecond(1_792_144_000L, 123_456_000L), local.createdAt)
        assertEquals("", ChatMessage.local(null, "x", 0).pubkey)

        val echo = ChatMessage("e1", me, "hallo", Instant.ofEpochSecond(1_792_144_000L + 119))
        assertTrue(ChatRules.isDuplicate(listOf(local), echo, me))
        assertFalse(ChatRules.isDuplicate(listOf(local), echo.copy(createdAt = Instant.ofEpochSecond(1_792_144_000L + 121)), me))
        assertFalse(ChatRules.isDuplicate(listOf(local), echo.copy(content = "other"), me))
        // Only own messages are de-duplicated.
        assertFalse(ChatRules.isDuplicate(listOf(local.copy(pubkey = "x")), echo.copy(pubkey = "x"), me))
        assertFalse(ChatRules.isDuplicate(listOf(local.copy(pubkey = "")), echo.copy(pubkey = ""), ""))
        assertFalse(ChatRules.isDuplicate(listOf(local), echo, null))

        assertNull(ChatRules.merge(listOf(local), echo, me))
        val other = ChatMessage("o", "x", "a", Instant.ofEpochSecond(10))
        assertEquals(listOf(other, local), ChatRules.merge(listOf(local), other, me))
        assertNull(ChatRules.merge(listOf(other), other, me))
    }

    @Test
    fun `short labels and time label`() {
        val hex = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123451234"
        assertEquals("abcdef…1234", ChatRules.shortKey(hex))
        assertEquals("abcdef012345", ChatRules.shortKey("abcdef012345"))
        assertEquals("npub1qv02xps…rxujnc", ChatRules.shortNpub("npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc"))
        assertEquals("npub1short", ChatRules.shortNpub("npub1short"))
        val zone = ZoneId.of("Europe/Berlin")
        val now = Instant.parse("2026-03-05T20:00:00Z")
        assertEquals("09:07", ChatRules.timeLabel(Instant.parse("2026-03-05T08:07:00Z"), now, zone))
        assertEquals("4.3. 09:07", ChatRules.timeLabel(Instant.parse("2026-03-04T08:07:00Z"), now, zone))
        assertEquals("31.12. 23:59", ChatRules.timeLabel(Instant.parse("2025-12-31T22:59:00Z"), now, zone))
    }

    @Test
    fun `event comment tag layouts`() {
        val pk = "68240d4932950242924434c5b118035a92448a9570caf1fde7aeca77afbc80e4"
        val addr = "31923:$pk:abc"
        assertEquals(
            listOf(listOf("A", addr), listOf("K", "31923"), listOf("P", pk), listOf("a", addr), listOf("k", "31923"), listOf("p", pk)),
            EventChatService.commentTags(addr, pk),
        )
        assertEquals(
            listOf(listOf("A", addr), listOf("K", "31923"), listOf("a", addr), listOf("k", "31923")),
            EventChatService.commentTags(addr, ""),
        )
        assertEquals(listOf("K", "noColon"), EventChatService.commentTags("noColon", "")[1])
        assertEquals("evt|$addr", EventChatService.readKey(addr))
    }

    @Test
    fun `relay unavailable texts`() {
        assertEquals("Keine Internetverbindung", ChatRelayUnavailable(offline = true).toString())
        assertEquals("Gruppen-Relay ohne Auskunft", ChatRelayUnavailable().toString())
    }
}
