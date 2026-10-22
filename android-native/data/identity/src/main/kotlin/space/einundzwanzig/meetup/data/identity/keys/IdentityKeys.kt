package space.einundzwanzig.meetup.data.identity.keys

/**
 * Storage key names of the identity domain. Identical to the Flutter key names (prefs without the
 * `flutter.` prefix, secure entries as flutter_secure_storage keys), spec 01 §4.1/§4.2.
 */
object IdentityKeys {
    // --- non-secret prefs (KeyValueStore) ---
    /** `local` / `amber` / `nip07` / `nip46`. */
    const val SIGNING_MODE = "signing_mode"
    const val AMBER_PUBKEY_HEX = "amber_pubkey_hex"
    const val AMBER_NPUB = "amber_npub"
    const val NIP07_PUBKEY_HEX = "nip07_pubkey_hex"
    const val NIP07_NPUB = "nip07_npub"
    const val NIP46_PUBKEY_HEX = "nip46_pubkey_hex"
    const val NIP46_NPUB = "nip46_npub"

    /** `bunker://<signer-hex>?relay=…` without secret. */
    const val NIP46_BUNKER_URI = "nip46_bunker_uri"

    /** Legacy plaintext → secure migration done (bool). */
    const val SECURE_MIGRATION_DONE = "secure_migration_done"

    /** App-Review demo identity flag (bool). */
    const val APP_REVIEW_DEMO = "app_review_demo"

    // --- secrets (SecureStore); before secure storage existed the first three were plain prefs ---
    const val NSEC = "nostr_nsec_key"
    const val NPUB = "nostr_npub_key"
    const val PRIV_HEX = "nostr_priv_hex"

    /** App-side NIP-46 session key (not the user's key). */
    const val NIP46_CLIENT_SK = "nip46_client_sk"
    const val EASYAUTH_PASSWORD_NCRYPTSEC = "easyauth_password_ncryptsec"
    const val EASYAUTH_PASSWORD_WRAP_NPUB = "easyauth_password_wrap_npub"
    const val EASYAUTH_PASSKEY_NCRYPTSEC = "easyauth_passkey_ncryptsec"
    const val EASYAUTH_PASSKEY_CREDENTIAL_ID = "easyauth_passkey_credential_id"

    // --- recovery names written by :data:migration (spec 14 §4.8) ---
    /** Prefixes of the recovery copies: `<prefix>/<flutter key>` in the secure store. */
    val RECOVERY_PREFIXES: List<String> = listOf("legacy_esp", "legacy_backup", "legacy_recovered")

    /** Every secure entry that holds (a copy of) the user's key or the NIP-46 session key. */
    val IDENTITY_SECRET_NAMES: List<String> = listOf(
        NSEC, NPUB, PRIV_HEX, NIP46_CLIENT_SK,
        EASYAUTH_PASSWORD_NCRYPTSEC, EASYAUTH_PASSWORD_WRAP_NPUB, EASYAUTH_PASSKEY_NCRYPTSEC, EASYAUTH_PASSKEY_CREDENTIAL_ID,
    )

    /** The recovery name `<prefix>/<name>`. */
    fun recoveryName(prefix: String, name: String): String = "$prefix/$name"
}
