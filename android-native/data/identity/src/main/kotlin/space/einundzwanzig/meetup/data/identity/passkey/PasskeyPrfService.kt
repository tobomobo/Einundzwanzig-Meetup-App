package space.einundzwanzig.meetup.data.identity.passkey

import android.app.Activity

/**
 * WebAuthn passkey with the PRF extension as the second EasyAuth unlock (Dart
 * `PasskeyPrfService`, spec 01 §7.5/§8.4). The PRF output (lowercase hex) is the NIP-49 password
 * of the passkey wrap.
 *
 * **Availability:** the relying party is `einundzwanzig.space`. Credential Manager only accepts it
 * when `https://einundzwanzig.space/.well-known/assetlinks.json` lists this app with the release
 * certificate (`delegate_permission/common.get_login_creds`). That file does not exist yet
 * (spec 14 F9), so [register] currently fails on real devices with a domain/RP error that
 * [PasskeyPrfException.isUnavailable] reports as "passkey unavailable". [isSupported] only says
 * whether the device has a user-verifying platform authenticator; it cannot see the missing
 * asset links (Credential Manager has no API to check them without trying).
 *
 * The [Activity] parameters are only used for the duration of the call (Credential Manager needs
 * an activity context to show its UI); nothing keeps a reference.
 */
interface PasskeyPrfService {
    /** API ≥ 28 and a user-verifying platform authenticator (Play Services FIDO2); errors → `false`. */
    suspend fun isSupported(): Boolean

    /**
     * Creates a passkey for [userIdHex] (32-byte pubkey hex) and returns its PRF secret. If the
     * create response carries no PRF output, an immediate [unlock] with the new credential obtains
     * it (second prompt).
     *
     * @throws PasskeyPrfException
     */
    suspend fun register(activity: Activity, userIdHex: String, userName: String): PasskeyPrfResult

    /**
     * Authenticates with one of [credentialIds] and evaluates PRF.
     *
     * @throws PasskeyPrfException (`PRF_UNSUPPORTED` if no PRF output came back)
     */
    suspend fun unlock(activity: Activity, credentialIds: List<String>): PasskeyPrfResult
}

/**
 * @property credentialId base64url credential id (WebAuthn JSON `id`).
 * @property prfSecretHex lowercase hex of `prf.results.first`.
 */
data class PasskeyPrfResult(val credentialId: String, val prfSecretHex: String) {
    override fun toString(): String = "PasskeyPrfResult(credentialId=$credentialId)"
}

/**
 * Passkey failure. [code] is a stable machine code ([PRF_UNSUPPORTED], [DOMAIN_NOT_ASSOCIATED],
 * [NO_CREATE_OPTION], [UNSUPPORTED], [NO_CREDENTIAL], [UNHANDLED], [CANCELLED], [FAILED]); [message]
 * is the code or the platform text. Only [FAILED] carries a platform text meant for the user.
 */
class PasskeyPrfException(
    val code: String,
    override val message: String = code,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** `true` if the UI should show `idSetupPasskeyUnavailable` (spec 01 §2.3.5). */
    val isUnavailable: Boolean get() = code in UNAVAILABLE_CODES

    /** The user dismissed the system dialog (the UI may stay silent). */
    val isCancelled: Boolean get() = code == CANCELLED

    override fun toString(): String = message

    companion object {
        const val PRF_UNSUPPORTED = "PRF_UNSUPPORTED"
        const val RP_ID_NOT_ALLOWED = "RP_ID_NOT_ALLOWED"
        const val DOMAIN_NOT_ASSOCIATED = "DomainNotAssociated"
        const val NO_CREATE_OPTION = "NoCreateOption"
        const val UNSUPPORTED = "TYPE_NOT_SUPPORTED_ERROR"
        const val NO_CREDENTIAL = "NoCredential"
        const val CANCELLED = "Cancelled"
        const val FAILED = "Failed"

        /** Any other platform error (Flutter `android-unhandled`, shown as "unavailable" there too). */
        const val UNHANDLED = "android-unhandled"

        private val UNAVAILABLE_CODES =
            setOf(PRF_UNSUPPORTED, RP_ID_NOT_ALLOWED, DOMAIN_NOT_ASSOCIATED, NO_CREATE_OPTION, UNSUPPORTED, NO_CREDENTIAL, UNHANDLED)
    }
}
