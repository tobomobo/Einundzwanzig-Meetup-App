package space.einundzwanzig.meetup.core.common.dart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.SystemTimeSource
import space.einundzwanzig.meetup.core.common.TimeSource
import java.time.ZoneId

/** Vectors computed with Dart 3.13.5 under `TZ=Europe/Berlin`. */
class DartDateTimeTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private data class ParseCase(val input: String, val iso: String, val isUtc: Boolean, val micros: Long, val dartString: String)

    @Test
    fun `parse and toIso8601String match Dart`() {
        val cases = listOf(
            ParseCase("2025-03-15T00:00:00.000Z", "2025-03-15T00:00:00.000Z", true, 1741996800000000, "2025-03-15 00:00:00.000Z"),
            ParseCase("2026-10-11T19:03:12.345678", "2026-10-11T19:03:12.345678", false, 1791738192345678, "2026-10-11 19:03:12.345678"),
            ParseCase("2026-10-11T19:03:12.345000", "2026-10-11T19:03:12.345", false, 1791738192345000, "2026-10-11 19:03:12.345"),
            ParseCase("2026-10-11T19:03:12Z", "2026-10-11T19:03:12.000Z", true, 1791745392000000, "2026-10-11 19:03:12.000Z"),
            ParseCase("2026-10-11T19:03:12+02:00", "2026-10-11T17:03:12.000Z", true, 1791738192000000, "2026-10-11 17:03:12.000Z"),
            ParseCase("2026-10-11 19:03", "2026-10-11T19:03:00.000", false, 1791738180000000, "2026-10-11 19:03:00.000"),
            ParseCase("2026-10-11T19:03:12.1234567", "2026-10-11T19:03:12.123456", false, 1791738192123456, "2026-10-11 19:03:12.123456"),
            ParseCase("2026-10-11T19:03:12,5", "2026-10-11T19:03:12.500", false, 1791738192500000, "2026-10-11 19:03:12.500"),
            ParseCase("20261011T190312", "2026-10-11T19:03:12.000", false, 1791738192000000, "2026-10-11 19:03:12.000"),
            ParseCase("2026-10-11", "2026-10-11T00:00:00.000", false, 1791669600000000, "2026-10-11 00:00:00.000"),
            // DST gap: 02:30 does not exist and becomes 03:30 CEST, like in Dart.
            ParseCase("2026-03-29T02:30:00", "2026-03-29T03:30:00.000", false, 1774747800000000, "2026-03-29 03:30:00.000"),
            // DST overlap: the earlier instant (CEST) wins.
            ParseCase("2026-10-25T02:30:00", "2026-10-25T02:30:00.000", false, 1792888200000000, "2026-10-25 02:30:00.000"),
            ParseCase("2026-10-25T03:00:00", "2026-10-25T03:00:00.000", false, 1792893600000000, "2026-10-25 03:00:00.000"),
            ParseCase("2026-10-25T01:59:59.999999", "2026-10-25T01:59:59.999999", false, 1792886399999999, "2026-10-25 01:59:59.999999"),
            ParseCase("2026-13-01", "2027-01-01T00:00:00.000", false, 1798758000000000, "2027-01-01 00:00:00.000"),
            ParseCase("2026-02-31T10:00", "2026-03-03T10:00:00.000", false, 1772528400000000, "2026-03-03 10:00:00.000"),
            ParseCase("2026-10-11T24:00:00", "2026-10-12T00:00:00.000", false, 1791756000000000, "2026-10-12 00:00:00.000"),
            ParseCase("+002026-10-11", "2026-10-11T00:00:00.000", false, 1791669600000000, "2026-10-11 00:00:00.000"),
            ParseCase("-000001-01-01T00:00:00Z", "-0001-01-01T00:00:00.000Z", true, -62198755200000000, "-0001-01-01 00:00:00.000Z"),
            ParseCase("2026-10-11T19:03:12 Z", "2026-10-11T19:03:12.000Z", true, 1791745392000000, "2026-10-11 19:03:12.000Z"),
            ParseCase("2026-10-11T19:03:12+0530", "2026-10-11T13:33:12.000Z", true, 1791725592000000, "2026-10-11 13:33:12.000Z"),
            ParseCase("2026-10-11T19:03:12-02", "2026-10-11T21:03:12.000Z", true, 1791752592000000, "2026-10-11 21:03:12.000Z"),
            ParseCase("2026-10-11T19:03:12.123+01:00", "2026-10-11T18:03:12.123Z", true, 1791741792123000, "2026-10-11 18:03:12.123Z"),
            ParseCase("2026-10-11T19:03:12z", "2026-10-11T19:03:12.000Z", true, 1791745392000000, "2026-10-11 19:03:12.000Z"),
            ParseCase("2026-10-11T1903", "2026-10-11T19:03:00.000", false, 1791738180000000, "2026-10-11 19:03:00.000"),
            ParseCase("2026-10-11T19", "2026-10-11T19:00:00.000", false, 1791738000000000, "2026-10-11 19:00:00.000"),
            ParseCase("2026-10-11T19:03:60", "2026-10-11T19:04:00.000", false, 1791738240000000, "2026-10-11 19:04:00.000"),
            ParseCase("2026-10-11T19:61", "2026-10-11T20:01:00.000", false, 1791741660000000, "2026-10-11 20:01:00.000"),
            ParseCase("10000-01-01T00:00:00Z", "+010000-01-01T00:00:00.000Z", true, 253402300800000000, "10000-01-01 00:00:00.000Z"),
            ParseCase("0099-01-01T00:00:00Z", "0099-01-01T00:00:00.000Z", true, -59042995200000000, "0099-01-01 00:00:00.000Z"),
            ParseCase("2026-10-11T19:03:12.000001Z", "2026-10-11T19:03:12.000001Z", true, 1791745392000001, "2026-10-11 19:03:12.000001Z"),
            ParseCase("2026-10-11T19:03:12.999999999Z", "2026-10-11T19:03:12.999999Z", true, 1791745392999999, "2026-10-11 19:03:12.999999Z"),
            ParseCase("2026-10-11T19:03:12 +02:00", "2026-10-11T17:03:12.000Z", true, 1791738192000000, "2026-10-11 17:03:12.000Z"),
            ParseCase("275760-09-13T00:00:00Z", "+275760-09-13T00:00:00.000Z", true, 8640000000000000000, "275760-09-13 00:00:00.000Z"),
            ParseCase("1969-12-31T23:59:59.999999Z", "1969-12-31T23:59:59.999999Z", true, -1, "1969-12-31 23:59:59.999999Z"),
        )
        for (c in cases) {
            val d = DartDateTime.parse(c.input, berlin)
            assertEquals(c.input, c.micros, d.microsecondsSinceEpoch)
            assertEquals(c.input, c.isUtc, d.isUtc)
            assertEquals(c.input, c.iso, d.toIso8601String(berlin))
            assertEquals(c.input, c.dartString, d.toDartString(berlin))
        }
    }

    @Test
    fun `parse rejects what Dart rejects`() {
        val invalid = listOf(
            "2026-10-11T19:03:12.", "2026-1-11", "abc", "2026-10-11T19:03:12+02:00:00", " 2026-10-11",
            "275760-09-13T00:00:00.001Z", "2026-10-11\n", "",
        )
        for (s in invalid) {
            assertNull(s, DartDateTime.tryParse(s, berlin))
            assertThrows(s, DartFormatException::class.java) { DartDateTime.parse(s, berlin) }
        }
    }

    @Test
    fun `spec fixture dartIso8601`() {
        // spec/fixtures/02-badges-claim-vectors.json "dartIso8601"
        assertEquals("2025-03-15T19:00:00.000Z", DartDateTime.utc(2025, 3, 15, 19).toIso8601String(berlin))
        assertEquals("2025-03-15T19:00:00.000", DartDateTime.local(2025, 3, 15, 19, zone = berlin).toIso8601String(berlin))
        assertEquals("2025-03-15T19:00:00.123456Z", DartDateTime.utc(2025, 3, 15, 19, 0, 0, 123, 456).toIso8601String(berlin))
        assertEquals("2025-01-02T03:04:05.006", DartDateTime.local(2025, 1, 2, 3, 4, 5, 6, 0, berlin).toIso8601String(berlin))
    }

    @Test
    fun `constructors normalise overflowing fields like Dart`() {
        val cases = listOf(
            DartDateTime.local(2026, 13, 1, zone = berlin) to Triple("2027-01-01T00:00:00.000", 1798758000000000L, 5),
            DartDateTime.local(2026, 2, 31, zone = berlin) to Triple("2026-03-03T00:00:00.000", 1772492400000000L, 2),
            DartDateTime.local(2026, 0, 0, zone = berlin) to Triple("2025-11-30T00:00:00.000", 1764457200000000L, 7),
            DartDateTime.local(2026, 10, 11, 19, 3, 12, 0, 7, berlin) to Triple("2026-10-11T19:03:12.000007", 1791738192000007L, 7),
            DartDateTime.utc(2025, 3, 15) to Triple("2025-03-15T00:00:00.000Z", 1741996800000000L, 6),
            DartDateTime.utc(-1, 1, 1) to Triple("-0001-01-01T00:00:00.000Z", -62198755200000000L, 5),
            DartDateTime.utc(10000, 1, 1) to Triple("+010000-01-01T00:00:00.000Z", 253402300800000000L, 6),
            DartDateTime.utc(123, 4, 5, 6, 7, 8, 9, 10) to Triple("0123-04-05T06:07:08.009010Z", -58277555571990990L, 1),
            DartDateTime.local(2026, 3, 29, 2, 30, zone = berlin) to Triple("2026-03-29T03:30:00.000", 1774747800000000L, 7),
            DartDateTime.local(2026, 10, 25, 2, 30, zone = berlin) to Triple("2026-10-25T02:30:00.000", 1792888200000000L, 7),
            DartDateTime.utc(2026, 1, 1, 25, 61, 61, 1001, 1001) to Triple("2026-01-02T02:02:02.002001Z", 1767319322002001L, 5),
            DartDateTime.local(2026, 10, 11, -1, zone = berlin) to Triple("2026-10-10T23:00:00.000", 1791666000000000L, 6),
            DartDateTime.utc(0, 1, 1) to Triple("0000-01-01T00:00:00.000Z", -62167219200000000L, 6),
            DartDateTime.utc(-10000, 1, 1) to Triple("-010000-01-01T00:00:00.000Z", -377736739200000000L, 6),
            DartDateTime.fromMillisecondsSinceEpoch(1000) to Triple("1970-01-01T01:00:01.000", 1000000L, 4),
            DartDateTime.fromMillisecondsSinceEpoch(1700000000123, isUtc = true) to Triple("2023-11-14T22:13:20.123Z", 1700000000123000L, 2),
            DartDateTime.fromMicrosecondsSinceEpoch(-1, isUtc = true) to Triple("1969-12-31T23:59:59.999999Z", -1L, 3),
        )
        for ((d, expected) in cases) {
            val (iso, micros, weekday) = expected
            assertEquals(iso, iso, d.toIso8601String(berlin))
            assertEquals(iso, micros, d.microsecondsSinceEpoch)
            assertEquals(iso, weekday, d.fields(berlin).dayOfWeek.value)
        }
    }

    @Test
    fun `spec 03 table and verification hash input`() {
        // spec/03-badge-wallet-ui.md §10.2
        assertEquals("2025-03-15T00:00:00.000Z", DartDateTime.utc(2025, 3, 15).toIso8601String(berlin))
        assertEquals("2026-10-11T19:03:12.000007", DartDateTime.local(2026, 10, 11, 19, 3, 12, 0, 7, berlin).toIso8601String(berlin))
        val badgeB = DartDateTime.parse("2026-10-11T19:03:12.345678", berlin)
        assertEquals("old-badge-München, DE-2026-10-11T19:03:12.345678-0", "old-badge-München, DE-${badgeB.toIso8601String(berlin)}-0")
        val withOffset = DartDateTime.parse("2026-10-11T19:03:12+02:00", berlin)
        assertTrue(withOffset.isUtc)
        assertEquals(11, withOffset.fields(berlin).dayOfMonth)
    }

    @Test
    fun `millisecondsSinceEpoch floors like Dart`() {
        assertEquals(-1L, DartDateTime.fromMicrosecondsSinceEpoch(-1).millisecondsSinceEpoch)
        assertEquals(-2L, DartDateTime.fromMicrosecondsSinceEpoch(-1001).millisecondsSinceEpoch)
        assertEquals(1791738192345L, DartDateTime.parse("2026-10-11T19:03:12.345678", berlin).millisecondsSinceEpoch)
    }

    @Test
    fun `difference and inDays match Dart`() {
        val a = DartDateTime.parse("2026-10-24T20:00:00", berlin)
        val b = DartDateTime.parse("2026-10-25T19:00:00", berlin) // 24 h because of the DST change
        assertEquals(1L, b.difference(a).inDays)
        assertEquals(24L, b.difference(a).inHours)
        assertEquals(-1L, a.difference(b).inDays)
    }

    @Test
    fun `equality includes isUtc while ordering only uses the instant`() {
        val utc = DartDateTime.parse("2026-10-11T17:03:12Z", berlin)
        val local = utc.toLocal()
        assertNotEquals(utc, local)
        assertTrue(utc.isAtSameMomentAs(local))
        assertEquals(0, utc.compareTo(local))
        assertEquals(utc, local.toUtc())
        assertEquals("2026-10-11T19:03:12.000", local.toIso8601String(berlin))
        assertFalse(utc.isBefore(local) || utc.isAfter(local))
    }

    @Test
    fun `fields of UTC values ignore the zone`() {
        val d = DartDateTime.utc(2026, 10, 11, 23, 30)
        assertEquals(11, d.fields(berlin).dayOfMonth)
        assertEquals(12, d.toLocal().fields(berlin).dayOfMonth)
        assertEquals(30, d.toLocal().fields(berlin).minute)
    }

    @Test
    fun `add and subtract work on the instant`() {
        val d = DartDateTime.utc(2026, 1, 1)
        assertEquals(DartDateTime.utc(2026, 1, 2), d.add(DartDuration.of(days = 1)))
        assertEquals(DartDateTime.utc(2025, 12, 31, 23), d.subtract(DartDuration.of(hours = 1)))
    }

    @Test
    fun `now uses the time source and is local`() {
        val source = object : TimeSource {
            override fun nowMillis() = 1700000000123L
            override fun nowMicros() = 1700000000123456L
        }
        val now = DartDateTime.now(source)
        assertFalse(now.isUtc)
        assertEquals(1700000000123456L, now.microsecondsSinceEpoch)
        // Dart DateTime.now() has microsecond precision: six fraction digits.
        assertEquals("2023-11-14T23:13:20.123456", now.toIso8601String(ZoneId.of("Europe/Berlin")))
        val utc = DartDateTime.nowUtc(source)
        assertTrue(utc.isUtc)
        assertEquals("2023-11-14T22:13:20.123456Z", utc.toIso8601String())
    }

    @Test
    fun `a millisecond-only time source still works`() {
        val source = object : TimeSource {
            override fun nowMillis() = 1700000000123L
        }
        assertEquals(1700000000123000L, DartDateTime.now(source).microsecondsSinceEpoch)
    }

    @Test
    fun `the system clock micros agree with its millis`() {
        val clock = SystemTimeSource()
        val before = clock.nowMillis()
        val micros = clock.nowMicros()
        val after = clock.nowMillis()
        assertTrue(micros / 1000 in before..after)
    }

    @Test
    fun `values outside the Dart range are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { DartDateTime.fromMillisecondsSinceEpoch(DartDateTime.MAX_MILLIS + 1) }
        assertThrows(IllegalArgumentException::class.java) { DartDateTime.utc(275760, 9, 13, 0, 0, 0, 1) }
    }
}
