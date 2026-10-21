package space.einundzwanzig.meetup.data.reputation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.data.badges.model.BadgeProofs
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.reputation.content.ReputationContentBuilder
import space.einundzwanzig.meetup.data.socialgraph.social.SocialStats
import java.time.ZoneOffset

/** Spec 04 §10.4 `ReputationContentBuilderTest` (§3.10 key order and types). */
class ReputationContentBuilderTest {

    private val time = FakeTime(FIXTURE_SECONDS * 1000)
    private val day = 86_400_000L

    private fun at(daysAgo: Long) = DartDateTime.fromMillisecondsSinceEpoch(time.millis - daysAgo * day, isUtc = true)

    /** total 8, bound 7 (1 retroactive), 4 meetups, 3 admin pubkeys, first badge 120 days ago. */
    private fun fixtureBasis(): List<MeetupBadge> = listOf(
        signedBadge("b1", "Aschaffenburg", at(120), adminPubkey = "1".repeat(64), bound = true, claimTimestamp = 1),
        signedBadge("b2", "Darmstadt", at(90), adminPubkey = "2".repeat(64), bound = true, claimTimestamp = 2),
        signedBadge("b3", "Frankfurt", at(60), adminPubkey = "3".repeat(64), bound = true, claimTimestamp = 3),
        signedBadge("b4", "Wien", at(50), adminPubkey = "1".repeat(64), bound = true, claimTimestamp = 4),
        signedBadge("b5", "Aschaffenburg", at(40), adminPubkey = "2".repeat(64), bound = true, claimTimestamp = 5),
        signedBadge("b6", "Darmstadt", at(30), adminPubkey = "3".repeat(64), bound = true, claimTimestamp = 6),
        signedBadge("b7", "Frankfurt", at(20), adminPubkey = "1".repeat(64), bound = true, retroactive = true, claimTimestamp = 7),
        signedBadge("b8", "Wien", at(10), adminPubkey = "2".repeat(64)),
    )

    private val social = SocialStats(120, 42, 2)
    private val zaps = ZapStats(12, 5, 4, 3, 6, true)
    private val humanity = jsonObjectOf("verified" to true, "method" to "lightning_zap", "first_zap_at" to 1_739_905_680L)
    private val proofs = linkedMapOf("telegram" to PlatformProof("telegram", "satoshi", "f".repeat(128), 1_760_000_000L))

    @Test
    fun contentMatchesFixtureLayout() {
        val basis = fixtureBasis()
        val content = ReputationContentBuilder.build(basis, "Satoshi", social, zaps, humanity, proofs, time, ZoneOffset.UTC)

        val fixture = DartJson.decode(
            Fixtures["reputationEvent30078"].getValue("event").jsonObject.getValue("content").jsonPrimitive.content,
        ) as JsonObject
        // The fixture is synthetic: `since` and the proof hash cannot both match real badges.
        val stats = fixture.getValue("stats").jsonObject
        val proof = fixture.getValue("proof").jsonObject
        val expected = JsonObject(
            fixture +
                ("stats" to JsonObject(stats + ("since" to JsonPrimitive("2025-06")))) +
                ("proof" to JsonObject(proof + ("badge_proof_hash" to JsonPrimitive(BadgeProofs.generateBadgeProofV2(basis))))),
        )
        assertEquals(DartJson.encode(expected), ReputationContentBuilder.encode(content))
    }

    @Test
    fun basicInputsMatchFixture() {
        val inputs = Fixtures["reputationEvent30078"].getValue("basic_score_inputs_for_content").jsonObject
        val stats = ReputationContentBuilder.build(fixtureBasis(), "x", null, null, null, null, time, ZoneOffset.UTC)
            .getValue("stats").jsonObject
        assertEquals(inputs.getValue("totalBadges").jsonPrimitive.int, stats.getValue("total_badges").jsonPrimitive.int)
        assertEquals(inputs.getValue("boundBadges").jsonPrimitive.int, stats.getValue("bound_badges").jsonPrimitive.int)
        assertEquals(inputs.getValue("uniqueMeetups").jsonPrimitive.int, stats.getValue("meetup_count").jsonPrimitive.int)
        assertEquals(inputs.getValue("uniqueSigners").jsonPrimitive.int, stats.getValue("signer_count").jsonPrimitive.int)
        assertEquals(inputs.getValue("accountAgeDays").jsonPrimitive.int, stats.getValue("account_age_days").jsonPrimitive.int)
        assertEquals(inputs.getValue("expected_basic_score").jsonPrimitive.content, stats.getValue("score").jsonPrimitive.content)
        assertEquals(inputs.getValue("expected_level").jsonPrimitive.content, stats.getValue("level").jsonPrimitive.content)
    }

    @Test
    fun optionalBlocksFollowDartRules() {
        val basis = fixtureBasis()
        val none = ReputationContentBuilder.build(basis, "", SocialStats(0, 5, 5), ZapStats.EMPTY, null, emptyMap(), time, ZoneOffset.UTC)
        assertEquals(listOf("version", "identity", "stats", "proof", "updated_at"), none.keys.toList())
        assertEquals("Anon", none.getValue("identity").jsonObject.getValue("nickname").jsonPrimitive.content)
        assertEquals(FIXTURE_SECONDS, none.getValue("updated_at").jsonPrimitive.content.toLong())

        val nulls = ReputationContentBuilder.build(basis, "N", null, null, null, null, time, ZoneOffset.UTC)
        assertEquals(none.keys, nulls.keys)

        val all = ReputationContentBuilder.build(basis, "N", social, zaps, humanity, proofs, time, ZoneOffset.UTC)
        assertEquals(
            listOf("version", "identity", "stats", "proof", "updated_at", "social", "lightning", "humanity_proof", "platform_proofs"),
            all.keys.toList(),
        )
    }

    @Test
    fun scoreIsADoubleAndCountsAreInts() {
        val one = listOf(signedBadge("b1", "Wien", at(0), bound = true))
        val json = ReputationContentBuilder.encode(ReputationContentBuilder.build(one, "N", null, null, null, null, time, ZoneOffset.UTC))
        // bound 1 → 0.3, meetups 1 → 0.5, signers 1 → 0.7, all bound → +1.0 = 2.5.
        assertTrue(json, json.contains(""""stats":{"score":2.5,"level":"AKTIV","total_badges":1,"verified_badges":1,"bound_badges":1,"retroactive_badges":0,"meetup_count":1,"signer_count":1,"account_age_days":0,"since":"2025-10"}"""))
        assertTrue(json, json.contains(""""proof_version":2"""))
        val unbound = listOf(signedBadge("b1", "Wien", at(0)))
        val json2 = ReputationContentBuilder.encode(ReputationContentBuilder.build(unbound, "N", null, null, null, null, time, ZoneOffset.UTC))
        assertTrue(json2, json2.contains(""""proof_version":1"""))
        assertTrue(json2, json2.contains(""""score":1.2,"""))
    }

    @Test
    fun publishBasisDropsMarkersAndUnsigned() {
        val signed = signedBadge("b1", "Wien", at(5))
        val signedMarker = signedBadge("m1", "Graz", at(5), organizer = true)
        val unsignedMarker = markerBadge("m2", "Linz", at(5))
        val legacy = MeetupBadge(id = "l", meetupName = "Bern", date = at(5), iconPath = "", sig = "c".repeat(128), sigVersion = 1)
        assertEquals(listOf(signed), ReputationContentBuilder.publishBasis(listOf(signedMarker, signed, unsignedMarker, legacy)))
        assertTrue(ReputationContentBuilder.publishBasis(listOf(unsignedMarker, legacy)).isEmpty())
    }

    @Test
    fun changeHashUsesCountsOfTheBasis() {
        // 3 badges, 2 bound, meetups {A, B}, admin pubkeys {1…} plus one empty → "3|2|2|1".
        val badges = listOf(
            signedBadge("b1", "A", at(1), adminPubkey = "1".repeat(64), bound = true),
            signedBadge("b2", "B", at(1), adminPubkey = "1".repeat(64), bound = true),
            signedBadge("b3", "A", at(1), adminPubkey = ""),
        )
        assertEquals("08e47cd91958e564", ReputationContentBuilder.changeHash(badges))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyBasisIsRejected() {
        ReputationContentBuilder.build(emptyList(), "N", null, null, null, null, time, ZoneOffset.UTC)
    }

    @Test
    fun sinceUsesLocalFieldsOfLocalDates() {
        // A local date just after midnight on July 1st in UTC+2 is still June in UTC.
        val local = DartDateTime.parse("2025-07-01T00:30:00.000", ZoneOffset.ofHours(2))
        val badge = signedBadge("b1", "Wien", local)
        val content = ReputationContentBuilder.build(listOf(badge), "N", null, null, null, null, time, ZoneOffset.ofHours(2))
        assertEquals("2025-07", content.getValue("stats").jsonObject.getValue("since").jsonPrimitive.content)
        assertFalse(local.isUtc)
    }
}
