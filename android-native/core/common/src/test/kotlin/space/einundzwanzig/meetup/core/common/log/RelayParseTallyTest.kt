package space.einundzwanzig.meetup.core.common.log

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Port of `test/relay_parse_tally_test.dart`: the tally stays silent when everything is fine and
 * reports ONCE instead of per message. Each test uses its own logger so the counts are exact.
 */
class RelayParseTallyTest {

    private val log = DiagnosticLogger()

    /** The facade shares one process-wide logger; start every test from a clean buffer. */
    @Before
    fun resetSharedLogger() {
        AppLogger.logger.resetForTests()
    }

    @Test
    fun `without failures the tally stays completely silent`() {
        val before = log.entries.value.size
        val t = RelayParseTally("T", "Admin-Liste von wss://relay", log)
        repeat(47) { t.message() }
        t.report()
        assertEquals("47 good messages must not produce a line", before, log.entries.value.size)
    }

    @Test
    fun `reports ONE line with the ratio, not one per message`() {
        val before = log.entries.value.size
        val t = RelayParseTally("T", "Admin-Liste von wss://relay", log)
        for (i in 0 until 47) {
            t.message()
            if (i % 16 == 0) t.failed() // 3 failures: i = 0, 16, 32
        }
        t.report()

        assertEquals("exactly one line — that was the point", before + 1, log.entries.value.size)
        val e = log.entries.value.last()
        assertEquals("WARN", e.level)
        assertTrue("the ratio is the actual information", e.message.contains("3 von 47"))
        assertTrue(e.message.contains("Admin-Liste von wss://relay"))
        assertEquals("Admin-Liste von wss://relay: 3 von 47 Relay-Nachrichten nicht auswertbar", e.message)
        assertEquals(47, t.seen)
        assertEquals(3, t.failures)
    }

    @Test
    fun `report is idempotent - a second call does not duplicate`() {
        val t = RelayParseTally("T", "X", log)
        t.message()
        t.failed(SerializationException("kaputt"))
        val before = log.entries.value.size
        t.report()
        t.report()
        assertEquals("finish() + finally or a double report() must not flood", before + 1, log.entries.value.size)
    }

    @Test
    fun `first parse error lands as debug in verbose mode`() {
        log.setVerbose(true)
        val before = log.entries.value.size
        val t = RelayParseTally("T", "Admin-Liste", log)
        t.message()
        t.failed(IllegalArgumentException("ungueltiges EVENT"))
        t.report()
        assertEquals("warn summary + debug sample", before + 2, log.entries.value.size)
        assertEquals("WARN", log.entries.value[before].level)
        assertEquals("DEBUG", log.entries.value[before + 1].level)
        assertTrue(log.entries.value[before + 1].message.contains("ungueltiges EVENT"))
        assertEquals(
            "Admin-Liste — erster Parse-Fehler: IllegalArgumentException: ungueltiges EVENT",
            log.entries.value[before + 1].message,
        )
        log.setVerbose(false)
    }

    @Test
    fun `only the first non-null error is kept`() {
        log.setVerbose(true)
        val t = RelayParseTally("T", "L", log)
        t.failed()
        t.failed("zweiter")
        t.failed("dritter")
        t.report()
        assertEquals("L — erster Parse-Fehler: zweiter", log.entries.value.last().message)
    }

    @Test
    fun `a report without failures does not block a later report`() {
        // Dart only sets _reported when something was reported.
        val t = RelayParseTally("T", "L", log)
        t.message()
        t.report()
        t.failed()
        val before = log.entries.value.size
        t.report()
        assertEquals(before + 1, log.entries.value.size)
    }

    @Test
    fun `default log is the static facade`() {
        val before = AppLogger.entries.value.size
        RelayParseTally("T", "Fassade").apply { message(); failed(); report() }
        assertEquals(before + 1, AppLogger.entries.value.size)
        assertEquals("Fassade: 1 von 1 Relay-Nachrichten nicht auswertbar", AppLogger.entries.value.last().message)
    }
}
