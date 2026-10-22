package space.einundzwanzig.meetup.core.common.result

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import java.io.IOException

class ResultExtensionsTest {

    @Test
    fun `runSuspendCatching captures exceptions but rethrows cancellation`() = runTest {
        assertEquals(Result.success(1), runSuspendCatching { 1 })
        val failure = runSuspendCatching { throw IOException("offline") }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertThrows(CancellationException::class.java) {
            runSuspendCatching { throw CancellationException("stop") }
        }
    }

    @Test
    fun `logFailure logs a warning with the cause and passes the result through`() {
        val log = DiagnosticLogger()
        val result = Result.failure<Int>(IOException("offline")).logFailure("Portal", "Abruf fehlgeschlagen", log)
        assertTrue(result.isFailure)
        assertEquals("Abruf fehlgeschlagen — IOException: offline", log.entries.value.single().message)
        assertEquals("WARN", log.entries.value.single().level)
        Result.success(1).logFailure("Portal", "x", log)
        assertEquals(1, log.entries.value.size)
        Result.failure<Int>(IOException("leise")).logFailureDebug("Portal", "y", log)
        assertEquals("debug is dropped without verbose mode", 1, log.entries.value.size)
    }
}
