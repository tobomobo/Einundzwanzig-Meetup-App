package space.einundzwanzig.meetup.data.identity.auth

import androidx.fragment.app.FragmentActivity

/**
 * Device authentication before revealing secrets (Security Audit M3, spec 01 §9, spec 14 §6):
 * PIN/pattern/password or biometrics via `BiometricPrompt` (`BIOMETRIC_WEAK or DEVICE_CREDENTIAL`).
 * A device without any screen lock passes; cancel/error denies.
 *
 * The host must be a [FragmentActivity] (`MainActivity` extends it, spec 14 F8). The activity is
 * used only for this call.
 */
interface DeviceAuthenticator {
    /**
     * Shows the system prompt and suspends until the user decides.
     *
     * @param title prompt title, e.g. "Bitte Identität bestätigen, um den Schlüssel anzuzeigen".
     * @return `true` if authenticated (or the device has no lock at all), `false` otherwise.
     */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String? = null): Boolean
}
