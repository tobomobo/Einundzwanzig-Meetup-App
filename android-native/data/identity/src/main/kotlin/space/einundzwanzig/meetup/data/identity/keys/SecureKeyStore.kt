package space.einundzwanzig.meetup.data.identity.keys

/**
 * The user's local Nostr key and the NIP-46 session key in the Keystore-backed secure store
 * (Dart `SecureKeyStore`). Key names: `nostr_nsec_key`, `nostr_npub_key`, `nostr_priv_hex`,
 * `nip46_client_sk`.
 *
 * Every call first awaits [ensureMigrated] (mutex: concurrent first callers wait, Security Audit 2
 * #8). Reads throw [space.einundzwanzig.meetup.data.identity.SecureStorageUnreadableException]
 * when an entry exists but cannot be decrypted (never "no key"); failed writes throw
 * [space.einundzwanzig.meetup.data.identity.IdentityStorageException].
 */
interface SecureKeyStore {
    /**
     * One-time import of legacy **plaintext** keys from the prefs (`nostr_nsec_key`,
     * `nostr_npub_key`, `nostr_priv_hex`) unless `secure_migration_done` is true; removes them
     * from the prefs afterwards and sets the flag.
     */
    suspend fun ensureMigrated()

    /** Stores nsec, npub and private hex atomically. */
    suspend fun saveKeys(nsec: String, npub: String, privHex: String)

    /** `nostr_nsec_key`. */
    suspend fun getNsec(): String?

    /** `nostr_npub_key`. */
    suspend fun getNpub(): String?

    /** `nostr_priv_hex` (64 lowercase hex). */
    suspend fun getPrivHex(): String?

    /** Both nsec and npub, or `null` unless both exist. */
    suspend fun loadKeys(): StoredKeys?

    /** `true` iff `nostr_nsec_key` is present and non-empty. */
    suspend fun hasKey(): Boolean

    /** Deletes the three key entries; **not** the NIP-46 session key. */
    suspend fun deleteKeys()

    /** Stores the app-side NIP-46 session key. */
    suspend fun saveNip46ClientKey(privHex: String)

    /** The app-side NIP-46 session key. */
    suspend fun getNip46ClientKey(): String?

    /** Deletes the NIP-46 session key (idempotent). */
    suspend fun deleteNip46ClientKey()
}

/** Result of [SecureKeyStore.loadKeys]. [toString] never prints the nsec. */
data class StoredKeys(val nsec: String, val npub: String) {
    override fun toString(): String = "StoredKeys(npub=$npub)"
}
