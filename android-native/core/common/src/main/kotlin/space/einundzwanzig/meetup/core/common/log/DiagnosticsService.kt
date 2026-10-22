package space.einundzwanzig.meetup.core.common.log

import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * Writes the environment fingerprint at the top of the log on every app start (Dart
 * `DiagnosticsService.logEnvironment`, field test July 2026: a bug report without device data is
 * half worthless).
 *
 * Only technical data — **no npub, keys, coordinates or names**. Every block is isolated: a
 * failing probe produces a `WARN` with its own text and never blocks the start. Run it in the
 * background (not awaited by the start-up sequence).
 *
 * Lines, in order (tag `System`):
 * ```
 * INFO [ooo]    ───── APP-START ─────
 * INFO [System] App 2.0.0 (Build 200)
 * INFO [System] System: android 15 (API 35; Google Pixel 8)
 * INFO [System] Sprache de_DE · Zeitzone Europe/Berlin (UTC+2)
 * INFO [System] Standort: Dienst AN · Berechtigung whileInUse
 * INFO [System] Letzte bekannte Position ist 12 Min alt (Genauigkeit 15 m)   (or a WARN without fix)
 * INFO [System] NFC: abgeschaltet (features.dart)
 * INFO [System] Signatur-Modus: local
 * INFO [System] Bestand: 3 Badge(s) · 1 Favorit(en)
 * INFO [System] Ausfuehrliches Log ist AKTIV.                                (verbose only)
 * ```
 */
class DiagnosticsService(
    private val log: DiagnosticLog,
    private val probe: EnvironmentProbe,
) {

    /**
     * Logs the fingerprint. [sources] supplies the parts owned by other domains (signing mode,
     * badge/favourite counts); a missing source skips its line.
     */
    suspend fun logEnvironment(sources: DiagnosticsSources = DiagnosticsSources()) {
        log.section("APP-START")

        block("App-Version nicht lesbar") {
            val v = probe.appVersion()
            log.info(TAG, "App ${v.versionName} (Build ${v.buildNumber})")
        }

        block("Plattformdaten nicht lesbar") {
            log.info(TAG, "System: ${probe.operatingSystem()}")
            val offsetSeconds = probe.utcOffsetSeconds()
            val sign = if (offsetSeconds < 0) "-" else "+"
            log.info(TAG, "Sprache ${probe.localeName()} · Zeitzone ${probe.timeZoneName()} (UTC$sign${abs(offsetSeconds / 3600)})")
        }

        block("Standort-Status nicht ermittelbar") {
            val status = probe.locationStatus()
            log.info(TAG, "Standort: Dienst ${if (status.serviceEnabled) "AN" else "AUS"} · Berechtigung ${status.permission}")
            val last = probe.lastKnownLocation()
            if (last == null) {
                log.warn(
                    TAG,
                    "Keine letzte bekannte Position vorhanden — auf Geraeten ohne " +
                        "Netzwerkortung (z.B. ohne Google Play Services) kann die Ortung " +
                        "in Gebaeuden fehlschlagen.",
                )
            } else {
                log.info(
                    TAG,
                    "Letzte bekannte Position ist ${last.ageMillis / 60_000} Min alt " +
                        "(Genauigkeit ${last.accuracyMeters.toStringAsFixed(0)} m)",
                )
            }
        }

        // NFC is disabled in this app (Dart kNfcEnabled = false, Security Audit N4).
        log.info(TAG, "NFC: abgeschaltet (features.dart)")

        sources.signingMode?.let { signingMode ->
            block("Signatur-Modus nicht lesbar") { log.info(TAG, "Signatur-Modus: ${signingMode()}") }
        }

        sources.inventory?.let { inventory ->
            block("Datenbestand nicht lesbar") {
                val counts = inventory()
                log.info(TAG, "Bestand: ${counts.badges} Badge(s) · ${counts.favorites} Favorit(en)")
            }
        }

        if (log.isVerbose) log.info(TAG, "Ausfuehrliches Log ist AKTIV.")
    }

    private inline fun block(failureMessage: String, body: () -> Unit) {
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, failureMessage, e)
        }
    }

    private companion object {
        const val TAG = "System"
    }
}

/** Parts of the fingerprint owned by other domains. */
data class DiagnosticsSources(
    /** Signing mode name as in Dart (`local`, `amber`, `nip46`). */
    val signingMode: (suspend () -> String)? = null,
    /** Badge and favourite counts. */
    val inventory: (suspend () -> InventoryCounts)? = null,
)

/** `Bestand:` line values. */
data class InventoryCounts(val badges: Int, val favorites: Int)

/** App version as shown in the fingerprint (`versionName`, `versionCode`). */
data class AppVersionInfo(val versionName: String, val buildNumber: Long)

/** Location service state; [permission] uses the geolocator names (`denied`, `whileInUse`, `always`). */
data class LocationStatus(val serviceEnabled: Boolean, val permission: String)

/** The last known fix: age and horizontal accuracy. No coordinates. */
data class LastKnownLocation(val ageMillis: Long, val accuracyMeters: Double)

/** Platform facts for [DiagnosticsService]; see `AndroidEnvironmentProbe`. Functions may throw. */
interface EnvironmentProbe {
    suspend fun appVersion(): AppVersionInfo
    fun operatingSystem(): String
    fun localeName(): String
    fun timeZoneName(): String
    fun utcOffsetSeconds(): Int
    suspend fun locationStatus(): LocationStatus

    /** `null` when the device has no last fix; throws without location permission (Dart: PermissionDeniedException). */
    suspend fun lastKnownLocation(): LastKnownLocation?
}
