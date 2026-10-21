package space.einundzwanzig.meetup.core.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonArrayOf
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import java.io.File

/**
 * Port of `test/relay_event_signature_test.dart` (the calendar parsing assertion belongs to
 * `:data:calendar`) plus the spec 07 §10.11 vectors and the Dart type semantics of
 * `verifiedEvent`.
 */
class EventGuardTest {

    private val kc = KeyPair.generate()
    private val now = System.currentTimeMillis() / 1000

    private fun signedCalendarEvent(): JsonObject = EventIds.sign(
        keyPair = kc,
        createdAt = now,
        kind = 31923,
        tags = listOf(
            listOf("d", "test-meetup"),
            listOf("title", "Echtes Meetup"),
            listOf("start", "${now + 86400}"),
        ),
        content = "",
    ).toJsonObject()

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    // ---- relay_event_signature_test.dart -------------------------------------------------

    @Test
    fun validSignedCalendarEventPasses() {
        val raw = signedCalendarEvent()
        val verified = EventGuard.verifiedEvent(raw, tag = "Test")
        assertNotNull(verified)
        assertSame("the raw object is returned", raw, verified)
        val title = verified!!["tags"]!!.jsonArray[1].jsonArray[1] as JsonPrimitive
        assertEquals("Echtes Meetup", title.content)
    }

    @Test
    fun unsignedEventIsRejected() {
        assertNull(EventGuard.verifiedEvent(signedCalendarEvent().with("sig", JsonPrimitive("")), tag = "Test"))
    }

    @Test
    fun tamperedTitleAfterSigningIsRejected() {
        val raw = signedCalendarEvent()
        val tags = raw["tags"]!!.jsonArray.toMutableList()
        tags[1] = jsonArrayOf("title", "Gefälschtes Meetup")
        assertNull(EventGuard.verifiedEvent(raw.with("tags", JsonArray(tags)), tag = "Test"))
    }

    @Test
    fun foreignPubkeyIsRejected() {
        val other = KeyPair.generate()
        assertNull(EventGuard.verifiedEvent(signedCalendarEvent().with("pubkey", JsonPrimitive(other.publicKeyHex)), tag = "Test"))
    }

    @Test
    fun replacedIdIsRejected() {
        assertNull(EventGuard.verifiedEvent(signedCalendarEvent().with("id", JsonPrimitive("ab".repeat(32))), tag = "Test"))
    }

    @Test
    fun nonHexPubkeyOrSignatureIsRejectedWithoutThrowing() {
        assertNull(EventGuard.verifiedEvent(signedCalendarEvent().with("sig", JsonPrimitive("zz".repeat(64))), tag = "Test"))
        assertNull(EventGuard.verifiedEvent(signedCalendarEvent().with("pubkey", JsonPrimitive("xyz")), tag = "Test"))
    }

    @Test
    fun validSignatureOfWrongKindOrAuthorDoesNotAnswerTheRequest() {
        val raw = signedCalendarEvent()
        val pubkey = raw["pubkey"]!!
        val asAsked = jsonObjectOf("kinds" to listOf(31923), "authors" to JsonArray(listOf(pubkey)), "#d" to listOf("test-meetup"))
        assertTrue(EventGuard.answersFilter(raw, asAsked))
        assertFalse(EventGuard.answersFilter(raw, jsonObjectOf("kinds" to listOf(0), "authors" to JsonArray(listOf(pubkey)))))
        assertFalse(EventGuard.answersFilter(raw, jsonObjectOf("kinds" to listOf(31923), "authors" to listOf("ab".repeat(32)))))
        assertFalse(EventGuard.answersFilter(raw, jsonObjectOf("kinds" to listOf(31923), "#d" to listOf("anderer-termin"))))
    }

    @Test
    fun futureCreatedAtDoesNotCountAsNewestReplaceable() {
        val raw = signedCalendarEvent()
        val nowS = System.currentTimeMillis() / 1000
        assertEquals(now, EventGuard.replaceableCreatedAt(raw, nowS))
        assertNull(EventGuard.replaceableCreatedAt(raw.with("created_at", JsonPrimitive(nowS + 3600)), nowS))
    }

    @Test
    fun brokenMessageDoesNotCrash() {
        assertNull(EventGuard.verifiedEvent(JsonPrimitive("kein objekt")))
        assertNull(EventGuard.verifiedEvent(jsonObjectOf("kind" to "x", "tags" to 42)))
        assertTrue(EventGuard.rejectedEventCount > 0)
    }

    // ---- spec 07 §10.11 vectors ---------------------------------------------------------

    private val fixture: JsonObject by lazy {
        val file = sequenceOf("spec/fixtures/07-nostr-infra-vectors.json", "../../spec/fixtures/07-nostr-infra-vectors.json")
            .map(::File).first { it.exists() }
        DartJson.decode(file.readText()).jsonObject
    }

    @Test
    fun officialSignedEventsVerify() {
        val events = fixture["valid_signed_events"]!!.jsonArray
        assertEquals(2, events.size)
        for (e in events) {
            assertNotNull(EventGuard.verifiedEvent(e))
            assertNotNull(NostrEvents.fromJsonOrNull(e))
            assertTrue(NostrEvents.fromJsonOrNull(e)!!.isValid())
        }
    }

    @Test
    fun createdAtMustHaveTenDigits() {
        val priv = KeyPair.generate()
        for (createdAt in listOf(999_999_999L, 10_000_000_000L)) {
            val ev = EventIds.sign(priv, createdAt, 1, emptyList(), "x").toJsonObject()
            assertNull("created_at=$createdAt", EventGuard.verifiedEvent(ev))
        }
        assertNotNull(EventGuard.verifiedEvent(EventIds.sign(priv, 1_000_000_000L, 1, emptyList(), "x").toJsonObject()))
    }

    @Test
    fun uppercaseIdIsRejectedButUppercasePubkeyIsAccepted() {
        val ev = fixture["valid_signed_events"]!!.jsonArray[0].jsonObject
        val id = (ev["id"] as JsonPrimitive).content
        val pubkey = (ev["pubkey"] as JsonPrimitive).content
        assertNull(EventGuard.verifiedEvent(ev.with("id", JsonPrimitive(id.uppercase()))))
        assertNotNull(EventGuard.verifiedEvent(ev.with("pubkey", JsonPrimitive(pubkey.uppercase()))))
    }

    @Test
    fun escapingVectorProducesTheDartId() {
        val v = fixture["event_id_escaping"]!!.jsonObject
        val tags = v["tags"]!!.jsonArray.map { t -> t.jsonArray.map { (it as JsonPrimitive).content } }
        val id = EventIds.compute(
            (v["pubkey"] as JsonPrimitive).content,
            (v["created_at"] as JsonPrimitive).content.toLong(),
            (v["kind"] as JsonPrimitive).content.toInt(),
            tags,
            (v["content"] as JsonPrimitive).content,
        )
        assertEquals((v["expected_id"] as JsonPrimitive).content, id)
    }

    // ---- Dart type semantics of verifiedEvent ------------------------------------------

    @Test
    fun missingFieldsTakeDartDefaultsAndAreRejectedWithoutThrowing() {
        val before = EventGuard.rejectedEventCount
        assertNull(EventGuard.verifiedEvent(JsonObject(emptyMap())))
        assertEquals(before + 1, EventGuard.rejectedEventCount)
    }

    @Test
    fun nonObjectIsNotCounted() {
        val before = EventGuard.rejectedEventCount
        assertNull(EventGuard.verifiedEvent(JsonArray(emptyList())))
        assertNull(EventGuard.verifiedEvent(null))
        assertEquals(before, EventGuard.rejectedEventCount)
    }

    @Test
    fun wrongJsonTypesAreRejected() {
        val ev = signedCalendarEvent()
        assertNull(EventGuard.verifiedEvent(ev.with("kind", JsonPrimitive(31923.0))))
        assertNull(EventGuard.verifiedEvent(ev.with("created_at", JsonPrimitive(now.toString()))))
        assertNull(EventGuard.verifiedEvent(ev.with("content", JsonPrimitive(1))))
        assertNull(EventGuard.verifiedEvent(ev.with("tags", JsonPrimitive("x"))))
        assertNull(EventGuard.verifiedEvent(ev.with("tags", jsonArrayOf("d"))))
        // 1.0 parsed from JSON text is a Dart double, not an int
        val text = DartJson.encode(ev).replace("\"kind\":31923", "\"kind\":31923.0")
        assertNull(EventGuard.verifiedEvent(DartJson.decode(text)))
    }

    @Test
    fun scalarTagValuesAreStringifiedLikeDart() {
        // Signed over the string "5"; a relay that sends the number 5 still verifies in Dart
        // because `e.toString()` restores "5". The raw object (with the number) is returned.
        val signed = EventIds.sign(kc, now, 1, listOf(listOf("n", "5"), listOf("b", "true")), "x").toJsonObject()
        val text = DartJson.encode(signed).replace("[\"n\",\"5\"]", "[\"n\",5]").replace("[\"b\",\"true\"]", "[\"b\",true]")
        val raw = DartJson.decode(text)
        val verified = EventGuard.verifiedEvent(raw)
        assertNotNull(verified)
        // The filter compares raw values: "5" (string) is not 5 (int) in Dart.
        assertFalse(EventGuard.answersFilter(verified!!, jsonObjectOf("#n" to listOf("5"))))
        assertTrue(EventGuard.answersFilter(verified, jsonObjectOf("#n" to listOf(5))))
    }

    @Test
    fun nullFieldsActLikeAbsent() {
        val ev = signedCalendarEvent()
        // tags null → [] → id mismatch → rejected, but no exception
        assertNull(EventGuard.verifiedEvent(ev.with("tags", kotlinx.serialization.json.JsonNull)))
    }
}
