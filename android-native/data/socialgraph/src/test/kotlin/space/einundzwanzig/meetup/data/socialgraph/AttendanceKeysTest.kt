package space.einundzwanzig.meetup.data.socialgraph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.socialgraph.coattendance.AttendanceKeyLabel
import space.einundzwanzig.meetup.data.socialgraph.coattendance.AttendanceKeys
import java.time.LocalDate

/** Spec 05 §10.2 vectors: attendanceKey, sharedKeys, degenerate ids, labels. */
class AttendanceKeysTest {

    private val superNpub = "npub1lf0rga7j66uj6enae2mxezamz5nsz3vechhvmh25tcarn4u8qf5q534jzc"

    @Test
    fun `attendanceKey vectors`() {
        assertEquals(
            "aschaffenburg-2026-06-03@u8qf5q534jzc",
            AttendanceKeys.attendanceKey("aschaffenburg-2026-06-03", superNpub),
        )
        assertEquals("ev|evt|31923:abc:xyz", AttendanceKeys.attendanceKey("evt|31923:abc:xyz", superNpub, isEvent = true))
        assertEquals("ev|evt|31923:abc:xyz", AttendanceKeys.attendanceKey("evt|31923:abc:xyz", "", isEvent = true))
        assertEquals("x-2026-01-01", AttendanceKeys.attendanceKey("x-2026-01-01", "  "))
        assertEquals("x-2026-01-01@abc", AttendanceKeys.attendanceKey("x-2026-01-01", "abc"))
        // Exactly 12 characters: kept whole; surrounding whitespace is trimmed first.
        assertEquals("x@abcdefghijkl", AttendanceKeys.attendanceKey("x", " abcdefghijkl "))
    }

    @Test
    fun `sharedKeys vectors`() {
        assertEquals(
            setOf("a-2026-06-03"),
            AttendanceKeys.sharedKeys(setOf("a-2026-06-03"), setOf("a-2026-06-03@u8qf5q534jzc")),
        )
        assertEquals(
            emptySet<String>(),
            AttendanceKeys.sharedKeys(setOf("a-2026-06-03@AAAAAAAAAAAA"), setOf("a-2026-06-03@BBBBBBBBBBBB")),
        )
        assertEquals(setOf("a-2026-06-03@X"), AttendanceKeys.sharedKeys(setOf("a-2026-06-03@X"), setOf("a-2026-06-03@X")))
        val mixed = AttendanceKeys.sharedKeys(
            linkedSetOf("a-2026-06-03@X", "b-2026-01-01"),
            linkedSetOf("a-2026-06-03", "b-2026-01-01@Y"),
        )
        assertEquals(listOf("a-2026-06-03", "b-2026-01-01"), mixed.toList())
        assertEquals(emptySet<String>(), AttendanceKeys.sharedKeys(emptySet(), setOf("a")))
        assertEquals(setOf("ev|e-2026-01-01"), AttendanceKeys.sharedKeys(setOf("ev|e-2026-01-01"), setOf("ev|e-2026-01-01")))
    }

    @Test
    fun `keysMatch and wantedKeys`() {
        assertTrue(AttendanceKeys.keysMatch("a-1@X", "a-1@X"))
        assertTrue(AttendanceKeys.keysMatch("a-1@X", "a-1"))
        assertTrue(AttendanceKeys.keysMatch("a-1", "a-1@Y"))
        assertFalse(AttendanceKeys.keysMatch("a-1@X", "a-1@Y"))
        assertFalse(AttendanceKeys.keysMatch("a-1", "b-1"))
        assertEquals(
            listOf("a-1@X", "a-1", "b-1", "@Z"),
            AttendanceKeys.wantedKeys(listOf("a-1@X", "b-1", "@Z")).toList(),
        )
        // A trailing "@" has an empty suffix but a different length: the base is added.
        assertEquals(listOf("c@", "c"), AttendanceKeys.wantedKeys(listOf("c@")).toList())
    }

    @Test
    fun `degenerate ids`() {
        val degenerate = listOf(
            "", "  ", "-2026-02-25", "Unbekanntes-Meetup-2026-01-01", "unknown-meetup",
            "meetup-desconocido-2026-01-01", "2026-02-25", "test-2026-07-24", "TEST-2026-07-24",
            "ab-test-2026-01-01", "testing",
        )
        for (id in degenerate) assertTrue("'$id' muss degeneriert sein", AttendanceKeys.isDegenerateEventId(id))
        val fine = listOf(
            "test-2026-07-24@u8qf5q534jzc", "berlin-2026-01-01", "home-2026", "ev|test-2026-07-24",
            "aschaffenburg-de-2026-06-03",
        )
        for (id in fine) assertFalse("'$id' ist kein Fehlwert", AttendanceKeys.isDegenerateEventId(id))
    }

    @Test
    fun `label vectors`() {
        assertEquals("Wuerzburg, 12.09.2026", AttendanceKeyLabel.label("wuerzburg-2026-09-12@u8qf5q534jzc"))
        assertEquals("Bad Neuenahr, 05.10.2026", AttendanceKeyLabel.label("bad-neuenahr-2026-10-05"))
        assertEquals("Blocktrainer Event, 05.10.2026", AttendanceKeyLabel.label("ev|blocktrainer-event-2026-10-05"))
        assertEquals("Evt|31923:abc:dtag", AttendanceKeyLabel.label("ev|evt|31923:abc:dtag"))
        assertEquals("Aschaffenburg De, 03.06.2026", AttendanceKeyLabel.label("aschaffenburg-de-2026-06-03@x"))
        assertEquals("Nodate", AttendanceKeyLabel.label("nodate"))
        assertEquals("München, 02.01.2026", AttendanceKeyLabel.label("münchen-2026-01-02"))
        assertEquals("12.09.", AttendanceKeyLabel.dateText(LocalDate.of(2026, 9, 12), withYear = false))
        assertEquals("12.09.2026", AttendanceKeyLabel.dateText(LocalDate.of(2026, 9, 12)))
    }

    @Test
    fun `date overflows like Dart DateTime`() {
        assertEquals(LocalDate.of(2027, 2, 14), AttendanceKeyLabel.date("x-2026-13-45"))
        assertEquals(LocalDate.of(2025, 11, 30), AttendanceKeyLabel.date("x-2026-00-00"))
        assertEquals(null, AttendanceKeyLabel.date("x-2026-1-01"))
        // Dart `$` does not match before a trailing newline (no Java `$` quirk).
        assertEquals(null, AttendanceKeyLabel.date("a-2026-01-01\n"))
        assertEquals("A 2026 01 01\n", AttendanceKeyLabel.label("a-2026-01-01\n"))
        // ß stays ß under Dart toUpperCase.
        assertEquals("ßtraße", AttendanceKeyLabel.place("ßtraße"))
    }

    @Test
    fun `newestFirst and newest`() {
        val keys = listOf("a-2026-01-01", "nodate", "b-2026-03-01@X", "ev|c-2025-12-31")
        assertEquals(listOf("b-2026-03-01@X", "a-2026-01-01", "ev|c-2025-12-31", "nodate"), AttendanceKeyLabel.newestFirst(keys))
        assertEquals(LocalDate.of(2026, 3, 1), AttendanceKeyLabel.newest(keys))
        assertEquals(null, AttendanceKeyLabel.newest(listOf("nodate")))
    }
}
