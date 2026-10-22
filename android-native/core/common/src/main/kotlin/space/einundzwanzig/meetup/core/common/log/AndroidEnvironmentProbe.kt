package space.einundzwanzig.meetup.core.common.log

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import java.util.Locale
import java.util.TimeZone

/**
 * [EnvironmentProbe] backed by the Android framework. Never prompts for a permission.
 *
 * Differences to the Flutter plugins it replaces: the OS line carries release, API level and
 * device model instead of Dart's `Platform.operatingSystemVersion`; the time zone is the
 * IANA id (`Europe/Berlin`) instead of an abbreviation; the location age is measured on the
 * elapsed-realtime clock, so a wrong wall clock cannot distort it.
 */
class AndroidEnvironmentProbe(context: Context) : EnvironmentProbe {

    private val context = context.applicationContext ?: context

    override suspend fun appVersion(): AppVersionInfo {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return AppVersionInfo(info.versionName.orEmpty(), code)
    }

    override fun operatingSystem(): String =
        "android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL})"

    override fun localeName(): String = Locale.getDefault().toString()

    override fun timeZoneName(): String = TimeZone.getDefault().id

    override fun utcOffsetSeconds(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

    override suspend fun locationStatus(): LocationStatus {
        val lm = locationManager()
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        return LocationStatus(enabled, permissionName())
    }

    @SuppressLint("MissingPermission") // checked by hasForegroundPermission() right before
    override suspend fun lastKnownLocation(): LastKnownLocation? {
        if (!hasForegroundPermission()) throw SecurityException("Standort-Berechtigung fehlt")
        val lm = locationManager()
        val newest: Location = lm.getProviders(true)
            .mapNotNull { provider -> lm.getLastKnownLocation(provider) }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?: return null
        val ageNanos = SystemClock.elapsedRealtimeNanos() - newest.elapsedRealtimeNanos
        return LastKnownLocation(
            ageMillis = maxOf(0L, ageNanos / 1_000_000),
            accuracyMeters = if (newest.hasAccuracy()) newest.accuracy.toDouble() else 0.0,
        )
    }

    private fun locationManager(): LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: throw IllegalStateException("LocationManager nicht verfuegbar")

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasForegroundPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** geolocator `LocationPermission.name`; `deniedForever` cannot be told apart without an Activity. */
    private fun permissionName(): String = when {
        !hasForegroundPermission() -> "denied"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> "always"
        else -> "whileInUse"
    }
}
