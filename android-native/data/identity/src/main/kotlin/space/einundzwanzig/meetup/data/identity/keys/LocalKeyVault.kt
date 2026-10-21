package space.einundzwanzig.meetup.data.identity.keys

/**
 * NIP-49 wraps of the local key for EasyAuth (Dart `LocalKeyVault`), in the secure store:
 * `easyauth_password_ncryptsec` + `easyauth_password_wrap_npub` (password wrap bound to an npub),
 * `easyauth_passkey_ncryptsec` + `easyauth_passkey_credential_id` (passkey/PRF wrap, never exported).
 *
 * A wrap is a full copy of the private key: [clearAll] on reset, import and new key.
 */
interface LocalKeyVault {
    /** Stores the password wrap and binds it to [npub]. */
    suspend fun savePasswordWrap(ncryptsec: String, npub: String)

    /** The password wrap (`ncryptsec1…`). */
    suspend fun getPasswordWrap(): String?

    /** The npub the password wrap belongs to. */
    suspend fun getPasswordWrapNpub(): String?

    /** `true` only if a wrap exists AND its binding is non-empty AND equals [npub] exactly. */
    suspend fun passwordWrapMatchesNpub(npub: String): Boolean

    /** Stores the passkey wrap and its WebAuthn credential id. */
    suspend fun savePasskeyWrap(ncryptsec: String, credentialId: String)

    /** The passkey wrap. */
    suspend fun getPasskeyWrap(): String?

    /** The WebAuthn credential id (base64url). */
    suspend fun getPasskeyCredentialId(): String?

    /** `true` iff wrap AND credential id are non-empty. */
    suspend fun hasPasskeyWrap(): Boolean

    /** Removes the passkey wrap; keeps the password wrap. */
    suspend fun clearPasskeyWrap()

    /** Removes all four entries. */
    suspend fun clearAll()
}
