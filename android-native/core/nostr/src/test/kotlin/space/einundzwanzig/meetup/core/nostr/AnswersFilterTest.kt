package space.einundzwanzig.meetup.core.nostr

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf

/**
 * `answersFilter` semantics: the filter-matching part of `test/zap_sent_filter_test.dart`
 * (the receipt filter builder itself belongs to `:data:proofs`) and spec 07 §8.5 / §8.6.
 */
class AnswersFilterTest {

    private val me = "aa"
    private val other = "bb"
    private val since = 1_700_000_000L

    private fun receipt(tags: List<List<String>>) = jsonObjectOf(
        "kind" to 9735,
        "pubkey" to "provider",
        "created_at" to since + 10,
        "tags" to tags,
    )

    /** `ZapVerificationService.receiptFilter(pubkeyHex, since, sent)` shape: `#P` for sent, `#p` for received. */
    private fun receiptFilter(sent: Boolean) = jsonObjectOf(
        "kinds" to listOf(9735),
        "since" to since,
        "limit" to 100,
        (if (sent) "#P" else "#p") to listOf(me),
    )

    @Test
    fun receivedQueryKeepsReceiptsWithoutUppercaseP() {
        val event = receipt(listOf(listOf("p", me)))
        assertTrue(EventGuard.answersFilter(event, receiptFilter(sent = false)))
        assertFalse(EventGuard.answersFilter(event, receiptFilter(sent = true)))
    }

    @Test
    fun sentFilterMatchesTheSenderNotTheRecipient() {
        val event = receipt(listOf(listOf("p", other), listOf("P", me)))
        assertTrue(EventGuard.answersFilter(event, receiptFilter(sent = true)))
        assertFalse(EventGuard.answersFilter(event, receiptFilter(sent = false)))
    }

    // ---- spec 07 §8.5 -------------------------------------------------------------------

    private val event = jsonObjectOf(
        "kind" to 1,
        "pubkey" to "pk",
        "created_at" to 100,
        "tags" to listOf(listOf("d", "x"), listOf("e")),
    )

    @Test
    fun sinceAndUntilAreInclusive() {
        assertTrue(EventGuard.answersFilter(event, jsonObjectOf("since" to 100, "until" to 100)))
        assertFalse(EventGuard.answersFilter(event, jsonObjectOf("since" to 101)))
        assertFalse(EventGuard.answersFilter(event, jsonObjectOf("until" to 99)))
    }

    @Test
    fun sinceRequiresAnIntCreatedAt() {
        val noTime = JsonObject(event - "created_at")
        assertTrue(EventGuard.answersFilter(noTime, jsonObjectOf("kinds" to listOf(1))))
        assertFalse(EventGuard.answersFilter(noTime, jsonObjectOf("since" to 1)))
        val doubleTime = DartJson.decode("""{"kind":1,"created_at":100.0}""") as JsonObject
        assertFalse(EventGuard.answersFilter(doubleTime, jsonObjectOf("until" to 1000)))
        // A non-int since is not compared, but created_at must still be an int.
        assertTrue(EventGuard.answersFilter(event, jsonObjectOf("since" to "x")))
        // null since/until count as absent
        assertTrue(EventGuard.answersFilter(noTime, JsonObject(mapOf("since" to JsonNull))))
    }

    @Test
    fun tagFiltersNeedANonEmptyListAndATwoElementTag() {
        assertTrue(EventGuard.answersFilter(event, jsonObjectOf("#d" to listOf("y", "x"))))
        assertFalse(EventGuard.answersFilter(event, jsonObjectOf("#d" to emptyList<String>())))
        assertFalse(EventGuard.answersFilter(event, jsonObjectOf("#d" to "x")))
        assertFalse("single-element tag never matches", EventGuard.answersFilter(event, jsonObjectOf("#e" to listOf("e"))))
        assertFalse(EventGuard.answersFilter(JsonObject(event - "tags"), jsonObjectOf("#d" to listOf("x"))))
        assertFalse("tag names are case-sensitive", EventGuard.answersFilter(event, jsonObjectOf("#D" to listOf("x"))))
        // "#" alone is not a tag filter
        assertTrue(EventGuard.answersFilter(event, jsonObjectOf("#" to emptyList<String>())))
    }

    @Test
    fun limitIdsAndUnknownKeysAreIgnored() {
        assertTrue(EventGuard.answersFilter(event, jsonObjectOf("limit" to 0, "ids" to listOf("nope"), "search" to "q")))
    }

    @Test
    fun kindsUseDartNumericEquality() {
        // Dart: [1.0].contains(1) is true
        val filter = DartJson.decode("""{"kinds":[1.0]}""") as JsonObject
        assertTrue(EventGuard.answersFilter(event, filter))
        assertFalse(EventGuard.answersFilter(event, jsonObjectOf("kinds" to listOf("1"))))
        assertFalse(EventGuard.answersFilter(event, jsonObjectOf("authors" to listOf("PK"))))
    }

    @Test
    fun typedFilterProducesTheSameJson() {
        val f = NostrFilter(
            kinds = listOf(30078),
            authors = listOf("a"),
            tags = mapOf("d" to listOf("einundzwanzig-reputation")),
            limit = 1,
        )
        assertEquals(
            """{"kinds":[30078],"authors":["a"],"#d":["einundzwanzig-reputation"],"limit":1}""",
            DartJson.encode(f.toJson()),
        )
        val ev = jsonObjectOf("kind" to 30078, "pubkey" to "a", "tags" to listOf(listOf("d", "einundzwanzig-reputation")))
        assertTrue(EventGuard.answersFilter(ev, f))
        assertTrue(EventGuard.answersAnyFilter(ev, listOf(jsonObjectOf("kinds" to listOf(0)), f.toJson())))
        assertFalse(EventGuard.answersAnyFilter(ev, emptyList()))
    }

    @Test
    fun replaceableCreatedAtBounds() {
        val now = 1_000_000_000L
        assertEquals(now + 600, EventGuard.replaceableCreatedAt(jsonObjectOf("created_at" to now + 600), now))
        assertNull(EventGuard.replaceableCreatedAt(jsonObjectOf("created_at" to now + 601), now))
        assertEquals(0L, EventGuard.replaceableCreatedAt(jsonObjectOf("created_at" to 0), now))
        assertNull(EventGuard.replaceableCreatedAt(jsonObjectOf("created_at" to -1), now))
        assertNull(EventGuard.replaceableCreatedAt(jsonObjectOf("created_at" to "5"), now))
        assertNull(EventGuard.replaceableCreatedAt(JsonObject(emptyMap()), now))
    }
}
