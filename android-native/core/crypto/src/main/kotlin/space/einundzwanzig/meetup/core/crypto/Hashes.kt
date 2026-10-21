package space.einundzwanzig.meetup.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** SHA-256 (`package:crypto` `sha256` / pointycastle `SHA256Digest`). */
object Sha256 {
    fun digest(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** `sha256.convert(utf8.encode(text)).toString()` — lowercase hex. */
    fun hexOfUtf8(text: String): String = digest(DartUtf8.encode(text)).toHex()

    fun hex(bytes: ByteArray): String = digest(bytes).toHex()
}

/** HMAC-SHA256 (`Hmac(sha256, key)`). Accepts any key length, including an empty key. */
object HmacSha256 {
    private const val BLOCK_SIZE = 64

    fun mac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // javax refuses an empty key; HMAC zero-pads short keys to the block size, so an
        // all-zero block is the same key.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(BLOCK_SIZE) else key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** `Hmac(sha256, utf8.encode(key)).convert(utf8.encode(message)).toString()`. */
    fun hexOfUtf8(key: String, message: String): String =
        mac(DartUtf8.encode(key), DartUtf8.encode(message)).toHex()
}

/** CSPRNG helpers (Dart `Random.secure()`). */
object CryptoRandom {
    private val random: SecureRandom by lazy { SecureRandom() }

    fun bytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    /** Dart `generate64RandomHexChars()` and friends: [byteCount] random bytes as lowercase hex. */
    fun hex(byteCount: Int): String = bytes(byteCount).toHex()
}

/** Constant-time comparison (length is not secret). */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
