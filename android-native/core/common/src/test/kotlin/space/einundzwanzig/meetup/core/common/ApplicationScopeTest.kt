package space.einundzwanzig.meetup.core.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.log.AppLogger

/** Spec 12 §2.1: an uncaught failure in the application scope is logged, never fatal. */
@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationScopeTest {

    @Before
    fun reset() {
        AppLogger.logger.resetForTests()
    }

    @Test
    fun `a throwing launch is logged with tag Async and the scope keeps running`() {
        val dispatcher = StandardTestDispatcher()
        val test = TestScope(dispatcher)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val uncaught = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val appScope = DispatchersModule.applicationScope(dispatcher)
            appScope.launch { throw IllegalStateException("relay kaputt") }
            var ranAfter = false
            appScope.launch { ranAfter = true }
            test.advanceUntilIdle()

            val e = AppLogger.entries.value.single { it.tag == "Async" }
            assertEquals("ERROR", e.level)
            assertTrue(e.message, e.message.startsWith("Unbehandelter Fehler"))
            assertTrue(e.message, e.message.contains("relay kaputt"))
            assertTrue("sibling work still runs", ranAfter)
            assertTrue("scope stays usable", appScope.isActive)
            assertTrue("nothing reaches the thread's uncaught handler: $uncaught", uncaught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
