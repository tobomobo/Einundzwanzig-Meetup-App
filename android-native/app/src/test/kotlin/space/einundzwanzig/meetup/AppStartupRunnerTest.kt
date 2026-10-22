package space.einundzwanzig.meetup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.startup.AppStartupRunner

class AppStartupRunnerTest {

    private val events = mutableListOf<String>()

    private fun task(name: String, order: Int, delayMs: Long = 0, fail: Boolean = false) = object : AppStartupTask {
        override val name = name
        override val order = order
        override suspend fun run() {
            events += "start:$name"
            delay(delayMs)
            if (fail) error("boom")
            events += "end:$name"
        }
    }

    @Test
    fun `waits for the migration before any task`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = AppStartupRunner(setOf(task("a", 0)), AppLogger)
        val job = launch { runner.run { gate.await() } }
        testScheduler.advanceUntilIdle()
        assertTrue(events.isEmpty())
        gate.complete(Unit)
        job.join()
        assertEquals(listOf("start:a", "end:a"), events)
    }

    @Test
    fun `groups run in order, tasks of a group concurrently`() = runTest {
        val runner = AppStartupRunner(
            setOf(task("late", 100), task("slow", 0, delayMs = 50), task("fast", 0, delayMs = 10), task("first", -100)),
            AppLogger,
        )
        runner.run { }
        assertEquals("start:first", events[0])
        assertEquals("end:first", events[1])
        // Both order-0 tasks start before either ends.
        assertEquals(setOf("start:fast", "start:slow"), events.subList(2, 4).toSet())
        assertEquals(listOf("end:fast", "end:slow", "start:late", "end:late"), events.subList(4, 8))
    }

    @Test
    fun `a failing task does not stop the others`() = runTest {
        val runner = AppStartupRunner(setOf(task("bad", 0, fail = true), task("good", 0), task("next", 1)), AppLogger)
        val failed = runner.run { }
        assertEquals(listOf("bad"), failed)
        assertTrue("end:good" in events)
        assertTrue("end:next" in events)
    }

    @Test
    fun `a throwing gate still starts the tasks`() = runTest {
        val runner = AppStartupRunner(setOf(task("a", 0)), AppLogger)
        runner.run { throw IllegalStateException("gate") }
        assertEquals(listOf("start:a", "end:a"), events)
    }
}
