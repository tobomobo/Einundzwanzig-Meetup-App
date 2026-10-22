package space.einundzwanzig.meetup.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM with a 128-bit tag, output `ciphertext ‖ tag` — what `package:encrypt`'s
 * `AES(key, mode: AESMode.gcm)` produces (no AAD unless given).
 *
 * Runs on BouncyCastle's GCM so that every IV length works identically on every Android
 * version: the badge store uses 12-byte IVs, the backup format (`enc_v2`/`enc_v1`) 16-byte IVs.
 */
object AesGcm {
    const val TAG_SIZE = 16

    fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = cipher(true, key, iv, aad)
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    /** Throws [AeadAuthenticationException] on a wrong key or tampered data. */
    fun decrypt(key: ByteArray, iv: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        if (ciphertextAndTag.size < TAG_SIZE) throw AeadAuthenticationException("Ciphertext too short")
        val cipher = cipher(false, key, iv, aad)
        val out = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        return try {
            var len = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, out, 0)
            len += cipher.doFinal(out, len)
            out.copyOf(len)
        } catch (e: InvalidCipherTextException) {
            throw AeadAuthenticationException("GCM tag mismatch", e)
        }
    }

    /** Opens `iv ‖ ciphertext ‖ tag` (e.g. flutter_secure_storage v10 values: 12-byte IV). */
    fun decryptIvPrefixed(key: ByteArray, data: ByteArray, ivLength: Int = 12): ByteArray {
        if (data.size < ivLength + TAG_SIZE) throw AeadAuthenticationException("Ciphertext too short")
        return decrypt(key, data.copyOfRange(0, ivLength), data.copyOfRange(ivLength, data.size))
    }

    private fun cipher(encrypt: Boolean, key: ByteArray, iv: ByteArray, aad: ByteArray) =
        GCMBlockCipher.newInstance(AESEngine.newInstance()).apply {
            require(key.size == 16 || key.size == 24 || key.size == 32) { "AES key must be 16, 24 or 32 bytes" }
            require(iv.isNotEmpty()) { "IV must not be empty" }
            init(encrypt, AEADParameters(KeyParameter(key), TAG_SIZE * 8, iv, aad))
        }
}

/** AES-CBC with PKCS#7 padding (legacy flutter_secure_storage values: `iv(16) ‖ ciphertext`). */
object AesCbc {
    fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plaintext)

    /** Throws [javax.crypto.BadPaddingException] (a [java.security.GeneralSecurityException]) on bad data. */
    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(ciphertext)

    fun decryptIvPrefixed(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size >= 32) { "Ciphertext too short" }
        return decrypt(key, data.copyOfRange(0, 16), data.copyOfRange(16, data.size))
    }

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
}

/**
 * The `base64(iv):base64(ciphertext ‖ tag)` string of the badge store (`MeetupBadge` blobs,
 * pending scans): AES-256-GCM, 12-byte random IV, plaintext = UTF-8 JSON.
 */
object AesGcmBlob {
    const val IV_SIZE = 12

    fun seal(key: ByteArray, plaintext: String, iv: ByteArray = CryptoRandom.bytes(IV_SIZE)): String =
        DartBase64.encode(iv) + ":" + DartBase64.encode(AesGcm.encrypt(key, iv, DartUtf8.encode(plaintext)))

    /**
     * `_decryptBlob` minus the JSON parsing: exactly two `:`-separated parts, decoded with Dart's
     * base64 rules, text decoded leniently (`allowMalformed: true`, as `package:encrypt` does).
     * Throws [IllegalArgumentException] for a malformed blob, [AeadAuthenticationException] for a
     * wrong key.
     */
    fun open(blob: String, key: ByteArray): String {
        val parts = blob.split(':')
        require(parts.size == 2) { "Blob must have exactly two parts" }
        val iv = DartBase64.decode(parts[0])
        val sealed = DartBase64.decode(parts[1])
        return DartUtf8.decodeLenient(AesGcm.decrypt(key, iv, sealed))
    }

    fun openOrNull(blob: String, key: ByteArray): String? = runCatching { open(blob, key) }.getOrNull()
}
