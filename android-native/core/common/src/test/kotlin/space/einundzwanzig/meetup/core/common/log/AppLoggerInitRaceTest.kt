package space.einundzwanzig.meetup.core.common.log

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.einundzwanzig.meetup.core.common.TimeSource

/**
 * Port of `test/app_logger_init_race_test.dart`: the narrow window in which the whole stored
 * history could be lost.
 *
 * The crash handler is active from the first line of start-up and can log before [init] has
 * loaded the stored buffer. Without the guard, such an early entry arms the persist timer; if
 * loading takes longer, the timer writes a buffer that contains ONLY the early error, and init
 * then loads exactly that one entry.
 */
class AppLoggerInitRaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `early entry does not destroy the stored history`() = runTest {
        // Pretend there is a stored history.
        val store = InMemoryLogStore(entries = listOf(LogEntry(1000, "INFO", "alt", "Eintrag von gestern")))
        val logger = testLogger()
        logger.attachStore(store) // like AppLogger.attach(context) at the very start

        // The crash handler reports BEFORE init() ran.
        logger.warn("Zone", "frueher Fehler vor init")

        // Wait longer than the persist interval (2 s). Without the guard the write would happen
        // here and "Eintrag von gestern" would be gone.
        advanceTimeBy(2400)
        runCurrent()
        assertEquals("nothing may be written before init()", listOf("Eintrag von gestern"), store.stored!!.map { it.message })

        // Now load.
        logger.init(store)

        val inMemory = logger.entries.value.map { it.message }
        assertTrue("stored history must survive", inMemory.contains("Eintrag von gestern"))
        assertTrue("the early error must not be dropped", inMemory.contains("frueher Fehler vor init"))
        assertEquals(listOf("Eintrag von gestern", "frueher Fehler vor init"), inMemory)

        // init() schedules the write itself, so the early entry survives a start-up crash.
        advanceTimeBy(2400)
        runCurrent()
        val persisted = store.stored!!.map { it.message }
        assertTrue(persisted.contains("Eintrag von gestern"))
        assertTrue(persisted.contains("frueher Fehler vor init"))
    }

    @Test
    fun `same scenario with the file store keeps the Dart JSON keys`() = runTest {
        val dir = tmp.newFolder()
        val file = java.io.File(dir, FileLogStore.LOG_FILE_NAME)
        file.writeText("""{"t":1000,"l":"INFO","g":"alt","m":"Eintrag von gestern"}""" + "\n")
        val store = FileLogStore(dir)
        val logger = testLogger()
        logger.attachStore(store)

        logger.warn("Zone", "frueher Fehler vor init")
        advanceTimeBy(2400)
        runCurrent()
        assertEquals(listOf("""{"t":1000,"l":"INFO","g":"alt","m":"Eintrag von gestern"}"""), file.readLines())

        logger.init(store)
        advanceTimeBy(2400)
        runCurrent()
        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertEquals("""{"t":1000,"l":"INFO","g":"alt","m":"Eintrag von gestern"}""", lines[0])
        assertEquals("""{"t":5000,"l":"WARN","g":"Zone","m":"frueher Fehler vor init"}""", lines[1])
    }

    private fun TestScope.testLogger() = DiagnosticLogger(
        timeSource = object : TimeSource {
            override fun nowMillis() = 5000L
        },
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        monotonicMillis = { testScheduler.currentTime },
    )
}
