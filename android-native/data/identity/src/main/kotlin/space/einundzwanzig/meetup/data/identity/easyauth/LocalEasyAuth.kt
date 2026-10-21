package space.einundzwanzig.meetup.data.identity.easyauth

import android.app.Activity

/**
 * Name + password onboarding with an optional passkey second unlock (Dart `LocalEasyAuth`,
 * spec 01 §8.3). The key is generated on the device and wrapped with NIP-49 locally.
 *
 * Errors: [space.einundzwanzig.meetup.data.identity.LocalEasyAuthException] for user input,
 * [space.einundzwanzig.meetup.core.crypto.Nip49Exception] for a wrong password,
 * [space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfException] for passkey failures.
 * scrypt runs on the default dispatcher (log_n 16 = 64 MiB, seconds).
 */
interface LocalEasyAuth {
    /**
     * New identity: validates (name not empty/`Anon`, password ≥ 8 UTF-16 units), generates the key
     * **in memory**, computes the password wrap (log_n 16, key security unknown) **before**
     * persisting anything, clears an old passkey wrap, stores the key, switches to local mode,
     * stores the wrap bound to the npub and saves the profile (nickname, npub, verified, not demo).
     * Overwrites an existing local key (Dart behaviour).
     */
    suspend fun register(nickname: String, password: String)

    /** Adds the passkey/PRF wrap (log_n 8, never exported) for the current local key. */
    suspend fun addPasskeyWrap(activity: Activity, displayName: String)

    /** Decrypts the stored password wrap, imports the key and switches to local mode. */
    suspend fun unlockWithPassword(password: String)

    /** Unlocks the passkey wrap via PRF, imports the key and switches to local mode. */
    suspend fun unlockWithPasskey(activity: Activity)
}
