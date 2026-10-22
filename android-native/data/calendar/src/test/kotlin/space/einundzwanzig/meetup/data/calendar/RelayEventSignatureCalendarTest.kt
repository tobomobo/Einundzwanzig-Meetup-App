package space.einundzwanzig.meetup.data.calendar

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.json.toJsonElement
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.NostrFilter
import space.einundzwanzig.meetup.data.calendar.nostr.NostrCalendarCodec

/**
 * Calendar part of `test/relay_event_signature_test.dart` (H2/M1): only verified events reach
 * the calendar parser; the filter guard rejects unrelated answers.
 */
class RelayEventSignatureCalendarTest {

    private val kc = KeyPair.generate()
    private val now = System.currentTimeMillis() / 1000

    private fun signedCalendarEvent(): JsonObject = signedRaw(
        kc,
        31923,
        listOf(listOf("d", "test-meetup"), listOf("title", "Echtes Meetup"), listOf("start", "${now + 86400}")),
        createdAt = now,
    )

    private fun JsonObject.with(key: String, value: Any): JsonObject = JsonObject(this + (key to value.toJsonElement()))

    @Test
    fun `valid signed calendar event passes and parses`() {
        val raw = EventGuard.verifiedEvent(signedCalendarEvent(), "Test")
        assertNotNull(raw)
        val cal = NostrCalendarCodec.parse(raw!!)
        assertEquals("Echtes Meetup", cal!!.title)
    }

    @Test
    fun `unsigned, tampered, spoofed and forged events are rejected`() {
        assertNull(EventGuard.verifiedEvent(signedCalendarEvent().with("sig", ""), "Test"))
        val tampered = signedCalendarEvent().let { ev ->
            val tags = ev["tags"]!!.jsonArray.map { t -> t.jsonArray.map { (it as JsonPrimitive).content } }
                .map { if (it[0] == "title") listOf("title", "Gefälscht") else it }
            ev.with("tags", tags)
        }
        assertNull(EventGuard.verifiedEvent(tampered, "Test"))
        assertNull(EventGuard.verifiedEvent(signedCalendarEvent().with("pubkey", KeyPair.generate().publicKeyHex), "Test"))
        assertNull(EventGuard.verifiedEvent(signedCalendarEvent().with("id", "ab".repeat(32)), "Test"))
        assertNull(EventGuard.verifiedEvent(signedCalendarEvent().with("sig", "zz".repeat(64)), "Test"))
        assertNull(EventGuard.verifiedEvent(signedCalendarEvent().with("pubkey", "xyz"), "Test"))
    }

    @Test
    fun `answersFilter matches the fetchByAddress filter only`() {
        val raw = signedCalendarEvent()
        val pk = kc.publicKeyHex
        assertTrue(EventGuard.answersFilter(raw, NostrFilter(kinds = listOf(31923), authors = listOf(pk), tags = mapOf("d" to listOf("test-meetup")))))
        assertFalse(EventGuard.answersFilter(raw, NostrFilter(kinds = listOf(0))))
        assertFalse(EventGuard.answersFilter(raw, NostrFilter(kinds = listOf(31923), authors = listOf("0".repeat(64)))))
        assertFalse(EventGuard.answersFilter(raw, NostrFilter(kinds = listOf(31923), tags = mapOf("d" to listOf("other")))))
    }

    @Test
    fun `replaceableCreatedAt guards future timestamps`() {
        assertEquals(now, EventGuard.replaceableCreatedAt(signedCalendarEvent(), now))
        assertNull(EventGuard.replaceableCreatedAt(signedCalendarEvent().with("created_at", now + 3600), now))
    }

    @Test
    fun `broken message does not crash`() {
        // Dart 'Kaputte Nachricht crasht nicht'.
        val before = EventGuard.rejectedEventCount
        assertNull(EventGuard.verifiedEvent(JsonPrimitive("kein objekt")))
        assertNull(EventGuard.verifiedEvent(jsonObjectOf("kind" to "x", "tags" to 42)))
        assertTrue(EventGuard.rejectedEventCount > 0)
        assertTrue(EventGuard.rejectedEventCount > before) // the map was counted as rejected
    }
}
