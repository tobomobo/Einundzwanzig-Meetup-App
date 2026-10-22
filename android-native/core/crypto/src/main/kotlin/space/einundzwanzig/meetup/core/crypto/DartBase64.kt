package space.einundzwanzig.meetup.core.crypto

import java.util.Base64

/**
 * Base64 as the Dart app uses it.
 *
 * - [encode]: `base64.encode` / `base64Encode` — standard alphabet with `=` padding.
 * - [decode]: `base64.decode` — accepts the standard **and** the URL-safe alphabet (even mixed),
 *   requires correct `=` padding and a length that is a multiple of four, rejects whitespace.
 *   (Dart also accepts a percent-encoded `%3D` as padding; that quirk is not reproduced.)
 * - [encodeUrlNoPadding] / [encodeUrl] / [decodeUrlLenient]: the passkey PRF helpers.
 */
object DartBase64 {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Dart `base64Url.encode(bytes)` (with padding). */
    fun encodeUrl(bytes: ByteArray): String = Base64.getUrlEncoder().encodeToString(bytes)

    /** `base64Url.encode(bytes).replaceAll('=', '')`. */
    fun encodeUrlNoPadding(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Dart `base64.decode`; throws [IllegalArgumentException] where Dart throws `FormatException`. */
    fun decode(value: String): ByteArray {
        require(value.length % 4 == 0) { "Invalid length, must be multiple of four" }
        val padStart = value.indexOf('=')
        if (padStart >= 0) {
            val pad = value.length - padStart
            require(pad <= 2 && value.substring(padStart).all { it == '=' } && padStart % 4 >= 2) {
                "Invalid padding"
            }
        }
        for (i in 0 until (if (padStart >= 0) padStart else value.length)) {
            val c = value[i]
            val ok = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '+' || c == '/' || c == '-' || c == '_'
            require(ok) { "Invalid character at $i" }
        }
        if (padStart >= 0) {
            // Dart: "Invalid encoding before padding" when the dropped bits are not zero.
            val last = sextet(value[padStart - 1])
            val mask = if (value.length - padStart == 2) 0x0f else 0x03
            require(last and mask == 0) { "Invalid encoding before padding" }
        }
        val normalized = value.replace('-', '+').replace('_', '/')
        return Base64.getDecoder().decode(normalized)
    }

    fun decodeOrNull(value: String): ByteArray? = runCatching { decode(value) }.getOrNull()

    private fun sextet(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+', '-' -> 62
        else -> 63
    }

    /**
     * `PasskeyPrfService._b64urlDecode`: URL-safe → standard, pad to a multiple of four,
     * then [decode].
     */
    fun decodeUrlLenient(value: String): ByteArray {
        val s = value.replace('-', '+').replace('_', '/')
        val pad = (4 - s.length % 4) % 4
        return decode(s + "=".repeat(pad))
    }
}
