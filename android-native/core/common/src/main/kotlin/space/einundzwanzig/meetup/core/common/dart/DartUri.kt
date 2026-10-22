package space.einundzwanzig.meetup.core.common.dart

/**
 * Dart `Uri.encodeQueryComponent` / `Uri.encodeComponent`.
 *
 * Both encode the UTF-8 bytes of the input (lone surrogates become U+FFFD, `%EF%BF%BD`, like
 * Dart's UTF-8 encoder) with **uppercase** hex.
 *
 * `java.net.URLEncoder` is not a substitute: it keeps `*` and encodes `~`, and Android's
 * `Uri.encode` keeps `!'()*`.
 */
object DartUri {

    /**
     * Dart `Uri.encodeQueryComponent`: keeps the RFC 3986 unreserved set `A-Z a-z 0-9 - . _ ~`,
     * turns a space into `+`, percent-encodes everything else.
     *
     * `"a b~*'"` → `a+b~%2A%27`.
     */
    fun encodeQueryComponent(component: String): String = encode(component, ::isUnreserved3986, spaceToPlus = true)

    /**
     * Dart `Uri.encodeComponent`: keeps the RFC 2396 unreserved set
     * `A-Z a-z 0-9 - _ . ! ~ * ' ( )`, encodes a space as `%20` and everything else as `%XX`.
     */
    fun encodeComponent(component: String): String = encode(component, ::isUnreserved2396, spaceToPlus = false)

    private fun isUnreserved3986(c: Int): Boolean =
        c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code

    private fun isUnreserved2396(c: Int): Boolean =
        isUnreserved3986(c) || c == '!'.code || c == '*'.code || c == '\''.code || c == '('.code || c == ')'.code

    private fun encode(text: String, keep: (Int) -> Boolean, spaceToPlus: Boolean): String {
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val code = ch.code
            when {
                code < 0x80 && keep(code) -> out.append(ch)
                spaceToPlus && ch == ' ' -> out.append('+')
                else -> {
                    val codePoint = when {
                        Character.isHighSurrogate(ch) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) ->
                            Character.toCodePoint(ch, text[++i])
                        Character.isSurrogate(ch) -> REPLACEMENT_CHARACTER
                        else -> code
                    }
                    appendUtf8Escaped(out, codePoint)
                }
            }
            i++
        }
        return out.toString()
    }

    private fun appendUtf8Escaped(out: StringBuilder, cp: Int) {
        when {
            cp < 0x80 -> escapeByte(out, cp)
            cp < 0x800 -> {
                escapeByte(out, 0xC0 or (cp shr 6))
                escapeByte(out, 0x80 or (cp and 0x3F))
            }
            cp < 0x10000 -> {
                escapeByte(out, 0xE0 or (cp shr 12))
                escapeByte(out, 0x80 or ((cp shr 6) and 0x3F))
                escapeByte(out, 0x80 or (cp and 0x3F))
            }
            else -> {
                escapeByte(out, 0xF0 or (cp shr 18))
                escapeByte(out, 0x80 or ((cp shr 12) and 0x3F))
                escapeByte(out, 0x80 or ((cp shr 6) and 0x3F))
                escapeByte(out, 0x80 or (cp and 0x3F))
            }
        }
    }

    private fun escapeByte(out: StringBuilder, byte: Int) {
        out.append('%').append(HEX[byte shr 4]).append(HEX[byte and 0xF])
    }

    private const val REPLACEMENT_CHARACTER = 0xFFFD
    private const val HEX = "0123456789ABCDEF"
}
