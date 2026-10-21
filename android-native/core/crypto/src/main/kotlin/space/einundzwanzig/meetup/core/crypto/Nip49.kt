package space.einundzwanzig.meetup.core.crypto

import org.bouncycastle.crypto.generators.SCrypt
import java.text.Normalizer

class Nip49Exception(message: String) : Exception(message)

/** NIP-49 key-security byte; it is also the AAD, so it cannot be forged. */
enum class KeySecurity(val byte: Int) {
    /** Has been handled in plaintext before (exported, copied). */
    INSECURE(0x00),

    /** Never left the device in plaintext. */
    SECURE(0x01),

    /** Unknown. */
    UNKNOWN(0x02),
    ;

    companion object {
        fun fromByte(b: Int): KeySecurity = when (b) {
            0x00 -> INSECURE
            0x01 -> SECURE
            else -> UNKNOWN
        }
    }
}

/**
 * NIP-49 password-encrypted private key (`ncryptsec1…`), a port of `lib/services/nip49.dart`.
 *
 * Payload (91 bytes, bech32 HRP `ncryptsec`, length limit 5000):
 * `0x02 | log_n | salt(16) | nonce(24) | key_security | ciphertext(32) ‖ tag(16)`,
 * key = scrypt(NFKC(password), salt, N = 2^log_n, r = 8, p = 1, 32), cipher XChaCha20-Poly1305
 * with AAD = key_security.
 *
 * Both directions are CPU- and memory-heavy (log_n 16 = 64 MiB): call them off the main thread
 * (e.g. `Dispatchers.Default`) and be prepared for [OutOfMemoryError] on small heaps.
 */
object Nip49 {
    private const val VERSION = 0x02
    private const val HRP = "ncryptsec"
    private const val MAX_BECH32_LENGTH = 5000
    private const val PAYLOAD_SIZE = 91

    /** Value used by the app for exported and password-wrapped keys (64 MiB scrypt). */
    const val DEFAULT_LOG_N = 16

    /**
     * Encrypts a hex private key. [salt] and [nonce] exist only for deterministic test vectors;
     * production calls must leave them null (random).
     */
    fun encrypt(
        privkeyHex: String,
        password: String,
        logN: Int = DEFAULT_LOG_N,
        keySecurity: KeySecurity = KeySecurity.UNKNOWN,
        salt: ByteArray? = null,
        nonce: ByteArray? = null,
    ): String {
        val privkey = decodePrivkey(privkeyHex)
        if (password.isEmpty()) throw Nip49Exception("Ohne Passwort gibt es keine Verschlüsselung.")
        if (logN < 1 || logN > 22) throw Nip49Exception("log_n ausserhalb des sinnvollen Bereichs: $logN")
        val s = salt ?: CryptoRandom.bytes(16)
        val n = nonce ?: CryptoRandom.bytes(24)
        require(s.size == 16 && n.size == 24) { "salt must be 16 and nonce 24 bytes" }

        val key = scrypt(password, s, logN)
        val sealed = XChaCha20Poly1305.seal(key, n, privkey, byteArrayOf(keySecurity.byte.toByte()))

        val payload = ByteArray(PAYLOAD_SIZE)
        payload[0] = VERSION.toByte()
        payload[1] = logN.toByte()
        s.copyInto(payload, 2)
        n.copyInto(payload, 18)
        payload[42] = keySecurity.byte.toByte()
        sealed.copyInto(payload, 43)
        return encodePayload(payload)
    }

    /** Decrypts `ncryptsec1…` to the lowercase hex private key. */
    fun decrypt(ncryptsec: String, password: String): String {
        val payload = decodeBech32(ncryptsec.trim())
        if (payload.size != PAYLOAD_SIZE) {
            throw Nip49Exception("Unerwartete Länge: ${payload.size} statt 91 Bytes.")
        }
        if (payload[0].toInt() != VERSION) {
            throw Nip49Exception("Unbekannte NIP-49-Version: ${payload[0].toInt() and 0xff}.")
        }
        val logN = payload[1].toInt() and 0xff
        // DoS guard: a forged log_n of 255 must not start scrypt.
        if (logN < 1 || logN > 22) throw Nip49Exception("log_n ausserhalb des sinnvollen Bereichs: $logN")
        val salt = payload.copyOfRange(2, 18)
        val nonce = payload.copyOfRange(18, 42)
        val keySecurity = payload[42]
        val sealed = payload.copyOfRange(43, 91)

        val key = scrypt(password, salt, logN)
        val plain = try {
            XChaCha20Poly1305.open(key, nonce, sealed, byteArrayOf(keySecurity))
        } catch (e: AeadAuthenticationException) {
            throw Nip49Exception("Falsches Passwort — oder der Schlüssel ist beschädigt.")
        }
        if (plain.size != 32) throw Nip49Exception("Entschlüsselter Schlüssel hat nicht 32 Bytes.")
        return plain.toHex()
    }

    /** Reads the key-security byte without the password (it is stored in plaintext). */
    fun keySecurityOf(ncryptsec: String): KeySecurity {
        val payload = decodeBech32(ncryptsec.trim())
        if (payload.size != PAYLOAD_SIZE) throw Nip49Exception("Unerwartete Länge.")
        return KeySecurity.fromByte(payload[42].toInt() and 0xff)
    }

    /** Test seam: raw 91-byte payload of an `ncryptsec`. */
    fun debugDecodePayload(ncryptsec: String): ByteArray = decodeBech32(ncryptsec.trim())

    /** Test seam: bech32-encodes a (possibly manipulated) payload. */
    fun debugEncodePayload(payload: ByteArray): String = encodePayload(payload)

    /** scrypt(NFKC(password) as UTF-8, salt, 2^logN, 8, 1, 32). */
    fun scrypt(password: String, salt: ByteArray, logN: Int): ByteArray {
        val normalized = Normalizer.normalize(password, Normalizer.Form.NFKC)
        return SCrypt.generate(DartUtf8.encode(normalized), salt, 1 shl logN, 8, 1, 32)
    }

    private fun encodePayload(payload: ByteArray): String = try {
        Bech32.encodeBytes(HRP, payload, MAX_BECH32_LENGTH)
    } catch (e: Bech32Exception) {
        throw Nip49Exception("Ungültiger Wert in der Kodierung.")
    }

    private fun decodeBech32(value: String): ByteArray {
        if (!value.lowercase().startsWith("${HRP}1")) {
            throw Nip49Exception("Das ist kein ncryptsec — es muss mit \"ncryptsec1\" beginnen.")
        }
        val decoded = try {
            Bech32.decode(value, MAX_BECH32_LENGTH)
        } catch (e: Bech32Exception) {
            throw Nip49Exception("Unlesbar: ${e.message}")
        }
        if (decoded.hrp != HRP) throw Nip49Exception("Falsches Präfix: ${decoded.hrp}.")
        val bytes = try {
            Bech32.convertBits(decoded.words, 5, 8, pad = false)
        } catch (e: Bech32Exception) {
            throw Nip49Exception("Überzählige Bits in der Kodierung.")
        }
        return ByteArray(bytes.size) { bytes[it].toByte() }
    }

    private fun decodePrivkey(privkeyHex: String): ByteArray {
        val bytes = Hex.decodeOrNull(privkeyHex.trim())
            ?: throw Nip49Exception("Der private Schlüssel ist kein gültiges Hex.")
        if (bytes.size != 32) {
            throw Nip49Exception("Der private Schlüssel muss 32 Bytes haben, hat ${bytes.size}.")
        }
        return bytes
    }
}
