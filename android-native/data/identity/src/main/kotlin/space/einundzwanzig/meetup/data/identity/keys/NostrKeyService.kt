package space.einundzwanzig.meetup.data.identity.keys

/**
 * Creates and imports the local key (the key parts of Dart `NostrService`). Every call stores
 * nsec/npub/privHex in [SecureKeyStore]; it does **not** switch the signing mode and does not
 * touch the vault (callers follow spec 01 §8.2: `LocalKeyVault.clearAll()` + `useLocalMode()`).
 */
interface NostrKeyService {
    /** New random key, stored. */
    suspend fun generateKeyPair(): GeneratedKeys

    /**
     * Imports an `nsec1…` (trimmed).
     *
     * @throws space.einundzwanzig.meetup.data.identity.KeyFormatException
     *   `Ungültiges Format. Der Key muss mit "nsec1" beginnen.` / `Ungültiger nsec Key: …`.
     */
    suspend fun importNsec(nsec: String): GeneratedKeys

    /**
     * Imports a hex private key (`trim().lowercase()` must be 64 hex).
     *
     * @throws space.einundzwanzig.meetup.data.identity.KeyFormatException `Ungültiger privater Schlüssel (hex).`
     */
    suspend fun importPrivHex(privHex: String): GeneratedKeys

    /**
     * Decrypts an `ncryptsec1…` with [password] (scrypt, seconds) and imports it.
     *
     * @throws space.einundzwanzig.meetup.core.crypto.Nip49Exception on a wrong password / bad input.
     */
    suspend fun importNcryptsec(ncryptsec: String, password: String): GeneratedKeys
}

/** A stored key pair. [toString] never prints the nsec. */
data class GeneratedKeys(val nsec: String, val npub: String, val pubHex: String) {
    override fun toString(): String = "GeneratedKeys(npub=$npub)"
}
