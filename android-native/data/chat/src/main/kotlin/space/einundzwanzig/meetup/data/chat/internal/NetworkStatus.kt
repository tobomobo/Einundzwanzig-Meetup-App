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
    /** `false` only when there is no usable network at all (no active network or no `INTERNET`). */
    fun isOnline(): Boolean
}

/**
 * The classification rule: online as soon as an active network offers `INTERNET`. `VALIDATED` is
 * deliberately ignored: some networks never get it although the internet works (Google's check
 * blocked, VPN / private DNS, corporate Wi-Fi), and requiring it would turn a real relay outage
 * into "offline" there, the misdirection Dart dacccf4 fixed. A captive portal then reads as
 * "relay unavailable" unless the exception itself looks offline ([looksOffline]).
 */
internal fun hasUsableNetwork(hasActiveNetwork: Boolean, hasInternet: Boolean): Boolean = hasActiveNetwork && hasInternet

/** [NetworkStatus] on [ConnectivityManager]: an active network with `INTERNET` ([hasUsableNetwork]). */
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
            hasUsableNetwork(
                hasActiveNetwork = caps != null,
                hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            )
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
