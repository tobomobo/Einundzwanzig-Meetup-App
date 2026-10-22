package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.meetups.location.LocationPermission
import space.einundzwanzig.meetup.data.meetups.location.LocationResult
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import space.einundzwanzig.meetup.data.meetups.location.MeetupLocationService
import space.einundzwanzig.meetup.data.meetups.location.Position

/** Platform seam of [LocationAcquisition] (Android `LocationManager` in production, fakes in tests). */
internal interface LocationPlatform {
    fun isServiceEnabled(): Boolean
    fun checkPermission(): LocationPermission
    suspend fun requestPermission(): LocationPermission

    /** Balanced-power fix (fused provider where available); throws on timeout/no fix. */
    suspend fun currentPositionBalanced(timeoutSeconds: Long): Position

    /** High-accuracy fix forced through `LocationManager` (GPS); throws on timeout/no fix. */
    suspend fun currentPositionLocationManager(timeoutSeconds: Long): Position

    /** The freshest last known fix of any provider, or `null`. */
    suspend fun lastKnownPosition(): Position?
}

/** Dart `NearbyMeetupService.getCurrentLocation` (spec 09 §8.5), platform-independent. */
internal class LocationAcquisition(
    private val platform: LocationPlatform,
    private val time: TimeSource,
    private val log: DiagnosticLog,
) {
    suspend fun getCurrentLocation(): LocationResult = try {
        acquire()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG_NEARBY, "Standortfehler: $e")
        LocationResult(LocationStatus.ERROR)
    }

    private suspend fun acquire(): LocationResult {
        if (!platform.isServiceEnabled()) return LocationResult(LocationStatus.SERVICE_DISABLED)
        var permission = platform.checkPermission()
        // DENIED_FOREVER is only remembered from the last prompt, and the user may have reset the
        // permission in the system settings since. Re-validate with one request: if it really is
        // denied forever, Android answers instantly without a dialog (Dart skipped the request).
        if (permission != LocationPermission.GRANTED) permission = platform.requestPermission()
        if (permission == LocationPermission.DENIED_FOREVER) return LocationResult(LocationStatus.DENIED_FOREVER)
        if (permission == LocationPermission.DENIED) return LocationResult(LocationStatus.DENIED)

        val first = attempt { platform.currentPositionBalanced(MeetupLocationService.FIRST_FIX_TIMEOUT_S) }
        first.getOrNull()?.let {
            log.diag(TAG, "Frische Position ermittelt (Genauigkeit ${it.accuracyMeters.toStringAsFixed(0)} m).")
            return LocationResult(LocationStatus.OK, it)
        }
        val e = first.exceptionOrNull()
        log.warn(TAG, "Erster Versuch ohne Fix — zweiter ueber den LocationManager.", e)
        val second = attempt { platform.currentPositionLocationManager(MeetupLocationService.SECOND_FIX_TIMEOUT_S) }
        second.getOrNull()?.let {
            log.diag(TAG, "Position ueber LocationManager (Genauigkeit ${it.accuracyMeters.toStringAsFixed(0)} m).")
            return LocationResult(LocationStatus.OK, it)
        }
        log.warn(TAG, "Auch der LocationManager lieferte keinen Fix.", second.exceptionOrNull())

        log.warn(TAG, "Kein frischer GPS-Fix — versuche letzte bekannte Position.", e)
        val last = platform.lastKnownPosition()
        if (last != null) {
            // Dart: DateTime.now().difference(last.timestamp).inMinutes (truncating) <= 10.
            val ageMinutes = (time.nowMillis() - last.timeMillis) / 60_000L
            if (ageMinutes <= MeetupLocationService.MAX_LAST_KNOWN_AGE_MIN) {
                log.diag(TAG, "Letzte bekannte Position akzeptiert ($ageMinutes Min alt).")
                return LocationResult(LocationStatus.OK, last)
            }
            log.warn(TAG, "Letzte bekannte Position ist $ageMinutes Min alt — zu alt, verworfen.")
        } else {
            log.warn(TAG, "Keine letzte bekannte Position vorhanden.")
        }
        return LocationResult(LocationStatus.NO_FIX)
    }

    private inline fun attempt(block: () -> Position): Result<Position> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private companion object {
        const val TAG = "Standort"
        const val TAG_NEARBY = "Nearby"
    }
}
