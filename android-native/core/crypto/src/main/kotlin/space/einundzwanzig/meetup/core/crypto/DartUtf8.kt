package space.einundzwanzig.meetup.core.crypto

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * UTF-8 exactly as Dart's `utf8` codec does it.
 *
 * The only difference to `String.toByteArray(UTF_8)` is the handling of lone UTF-16
 * surrogates: Dart writes U+FFFD (`EF BF BD`), the JDK writes `?`. Every hash input,
 * password and event content in this module goes through [encode] so that such strings
 * hash and derive identically on both platforms.
 */
object DartUtf8 {
    fun encode(value: String): ByteArray {
        // Fast path: no surrogates → the JDK encoder is identical.
        if (value.none { it.isSurrogate() }) return value.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) {
                sb.append(c).append(value[i + 1])
                i += 2
                continue
            }
            sb.append(if (c.isSurrogate()) '\uFFFD' else c)
            i++
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Dart `utf8.decode(bytes)`: malformed input throws [CharacterCodingException]. Like Dart, one
     * leading byte-order mark (`EF BB BF`) is dropped.
     */
    fun decodeStrict(bytes: ByteArray): String {
        val start = bomLength(bytes)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
            .toString()
    }

    private fun bomLength(bytes: ByteArray): Int =
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0

    /**
     * Dart `utf8.decode(bytes, allowMalformed: true)`: every maximal ill-formed subpart becomes one
     * U+FFFD (WHATWG/Unicode practice; the JDK decoder replaces differently, hence the own loop).
     * One leading byte-order mark is dropped, as in Dart.
     */
    fun decodeLenient(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        var needed = 0
        var seen = 0
        var codePoint = 0
        var lower = 0x80
        var upper = 0xBF
        var i = bomLength(bytes)
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xff
            if (needed == 0) {
                when (b) {
                    in 0x00..0x7F -> sb.append(b.toChar())
                    in 0xC2..0xDF -> { needed = 1; codePoint = b and 0x1F }
                    in 0xE0..0xEF -> {
                        if (b == 0xE0) lower = 0xA0
                        if (b == 0xED) upper = 0x9F
                        needed = 2
                        codePoint = b and 0x0F
                    }
                    in 0xF0..0xF4 -> {
                        if (b == 0xF0) lower = 0x90
                        if (b == 0xF4) upper = 0x8F
                        needed = 3
                        codePoint = b and 0x07
                    }
                    else -> sb.append('\uFFFD')
                }
                i++
                continue
            }
            if (b < lower || b > upper) {
                // Ill-formed: one replacement for the subpart so far, then re-read this byte.
                needed = 0; seen = 0; codePoint = 0; lower = 0x80; upper = 0xBF
                sb.append('\uFFFD')
                continue
            }
            lower = 0x80
            upper = 0xBF
            codePoint = (codePoint shl 6) or (b and 0x3F)
            seen++
            i++
            if (seen == needed) {
                sb.appendCodePoint(codePoint)
                needed = 0; seen = 0; codePoint = 0
            }
        }
        if (needed != 0) sb.append('\uFFFD')
        return sb.toString()
    }
}
