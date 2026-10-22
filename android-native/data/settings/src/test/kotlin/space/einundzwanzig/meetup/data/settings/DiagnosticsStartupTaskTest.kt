package space.einundzwanzig.meetup.data.settings

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppVersionInfo
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticsService
import space.einundzwanzig.meetup.core.common.log.EnvironmentProbe
import space.einundzwanzig.meetup.core.common.log.InventoryCounts
import space.einundzwanzig.meetup.core.common.log.LastKnownLocation
import space.einundzwanzig.meetup.core.common.log.LocationStatus
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.settings.diagnostics.DiagnosticsInventorySource
import space.einundzwanzig.meetup.data.settings.diagnostics.DiagnosticsStartupTask
import java.util.Optional

/** Spec 12 §8.13: the startup task feeds the core fingerprint with the domain sources. */
class DiagnosticsStartupTaskTest {

    private val log = DiagnosticLogger(timeSource = object : TimeSource {
        override fun nowMillis() = 0L
    })

    private val probe = object : EnvironmentProbe {
        override suspend fun appVersion() = AppVersionInfo("2.0.0", 200)
        override fun operatingSystem() = "android 15 (API 35; Google Pixel 8)"
        override fun localeName() = "de_DE"
        override fun timeZoneName() = "Europe/Berlin"
        override fun utcOffsetSeconds() = 7200
        override suspend fun locationStatus() = LocationStatus(serviceEnabled = false, permission = "denied")
        override suspend fun lastKnownLocation(): LastKnownLocation? = null
    }

    private fun lines() = log.entries.value.map { "${it.level} [${it.tag}] ${it.message}" }

    private fun TestScope.task(prefs: InMemoryKeyValueStore, inventory: DiagnosticsInventorySource? = null) =
        DiagnosticsStartupTask(DiagnosticsService(log, probe), prefs, Optional.ofNullable(inventory), this)

    @Test
    fun `fingerprint reads the signing mode from prefs and the inventory from its source`() = runTest {
        val prefs = InMemoryKeyValueStore(mapOf("signing_mode" to PrefValue.StringValue("amber")))
        task(prefs) { InventoryCounts(badges = 4, favorites = 2) }.run()
        advanceUntilIdle()

        val lines = lines()
        assertEquals("INFO [ooo] ───── APP-START ─────", lines.first())
        assertTrue("INFO [System] NFC: abgeschaltet (features.dart)" in lines)
        assertTrue("INFO [System] Signatur-Modus: amber" in lines)
        assertEquals("INFO [System] Bestand: 4 Badge(s) · 2 Favorit(en)", lines.last())
    }

    @Test
    fun `without an inventory source the Bestand line is skipped`() = runTest {
        task(InMemoryKeyValueStore()).run()
        advanceUntilIdle()

        val lines = lines()
        assertEquals("INFO [System] Signatur-Modus: local", lines.last())
        assertFalse(lines.any { it.contains("Bestand") })
    }

    @Test
    fun `failing inventory source logs its own warning`() = runTest {
        task(InMemoryKeyValueStore()) { throw IllegalStateException("Badges gesperrt") }.run()
        advanceUntilIdle()

        assertEquals(
            "WARN [System] Datenbestand nicht lesbar — IllegalStateException: Badges gesperrt",
            lines().last(),
        )
    }

    @Test
    fun `signing mode mapping follows SigningService getMode on Android`() {
        assertEquals("local", DiagnosticsStartupTask.signingModeName(null))
        assertEquals("local", DiagnosticsStartupTask.signingModeName("local"))
        assertEquals("local", DiagnosticsStartupTask.signingModeName("legacy-unknown"))
        assertEquals("amber", DiagnosticsStartupTask.signingModeName("amber"))
        assertEquals("nip07", DiagnosticsStartupTask.signingModeName("nip07"))
        assertEquals("nip46", DiagnosticsStartupTask.signingModeName("nip46"))
    }

    @Test
    fun `task runs alone in the first startup group`() = runTest {
        val t = task(InMemoryKeyValueStore())
        assertEquals(-100, t.order)
        assertEquals("DiagnosticsFingerprint", t.name)
    }

    @Test
    fun `header is written before run returns, the probes continue in the background`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        task(InMemoryKeyValueStore()) { gate.await(); InventoryCounts(badges = 1, favorites = 0) }.run()

        assertEquals("INFO [ooo] ───── APP-START ─────", lines().first())
        assertFalse(lines().any { it.contains("Bestand") })

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("INFO [System] Bestand: 1 Badge(s) · 0 Favorit(en)", lines().last())
    }
}
