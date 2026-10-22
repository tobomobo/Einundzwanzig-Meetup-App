package space.einundzwanzig.meetup.feature.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * The flows run on the application scope (`Dispatchers.Default` in the app); the SAF pickers must
 * still be started on the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupCoordinatorThreadTest {

    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, MAIN) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val h = Harness(appScope, Dispatchers.Default)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
        h.coordinator.attach(h.platform)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    @Test
    fun `launchExport starts the save picker on the main thread`() = runBlocking {
        val result = CompletableDeferred<Pair<Boolean, String>>()
        h.coordinator.launchExport { result.complete(it to Thread.currentThread().name.substringBefore(" @")) }
        withTimeout(TIMEOUT) { h.coordinator.ui.first { it.dialog != null } }
        h.coordinator.onAction(BackupAction.SubmitPassword("correct horse"))
        withTimeout(TIMEOUT) { while (h.platform.created.isEmpty()) kotlinx.coroutines.delay(5) }
        h.coordinator.onDocumentResult("content://docs/1")

        assertEquals(true to MAIN, withTimeout(TIMEOUT) { result.await() })
        assertEquals(listOf(MAIN), h.platform.launchThreads)
    }

    @Test
    fun `launchRestore starts the open picker on the main thread`() = runBlocking {
        val result = CompletableDeferred<Boolean>()
        h.coordinator.launchRestore { result.complete(it) }
        withTimeout(TIMEOUT) { while (h.platform.opened == 0) kotlinx.coroutines.delay(5) }
        h.coordinator.onDocumentResult(null)

        assertEquals(false, withTimeout(TIMEOUT) { result.await() })
        assertEquals(listOf(MAIN), h.platform.launchThreads)
    }

    private companion object {
        const val MAIN = "fake-main"
        const val TIMEOUT = 5_000L
    }
}
