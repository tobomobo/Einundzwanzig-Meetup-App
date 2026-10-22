package space.einundzwanzig.meetup.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

/** Any NIP-44 failure (bad key, tampered payload, wrong length …). */
class Nip44Exception(message: String) : Exception(message)

/**
 * NIP-44 v2, a port of `lib/services/nip44.dart` (verified against the official vectors).
 *
 * ```
 * conversation_key = HMAC-SHA256(key = "nip44-v2", msg = x(ECDH(priv, lift_x(pub))))
 * chacha_key | chacha_nonce | hmac_key = HKDF-Expand(conversation_key, info = nonce, 76)
 * payload = base64( 0x02 | nonce | ChaCha20(padded) | HMAC(hmac_key, nonce | ciphertext) )
 * ```
 */
object Nip44 {
    private const val VERSION = 2

    const val MIN_PLAINTEXT_SIZE = 1
    const val MAX_PLAINTEXT_SIZE = 65535

    private const val MIN_PAYLOAD_CHARS = 132
    private const val MAX_PAYLOAD_CHARS = 87472
    private const val MIN_PAYLOAD_BYTES = 99
    private const val MAX_PAYLOAD_BYTES = 65603

    private val SALT = "nip44-v2".toByteArray(Charsets.US_ASCII)

    class MessageKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray)

    /**
     * Shared conversation key. [pubkeyHex] is x-only; the even-y point is used (P and -P share x).
     * The ECDH output is the raw x coordinate, not libsecp256k1's hashed `ecdh()`.
     */
    fun conversationKey(privkeyHex: String, pubkeyHex: String): ByteArray {
        val priv = decodeHex32(privkeyHex, "Privater Schlüssel")
        if (!Keys.isValidPrivateKey(priv)) {
            throw Nip44Exception("Privater Schlüssel liegt außerhalb der Kurvenordnung.")
        }
        val pub = decodeHex32(pubkeyHex, "Öffentlicher Schlüssel")
        val point = try {
            Keys.liftX(pub)
        } catch (e: IllegalArgumentException) {
            throw Nip44Exception("Öffentlicher Schlüssel liegt nicht auf der Kurve.")
        }
        val shared = try {
            Secp256k1Native.lib.pubKeyTweakMul(point, priv)
        } catch (e: Exception) {
            throw Nip44Exception("ECDH fehlgeschlagen: ${e.message}")
        }
        // pubKeyTweakMul returns the uncompressed point 0x04 | x | y.
        val sharedX = shared.copyOfRange(1, 33)
        return HmacSha256.mac(SALT, sharedX)
    }

    /** Encrypts [plaintext]. [nonce] is for test vectors only; production uses a random nonce. */
    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray? = null): String {
        val n = nonce ?: CryptoRandom.bytes(32)
        val keys = messageKeys(conversationKey, n)
        val padded = pad(plaintext)
        val ciphertext = chacha20(keys, padded)
        val mac = hmacWithAad(keys.hmacKey, ciphertext, n)
        val out = ByteArray(1 + 32 + ciphertext.size + 32)
        out[0] = VERSION.toByte()
        n.copyInto(out, 1)
        ciphertext.copyInto(out, 33)
        mac.copyInto(out, 33 + ciphertext.size)
        return DartBase64.encode(out)
    }

    /** Decrypts a payload; throws [Nip44Exception] when it was tampered with or the key is wrong. */
    fun decrypt(payload: String, conversationKey: ByteArray): String {
        val data = decodePayload(payload)
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val keys = messageKeys(conversationKey, nonce)
        if (!constantTimeEquals(hmacWithAad(keys.hmacKey, ciphertext, nonce), mac)) {
            throw Nip44Exception("MAC stimmt nicht.")
        }
        return unpad(chacha20(keys, ciphertext))
    }

    /** HKDF-Expand to 76 bytes, split into the three message keys. */
    fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        if (conversationKey.size != 32) throw Nip44Exception("Sitzungsschlüssel muss 32 Bytes haben.")
        if (nonce.size != 32) throw Nip44Exception("Nonce muss 32 Bytes haben.")
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        // The conversation key already is the PRK: skip the extract step.
        hkdf.init(HKDFParameters.skipExtractParameters(conversationKey, nonce))
        val out = ByteArray(76)
        hkdf.generateBytes(out, 0, out.size)
        return MessageKeys(out.copyOfRange(0, 32), out.copyOfRange(32, 44), out.copyOfRange(44, 76))
    }

    /** Padded plaintext length (integer arithmetic, no log2). */
    fun calcPaddedLen(unpaddedLen: Int): Int {
        if (unpaddedLen <= 32) return 32
        val bitLength = 32 - Integer.numberOfLeadingZeros(unpaddedLen - 1)
        val nextPower = 1 shl bitLength
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1)
    }

    // ---------- intern ----------

    private fun chacha20(keys: MessageKeys, input: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(keys.chachaKey), keys.chachaNonce))
        val out = ByteArray(input.size)
        engine.processBytes(input, 0, input.size, out, 0)
        return out
    }

    private fun pad(plaintext: String): ByteArray {
        val unpadded = DartUtf8.encode(plaintext)
        val len = unpadded.size
        if (len < MIN_PLAINTEXT_SIZE || len > MAX_PLAINTEXT_SIZE) {
            throw Nip44Exception("Ungültige Nachrichtenlänge: $len Bytes.")
        }
        val padded = ByteArray(2 + calcPaddedLen(len))
        padded[0] = (len shr 8).toByte()
        padded[1] = len.toByte()
        unpadded.copyInto(padded, 2)
        return padded
    }

    private fun unpad(padded: ByteArray): String {
        if (padded.size < 2) throw Nip44Exception("Ungültiges Padding.")
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        if (len < MIN_PLAINTEXT_SIZE || len > MAX_PLAINTEXT_SIZE || padded.size != 2 + calcPaddedLen(len)) {
            throw Nip44Exception("Ungültiges Padding.")
        }
        return try {
            DartUtf8.decodeStrict(padded.copyOfRange(2, 2 + len))
        } catch (e: Exception) {
            throw Nip44Exception("Klartext ist kein gültiges UTF-8.")
        }
    }

    private fun decodePayload(payload: String): ByteArray {
        if (payload.startsWith("#")) throw Nip44Exception("Unbekannte NIP-44-Version.")
        if (payload.length < MIN_PAYLOAD_CHARS || payload.length > MAX_PAYLOAD_CHARS) {
            throw Nip44Exception("Ungültige Nutzlastlänge: ${payload.length}.")
        }
        val data = DartBase64.decodeOrNull(payload)
            ?: throw Nip44Exception("Nutzlast ist kein gültiges Base64.")
        if (data.size < MIN_PAYLOAD_BYTES || data.size > MAX_PAYLOAD_BYTES) {
            throw Nip44Exception("Ungültige Nutzlastlänge: ${data.size} Bytes.")
        }
        if (data[0].toInt() != VERSION) throw Nip44Exception("Unbekannte NIP-44-Version: ${data[0]}.")
        return data
    }

    private fun decodeHex32(value: String, label: String): ByteArray {
        val bytes = Hex.decodeOrNull(value) ?: throw Nip44Exception("$label ist kein gültiges Hex.")
        if (bytes.size != 32) throw Nip44Exception("$label muss 32 Bytes haben, hat ${bytes.size}.")
        return bytes
    }

    private fun hmacWithAad(key: ByteArray, message: ByteArray, aad: ByteArray): ByteArray {
        if (aad.size != 32) throw Nip44Exception("AAD muss 32 Bytes haben.")
        return HmacSha256.mac(key, aad, message)
    }
}
