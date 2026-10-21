package space.einundzwanzig.meetup.core.crypto

/** Any bech32 encoding/decoding failure (Dart `bech32` package exceptions, `convertBits` errors). */
class Bech32Exception(message: String) : IllegalArgumentException(message)

/**
 * Classic bech32 (BIP-173, checksum constant 1 — **not** bech32m), a port of
 * `package:bech32` 0.2.2 including its validation order and the default length limit of 90.
 *
 * NIP-19 keys fit into 90 characters; `ncryptsec` (5000) and bolt11 (4096) pass a larger limit.
 */
object Bech32 {
    const val DEFAULT_MAX_LENGTH = 90
    private const val CHECKSUM_LENGTH = 6
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /** A decoded string: lowercase human readable part and the 5-bit data words (checksum removed). */
    class Decoded(val hrp: String, val words: IntArray)

    fun encode(hrp: String, words: IntArray, maxLength: Int = DEFAULT_MAX_LENGTH): String {
        if (hrp.length + words.size + 1 + CHECKSUM_LENGTH > maxLength) {
            throw Bech32Exception("Too long: ${hrp.length + words.size + 1 + CHECKSUM_LENGTH}")
        }
        if (hrp.isEmpty()) throw Bech32Exception("Human readable part too short")
        if (hrp.any { it.code < 33 || it.code > 126 }) throw Bech32Exception("Out of range HRP characters")
        if (isMixedCase(hrp)) throw Bech32Exception("Mixed case")
        val lower = hrp.lowercase()
        val checksummed = words + createChecksum(lower, words)
        if (checksummed.any { it < 0 || it > 31 }) throw Bech32Exception("Out of bound characters")
        return buildString(lower.length + 1 + checksummed.size) {
            append(lower).append('1')
            for (w in checksummed) append(CHARSET[w])
        }
    }

    fun decode(input: String, maxLength: Int = DEFAULT_MAX_LENGTH): Decoded {
        if (input.length > maxLength) throw Bech32Exception("Too long: ${input.length}")
        if (isMixedCase(input)) throw Bech32Exception("Mixed case")
        val sep = input.lastIndexOf('1')
        if (sep == -1) throw Bech32Exception("Invalid separator")
        if (input.length - sep - 1 - CHECKSUM_LENGTH < 0) throw Bech32Exception("Checksum too short")
        if (sep == 0) throw Bech32Exception("Human readable part too short")
        val lower = input.lowercase()
        val hrp = lower.substring(0, sep)
        val data = lower.substring(sep + 1, lower.length - CHECKSUM_LENGTH)
        val checksum = lower.substring(lower.length - CHECKSUM_LENGTH)
        if (hrp.any { it.code < 33 || it.code > 126 }) throw Bech32Exception("Out of range HRP characters")
        val dataWords = IntArray(data.length) { CHARSET.indexOf(data[it]) }
        if (dataWords.any { it == -1 }) throw Bech32Exception("Out of bound characters")
        val checksumWords = IntArray(checksum.length) { CHARSET.indexOf(checksum[it]) }
        if (checksumWords.any { it == -1 }) throw Bech32Exception("Out of bound characters")
        if (polymod(hrpExpand(hrp) + dataWords + checksumWords) != 1) {
            throw Bech32Exception("Invalid checksum")
        }
        return Decoded(hrp, dataWords)
    }

    /**
     * Regroups bits (`convertBits` of `nostr`'s Nip19 and of `nip49.dart`).
     * With [pad] = false, leftover bits ≥ [fromBits] or non-zero padding throw.
     */
    fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        val out = ArrayList<Int>(data.size * fromBits / toBits + 1)
        for (value in data) {
            if (value < 0 || (value shr fromBits) != 0) throw Bech32Exception("Invalid value: $value")
            acc = ((acc shl fromBits) or value) and 0xffffff
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add((acc shr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (toBits - bits)) and maxv)
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            throw Bech32Exception("Invalid data")
        }
        return out.toIntArray()
    }

    /**
     * The unchecked 5→8 regrouping of `ZapReceiptValidator._convertBits` (bolt11): no
     * validation, trailing bits are dropped.
     */
    fun convertBitsUnchecked(data: IntArray, fromBits: Int, toBits: Int): IntArray {
        var acc = 0L
        var bits = 0
        val maxv = (1 shl toBits) - 1
        val out = ArrayList<Int>()
        for (v in data) {
            acc = ((acc shl fromBits) or v.toLong()) and 0xffffffffL
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add(((acc shr bits) and maxv.toLong()).toInt())
            }
        }
        return out.toIntArray()
    }

    /** bytes → bech32 with 8→5 regrouping (padded). */
    fun encodeBytes(hrp: String, bytes: ByteArray, maxLength: Int = DEFAULT_MAX_LENGTH): String =
        encode(hrp, convertBits(bytes.toUnsignedInts(), 8, 5, pad = true), maxLength)

    /** bech32 → hrp + bytes with strict 5→8 regrouping (unpadded). */
    fun decodeBytes(input: String, maxLength: Int = DEFAULT_MAX_LENGTH): Pair<String, ByteArray> {
        val decoded = decode(input, maxLength)
        val bytes = convertBits(decoded.words, 5, 8, pad = false)
        return decoded.hrp to ByteArray(bytes.size) { bytes[it].toByte() }
    }

    private fun isMixedCase(s: String) = s.lowercase() != s && s.uppercase() != s

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if ((top ushr i) and 1 == 1) chk = chk xor GENERATOR[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            out[i] = hrp[i].code ushr 5
            out[hrp.length + 1 + i] = hrp[i].code and 31
        }
        return out
    }

    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val mod = polymod(hrpExpand(hrp) + data + IntArray(CHECKSUM_LENGTH)) xor 1
        return IntArray(CHECKSUM_LENGTH) { (mod ushr (5 * (5 - it))) and 31 }
    }
}

internal fun ByteArray.toUnsignedInts(): IntArray = IntArray(size) { this[it].toInt() and 0xff }
