package space.einundzwanzig.meetup.feature.home

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import space.einundzwanzig.meetup.core.common.log.InMemoryLogStore
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.InMemoryTourProgressStore
import space.einundzwanzig.meetup.feature.home.reset.ResetAppUseCase
import java.lang.reflect.Proxy

class ResetAppUseCaseTest {
    /** Only reset operations are supported; an unexpected call fails even inside an optional step. */
    private inline fun <reified T> resetPort(operation: String): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ ->
        if (method.name != operation) throw AssertionError("Unexpected reset call: ${method.name}")
        when (operation) {
            "wipeAll", "setLocale" -> true
            else -> Unit
        }
    } as T

    @Test
    fun `successful full reset disables verbose logging and clears persisted log contents`() = runTest {
        val logStore = InMemoryLogStore()
        val io = StandardTestDispatcher(testScheduler)
        val log = DiagnosticLogger(timeSource = FixedTime(), scope = backgroundScope, ioDispatcher = io)
        log.init(logStore)
        log.setVerbose(true)
        log.debug("BeforeReset", "Old diagnostic data")
        val resetter = ResetAppUseCase(
            signing = resetPort("disconnectNip46"),
            badges = resetPort("resetAll"),
            profileService = resetPort("clearCache"),
            migration = resetPort("onAppReset"),
            store = InMemoryKeyValueStore(),
            identity = resetPort("wipeAll"),
            portal = resetPort("logout"),
            rollingQr = resetPort("endSession"),
            mempool = resetPort("clearState"),
            mempoolConfig = resetPort("resetToDefaults"),
            currency = resetPort("reset"),
            locale = resetPort("setLocale"),
            guide = GuideController(InMemoryTourProgressStore(), backgroundScope),
            widget = resetPort("updateMeetup"),
            log = log,
        )
        assertTrue(resetter.reset())
        assertFalse(log.isVerbose)
        assertFalse(log.exportText().contains("Old diagnostic data"))
        log.debug("AfterReset", "Must not be retained")
        assertFalse(log.exportText().contains("Must not be retained"))
        runCurrent()
        val restarted = DiagnosticLogger(timeSource = FixedTime(), scope = backgroundScope, ioDispatcher = io)
        restarted.init(logStore)
        assertFalse(restarted.isVerbose)
        assertFalse(restarted.exportText().contains("Old diagnostic data"))
    }
}
