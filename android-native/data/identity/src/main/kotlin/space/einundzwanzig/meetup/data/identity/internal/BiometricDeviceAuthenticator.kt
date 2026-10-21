package space.einundzwanzig.meetup.data.identity.internal

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.identity.auth.DeviceAuthenticator
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
internal class BiometricDeviceAuthenticator @Inject constructor() : DeviceAuthenticator {

    override suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String?): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
            val canAuth = BiometricManager.from(activity).canAuthenticate(authenticators)
            val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguard?.isDeviceSecure != true && canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                // No screen lock and no biometrics: nothing to verify against (Flutter local_auth parity).
                return@withContext true
            }
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) return@withContext false
            suspendCancellableCoroutine { cont ->
                var prompt: BiometricPrompt? = null
                var observer: LifecycleEventObserver? = null
                fun finish(ok: Boolean) {
                    observer?.let { activity.lifecycle.removeObserver(it) }
                    observer = null
                    if (cont.isActive) cont.resume(ok)
                }
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        finish(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            AppLogger.warn("DeviceAuth", "Authentifizierung abgebrochen (Code $errorCode)")
                        }
                        finish(false)
                    }
                    // onAuthenticationFailed: a single wrong finger; the prompt stays open.
                }
                // The callback is bound to this Activity: if it is destroyed (configuration
                // change) while the prompt is up, nobody would ever resume us.
                observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        runCatching { prompt?.cancelAuthentication() }
                        finish(false)
                    }
                }.also { activity.lifecycle.addObserver(it) }
                cont.invokeOnCancellation {
                    // Main.immediate: the continuation is cancelled on the main thread or posted there.
                    ContextCompat.getMainExecutor(activity).execute {
                        runCatching { prompt?.cancelAuthentication() }
                        observer?.let { activity.lifecycle.removeObserver(it) }
                        observer = null
                    }
                }
                try {
                    val info = BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title)
                        .apply { if (subtitle != null) setSubtitle(subtitle) }
                        .setAllowedAuthenticators(authenticators)
                        .build()
                    prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
                        .also { it.authenticate(info) }
                } catch (e: IllegalStateException) {
                    // FragmentManager state already saved / Activity going away.
                    AppLogger.warn("DeviceAuth", "Geräte-Authentifizierung konnte nicht gestartet werden", e)
                    finish(false)
                } catch (e: IllegalArgumentException) {
                    AppLogger.warn("DeviceAuth", "Geräte-Authentifizierung konnte nicht gestartet werden", e)
                    finish(false)
                }
            }
        }
}
