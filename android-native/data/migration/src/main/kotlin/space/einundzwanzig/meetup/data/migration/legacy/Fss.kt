package space.einundzwanzig.meetup.data.migration.legacy

/** flutter_secure_storage 9.2.4 / 10.3.4 on-device names (spec 14 §4.5, fixture `fss_constants`). */
internal object Fss {
    const val DATA = "FlutterSecureStorage"
    const val KEYS = "FlutterSecureKeyStorage"
    const val CONFIG = "FlutterSecureStorageConfiguration:FlutterSecureStorage"
    const val LEGACY_CONFIG = "FlutterSecureStorageConfiguration"
    const val PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg"
    const val ENTRY = "${PREFIX}_"
    const val BACKUP = "_BACKUP"
    const val MIGRATED = "_MIGRATED"

    const val WRAPPED_V10_GCM = "AESVGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK"
    const val WRAPPED_V9_GCM_TYPO = "VGhpcyBpcyB0aGUga2V5IGZvcihBIHNlY3XyZZBzdG9yYWdlIEFFUyBLZXkK"
    const val WRAPPED_V9_CBC = "VGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK"

    /** Default order: v10 GCM name → v9 typo name → v9 CBC name. */
    val WRAPPED_KEY_NAMES = listOf(WRAPPED_V10_GCM, WRAPPED_V9_GCM_TYPO, WRAPPED_V9_CBC)

    const val MARKER_KEY_ALGORITHM = "FlutterSecureSAlgorithmKey"
    const val MARKER_STORAGE_ALGORITHM = "FlutterSecureSAlgorithmStorage"
    const val STORAGE_GCM = "AES_GCM_NoPadding"

    const val ESP_KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    const val ESP_VALUE_KEYSET = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    const val ESP_MASTER_ALIAS = "_androidx_security_master_key_"

    fun pkcs1Alias(pkg: String) = "$pkg.FlutterSecureStoragePluginKey"
    fun oaepAlias(pkg: String) = "$pkg.FlutterSecureStoragePluginKeyOAEP"
}

/**
 * The 19 flutter_secure_storage keys the Flutter app ever wrote (spec 14 §4.4) with the
 * plausibility check of each (§4.6.5). Used to accept CBC trial decryptions (unauthenticated) and
 * to flag, never reject, authenticated values.
 */
internal object SecureKeyInventory {
    private val NSEC = Regex("^nsec1[02-9ac-hj-np-z]{58}$")
    private val NPUB = Regex("^npub1[02-9ac-hj-np-z]{58}$")
    private val HEX64_LOWER = Regex("^[0-9a-f]{64}$")
    private val HEX64_ANY = Regex("^[0-9a-fA-F]{64}$")
    private val HEX64_TRIMMED = Regex("^\\s*[0-9a-fA-F]{64}\\s*$")
    private val NCRYPTSEC = Regex("^ncryptsec1[02-9ac-hj-np-z]+$")
    private val CREDENTIAL_ID = Regex("^[A-Za-z0-9_-]+={0,2}$")
    private val BADGE_BLOB = Regex("^[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+$")

    private val nonEmpty: (String) -> Boolean = { it.isNotEmpty() }
    private val nonEmptyNoControl: (String) -> Boolean = { s -> s.isNotEmpty() && s.none { it.isISOControl() } }
    private val jsonArray: (String) -> Boolean = { s ->
        runCatching { space.einundzwanzig.meetup.core.common.json.DartJson.decode(s) is kotlinx.serialization.json.JsonArray }
            .getOrDefault(false)
    }

    /** Key name → validator, in the order of the spec table. */
    val VALIDATORS: Map<String, (String) -> Boolean> = linkedMapOf(
        "nostr_nsec_key" to NSEC::matches,
        "nostr_npub_key" to NPUB::matches,
        "nostr_priv_hex" to HEX64_LOWER::matches,
        "nip46_client_sk" to HEX64_LOWER::matches,
        "easyauth_password_ncryptsec" to NCRYPTSEC::matches,
        "easyauth_password_wrap_npub" to NPUB::matches,
        "easyauth_passkey_ncryptsec" to NCRYPTSEC::matches,
        "easyauth_passkey_credential_id" to CREDENTIAL_ID::matches,
        "portal_api_token" to nonEmptyNoControl,
        "portal_api_token_npub" to NPUB::matches,
        "rqr_session_seed_secure" to HEX64_LOWER::matches,
        "rqr_session_base_payload_secure" to nonEmpty,
        "badge_encryption_key" to HEX64_TRIMMED::matches,
        "badge_encryption_key_suspect" to nonEmpty,
        "badge_pending_key" to HEX64_ANY::matches,
        "badge_pending_key_suspect" to nonEmpty,
        "badges_encrypted" to BADGE_BLOB::matches,
        "badges_former" to jsonArray,
        "badge_reset_pending" to { it == "1" },
    )

    /** Every known key name. */
    val KEYS: Set<String> get() = VALIDATORS.keys

    /**
     * Identity keys (spec 14 §4.8): an ESP value for one of these becomes primary only when the
     * ESP identity matches an npub the Flutter prefs still reference.
     */
    val IDENTITY_KEYS: Set<String> = setOf(
        "nostr_nsec_key", "nostr_npub_key", "nostr_priv_hex", "nip46_client_sk",
        "easyauth_password_ncryptsec", "easyauth_password_wrap_npub",
        "easyauth_passkey_ncryptsec", "easyauth_passkey_credential_id",
        "portal_api_token", "portal_api_token_npub",
    )

    /** Whether [value] passes the plausibility check of [key]; unknown keys: non-empty, no control chars. */
    fun isPlausible(key: String, value: String): Boolean = (VALIDATORS[key] ?: nonEmptyNoControl)(value)
}
