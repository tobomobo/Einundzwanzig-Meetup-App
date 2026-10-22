package space.einundzwanzig.meetup.core.common.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Port of `test/app_logger_volume_test.dart`: debug-level lines must NOT fill the persistent
 * buffer in normal operation. Otherwise one line per unreachable relay × relays × queries would
 * make the 800-entry ring cover minutes instead of hours, and the few real errors would roll out.
 * Uses the static facade like the Dart test.
 */
class AppLoggerVolumeTest {

    /** The facade shares one process-wide logger; start every test from a clean buffer. */
    @Before
    fun resetSharedLogger() {
        AppLogger.logger.resetForTests()
    }

    @Test
    fun `debug does not land in the buffer in normal mode, warn does`() {
        assertFalse("default is off", AppLogger.isVerbose)

        val before = AppLogger.entries.value.size

        // This is how the relay loops log: one line per relay.
        repeat(25) { i -> AppLogger.debug("T", "Relay $i fehlgeschlagen: SocketException") }
        assertEquals("25 debug lines must not grow the buffer", before, AppLogger.entries.value.size)

        // A real failure must arrive.
        AppLogger.warn("T", "Eigener npub nicht dekodierbar: FormatException")
        assertEquals(before + 1, AppLogger.entries.value.size)
        assertEquals("WARN", AppLogger.entries.value.last().level)
    }

    @Test
    fun `in verbose mode the detail lines are there`() {
        AppLogger.setVerbose(true)
        try {
            val before = AppLogger.entries.value.size
            AppLogger.debug("T", "Relay-Kette Schritt 1")
            AppLogger.debug("T", "Relay-Kette Schritt 2")
            assertEquals("that is what the mode is for: following the chain", before + 2, AppLogger.entries.value.size)
        } finally {
            AppLogger.setVerbose(false)
        }
    }
}
