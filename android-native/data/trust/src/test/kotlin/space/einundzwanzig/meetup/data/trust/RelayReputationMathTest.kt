package space.einundzwanzig.meetup.data.trust

import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.trust.reputation.RelayReputationMath

/** `relayBasicScore`, `hashes.publishContentHash16` and `toStringAsFixed1` of the spec 04 fixture. */
class RelayReputationMathTest {

    @Test
    fun relayBasicScoreTable() {
        for (row in Fixtures["relayBasicScore"].jsonArray.map { it.jsonObject }) {
            fun i(k: String) = row.getValue(k).jsonPrimitive.int
            val raw = RelayReputationMath.basicScore(
                totalBadges = i("totalBadges"),
                boundBadges = i("boundBadges"),
                uniqueMeetups = i("uniqueMeetups"),
                uniqueSigners = i("uniqueSigners"),
                accountAgeDays = i("accountAgeDays"),
            )
            assertEquals(row.toString(), row.getValue("score_raw").jsonPrimitive.double, raw, 1e-9)
            val published = RelayReputationMath.publishedScore(raw)
            assertEquals(row.toString(), row.getValue("score_published").jsonPrimitive.double, published, 0.0)
            assertEquals(row.toString(), row.getValue("level").jsonPrimitive.content, RelayReputationMath.levelForBasic(raw))
        }
    }

    @Test
    fun basicScore_longAccountAge_matchesIntOverloadAndCaps() {
        assertEquals(
            RelayReputationMath.basicScore(4, 2, 2, 2, 90),
            RelayReputationMath.basicScore(4, 2, 2, 2, 90L),
            0.0,
        )
        // Beyond the Int range: age term caps at 2.0 (Dart int64), negative ages clamp to 0.
        val huge = RelayReputationMath.basicScore(0, 0, 0, 0, Int.MAX_VALUE.toLong() * 4)
        assertEquals(2.0, huge, 0.0)
        assertEquals(0.0, RelayReputationMath.basicScore(0, 0, 0, 0, -5L), 0.0)
    }

    @Test
    fun levelForBasicBoundaries() {
        assertEquals("NEU", RelayReputationMath.levelForBasic(0.0))
        assertEquals("STARTER", RelayReputationMath.levelForBasic(0.1))
        assertEquals("STARTER", RelayReputationMath.levelForBasic(1.99))
        assertEquals("AKTIV", RelayReputationMath.levelForBasic(2.0))
        assertEquals("ETABLIERT", RelayReputationMath.levelForBasic(5.0))
        assertEquals("VETERAN", RelayReputationMath.levelForBasic(8.0))
    }

    @Test
    fun toStringAsFixed1Vectors() {
        for (row in Fixtures["toStringAsFixed1"].jsonArray.map { it.jsonObject }) {
            val input = row.getValue("input").jsonPrimitive.double
            assertEquals("$input", row.getValue("expected").jsonPrimitive.content, input.toStringAsFixed(1))
            assertEquals("$input", row.getValue("expected").jsonPrimitive.content.toDouble(), RelayReputationMath.publishedScore(input), 0.0)
        }
    }

    @Test
    fun publishContentHash16Vectors() {
        val rows = Fixtures["hashes"].jsonObject.getValue("publishContentHash16").jsonArray.map { it.jsonObject }
        for (row in rows) {
            val parts = row.getValue("input").jsonPrimitive.content.split("|").map { it.toInt() }
            assertEquals(
                row.getValue("hex16").jsonPrimitive.content,
                RelayReputationMath.changeHash(parts[0], parts[1], parts[2], parts[3]),
            )
        }
    }

    @Test
    fun changeHashFromBadges() {
        // 3 badges, 2 bound, meetups {A, B}, admin pubkeys {"b"*64} plus one empty → "3|2|2|1".
        fun badge(id: String, meetup: String, bound: Boolean, admin: String) = MeetupBadge(
            id = id,
            meetupName = meetup,
            date = DartDateTime.utc(2026, 10, 1),
            iconPath = "",
            sig = "a".repeat(128),
            sigVersion = 2,
            adminPubkey = admin,
            claimSig = if (bound) "d".repeat(128) else "",
            claimPubkey = if (bound) "e".repeat(64) else "",
        )
        val badges = listOf(
            badge("1", "A", true, "b".repeat(64)),
            badge("2", "B", true, "b".repeat(64)),
            badge("3", "A", false, ""),
        )
        assertEquals("08e47cd91958e564", RelayReputationMath.changeHash(badges))
        assertEquals("7ae0599afb0f4e0d", RelayReputationMath.changeHash(emptyList()))
    }

    @Test
    fun meetupListHash12Vectors() {
        for (row in Fixtures["hashes"].jsonObject.getValue("meetupListHash12").jsonArray.map { it.jsonObject }) {
            assertEquals(
                row.getValue("hex12").jsonPrimitive.content,
                RelayReputationMath.meetupListHash(row.getValue("meetupName").jsonPrimitive.content),
            )
        }
        assertEquals("d4ca521ba896", RelayReputationMath.meetupListHash("Aschaffenburg"))
    }

    @Test
    fun meetupListHashes_countedFirstSeenTakeTen() {
        fun badge(id: String, meetup: String, signed: Boolean = true, organizer: Boolean = false) = MeetupBadge(
            id = id,
            meetupName = meetup,
            date = DartDateTime.utc(2026, 10, 1),
            iconPath = "",
            sig = if (signed) "a".repeat(128) else "",
            sigVersion = if (signed) 2L else 0L,
            adminPubkey = if (signed) "b".repeat(64) else "",
            isOrganizer = organizer,
        )
        val badges = listOf(
            badge("u", "Unsigned", signed = false),
            badge("o", "Organizer", organizer = true),
            badge("1", "Wien"),
            badge("2", "Aschaffenburg"),
            badge("3", "Wien"),
        ) + (1..12).map { badge("m$it", "M$it") }
        val hashes = RelayReputationMath.meetupListHashes(badges)
        assertEquals(10, hashes.size)
        assertEquals("7ee037922dee", hashes[0])
        assertEquals("d4ca521ba896", hashes[1])
        assertEquals(RelayReputationMath.meetupListHash("M8"), hashes[9])
        assertEquals(emptyList<String>(), RelayReputationMath.meetupListHashes(emptyList()))
    }

    @Test
    fun sinceUsesStoredZoneFields() {
        val berlin = java.time.ZoneId.of("Europe/Berlin")
        // UTC value: UTC fields regardless of the zone.
        assertEquals("2025-12", RelayReputationMath.since(DartDateTime.utc(2025, 12, 31, 23, 30), berlin))
        // Local value: the same instant read in Berlin is already January.
        val local = DartDateTime.fromMicrosecondsSinceEpoch(DartDateTime.utc(2025, 12, 31, 23, 30).microsecondsSinceEpoch)
        assertEquals("2026-01", RelayReputationMath.since(local, berlin))
        assertEquals("2025-12", RelayReputationMath.since(local, java.time.ZoneOffset.UTC))
        // Year not padded.
        assertEquals("999-03", RelayReputationMath.since(DartDateTime.utc(999, 3, 1), berlin))
    }

    @Test
    fun accountAgeDaysFromEarliestBadge() {
        fun badge(id: String, date: DartDateTime) = MeetupBadge(id = id, meetupName = "A", date = date, iconPath = "")
        val now = DartDateTime.utc(2026, 10, 21, 12)
        val time = FakeTime(now.microsecondsSinceEpoch)
        val badges = listOf(
            badge("1", DartDateTime.utc(2026, 10, 1, 12)),
            badge("2", DartDateTime.utc(2026, 4, 24, 13)),
        )
        // 179 days and 23 hours → 179.
        assertEquals(179L, RelayReputationMath.accountAgeDays(badges, time))
        assertEquals(0L, RelayReputationMath.accountAgeDays(emptyList(), time))
    }
}
