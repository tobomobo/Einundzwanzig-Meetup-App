package space.einundzwanzig.meetup.data.reputation

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.nostr.NostrEvents
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.reputation.event.ReputationEvent
import space.einundzwanzig.meetup.data.reputation.event.ReputationEventParser
import space.einundzwanzig.meetup.data.socialgraph.social.SocialStats

/** Spec 04 §10.4 `ReputationEventParserTest` on the `reputationEvent30078` fixture. */
class ReputationEventParserTest {

    private val section = Fixtures["reputationEvent30078"]
    private val rawEvent = section.getValue("event").jsonObject

    /** The `["EVENT", {…}]` frame the publisher sends is byte-identical to Flutter's. */
    @Test
    fun publishFrameMatchesFixture() {
        val event = NostrEvents.fromJsonOrNull(rawEvent)!!
        assertEquals(section.getValue("publish_frame").jsonPrimitive.content, event.toEventMessage())
    }

    @Test
    fun fixtureEventIsValidAndParsesToExpected() {
        val event = NostrEvents.fromJsonOrNull(rawEvent)!!
        assertTrue(event.isValid())
        val parsed = ReputationEventParser.parse(event)!!
        val e = section.getValue("expected_parsed").jsonObject
        fun s(k: String) = e.getValue(k).jsonPrimitive.content
        fun i(k: String) = e.getValue(k).jsonPrimitive.int

        assertEquals(s("pubkeyHex"), parsed.pubkeyHex)
        assertEquals(s("npub"), parsed.npub)
        assertEquals(s("eventId"), parsed.eventId)
        assertEquals(rawEvent.getValue("sig").jsonPrimitive.content, parsed.signature)
        assertEquals(e.getValue("createdAt").jsonPrimitive.long, parsed.createdAt)
        assertEquals(i("version"), parsed.version)
        assertEquals(s("nickname"), parsed.nickname)
        assertEquals(e.getValue("score").jsonPrimitive.double, parsed.score, 0.0)
        assertEquals(s("level"), parsed.level)
        assertEquals(i("totalBadges"), parsed.totalBadges)
        assertEquals(i("verifiedBadges"), parsed.verifiedBadges)
        assertEquals(i("boundBadges"), parsed.boundBadges)
        assertEquals(i("retroactiveBadges"), parsed.retroactiveBadges)
        assertEquals(i("meetupCount"), parsed.meetupCount)
        assertEquals(i("signerCount"), parsed.signerCount)
        assertEquals(i("accountAgeDays"), parsed.accountAgeDays)
        assertEquals(s("since"), parsed.since)
        assertEquals(s("badgeProofHash"), parsed.badgeProofHash)
        assertEquals(i("proofVersion"), parsed.proofVersion)
        assertEquals(e.getValue("platformProofs").jsonObject, JsonObject(parsed.platformProofs))
        val social = e.getValue("socialStats").jsonObject
        assertEquals(
            SocialStats(social.getValue("followCount").jsonPrimitive.int, social.getValue("estimatedMutualCount").jsonPrimitive.int, social.getValue("orgFollowerCount").jsonPrimitive.int),
            parsed.socialStats,
        )
        val zap = e.getValue("zapStats").jsonObject
        assertEquals(
            ZapStats(
                zap.getValue("sentCount").jsonPrimitive.int,
                zap.getValue("receivedCount").jsonPrimitive.int,
                zap.getValue("uniqueRecipientCount").jsonPrimitive.int,
                zap.getValue("uniqueSenderCount").jsonPrimitive.int,
                zap.getValue("activeMonths").jsonPrimitive.int,
                zap.getValue("hasLightningProof").jsonPrimitive.content.toBoolean(),
            ),
            parsed.zapStats,
        )
        assertTrue(parsed.humanityVerified)
        assertEquals(JsonNull, e.getValue("humanityReceiptId"))
        assertNull(parsed.humanityReceiptId)
        assertEquals(e.getValue("updatedAt").jsonPrimitive.long, parsed.updatedAt)

        val proof = parsed.platformProof("telegram")!!
        assertEquals("satoshi", proof.username)
        assertEquals(1_760_000_000L, proof.createdAt)
        assertEquals(7, parsed.toFacts().boundBadges)
        assertEquals(8, parsed.toFacts().totalBadges)
    }

    @Test
    fun rawObjectParsesTheSame() {
        val viaRaw = ReputationEventParser.parseRaw(rawEvent)
        val viaEvent = ReputationEventParser.parse(NostrEvents.fromJsonOrNull(rawEvent)!!)
        assertEquals(viaEvent, viaRaw)
    }

    @Test
    fun emptyContentObjectGivesDartDefaults() {
        val parsed = ReputationEventParser.parse(KEY1.publicKeyHex, "id", "sig", 1_700_000_000L, "{}")!!
        val d = section.getValue("parse_defaults_for_empty_content_object").jsonObject
        assertEquals(d.getValue("version").jsonPrimitive.int, parsed.version)
        assertEquals(d.getValue("nickname").jsonPrimitive.content, parsed.nickname)
        assertEquals(d.getValue("score").jsonPrimitive.double, parsed.score, 0.0)
        assertEquals(d.getValue("level").jsonPrimitive.content, parsed.level)
        for ((key, value) in listOf(
            "totalBadges" to parsed.totalBadges, "verifiedBadges" to parsed.verifiedBadges,
            "boundBadges" to parsed.boundBadges, "retroactiveBadges" to parsed.retroactiveBadges,
            "meetupCount" to parsed.meetupCount, "signerCount" to parsed.signerCount,
            "accountAgeDays" to parsed.accountAgeDays, "proofVersion" to parsed.proofVersion,
        )) {
            assertEquals(key, d.getValue(key).jsonPrimitive.int, value)
        }
        assertEquals("", parsed.since)
        assertEquals("", parsed.badgeProofHash)
        assertTrue(parsed.platformProofs.isEmpty())
        assertNull(parsed.socialStats)
        assertNull(parsed.zapStats)
        assertFalse(parsed.humanityVerified)
        assertNull(parsed.humanityReceiptId)
        assertEquals(1_700_000_000L, parsed.updatedAt) // "= event.created_at"
        assertEquals(KEY1.npub, parsed.npub)
    }

    @Test
    fun contentThatIsNotAnObjectIsUnusable() {
        assertNull(ReputationEventParser.parse(KEY1.publicKeyHex, "id", "sig", 1L, "[1]"))
        assertNull(ReputationEventParser.parse(KEY1.publicKeyHex, "id", "sig", 1L, "not json"))
        assertNull(ReputationEventParser.parse(KEY1.publicKeyHex, "id", "sig", 1L, "\"x\""))
    }

    @Test
    fun wrongTypesAreReadLeniently() {
        // Documented choice (spec 04 §3.8): Flutter drops the event (`3.0 as int` throws); native
        // accepts integral doubles and falls back to the default for other wrong types.
        val parsed = ReputationEventParser.parse(
            KEY1.publicKeyHex, "id", "sig", 1L,
            """{"version":2.0,"identity":"x","stats":{"total_badges":3.0,"bound_badges":"3","score":"7","level":5,"meetup_count":2.5},"humanity_proof":{"verified":"true"},"updated_at":12.0}""",
        )!!
        assertEquals(2, parsed.version)
        assertEquals("Anon", parsed.nickname)
        assertEquals(3, parsed.totalBadges)
        assertEquals(0, parsed.boundBadges)
        assertEquals(0.0, parsed.score, 0.0)
        assertEquals("NEU", parsed.level)
        assertEquals(0, parsed.meetupCount)
        assertFalse(parsed.humanityVerified)
        assertEquals(12L, parsed.updatedAt)
    }

    @Test
    fun invalidPubkeyGivesEmptyNpub() {
        assertEquals("", ReputationEventParser.parse("zz", "id", "sig", 1L, "{}")!!.npub)
    }

    @Test
    fun ageInHoursAndFreshness() {
        val e = ReputationEvent(pubkeyHex = "p", npub = "", eventId = "i", signature = "s", createdAt = 1_000_000L)
        assertEquals(0L, e.ageInHours(1_000_000L + 1799))
        assertEquals(1L, e.ageInHours(1_000_000L + 1800)) // .5 rounds away from zero
        assertTrue(e.isFresh(1_000_000L + 167 * 3600))
        assertFalse(e.isFresh(1_000_000L + 168 * 3600))
        assertEquals(-1L, e.ageInHours(1_000_000L - 1800))
        assertNotNull(e.toFacts())
    }

    @Test
    fun platformProofOfNonObjectEntryIsNull() {
        val e = ReputationEvent(
            pubkeyHex = "p", npub = "", eventId = "i", signature = "s", createdAt = 1L,
            platformProofs = mapOf("x" to JsonPrimitive("y")),
        )
        assertNull(e.platformProof("x"))
        assertNull(e.platformProof("missing"))
    }
}
