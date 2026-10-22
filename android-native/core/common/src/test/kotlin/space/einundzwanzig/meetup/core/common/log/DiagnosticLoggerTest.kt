package space.einundzwanzig.meetup.core.common.log

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.einundzwanzig.meetup.core.common.TimeSource
import java.io.IOException
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext

class DiagnosticLoggerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private val clock = object : TimeSource {
        override fun nowMillis() = now
    }

    @Test
    fun `ring buffer keeps the newest 800 entries`() {
        val log = DiagnosticLogger(timeSource = clock)
        repeat(805) { log.info("T", "m$it") }
        val entries = log.entries.value
        assertEquals(800, entries.size)
        assertEquals("m5", entries.first().message)
        assertEquals("m804", entries.last().message)
    }

    @Test
    fun `levels, section and diag follow the Dart mapping`() {
        val log = DiagnosticLogger(timeSource = clock)
        log.info("A", "i")
        log.diag("B", "d")
        log.security("C", "s")
        log.section("APP-START")
        log.debug("D", "invisible")
        assertEquals(
            listOf(
                LogEntry(1000, "INFO", "A", "i"),
                LogEntry(1000, "INFO", "B", "d"),
                LogEntry(1000, "SEC", "C", "s"),
                LogEntry(1000, "INFO", "ooo", "───── APP-START ─────"),
            ),
            log.entries.value,
        )
    }

    @Test
    fun `setVerbose records the switch with the Dart texts`() {
        val log = DiagnosticLogger(timeSource = clock)
        log.setVerbose(true)
        assertTrue(log.isVerbose)
        log.setVerbose(false)
        assertEquals(
            listOf(
                "Ausfuehrliches Log EINGESCHALTET — auch Detailmeldungen werden erfasst.",
                "Ausfuehrliches Log ausgeschaltet.",
            ),
            log.entries.value.map { it.message },
        )
        assertTrue(log.entries.value.all { it.tag == "Log" && it.level == "INFO" })
    }

    @Test
    fun `verbose flag is persisted and restored by init`() = runTest {
        val store = InMemoryLogStore(verbose = true)
        val log = testLogger()
        log.init(store)
        assertTrue("init reads diagnostic_verbose", log.isVerbose)
        log.setVerbose(false)
        runCurrent()
        assertFalse(store.verbose)
    }

    @Test
    fun `writes are debounced 2 s after the last entry`() = runTest {
        val store = InMemoryLogStore()
        val log = testLogger()
        log.init(store)
        log.info("T", "1")
        advanceTimeBy(1500)
        log.info("T", "2")
        advanceTimeBy(1500)
        runCurrent()
        assertEquals("debounce restarted by the second entry", 0, store.writes)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(1, store.writes)
        assertEquals(listOf("1", "2"), store.stored!!.map { it.message })
    }

    @Test
    fun `a steady stream is still saved after at most 10 s`() = runTest {
        val store = InMemoryLogStore()
        val log = testLogger()
        log.init(store)
        repeat(12) {
            log.info("T", "tick $it")
            advanceTimeBy(1000)
            runCurrent()
        }
        assertTrue("written although entries never paused for 2 s", store.writes >= 1)
        assertTrue(store.stored!!.size >= 10)
    }

    @Test
    fun `clear empties memory and storage`() = runTest {
        val store = InMemoryLogStore(entries = listOf(LogEntry(1, "INFO", "a", "b")))
        val log = testLogger()
        log.init(store)
        log.info("T", "x")
        log.clear()
        assertTrue(log.entries.value.isEmpty())
        assertNull(store.stored)
        advanceTimeBy(5000)
        runCurrent()
        assertNull("the pending write was cancelled", store.stored)
    }

    @Test
    fun `unreadable storage keeps only the early entries (Dart corrupt buffer)`() = runTest {
        val store = InMemoryLogStore(entries = listOf(LogEntry(1, "INFO", "a", "alt")))
        store.failReadsWith = IOException("kaputt")
        val log = testLogger()
        log.warn("Zone", "frueh")
        log.init(store)
        assertEquals(
            listOf("frueh", "Gespeicherter Log nicht lesbar — beiseitegelegt — IOException: kaputt"),
            log.entries.value.map { it.message },
        )
    }

    @Test
    fun `an unreadable stored buffer is moved aside before the next write`() = runTest {
        val original = listOf(LogEntry(1, "INFO", "a", "alt 1"), LogEntry(2, "INFO", "a", "alt 2"))
        val store = InMemoryLogStore(entries = original)
        store.failReadsWith = IOException("voruebergehend")
        val log = testLogger()
        log.warn("Zone", "frueh")
        log.init(store)
        advanceTimeBy(5000)
        runCurrent()
        assertTrue("the new buffer was written", store.writes >= 1)
        assertEquals("frueh", store.stored!!.first().message)
        assertEquals("the old history is still recoverable", original, store.quarantined)
    }

    @Test
    fun `nothing is written when the unreadable buffer cannot be moved aside`() = runTest {
        val original = listOf(LogEntry(1, "INFO", "a", "alt"))
        val store = InMemoryLogStore(entries = original)
        store.failReadsWith = IOException("kaputt")
        store.failQuarantineWith = IOException("schreibgeschuetzt")
        val log = testLogger()
        log.warn("Zone", "frueh")
        log.init(store)
        log.info("T", "spaeter")
        advanceTimeBy(15_000)
        runCurrent()
        log.flushBlocking()
        assertEquals(0, store.writes)
        assertEquals(original, store.stored)
        assertTrue(log.entries.value.any { it.level == "WARN" && it.message.contains("nicht verschiebbar") })
    }

    @Test
    fun `an unreadable verbose flag does not stop the buffer from loading`() = runTest {
        val store = InMemoryLogStore(entries = listOf(LogEntry(1, "INFO", "a", "alt")), verbose = true)
        store.failVerboseReadsWith = IOException("kaputt")
        val log = testLogger()
        log.init(store)
        assertFalse(log.isVerbose)
        assertEquals(
            listOf("alt", "Gespeicherte Log-Einstellung nicht lesbar — IOException: kaputt"),
            log.entries.value.map { it.message },
        )
        assertNull("nothing moved aside", store.quarantined)
    }

    @Test
    fun `FileLogStore - an unreadable log file survives as diagnostic_log jsonl unreadable`() = runTest {
        val dir = tmp.newFolder()
        // A directory where the file should be: exists() is true, reading throws an IOException.
        java.io.File(dir, FileLogStore.LOG_FILE_NAME).mkdirs()
        java.io.File(dir, "${FileLogStore.LOG_FILE_NAME}/inhalt").writeText("alt")
        val store = FileLogStore(dir)
        val log = testLogger()
        log.init(store)
        log.flushBlocking()
        assertEquals("alt", java.io.File(dir, "${FileLogStore.UNREADABLE_FILE_NAME}/inhalt").readText())
        assertTrue(store.readEntries().entries.single().message.startsWith("Gespeicherter Log nicht lesbar — beiseitegelegt"))
    }

    @Test
    fun `quick verbose toggles store the last choice`() = runTest {
        val store = InMemoryLogStore()
        val log = testLogger()
        log.init(store)
        log.setVerbose(true)
        log.setVerbose(false)
        advanceUntilIdle()
        assertFalse(store.verbose)
    }

    @Test
    fun `verbose saves that run out of order still store the last choice`() {
        val dispatcher = ReversingDispatcher()
        val job = Job()
        val store = InMemoryLogStore()
        val log = DiagnosticLogger(timeSource = clock, scope = CoroutineScope(job + dispatcher), ioDispatcher = dispatcher)
        log.attachStore(store)
        log.flushBlocking() // loads the store synchronously
        log.setVerbose(true)
        log.setVerbose(false)
        dispatcher.runReversed()
        job.cancel()
        assertFalse(store.verbose)
    }

    @Test
    fun `verbose set before attach is saved once the store is loaded`() = runTest {
        val store = InMemoryLogStore(verbose = false)
        val log = testLogger()
        log.setVerbose(true)
        log.init(store)
        assertTrue(log.isVerbose)
        assertTrue(store.verbose)
    }

    @Test
    fun `verbose set between attach and load is not overwritten by the stored flag`() = runTest {
        val store = InMemoryLogStore(verbose = true)
        val log = testLogger()
        log.attachStore(store)
        log.setVerbose(false)
        log.init(store)
        advanceUntilIdle()
        assertFalse("the user's choice wins", log.isVerbose)
        assertFalse(store.verbose)
    }

    @Test
    fun `resetForTests empties the buffer and switches verbose off`() = runTest {
        val store = InMemoryLogStore()
        val log = testLogger()
        log.init(store)
        log.setVerbose(true)
        repeat(900) { log.info("T", "m$it") }
        log.resetForTests()
        assertTrue(log.entries.value.isEmpty())
        assertFalse(log.isVerbose)
        assertFalse(log.isInitialized)
        log.info("T", "danach")
        assertEquals(1, log.entries.value.size)
    }

    @Test
    fun `corrupt lines in the file are skipped and reported`() = runTest {
        val dir = tmp.newFolder()
        java.io.File(dir, FileLogStore.LOG_FILE_NAME).writeText(
            """{"t":1,"l":"INFO","g":"a","m":"gut"}""" + "\n" + """{"t":2,"l":"INF""" + "\n" + "\n" + """{"t":3,"m":"auch gut"}""" + "\n",
        )
        val log = testLogger()
        log.init(FileLogStore(dir))
        val messages = log.entries.value.map { it.message }
        assertEquals(listOf("gut", "auch gut", "1 beschaedigte Log-Eintraege beim Laden verworfen."), messages)
    }

    @Test
    fun `flushBlocking writes synchronously, also before init finished`() {
        val dir = tmp.newFolder()
        java.io.File(dir, FileLogStore.LOG_FILE_NAME).writeText("""{"t":1,"l":"INFO","g":"a","m":"alt"}""" + "\n")
        val store = FileLogStore(dir)
        val log = DiagnosticLogger(timeSource = clock)
        log.attachStore(store)
        log.error("Async", "Unbehandelter Fehler", IllegalStateException("boom"))
        log.flushBlocking()
        val stored = store.readEntries().entries.map { it.message }
        assertEquals("alt", stored.first())
        assertTrue(stored.last().startsWith("Unbehandelter Fehler — IllegalStateException: boom"))
        assertTrue(log.isInitialized)
    }

    @Test
    fun `flushBlocking without a store does nothing`() {
        DiagnosticLogger(timeSource = clock).apply {
            info("T", "x")
            flushBlocking()
        }
    }

    @Test
    fun `importLegacy puts the Flutter buffer before the current entries`() = runTest {
        val store = InMemoryLogStore()
        val log = testLogger()
        log.init(store)
        log.info("T", "neu")
        log.importLegacy("""[{"t":1000,"l":"INFO","g":"alt","m":"Eintrag von gestern"}]""", verbose = true)
        val imported = listOf("Eintrag von gestern", "neu", "1 Eintraege aus der Flutter-App uebernommen")
        assertEquals(imported, log.entries.value.map { it.message })
        assertTrue(log.isVerbose)
        assertTrue(store.verbose)
        assertEquals(imported, store.stored!!.map { it.message })
        // A corrupt Flutter buffer is not imported, but leaves a trace.
        log.importLegacy("{kaputt", verbose = null)
        assertEquals(4, log.entries.value.size)
        val warning = log.entries.value.last()
        assertEquals("WARN", warning.level)
        assertTrue(warning.message, warning.message.startsWith("Flutter-Diagnose-Log nicht lesbar — nicht uebernommen — "))
        // Flutter never redacted; imported lines are.
        log.importLegacy("""[{"t":1,"l":"WARN","g":"Nip46","m":"auth_url https://x.example/a?token=geheim123"}]""", verbose = null)
        assertEquals("auth_url https://x.example/a?token=${SecretRedactor.REDACTED}", log.entries.value.first().message)
    }

    @Test
    fun `very long messages are cut`() {
        val log = DiagnosticLogger(timeSource = clock)
        log.info("T", "x".repeat(DiagnosticLogger.MAX_MESSAGE_CHARS + 10))
        val message = log.entries.value.single().message
        assertTrue(message.startsWith("x".repeat(DiagnosticLogger.MAX_MESSAGE_CHARS)))
        assertTrue(message.endsWith(" … (+10 Zeichen gekuerzt)"))
    }

    @Test
    fun `exportText joins the export format`() {
        val log = DiagnosticLogger(timeSource = clock)
        log.info("A", "eins")
        log.warn("B", "zwei")
        assertEquals("01.01 01:00:01  INFO  [A] eins\n01.01 01:00:01  WARN  [B] zwei", log.exportText(ZoneId.of("Europe/Berlin")))
    }

    @Test
    fun `echo receives redacted lines including unrecorded debug`() {
        val lines = mutableListOf<String>()
        val log = DiagnosticLogger(timeSource = clock)
        log.echo = LogEcho { level, tag, message -> lines += "$level/$tag/$message" }
        log.debug("T", "nur Konsole")
        log.info("T", "token=abcdefghijk")
        assertEquals(listOf("DEBUG/T/nur Konsole", "INFO/T/token=${SecretRedactor.REDACTED}"), lines)
        assertEquals(1, log.entries.value.size)
    }

    @Test
    fun `measure logs success on debug and failure on error`() = runTest {
        val log = DiagnosticLogger(timeSource = clock)
        log.setVerbose(true)
        assertEquals(42, log.measure("T", "Laden") { 42 })
        assertTrue(log.entries.value.last().message.matches(Regex("""Laden ok \(\d+ ms\)""")))
        assertEquals("DEBUG", log.entries.value.last().level)

        assertNull(log.measure<Int>("T", "Laden") { throw IOException("offline") })
        val e = log.entries.value.last()
        assertEquals("ERROR", e.level)
        assertTrue(e.message, e.message.matches(Regex("""Laden FEHLGESCHLAGEN nach \d+ ms — IOException: offline \| bei .*""")))

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { log.measure<Int>("T", "x", rethrowError = true) { throw IOException("y") } }
        }
        val before = log.entries.value.size
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { log.measure<Int>("T", "x") { throw CancellationException("stop") } }
        }
        assertEquals("cancellation is not logged", before, log.entries.value.size)
    }

    @Test
    fun `describeError includes type, message and first cause on one line`() {
        assertEquals("IllegalStateException: kaputt", describeError(IllegalStateException("kaputt")))
        assertEquals("IllegalStateException", describeError(IllegalStateException()))
        assertEquals(
            "IOException: a b (Ursache: IllegalArgumentException: c)",
            describeError(IOException("a\n  b", IllegalArgumentException("c"))),
        )
        assertEquals("String: x", describeError("x"))
    }

    /** Runs dispatched tasks newest first, to provoke out-of-order background writes. */
    private class ReversingDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(tasks) { tasks.addLast(block) }
        }

        fun runReversed() {
            while (true) {
                val task = synchronized(tasks) { tasks.removeLastOrNull() } ?: return
                task.run()
            }
        }
    }

    private fun TestScope.testLogger() = DiagnosticLogger(
        timeSource = clock,
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        monotonicMillis = { testScheduler.currentTime },
    )
}
