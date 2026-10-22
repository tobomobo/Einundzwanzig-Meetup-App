package space.einundzwanzig.meetup.data.backup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.crypto.AeadAuthenticationException
import space.einundzwanzig.meetup.core.crypto.AesGcm
import space.einundzwanzig.meetup.core.crypto.BackupEnvelope
import space.einundzwanzig.meetup.core.crypto.CryptoRandom
import space.einundzwanzig.meetup.core.crypto.DartBase64
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.crypto.Pbkdf2

/**
 * Encryption of `.21bkp` files (Dart `BackupService`, spec 08 §3.1, §7.1, §7.2).
 *
 * Thin, suspending facade over `:core:crypto` [BackupEnvelope]/[Pbkdf2]/[AesGcm]: PBKDF2-HMAC-SHA256
 * (600 000 rounds, 32-byte salt, UTF-8 password exactly as typed) → AES-256-GCM with a 16-byte IV,
 * no AAD, output `enc_v2:<b64 salt>:<b64 iv>:<b64 ct‖tag>`. Legacy `enc_v1` (key = SHA-256 of the
 * password) is decrypt-only. Key derivation takes ~1–2 s: every suspend function here runs it on
 * [Dispatchers.Default] (or the dispatcher passed in).
 */
object BackupCrypto {
    /** `_pbkdf2Iterations`. */
    const val PBKDF2_ITERATIONS: Int = Pbkdf2.BACKUP_ITERATIONS

    /** `_saltLengthBytes`. */
    const val SALT_BYTES: Int = BackupEnvelope.SALT_SIZE

    /** `_keyLengthBytes` (AES-256). */
    const val KEY_BYTES: Int = BackupEnvelope.KEY_SIZE

    /** IV length of `enc_v2` (16, not the usual 12). */
    const val IV_BYTES: Int = BackupEnvelope.IV_SIZE

    /**
     * RFC 8018 PBKDF2-HMAC-SHA256 (Dart `pbkdf2DartBytes`).
     *
     * @throws IllegalArgumentException for `iterations < 1` or `keyBytes < 1`.
     */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray =
        Pbkdf2.hmacSha256(password, salt, iterations, keyBytes)

    /**
     * Encrypts the backup [json] with [password] into the `enc_v2` file content.
     * [salt] and [iv] exist for test vectors only; production code must not pass them.
     */
    suspend fun encryptV2(
        json: String,
        password: String,
        salt: ByteArray = CryptoRandom.bytes(SALT_BYTES),
        iv: ByteArray = CryptoRandom.bytes(IV_BYTES),
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): String = withContext(dispatcher) {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        require(iv.size == IV_BYTES) { "iv must be $IV_BYTES bytes" }
        BackupEnvelope.encryptV2(json, password, salt, iv)
    }

    /**
     * Opens [container] with [password]. [BackupContainer.Plain] is returned unchanged (no password
     * needed). Wrong password, tampered or malformed content → [OpenResult.WrongPasswordOrCorrupt]
     * (Flutter shows `backupWrongPassword` for all of them). The plaintext is decoded leniently
     * (`utf8.decode(allowMalformed: true)` like `Encrypter.decrypt64`).
     */
    suspend fun decrypt(
        container: BackupContainer,
        password: String,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        iterations: Int = PBKDF2_ITERATIONS,
    ): OpenResult = when (container) {
        is BackupContainer.Plain -> OpenResult.Ok(container.json)
        is BackupContainer.Corrupt -> OpenResult.WrongPasswordOrCorrupt
        is BackupContainer.V2 -> withContext(dispatcher) {
            open(BackupEnvelope.deriveKeyV2(password, container.salt, iterations), container.iv, container.sealed)
        }
        is BackupContainer.V1 -> withContext(dispatcher) {
            open(BackupEnvelope.deriveKeyV1(password), container.iv, container.sealed)
        }
    }

    /**
     * The whole Dart open step for a file's text: parses the container, asks for the [password]
     * only if the file is encrypted (`null` = cancelled), and calls [onKeyDerivation] with `true`
     * right before and `false` right after the PBKDF2 derivation of `enc_v2` (the blocking overlay;
     * guaranteed to be called with `false` exactly once after `true`, also on failure).
     */
    suspend fun open(
        content: String,
        password: suspend () -> String?,
        onKeyDerivation: suspend (running: Boolean) -> Unit = {},
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): OpenResult {
        val container = BackupContainer.parse(content)
        if (!container.needsPassword) return decrypt(container, "", dispatcher)
        val pw = password() ?: return OpenResult.Cancelled
        if (container !is BackupContainer.V2) return decrypt(container, pw, dispatcher)
        onKeyDerivation(true)
        val key = try {
            withContext(dispatcher) { runCatching { BackupEnvelope.deriveKeyV2(pw, container.salt) } }
        } finally {
            // Closed exactly once, also when cancelled during PBKDF2 (spec 08 §9 #13, Flutter bug #18).
            withContext(NonCancellable) { onKeyDerivation(false) }
        }
        return key.fold(
            onSuccess = { withContext(dispatcher) { open(it, container.iv, container.sealed) } },
            onFailure = { OpenResult.WrongPasswordOrCorrupt },
        )
    }

    private fun open(key: ByteArray, iv: ByteArray, sealed: ByteArray): OpenResult = try {
        OpenResult.Ok(DartUtf8.decodeLenient(AesGcm.decrypt(key, iv, sealed)))
    } catch (_: AeadAuthenticationException) {
        OpenResult.WrongPasswordOrCorrupt
    } catch (_: IllegalArgumentException) {
        OpenResult.WrongPasswordOrCorrupt
    } catch (_: IllegalStateException) {
        OpenResult.WrongPasswordOrCorrupt
    }
}

/**
 * The outer layer of a `.21bkp` file (spec 08 §3.1). Detection is by exact prefix; anything else is
 * treated as plain JSON. Malformed encrypted containers are [Corrupt] rather than an exception,
 * because Flutter asks for the password first and only then reports "wrong password or corrupt".
 */
sealed interface BackupContainer {
    /** `true` for every encrypted form (the UI must prompt before [BackupCrypto.decrypt]). */
    val needsPassword: Boolean

    /** `enc_v2:<salt>:<iv>:<ct‖tag>`. */
    class V2(val salt: ByteArray, val iv: ByteArray, val sealed: ByteArray) : BackupContainer {
        override val needsPassword: Boolean get() = true
    }

    /** Legacy `enc_v1:<iv>:<ct‖tag>` (any IV length). */
    class V1(val iv: ByteArray, val sealed: ByteArray) : BackupContainer {
        override val needsPassword: Boolean get() = true
    }

    /** Legacy unencrypted backup: [json] is the file text itself. */
    data class Plain(val json: String) : BackupContainer {
        override val needsPassword: Boolean get() = false
    }

    /** An `enc_v2:`/`enc_v1:` file with the wrong number of parts or invalid base64. */
    data class Corrupt(val format: BackupEnvelope.Format) : BackupContainer {
        override val needsPassword: Boolean get() = true
    }

    companion object {
        /** Prefix dispatch + `split(':')` + Dart `base64Decode` (standard and URL-safe alphabet). */
        fun parse(content: String): BackupContainer = when (val format = BackupEnvelope.formatOf(content)) {
            BackupEnvelope.Format.PLAIN -> Plain(content)
            BackupEnvelope.Format.V2 -> {
                val parts = content.split(':')
                if (parts.size != 4) {
                    Corrupt(format)
                } else {
                    val salt = DartBase64.decodeOrNull(parts[1])
                    val iv = DartBase64.decodeOrNull(parts[2])
                    val sealed = DartBase64.decodeOrNull(parts[3])
                    if (salt == null || iv == null || sealed == null) Corrupt(format) else V2(salt, iv, sealed)
                }
            }
            BackupEnvelope.Format.V1 -> {
                val parts = content.split(':')
                if (parts.size != 3) {
                    Corrupt(format)
                } else {
                    val iv = DartBase64.decodeOrNull(parts[1])
                    val sealed = DartBase64.decodeOrNull(parts[2])
                    if (iv == null || sealed == null) Corrupt(format) else V1(iv, sealed)
                }
            }
        }
    }
}

/** Result of opening a backup file. */
sealed interface OpenResult {
    /** The decrypted (or plain) backup JSON text. */
    data class Ok(val json: String) : OpenResult {
        override fun toString(): String = "Ok(<${json.length} chars>)"
    }

    /** The user cancelled the password dialog. */
    data object Cancelled : OpenResult

    /** Wrong password, tampered or malformed file (`backupWrongPassword`). */
    data object WrongPasswordOrCorrupt : OpenResult
}
