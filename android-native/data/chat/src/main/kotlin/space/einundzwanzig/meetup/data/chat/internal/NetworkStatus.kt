package space.einundzwanzig.meetup.data.chat.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import space.einundzwanzig.meetup.core.common.log.AppLogger
import javax.inject.Inject

/**
 * Whether the device currently has a usable internet connection. Consulted only after a failed
 * relay connect, to classify it as "offline" (`chatOffline`) or "relay unavailable"
 * (spec 11 §8.2, §11: ConnectivityManager complements the exception heuristics).
 */
internal fun interface NetworkStatus {
    /** `false` when there is no active network or it has no validated internet access. */
    fun isOnline(): Boolean
}

/** [NetworkStatus] on [ConnectivityManager]: active network with `INTERNET` + `VALIDATED`. */
internal class ConnectivityNetworkStatus @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkStatus {

    override fun isOnline(): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let(cm::getNetworkCapabilities)
        // Unknown (no service) counts as online: then only the exception heuristics decide.
        if (cm == null) {
            true
        } else {
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    } catch (e: SecurityException) {
        // Missing ACCESS_NETWORK_STATE (should not happen, the module manifest declares it).
        AppLogger.debug(TAG, "Netzstatus nicht lesbar: ${e.message}")
        true
    }

    private companion object {
        const val TAG = "Chat"
    }
}
