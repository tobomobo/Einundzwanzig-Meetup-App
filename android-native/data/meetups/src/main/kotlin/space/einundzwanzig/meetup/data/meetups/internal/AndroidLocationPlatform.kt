package space.einundzwanzig.meetup.data.meetups.internal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import space.einundzwanzig.meetup.data.meetups.location.LocationPermission
import space.einundzwanzig.meetup.data.meetups.location.Position
import java.io.IOException
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * [LocationPlatform] on the framework `LocationManager` only — no Google Play services, so it
 * behaves the same on GrapheneOS/LineageOS/microG (spec 09 §8.5, §14 item 20).
 *
 * - Stage 1 ("balanced", 25 s): the OS fused provider (`fused`, API 31+ or when the device
 *   offers it), else the network provider, else GPS.
 * - Stage 2 ("LocationManager, high accuracy", 20 s): GPS, else network.
 * - Last known: the newest fix of all providers.
 */
@Singleton
internal class AndroidLocationPlatform @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: LocationPermissionBridgeImpl,
) : LocationPlatform {

    private val manager: LocationManager?
        get() = ContextCompat.getSystemService(context, LocationManager::class.java)

    private val directExecutor = Executor { it.run() }

    override fun isServiceEnabled(): Boolean = manager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

    override fun checkPermission(): LocationPermission {
        val granted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            granted -> LocationPermission.GRANTED.also { permissions.noteGranted() }
            permissions.deniedForever -> LocationPermission.DENIED_FOREVER
            else -> LocationPermission.DENIED
        }
    }

    override suspend fun requestPermission(): LocationPermission = permissions.request()

    override suspend fun currentPositionBalanced(timeoutSeconds: Long): Position {
        val lm = manager ?: throw IOException("Kein LocationManager")
        val provider = firstEnabled(lm, FUSED, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            ?: throw IOException("Kein Standortanbieter aktiv")
        return currentFix(lm, provider, timeoutSeconds)
    }

    override suspend fun currentPositionLocationManager(timeoutSeconds: Long): Position {
        val lm = manager ?: throw IOException("Kein LocationManager")
        val provider = firstEnabled(lm, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            ?: throw IOException("Kein Standortanbieter aktiv")
        return currentFix(lm, provider, timeoutSeconds)
    }

    @SuppressLint("MissingPermission") // checked in checkPermission() before every call
    override suspend fun lastKnownPosition(): Position? {
        val lm = manager ?: return null
        return lm.getProviders(true)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toPosition()
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFix(lm: LocationManager, provider: String, timeoutSeconds: Long): Position {
        val signal = CancellationSignal()
        val location = withTimeoutOrNull(timeoutSeconds * 1000) {
            suspendCancellableCoroutine<Location?> { cont ->
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, directExecutor) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            }
        }
        if (location == null) {
            signal.cancel()
            throw IOException("Kein Fix von '$provider' innerhalb von $timeoutSeconds s")
        }
        return location.toPosition()
    }

    private fun firstEnabled(lm: LocationManager, vararg providers: String): String? {
        val all = runCatching { lm.allProviders }.getOrDefault(emptyList())
        return providers.firstOrNull { p ->
            p in all && runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toPosition(): Position = Position(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else 0.0,
        timeMillis = time,
    )

    private companion object {
        /** `LocationManager.FUSED_PROVIDER` (public since API 31; the string exists on older OS builds too). */
        val FUSED: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else "fused"
    }
}
