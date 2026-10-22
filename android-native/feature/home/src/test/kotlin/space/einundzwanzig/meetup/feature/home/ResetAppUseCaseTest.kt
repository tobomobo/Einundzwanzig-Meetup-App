package space.einundzwanzig.meetup.feature.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.InMemoryLogStore
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.InMemoryTourProgressStore
import space.einundzwanzig.meetup.feature.home.reset.ResetAppUseCase
import space.einundzwanzig.meetup.data.identity.IdentityRepository
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import java.io.IOException
import java.lang.reflect.Proxy

class ResetAppUseCaseTest {
    /** Only reset operations are supported; an unexpected call fails even inside an optional step. */
    private inline fun <reified T> resetPort(operation: String, result: Boolean = true, crossinline onCall: () -> Unit = {}): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ ->
        if (method.name != operation) throw AssertionError("Unexpected reset call: ${method.name}")
        onCall()
        when (operation) {
            "wipeAll", "setLocale" -> result
            else -> Unit
        }
    } as T

    private fun resetter(
        scope: CoroutineScope,
        log: DiagnosticLog,
        identity: IdentityRepository = resetPort("wipeAll"),
        portal: PortalApiService = resetPort("logout"),
    ) = ResetAppUseCase(
        signing = resetPort("disconnectNip46"),
        badges = resetPort("resetAll"),
        profileService = resetPort("clearCache"),
        migration = resetPort("onAppReset"),
        store = InMemoryKeyValueStore(),
        identity = identity,
        portal = portal,
        rollingQr = resetPort("endSession"),
        mempool = resetPort("clearState"),
        mempoolConfig = resetPort("resetToDefaults"),
        currency = resetPort("reset"),
        locale = resetPort("setLocale"),
        guide = GuideController(InMemoryTourProgressStore(), scope),
        widget = resetPort("updateMeetup"),
        log = log,
    )

    @Test
    fun `successful full reset disables verbose logging and clears persisted log contents`() = runTest {
        val logStore = InMemoryLogStore()
        val io = StandardTestDispatcher(testScheduler)
        val log = DiagnosticLogger(timeSource = FixedTime(), scope = backgroundScope, ioDispatcher = io)
        log.init(logStore)
        log.setVerbose(true)
        log.debug("BeforeReset", "Old diagnostic data")
        val resetter = resetter(backgroundScope, log)
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

    @Test
    fun `a false identity wipe leaves reset incomplete while other cleanup continues`() = runTest {
        var portalLoggedOut = false
        val log = DiagnosticLogger(timeSource = FixedTime(), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        val resetter = resetter(
            backgroundScope, log,
            identity = resetPort("wipeAll", result = false),
            portal = resetPort("logout", onCall = { portalLoggedOut = true }),
        )

        assertFalse(resetter.reset())
        assertTrue(portalLoggedOut)
        assertTrue(log.exportText().contains("Zurücksetzen unvollständig"))
        assertFalse(log.exportText().contains("App zurückgesetzt."))
    }

    @Test
    fun `an identity deletion exception cannot become reset success`() = runTest {
        val log = DiagnosticLogger(timeSource = FixedTime(), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        val resetter = resetter(
            backgroundScope, log,
            identity = resetPort("wipeAll", onCall = { throw IOException("Key store locked") }),
        )

        assertFalse(resetter.reset())
        assertTrue(log.exportText().contains("Zurücksetzen unvollständig"))
    }
}
