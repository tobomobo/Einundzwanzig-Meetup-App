package space.einundzwanzig.meetup.data.identity.internal

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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
            suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                AppLogger.warn("DeviceAuth", "Authentifizierung abgebrochen (Code $errorCode)")
                            }
                            if (cont.isActive) cont.resume(false)
                        }
                        // onAuthenticationFailed: a single wrong finger; the prompt stays open.
                    },
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .apply { if (subtitle != null) setSubtitle(subtitle) }
                    .setAllowedAuthenticators(authenticators)
                    .build()
                prompt.authenticate(info)
                cont.invokeOnCancellation { prompt.cancelAuthentication() }
            }
        }
}
