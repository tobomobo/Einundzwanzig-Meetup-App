package space.einundzwanzig.meetup.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** Authentication failure of an AEAD (wrong key, tampered ciphertext or AAD). */
class AeadAuthenticationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * XChaCha20-Poly1305 (draft-irtf-cfrg-xchacha, as in `package:cryptography`'s
 * `Xchacha20.poly1305Aead()`): HChaCha20 derives a sub-key from the key and the first 16 nonce
 * bytes, then IETF ChaCha20-Poly1305 runs with nonce `00000000 | nonce[16..24]`.
 * Output layout of [seal]: ciphertext ‖ 16-byte tag.
 */
object XChaCha20Poly1305 {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 24
    const val TAG_SIZE = 16

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = cipher(true, key, nonce, aad)
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        if (sealed.size < TAG_SIZE) throw AeadAuthenticationException("Ciphertext too short")
        val cipher = cipher(false, key, nonce, aad)
        val out = ByteArray(cipher.getOutputSize(sealed.size))
        return try {
            var len = cipher.processBytes(sealed, 0, sealed.size, out, 0)
            len += cipher.doFinal(out, len)
            out.copyOf(len)
        } catch (e: InvalidCipherTextException) {
            throw AeadAuthenticationException("Poly1305 tag mismatch", e)
        }
    }

    /** HChaCha20(key, nonce16) → 32-byte sub-key. */
    fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "Key must be 32 bytes" }
        require(nonce16.size == 16) { "HChaCha20 nonce must be 16 bytes" }
        val x = IntArray(16)
        x[0] = 0x61707865
        x[1] = 0x3320646e
        x[2] = 0x79622d32
        x[3] = 0x6b206574
        for (i in 0 until 8) x[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) x[12 + i] = le32(nonce16, i * 4)
        repeat(10) {
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 1, 5, 9, 13)
            quarterRound(x, 2, 6, 10, 14)
            quarterRound(x, 3, 7, 11, 15)
            quarterRound(x, 0, 5, 10, 15)
            quarterRound(x, 1, 6, 11, 12)
            quarterRound(x, 2, 7, 8, 13)
            quarterRound(x, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        for (i in 0 until 4) putLe32(out, i * 4, x[i])
        for (i in 0 until 4) putLe32(out, 16 + i * 4, x[12 + i])
        return out
    }

    private fun cipher(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray): ChaCha20Poly1305 {
        require(key.size == KEY_SIZE) { "Key must be 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "Nonce must be 24 bytes" }
        val subKey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val nonce12 = ByteArray(12)
        nonce.copyInto(nonce12, destinationOffset = 4, startIndex = 16, endIndex = 24)
        return ChaCha20Poly1305().apply {
            init(encrypt, AEADParameters(KeyParameter(subKey), TAG_SIZE * 8, nonce12, aad))
        }
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }
}
