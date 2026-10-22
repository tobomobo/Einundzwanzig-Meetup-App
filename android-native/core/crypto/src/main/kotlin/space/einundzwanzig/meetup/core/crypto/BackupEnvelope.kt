package space.einundzwanzig.meetup.core.crypto

/** Backup file could not be opened: wrong password, corrupt or tampered file. */
class BackupCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The encryption envelope of `.21bkp` files (`BackupService.exportData` / `importData`):
 *
 * - `enc_v2:<b64 salt(32)>:<b64 iv(16)>:<b64 ciphertext‖tag>` — key = PBKDF2-HMAC-SHA256(
 *   UTF-8 password, salt, 600 000, 32), AES-256-GCM, no AAD. Written by the app.
 * - `enc_v1:<b64 iv>:<b64 ciphertext‖tag>` — legacy, key = SHA-256(UTF-8 password). Read only.
 * - anything else is plain JSON.
 *
 * Key derivation takes seconds: call off the main thread.
 */
object BackupEnvelope {
    const val PREFIX_V2 = "enc_v2:"
    const val PREFIX_V1 = "enc_v1:"
    const val SALT_SIZE = 32
    const val IV_SIZE = 16
    const val KEY_SIZE = 32

    enum class Format { V2, V1, PLAIN }

    fun formatOf(content: String): Format = when {
        content.startsWith(PREFIX_V2) -> Format.V2
        content.startsWith(PREFIX_V1) -> Format.V1
        else -> Format.PLAIN
    }

    fun deriveKeyV2(password: String, salt: ByteArray, iterations: Int = Pbkdf2.BACKUP_ITERATIONS): ByteArray =
        Pbkdf2.hmacSha256(password, salt, iterations, KEY_SIZE)

    fun deriveKeyV1(password: String): ByteArray = Sha256.digest(DartUtf8.encode(password))

    /** Encrypts backup JSON into the `enc_v2` format. [salt]/[iv] are for test vectors only. */
    fun encryptV2(
        plaintext: String,
        password: String,
        salt: ByteArray = CryptoRandom.bytes(SALT_SIZE),
        iv: ByteArray = CryptoRandom.bytes(IV_SIZE),
        iterations: Int = Pbkdf2.BACKUP_ITERATIONS,
    ): String {
        val key = deriveKeyV2(password, salt, iterations)
        val sealed = AesGcm.encrypt(key, iv, DartUtf8.encode(plaintext))
        return PREFIX_V2 + DartBase64.encode(salt) + ":" + DartBase64.encode(iv) + ":" + DartBase64.encode(sealed)
    }

    /**
     * Decrypts an `enc_v2` or `enc_v1` file to its JSON text (decoded leniently like
     * `Encrypter.decrypt64`). Plain content is returned unchanged. Any failure → [BackupCryptoException].
     */
    fun decrypt(content: String, password: String, iterations: Int = Pbkdf2.BACKUP_ITERATIONS): String =
        when (formatOf(content)) {
            Format.PLAIN -> content
            Format.V2 -> {
                val parts = content.split(':')
                if (parts.size != 4) throw BackupCryptoException("Backup beschädigt")
                val salt = decodeB64(parts[1])
                val iv = decodeB64(parts[2])
                val sealed = decodeB64(parts[3])
                open(deriveKeyV2(password, salt, iterations), iv, sealed)
            }
            Format.V1 -> {
                val parts = content.split(':')
                if (parts.size != 3) throw BackupCryptoException("Backup beschädigt")
                open(deriveKeyV1(password), decodeB64(parts[1]), decodeB64(parts[2]))
            }
        }

    private fun open(key: ByteArray, iv: ByteArray, sealed: ByteArray): String = try {
        DartUtf8.decodeLenient(AesGcm.decrypt(key, iv, sealed))
    } catch (e: AeadAuthenticationException) {
        throw BackupCryptoException("Falsches Passwort oder beschädigte Datei", e)
    } catch (e: IllegalArgumentException) {
        throw BackupCryptoException("Backup beschädigt", e)
    }

    private fun decodeB64(value: String): ByteArray =
        DartBase64.decodeOrNull(value) ?: throw BackupCryptoException("Backup beschädigt")
}
