package space.einundzwanzig.meetup.core.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonArrayOf
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.nostr.relay.Nip42
import space.einundzwanzig.meetup.core.nostr.relay.RelayFrames
import space.einundzwanzig.meetup.core.nostr.relay.RelayMessage
import space.einundzwanzig.meetup.core.nostr.relay.SubscriptionIds

class NostrEventsTest {

    private val event = EventIds.sign(
        KeyPair.fromPrivateKeyHex("0".repeat(63) + "1"),
        1_700_000_000,
        30078,
        listOf(listOf("d", "einundzwanzig-reputation"), listOf("t", "Ünï")),
        "line1\nline2\t\"q\" \\ / \u0001\u007f ä€😀   <&>",
        auxRand = ByteArray(32),
    )

    @Test
    fun jsonObjectHasTheDartKeyOrderAndRoundTrips() {
        val obj = event.toJsonObject()
        assertEquals(listOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig"), obj.keys.toList())
        assertEquals(event.toJson(), DartJson.encode(obj))
        assertEquals(event, NostrEvents.fromJsonOrNull(obj))
        assertEquals(event, NostrEvents.fromJsonStringOrNull(event.toJson()))
        assertTrue(NostrEvents.fromJsonOrNull(obj)!!.isValid())
    }

    @Test
    fun strictParsingRejectsWrongTypes() {
        val obj = event.toJsonObject()
        fun with(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(obj + (key to value))
        assertNull(NostrEvents.fromJsonOrNull(JsonObject(obj - "sig")))
        assertNull(NostrEvents.fromJsonOrNull(with("kind", JsonPrimitive("1"))))
        assertNull(NostrEvents.fromJsonOrNull(with("tags", JsonArray(listOf(jsonArrayOf("n", 5))))))
        assertNull(NostrEvents.fromJsonOrNull(DartJson.decode(event.toJson().replace("\"kind\":30078", "\"kind\":30078.0"))))
        assertNull(NostrEvents.fromJsonOrNull(JsonPrimitive("x")))
        assertNull(NostrEvents.fromJsonStringOrNull("not json"))
    }

    @Test
    fun relayFramesAreDartJson() {
        val filter = NostrFilter(kinds = listOf(0), authors = listOf("ab"), limit = 1)
        assertEquals("""["REQ","nam-1",{"kinds":[0],"authors":["ab"],"limit":1}]""", RelayFrames.req("nam-1", filter))
        assertEquals("""["CLOSE","s"]""", RelayFrames.close("s"))
        assertEquals(event.toEventMessage(), RelayFrames.event(event))
        assertEquals(event.toEventMessage(), RelayFrames.event(event.toJsonObject()))
        assertEquals("[\"AUTH\",${event.toJson()}]", RelayFrames.auth(event))
        assertEquals(listOf(listOf("relay", "wss://r"), listOf("challenge", "c")), Nip42.authTags("wss://r", "c"))
        assertEquals(22242, Nip42.KIND_CLIENT_AUTH)
    }

    @Test
    fun subscriptionIdsAreSixteenHexCharsAfterThePrefix() {
        val id = SubscriptionIds.secure("names")
        assertTrue(id, Regex("names-[0-9a-f]{16}").matches(id))
        assertFalse(SubscriptionIds.secure("x") == SubscriptionIds.secure("x"))
    }

    @Test
    fun relayMessagesAreClassifiedLikeTheDartChecks() {
        val ev = RelayMessage.parse("""["EVENT","s",{"kind":1}]""") as RelayMessage.Event
        assertEquals("s", ev.subscriptionId)
        assertTrue(RelayMessage.parse("""["EVENT","s"]""") is RelayMessage.Other)
        assertEquals("s", (RelayMessage.parse("""["EOSE","s"]""") as RelayMessage.Eose).subscriptionId)
        val closed = RelayMessage.parse("""["CLOSED","s","auth-required: bitte anmelden"]""") as RelayMessage.Closed
        assertEquals("auth-required: bitte anmelden", closed.reason)
        assertNull((RelayMessage.parse("""["CLOSED","s"]""") as RelayMessage.Closed).reason)
        val ok = RelayMessage.parse("""["OK","id",true,""]""") as RelayMessage.Ok
        assertTrue(ok.accepted)
        assertEquals("id", ok.eventId)
        val notOk = RelayMessage.parse("""["OK","id",false,"blocked: nope"]""") as RelayMessage.Ok
        assertFalse(notOk.accepted)
        assertEquals("blocked: nope", notOk.message)
        assertFalse((RelayMessage.parse("""["OK","id","true"]""") as RelayMessage.Ok).accepted)
        assertTrue(RelayMessage.parse("""["OK","id"]""") is RelayMessage.Other)
        assertEquals("c", (RelayMessage.parse("""["AUTH","c"]""") as RelayMessage.Auth).challenge)
        assertTrue(RelayMessage.parse("""["AUTH",5]""") is RelayMessage.Other)
        assertEquals("hi", (RelayMessage.parse("""["NOTICE","hi"]""") as RelayMessage.Notice).message)
        assertNull(RelayMessage.parse("invalid JSON"))
        assertNull(RelayMessage.parse("[]"))
        assertNull(RelayMessage.parse("""{"a":1}"""))
        assertNotNull(RelayMessage.parse("""["X"]"""))
    }
}
