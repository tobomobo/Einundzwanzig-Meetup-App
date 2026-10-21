package space.einundzwanzig.meetup.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import space.einundzwanzig.meetup.data.backup.internal.DefaultBackupSession

/** Spec 08 §9 #13 / §1 #23: one silent single-flight guard for export and import. */
class BackupSessionTest {

    @Test
    fun `a second flow is ignored while one runs and the guard is released afterwards`() = runTest {
        val session = DefaultBackupSession()
        val gate = CompletableDeferred<Unit>()
        val first = async { session.tryRun { gate.await(); "export" } }
        testScheduler.runCurrent()
        assertTrue(session.busy.value)

        var ran = false
        assertNull(session.tryRun { ran = true; "import" })
        assertFalse(ran)

        gate.complete(Unit)
        assertEquals("export", first.await())
        assertFalse(session.busy.value)
        assertEquals("import", session.tryRun { "import" })
    }

    @Test
    fun `an exception releases the guard`() = runTest {
        val session = DefaultBackupSession()
        try {
            session.tryRun<String> { throw IllegalStateException("x") }
            fail()
        } catch (_: IllegalStateException) {
        }
        assertFalse(session.busy.value)
        assertEquals(1, session.tryRun { 1 })
    }
}
