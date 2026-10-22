package space.einundzwanzig.meetup.core.common.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writes one Dart JSON object per line and reads it back`() {
        val dir = tmp.newFolder()
        val store = FileLogStore(dir)
        val entries = listOf(
            LogEntry(1700000000123, "WARN", "Portal", "Zeile mit \"Quote\", ä und\nUmbruch"),
            LogEntry(1700000000456, "SEC", "Signing", "✓ 😀 </x>"),
        )
        store.writeEntries(entries)
        val lines = File(dir, FileLogStore.LOG_FILE_NAME).readLines(Charsets.UTF_8)
        assertEquals(
            listOf(
                """{"t":1700000000123,"l":"WARN","g":"Portal","m":"Zeile mit \"Quote\", ä und\nUmbruch"}""",
                """{"t":1700000000456,"l":"SEC","g":"Signing","m":"✓ 😀 </x>"}""",
            ),
            lines,
        )
        assertEquals(StoredEntries(entries), store.readEntries())
        assertFalse("no temp file left", File(dir, "${FileLogStore.LOG_FILE_NAME}.tmp").exists())
    }

    @Test
    fun `missing file means empty, empty list deletes the file`() {
        val dir = tmp.newFolder()
        val store = FileLogStore(dir)
        assertEquals(StoredEntries.EMPTY, store.readEntries())
        store.writeEntries(listOf(LogEntry(1, "INFO", "a", "b")))
        assertTrue(File(dir, FileLogStore.LOG_FILE_NAME).exists())
        store.writeEntries(emptyList())
        assertFalse(File(dir, FileLogStore.LOG_FILE_NAME).exists())
        store.writeEntries(listOf(LogEntry(1, "INFO", "a", "b")))
        store.clearEntries()
        assertEquals(StoredEntries.EMPTY, store.readEntries())
    }

    @Test
    fun `verbose flag round trip`() {
        val store = FileLogStore(tmp.newFolder())
        assertFalse(store.readVerbose())
        store.writeVerbose(true)
        assertTrue(store.readVerbose())
        store.writeVerbose(false)
        assertFalse(store.readVerbose())
    }

    @Test
    fun `a leftover temp file from a crash is ignored and replaced`() {
        val dir = tmp.newFolder()
        File(dir, "${FileLogStore.LOG_FILE_NAME}.tmp").writeText("{halb")
        val store = FileLogStore(dir)
        assertEquals(StoredEntries.EMPTY, store.readEntries())
        store.writeEntries(listOf(LogEntry(1, "INFO", "a", "b")))
        assertEquals(1, store.readEntries().entries.size)
    }
}

class LogExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `large exports go to a file`() {
        assertFalse(LogExport.shouldShareAsFile("x".repeat(LogExport.INLINE_TEXT_LIMIT_BYTES)))
        assertTrue(LogExport.shouldShareAsFile("ä".repeat(LogExport.INLINE_TEXT_LIMIT_BYTES / 2 + 1)))
        val file = LogExport.writeToFile("05.03 07:08:09  WARN  [Portal] ä", tmp.newFolder())
        assertEquals("21meetup-diagnose-log.txt", file.name)
        assertEquals("05.03 07:08:09  WARN  [Portal] ä", file.readText(Charsets.UTF_8))
        assertEquals("21Meetup Diagnose-Log", LogExport.SHARE_SUBJECT)
    }
}
