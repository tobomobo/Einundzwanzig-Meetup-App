package space.einundzwanzig.meetup.data.meetups.internal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.location.Geo
import space.einundzwanzig.meetup.data.meetups.location.GpsStatus
import space.einundzwanzig.meetup.data.meetups.location.LocationMeetupResult
import space.einundzwanzig.meetup.data.meetups.location.LocationPermission
import space.einundzwanzig.meetup.data.meetups.location.LocationResult
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import space.einundzwanzig.meetup.data.meetups.location.MeetupCandidate
import space.einundzwanzig.meetup.data.meetups.location.MeetupLocationService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import javax.inject.Inject
import javax.inject.Singleton

/** Dart `MeetupLocationService` + `NearbyMeetupService.getCurrentLocation` (spec 09 §8.5–§8.7). */
@Singleton
internal class MeetupLocationServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val platform: LocationPlatform,
    private val meetups: MeetupService,
    time: TimeSource,
    private val log: DiagnosticLog,
) : MeetupLocationService {

    private val acquisition = LocationAcquisition(platform, time, log)

    override fun isLocationServiceEnabled(): Boolean = platform.isServiceEnabled()

    override fun permissionState(): LocationPermission = platform.checkPermission()

    override suspend fun getCurrentLocation(): LocationResult = acquisition.getCurrentLocation()

    override suspend fun resolveLocation(): LocationMeetupResult {
        val loc = getCurrentLocation()
        if (loc.status != LocationStatus.OK || loc.position == null) return LocationMeetupResult(loc.status.toGpsStatus())
        return resolveWith(loc, meetups.fetchMeetups())
    }

    override fun openLocationSettings(): Boolean = launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    override fun openAppSettings(): Boolean = launch(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )

    private fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        log.warn(TAG, "Einstellungen nicht zu oeffnen: ${intent.action}", e)
        false
    }

    companion object {
        private const val TAG = "Standort"

        /** Pure part of [resolveLocation]: candidates with coordinates, sorted by distance. */
        internal fun resolveWith(
            loc: LocationResult,
            list: List<Meetup>,
        ): LocationMeetupResult {
            val pos = loc.position
            if (loc.status != LocationStatus.OK || pos == null) return LocationMeetupResult(loc.status.toGpsStatus())
            val candidates = list
                .filter { !(it.lat == 0.0 && it.lng == 0.0) }
                .map { MeetupCandidate(it, Geo.distanceKm(pos.latitude, pos.longitude, it.lat, it.lng)) }
                .sortedBy { it.distanceKm }
            return LocationMeetupResult(
                status = GpsStatus.OK,
                lat = pos.latitude,
                lng = pos.longitude,
                candidates = candidates,
            )
        }
    }
}
