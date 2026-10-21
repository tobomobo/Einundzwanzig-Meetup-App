package space.einundzwanzig.meetup.core.common.log

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import space.einundzwanzig.meetup.core.common.TimeSource

class DiagnosticsServiceTest {

    private val log = DiagnosticLogger(
        timeSource = object : TimeSource {
            override fun nowMillis() = 0L
        },
    )

    private open class FakeProbe : EnvironmentProbe {
        override suspend fun appVersion() = AppVersionInfo("2.0.0", 200)
        override fun operatingSystem() = "android 15 (API 35; Google Pixel 8)"
        override fun localeName() = "de_DE"
        override fun timeZoneName() = "Europe/Berlin"
        override fun utcOffsetSeconds() = 7200
        override suspend fun locationStatus() = LocationStatus(serviceEnabled = true, permission = "whileInUse")
        override suspend fun lastKnownLocation(): LastKnownLocation? = LastKnownLocation(ageMillis = 12 * 60_000 + 59_999, accuracyMeters = 14.5)
    }

    private fun lines() = log.entries.value.map { "${it.level} [${it.tag}] ${it.message}" }

    @Test
    fun `writes the fingerprint in the Dart order and wording`() = runTest {
        log.setVerbose(true)
        DiagnosticsService(log, FakeProbe()).logEnvironment(
            DiagnosticsSources(signingMode = { "nip46" }, inventory = { InventoryCounts(badges = 3, favorites = 1) }),
        )
        assertEquals(
            listOf(
                "INFO [Log] Ausfuehrliches Log EINGESCHALTET — auch Detailmeldungen werden erfasst.",
                "INFO [ooo] ───── APP-START ─────",
                "INFO [System] App 2.0.0 (Build 200)",
                "INFO [System] System: android 15 (API 35; Google Pixel 8)",
                "INFO [System] Sprache de_DE · Zeitzone Europe/Berlin (UTC+2)",
                "INFO [System] Standort: Dienst AN · Berechtigung whileInUse",
                "INFO [System] Letzte bekannte Position ist 12 Min alt (Genauigkeit 15 m)",
                "INFO [System] NFC: abgeschaltet (features.dart)",
                "INFO [System] Signatur-Modus: nip46",
                "INFO [System] Bestand: 3 Badge(s) · 1 Favorit(en)",
                "INFO [System] Ausfuehrliches Log ist AKTIV.",
            ),
            lines(),
        )
    }

    @Test
    fun `each block fails on its own with its own warning`() = runTest {
        val probe = object : FakeProbe() {
            override suspend fun appVersion(): AppVersionInfo = throw IllegalStateException("kein PackageInfo")
            override fun utcOffsetSeconds() = -12600 // UTC-3:30 → hours truncated like Dart's inHours
            override suspend fun lastKnownLocation(): LastKnownLocation? = null
        }
        DiagnosticsService(log, probe).logEnvironment(
            DiagnosticsSources(signingMode = { throw SecurityException("gesperrt") }),
        )
        assertEquals(
            listOf(
                "INFO [ooo] ───── APP-START ─────",
                "WARN [System] App-Version nicht lesbar — IllegalStateException: kein PackageInfo",
                "INFO [System] System: android 15 (API 35; Google Pixel 8)",
                "INFO [System] Sprache de_DE · Zeitzone Europe/Berlin (UTC-3)",
                "INFO [System] Standort: Dienst AN · Berechtigung whileInUse",
                "WARN [System] Keine letzte bekannte Position vorhanden — auf Geraeten ohne Netzwerkortung " +
                    "(z.B. ohne Google Play Services) kann die Ortung in Gebaeuden fehlschlagen.",
                "INFO [System] NFC: abgeschaltet (features.dart)",
                "WARN [System] Signatur-Modus nicht lesbar — SecurityException: gesperrt",
            ),
            lines(),
        )
    }

    @Test
    fun `missing location permission is reported like Dart's PermissionDeniedException`() = runTest {
        val probe = object : FakeProbe() {
            override suspend fun locationStatus() = LocationStatus(serviceEnabled = false, permission = "denied")
            override suspend fun lastKnownLocation(): LastKnownLocation? = throw SecurityException("Standort-Berechtigung fehlt")
        }
        DiagnosticsService(log, probe).logEnvironment()
        val l = lines()
        assertEquals("INFO [System] Standort: Dienst AUS · Berechtigung denied", l[4])
        assertEquals("WARN [System] Standort-Status nicht ermittelbar — SecurityException: Standort-Berechtigung fehlt", l[5])
    }
}
