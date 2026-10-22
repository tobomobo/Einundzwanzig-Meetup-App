package space.einundzwanzig.meetup.core.common.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Port of `test/app_logger_test.dart`: the error message must not be boiled down to the class
 * name, and the stack head must survive without verbose mode. Uses the static facade like the
 * Dart test.
 */
class AppLoggerTest {

    /** The facade shares one process-wide logger; start every test from a clean buffer. */
    @Before
    fun resetSharedLogger() {
        AppLogger.logger.resetForTests()
    }

    @Test
    fun `warn appends the error message, not only the type`() {
        AppLogger.warn("T", "Plattformdaten nicht lesbar", UnsupportedOperationException("Platform._version"))
        val e = AppLogger.entries.value.last()
        assertEquals("WARN", e.level)
        assertTrue("only the type is worthless for debugging", e.message.contains("Platform._version"))
        assertTrue(e.message.contains("UnsupportedOperationException"))
        assertEquals("Plattformdaten nicht lesbar — UnsupportedOperationException: Platform._version", e.message)
    }

    @Test
    fun `error writes the stack head even without verbose mode`() {
        assertFalse("default is off", AppLogger.isVerbose)
        try {
            throw IllegalStateException("kaputt")
        } catch (err: IllegalStateException) {
            AppLogger.error("T", "Ablauf gescheitert", err, err.stackTrace)
        }
        val e = AppLogger.entries.value.last()
        // Level ERROR so the "problems only" filter of the log screen keeps it.
        assertEquals("ERROR", e.level)
        assertTrue(e.message.contains("kaputt"))
        assertTrue("stack head in the same entry", e.message.contains("bei "))
        assertFalse("one line per entry, otherwise exportText() is unreadable", e.message.contains('\n'))
        assertTrue(e.message.startsWith("Ablauf gescheitert — IllegalStateException: kaputt | bei "))
        assertTrue(e.message.contains("AppLoggerTest"))
    }

    @Test
    fun `framework errors land in the log completely`() {
        // Dart: FlutterErrorDetails(exception: ArgumentError(...), library: 'testlib') logged by
        // the FlutterError.onError handler as error('Flutter', details.library ?? 'widget', ...).
        val exception = IllegalArgumentException("Testfehler aus dem Widget-Baum")
        AppLogger.error("Flutter", "testlib", exception, Thread.currentThread().stackTrace)

        val e = AppLogger.entries.value.last()
        assertEquals("Flutter", e.tag)
        assertEquals("ERROR", e.level)
        assertTrue(e.message.contains("Testfehler aus dem Widget-Baum"))
        assertTrue(e.message.contains("testlib"))
    }

    @Test
    fun `error uses the throwable's own stack by default`() {
        AppLogger.error("T", "ohne expliziten Stack", RuntimeException("x"))
        val message = AppLogger.entries.value.last().message
        assertTrue(message, message.contains(" | bei ") && message.contains(" <- "))
    }

    @Test
    fun `error and warn accept non-throwable errors like Dart objects`() {
        AppLogger.warn("T", "Antwort", "HTTP 500")
        assertEquals("Antwort — String: HTTP 500", AppLogger.entries.value.last().message)
        AppLogger.error("T", "Antwort", 42)
        assertEquals("Antwort — Int: 42", AppLogger.entries.value.last().message)
    }
}
