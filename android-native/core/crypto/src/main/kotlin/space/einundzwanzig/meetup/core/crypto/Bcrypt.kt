package space.einundzwanzig.meetup.core.crypto

import org.bouncycastle.crypto.generators.BCrypt as BcBCrypt

/**
 * OpenBSD bcrypt with the exact string format of `package:dbcrypt` 2.0.0 (a jBCrypt port):
 * `$2a$`, `$2b$`, `$2y$` and the minor-less `$2$`; the password is UTF-8 plus a trailing NUL for
 * the lettered variants and only its first 72 bytes count; [gensalt] produces `$2b$`.
 *
 * The current Dart app no longer calls dbcrypt (it was the removed admin-password gate); this is
 * provided for parity and for verifying legacy hashes.
 */
object Bcrypt {
    const val DEFAULT_LOG_ROUNDS = 10
    private const val SALT_SIZE = 16
    private const val HASH_ENCODED_BYTES = 23 // 6 ciphertext words * 4 - 1
    private const val MAX_KEY_BYTES = 72
    private const val ALPHABET = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** `DBCrypt().gensaltWithRounds(logRounds)`: `$2b$NN$` + 22 salt characters. */
    fun gensalt(logRounds: Int = DEFAULT_LOG_ROUNDS, salt: ByteArray = CryptoRandom.bytes(SALT_SIZE)): String {
        require(salt.size == SALT_SIZE) { "Salt must be 16 bytes" }
        return "\$2b\$" + (if (logRounds < 10) "0" else "") + logRounds + "\$" + encodeBase64(salt, SALT_SIZE)
    }

    /** `DBCrypt().hashpw(password, salt)`; [salt] may be a full hash (its first 29 chars are used). */
    fun hashpw(password: String, salt: String): String {
        require(salt.length >= 4 && salt[0] == '$' && salt[1] == '2') { "Invalid salt version" }
        val minor: Char?
        val off: Int
        if (salt[2] == '$') {
            minor = null
            off = 3
        } else {
            minor = salt[2]
            require((minor == 'a' || minor == 'b' || minor == 'y') && salt[3] == '$') { "Invalid salt revision" }
            off = 4
        }
        require(salt.length >= off + 25) { "Invalid salt" }
        require(salt[off + 2] <= '$') { "Missing salt rounds" }
        val rounds = salt.substring(off, off + 2).toIntOrNull() ?: throw IllegalArgumentException("Invalid salt rounds")
        require(rounds in 4..31) { "Bad number of rounds" }
        val saltBytes = decodeBase64(salt.substring(off + 3, off + 25), SALT_SIZE)
        require(saltBytes.size == SALT_SIZE) { "Bad salt length" }

        val key = DartUtf8.encode(if (minor != null) password + "\u0000" else password)
        // Only the first 72 key bytes enter the key schedule (jBCrypt cycles through them).
        val hashed = BcBCrypt.generate(key.copyOf(minOf(key.size, MAX_KEY_BYTES)), saltBytes, rounds, false)

        return buildString {
            append("\$2")
            if (minor != null) append(minor)
            append('$')
            if (rounds < 10) append('0')
            append(rounds)
            append('$')
            append(encodeBase64(saltBytes, SALT_SIZE))
            append(encodeBase64(hashed, HASH_ENCODED_BYTES))
        }
    }

    /** `DBCrypt().checkpw(plaintext, hashed)`; a malformed hash yields false instead of throwing. */
    fun checkpw(plaintext: String, hashed: String): Boolean {
        val computed = runCatching { hashpw(plaintext, hashed) }.getOrNull() ?: return false
        return constantTimeEquals(DartUtf8.encode(computed), DartUtf8.encode(hashed))
    }

    private fun encodeBase64(d: ByteArray, len: Int): String {
        val sb = StringBuilder()
        var off = 0
        while (off < len) {
            var c1 = d[off++].toInt() and 0xff
            sb.append(ALPHABET[(c1 shr 2) and 0x3f])
            c1 = (c1 and 0x03) shl 4
            if (off >= len) {
                sb.append(ALPHABET[c1 and 0x3f])
                break
            }
            var c2 = d[off++].toInt() and 0xff
            c1 = c1 or ((c2 shr 4) and 0x0f)
            sb.append(ALPHABET[c1 and 0x3f])
            c1 = (c2 and 0x0f) shl 2
            if (off >= len) {
                sb.append(ALPHABET[c1 and 0x3f])
                break
            }
            c2 = d[off++].toInt() and 0xff
            c1 = c1 or ((c2 shr 6) and 0x03)
            sb.append(ALPHABET[c1 and 0x3f])
            sb.append(ALPHABET[c2 and 0x3f])
        }
        return sb.toString()
    }

    private fun decodeBase64(s: String, maxLen: Int): ByteArray {
        val out = ArrayList<Byte>(maxLen)
        var off = 0
        val slen = s.length
        fun v(i: Int): Int = if (i < slen) ALPHABET.indexOf(s[i]) else -1
        while (off < slen - 1 && out.size < maxLen) {
            val c1 = v(off++)
            val c2 = v(off++)
            if (c1 == -1 || c2 == -1) break
            out.add(((c1 shl 2) or ((c2 and 0x30) shr 4)).toByte())
            if (out.size >= maxLen || off >= slen) break
            val c3 = v(off++)
            if (c3 == -1) break
            out.add((((c2 and 0x0f) shl 4) or ((c3 and 0x3c) shr 2)).toByte())
            if (out.size >= maxLen || off >= slen) break
            val c4 = v(off++)
            out.add((((c3 and 0x03) shl 6) or c4).toByte())
        }
        return out.toByteArray()
    }
}
