package space.einundzwanzig.meetup.data.meetups.location

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance exactly like geolocator's pure-Dart `Geolocator.distanceBetween`
 * (haversine with R = 6 378 137 m, spec 09 §8.6).
 *
 * Do NOT use `android.location.Location.distanceBetween` (Vincenty on WGS84): its results differ
 * and would change radius decisions near the 5/10/50 km boundaries.
 */
object Geo {
    /** Earth radius used by geolocator, in meters. */
    const val EARTH_RADIUS_M = 6378137.0

    /** Distance in meters between two WGS84 points given in degrees. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + sin(dLon / 2).pow(2) * cos(rad(lat1)) * cos(rad(lat2))
        return EARTH_RADIUS_M * 2 * asin(sqrt(a))
    }

    /** [distanceMeters] / 1000. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        distanceMeters(lat1, lon1, lat2, lon2) / 1000.0

    private fun rad(deg: Double): Double = deg * PI / 180
}
