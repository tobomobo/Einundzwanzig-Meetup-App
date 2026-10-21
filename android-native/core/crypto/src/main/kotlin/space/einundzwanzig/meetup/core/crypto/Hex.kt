package space.einundzwanzig.meetup.core.crypto

/**
 * Hex codec matching `package:convert`'s `hex` as used throughout the Dart app:
 * encoding is lowercase, decoding accepts both cases and rejects odd lengths and
 * non-hex characters with [IllegalArgumentException].
 */
object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0f]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Invalid hex length ${hex.length}" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = digit(hex[i * 2])
            val lo = digit(hex[i * 2 + 1])
            require(hi >= 0 && lo >= 0) { "Invalid hex character at ${i * 2}" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Decodes or returns null instead of throwing. */
    fun decodeOrNull(hex: String): ByteArray? = runCatching { decode(hex) }.getOrNull()

    /** True for an even-length string of hex digits (either case). Empty input is not hex. */
    fun isHex(value: String): Boolean =
        value.isNotEmpty() && value.length % 2 == 0 && value.all { digit(it) >= 0 }

    /** `^[0-9a-fA-F]{length}$` — the pubkey/signature format checks of the Dart code. */
    fun isHexOfLength(value: String, length: Int): Boolean =
        value.length == length && value.all { digit(it) >= 0 }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}

/** Lowercase hex of these bytes. */
fun ByteArray.toHex(): String = Hex.encode(this)

/** Bytes of this hex string; throws [IllegalArgumentException] if it is not hex. */
fun String.hexToBytes(): ByteArray = Hex.decode(this)
