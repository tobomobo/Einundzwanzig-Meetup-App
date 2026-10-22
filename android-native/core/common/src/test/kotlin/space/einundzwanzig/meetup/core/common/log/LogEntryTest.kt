package space.einundzwanzig.meetup.core.common.log

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import java.time.LocalDateTime
import java.time.ZoneId

class LogEntryTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val time = LocalDateTime.of(2026, 3, 5, 7, 8, 9).atZone(berlin).toInstant().toEpochMilli()

    @Test
    fun `export and screen line formats (spec 12 test 9)`() {
        val entry = LogEntry(time, "WARN", "Portal", "x")
        assertEquals("05.03 07:08:09  WARN  [Portal] x", entry.format(berlin))
        assertEquals("07:08:09  WARN   Portal: x", entry.screenLine(berlin))
        assertEquals("07:08:09", entry.timeOfDay(berlin))
        assertEquals("05.03 07:08:09  DEBUG [T] m", LogEntry(time, "DEBUG", "T", "m").format(berlin))
        assertEquals("05.03 07:08:09  SEC   [T] m", LogEntry(time, "SEC", "T", "m").format(berlin))
    }

    @Test
    fun `toJson writes the Dart keys in order`() {
        assertEquals("""{"t":1000,"l":"INFO","g":"alt","m":"Eintrag von gestern"}""", LogEntry(1000, "INFO", "alt", "Eintrag von gestern").toJsonLine())
        assertEquals("""{"t":1,"l":"ERROR","g":"T","m":"a\nb \"q\""}""", LogEntry(1, "ERROR", "T", "a\nb \"q\"").toJsonLine())
    }

    @Test
    fun `fromJson applies the Dart defaults and casts`() {
        assertEquals(LogEntry(1000, "INFO", "", ""), LogEntry.fromJson(obj("""{"t":1000}""")))
        assertEquals(LogEntry(1000, "INFO", "", ""), LogEntry.fromJson(obj("""{"t":1000,"l":null,"g":null,"m":null}""")))
        assertEquals(LogEntry(1999, "SEC", "g", "m"), LogEntry.fromJson(obj("""{"t":1999.9,"l":"SEC","g":"g","m":"m"}""")))
        assertThrows(IllegalArgumentException::class.java) { LogEntry.fromJson(obj("""{"l":"INFO"}""")) }
        assertThrows(IllegalArgumentException::class.java) { LogEntry.fromJson(obj("""{"t":"1000"}""")) }
        assertThrows(IllegalArgumentException::class.java) { LogEntry.fromJson(obj("""{"t":1,"l":5}""")) }
    }

    @Test
    fun `reads the Flutter preferences buffer`() {
        val raw = """[{"t":1000,"l":"INFO","g":"alt","m":"Eintrag von gestern"},{"t":2000,"l":"WARN","g":"Portal","m":"x"}]"""
        assertEquals(
            listOf(LogEntry(1000, "INFO", "alt", "Eintrag von gestern"), LogEntry(2000, "WARN", "Portal", "x")),
            LogEntry.listFromDartJson(raw),
        )
        assertThrows(Exception::class.java) { LogEntry.listFromDartJson("{corrupt") }
        assertThrows(IllegalArgumentException::class.java) { LogEntry.listFromDartJson("""{"t":1}""") }
    }

    @Test
    fun `levels and problem filter`() {
        val entries = listOf("DEBUG", "INFO", "SEC", "WARN", "ERROR", "CUSTOM").map { LogEntry(0, it, "T", it) }
        assertEquals(listOf("WARN", "ERROR"), entries.problemsOnly().map { it.level })
        assertEquals(LogLevel.SEC, entries[2].logLevel)
        assertEquals(null, entries[5].logLevel)
        assertTrue(entries[3].isProblem)
        assertFalse(entries[2].isProblem)
    }

    private fun obj(json: String) = DartJson.decode(json) as JsonObject
}
