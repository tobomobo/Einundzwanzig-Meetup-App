package space.einundzwanzig.meetup.data.identity.easyauth

import android.app.Activity
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfResult

/**
 * Name + password onboarding with an optional passkey second unlock (Dart `LocalEasyAuth`,
 * spec 01 §8.3). The key is generated on the device and wrapped with NIP-49 locally.
 *
 * Errors: [space.einundzwanzig.meetup.data.identity.LocalEasyAuthException] for user input,
 * [space.einundzwanzig.meetup.core.crypto.Nip49Exception] for a wrong password,
 * [space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfException] for passkey failures.
 * scrypt runs on the default dispatcher (log_n 16 = 64 MiB, seconds).
 *
 * **Passkeys from a ViewModel**: use the split steps. The UI runs the short Credential Manager
 * call with its Activity (`PasskeyPrfService.register/unlock`), the ViewModel does the slow,
 * Activity-free part:
 * ```
 * // add:    val req = easyAuth.passkeyRegistration(name)                      (VM)
 * //         val res = passkeys.register(activity, req.userIdHex, req.userName) (UI)
 * //         easyAuth.savePasskeyWrap(req, res)                                 (VM)
 * // unlock: val ids = easyAuth.passkeyCredentialIds()                          (VM)
 * //         val res = passkeys.unlock(activity, ids)                           (UI)
 * //         easyAuth.unlockWithPrfSecret(res)                                  (VM)
 * ```
 * [addPasskeyWrap] / [unlockWithPasskey] combine both and hold the Activity for the whole scrypt;
 * call them only from a UI-scoped coroutine.
 */
interface LocalEasyAuth {
    /**
     * New identity: validates (name not empty/`Anon`, password ≥ [MIN_PASSWORD_LENGTH] UTF-16
     * units), generates the key **in memory**, computes the password wrap (log_n 16, key security
     * unknown) **before** persisting anything, clears an old passkey wrap, stores the key, switches
     * to local mode, stores the wrap bound to the npub and saves the profile (nickname, npub,
     * verified, not demo). Overwrites an existing local key (Dart behaviour).
     */
    suspend fun register(nickname: String, password: String)

    /**
     * Step 1 of adding a passkey: the WebAuthn user for the current local key.
     *
     * @throws space.einundzwanzig.meetup.data.identity.LocalEasyAuthException
     *   "Kein lokaler Schlüssel vorhanden." without a local key.
     */
    suspend fun passkeyRegistration(displayName: String): PasskeyRegistration

    /**
     * Step 3 of adding a passkey: wraps the current local key with the PRF secret of [result]
     * (log_n 8, never exported) and stores it with the credential id.
     *
     * @throws space.einundzwanzig.meetup.data.identity.LocalEasyAuthException if the local key
     *   is gone or is no longer the one [registration] was made for.
     */
    suspend fun savePasskeyWrap(registration: PasskeyRegistration, result: PasskeyPrfResult)

    /** [passkeyRegistration] + `PasskeyPrfService.register` + [savePasskeyWrap] in one call (holds [activity]). */
    suspend fun addPasskeyWrap(activity: Activity, displayName: String)

    /** Decrypts the stored password wrap, imports the key and switches to local mode. */
    suspend fun unlockWithPassword(password: String)

    /**
     * Step 1 of the passkey unlock: the credential ids to offer.
     *
     * @throws space.einundzwanzig.meetup.data.identity.LocalEasyAuthException
     *   "Kein Passkey hinterlegt." without a passkey wrap.
     */
    suspend fun passkeyCredentialIds(): List<String>

    /** Step 3 of the passkey unlock: decrypts the passkey wrap with [result], imports the key, local mode. */
    suspend fun unlockWithPrfSecret(result: PasskeyPrfResult)

    /** [passkeyCredentialIds] + `PasskeyPrfService.unlock` + [unlockWithPrfSecret] in one call (holds [activity]). */
    suspend fun unlockWithPasskey(activity: Activity)

    companion object {
        /** `kMinPasswordLength` (UTF-16 code units). */
        const val MIN_PASSWORD_LENGTH: Int = 8
    }
}

/** The WebAuthn user for `PasskeyPrfService.register` (see [LocalEasyAuth.passkeyRegistration]). */
data class PasskeyRegistration(
    /** x-only public key (hex) of the local key; the WebAuthn `user.id`. */
    val userIdHex: String,
    /** Trimmed display name, `21meetup` if empty. */
    val userName: String,
)
