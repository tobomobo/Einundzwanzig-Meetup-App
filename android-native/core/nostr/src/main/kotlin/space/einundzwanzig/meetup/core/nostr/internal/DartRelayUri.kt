package space.einundzwanzig.meetup.core.nostr.internal

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import java.math.BigInteger

/**
 * The subset of Dart's `Uri.tryParse` needed for relay URLs (`scheme://authority path?query#fragment`):
 * scheme and host lower-cased, characters outside the allowed set percent-encoded (UTF-8,
 * upper-case hex), escapes of unreserved characters decoded, malformed escapes encoded as `%25`,
 * dot segments removed from the path, a backslash in the authority or path treated as `/`.
 * Ports are parsed like Dart `int.tryParse` (surrounding whitespace, optional sign, `0x` hex;
 * `wss://a.b:+1` → port 1, `wss://a.b:-1` → port -1) and dropped when equal to the scheme's
 * default (`http` 80, `https` 443, otherwise 0). IPv6 literals are returned without brackets
 * (zone ids normalised to `%25<zone>`), IPvFuture literals (`[v1.x]`) with brackets, as Dart's
 * `uri.host` does.
 *
 * Inputs without an absolute `scheme://` prefix, with an invalid scheme, a non-numeric port or
 * a malformed IP literal yield `null` (callers only accept `wss://` URIs with a host). Where
 * Dart *throws* from `Uri.tryParse` (a port beyond 64 bit, non-ASCII in an IPvFuture literal),
 * this parser returns `null`.
 */
internal data class DartRelayUri(
    val scheme: String,
    val userInfo: String,
    val host: String,
    val port: Long?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    val hasQuery: Boolean get() = query != null
    val hasFragment: Boolean get() = fragment != null

    /** Dart `uri.toString()`. */
    override fun toString(): String = buildString {
        append(scheme).append("://")
        if (userInfo.isNotEmpty()) append(userInfo).append('@')
        if (host.contains(':') && !host.startsWith("[")) append('[').append(host).append(']') else append(host)
        port?.let { append(':').append(it.toString()) }
        append(path)
        query?.let { append('?').append(it) }
        fragment?.let { append('#').append(it) }
    }

    companion object {
        fun parse(input: String): DartRelayUri? {
            val colon = input.indexOf(':')
            if (colon <= 0) return null
            val firstDelimiter = input.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (firstDelimiter in 0 until colon) return null
            val schemeRaw = input.substring(0, colon)
            if (!schemeRaw[0].isAsciiLetter() || !schemeRaw.all { it.isAsciiLetter() || it.isAsciiDigit() || it in "+-." }) {
                return null
            }
            val scheme = schemeRaw.lowercase()
            val rest = input.substring(colon + 1)
            if (!rest.startsWith("//")) return null

            val afterSlashes = rest.substring(2)
            // Dart's scanner ends the authority at a backslash too (and treats it as '/').
            val authorityEnd = afterSlashes.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }
                .let { if (it < 0) afterSlashes.length else it }
            val authority = afterSlashes.substring(0, authorityEnd)
            var remainder = afterSlashes.substring(authorityEnd)

            val fragment = remainder.indexOf('#').takeIf { it >= 0 }?.let {
                val f = remainder.substring(it + 1)
                remainder = remainder.substring(0, it)
                normalize(f, FRAGMENT_EXTRA)
            }
            val query = remainder.indexOf('?').takeIf { it >= 0 }?.let {
                val q = remainder.substring(it + 1)
                remainder = remainder.substring(0, it)
                normalize(q, QUERY_EXTRA)
            }
            // Dart `_normalizePath(replaceBackslash: true)` for hierarchical URIs; query and
            // fragment keep the backslash (encoded as %5C).
            val path = removeDotSegments(normalize(remainder.replace('\\', '/'), PATH_EXTRA))

            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) normalize(authority.substring(0, at), USERINFO_EXTRA) else ""
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority

            val host: String
            val portText: String?
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                host = parseIpLiteral(hostPort.substring(1, close)) ?: return null
                val after = hostPort.substring(close + 1)
                portText = when {
                    after.isEmpty() -> null
                    after.startsWith(":") -> after.substring(1)
                    else -> return null
                }
            } else {
                val portColon = hostPort.indexOf(':')
                host = normalizeRegName(if (portColon >= 0) hostPort.substring(0, portColon) else hostPort)
                portText = if (portColon >= 0) hostPort.substring(portColon + 1) else null
            }
            val port = when {
                portText.isNullOrEmpty() -> null
                else -> (parsePort(portText) ?: return null).takeUnless { it == defaultPort(scheme) }
            }
            return DartRelayUri(scheme, userInfo, host, port, path, query, fragment)
        }

        /**
         * The content of `[...]` (Dart `_makeHost`): IPvFuture `v<hex>+.<unreserved/sub-delims/:>+`
         * kept verbatim with brackets; otherwise an IPv6 address (hex digits, `:` and `.` only,
         * lower-cased) with an optional RFC 6874 zone id after the first `%` (a leading `25` is
         * skipped), normalised to `%25<zone>`.
         */
        private fun parseIpLiteral(literal: String): String? {
            if (literal.startsWith("v")) {
                val dot = literal.indexOf('.')
                if (dot <= 1 || !literal.substring(1, dot).all { it.isHexDigitChar() }) return null
                val address = literal.substring(dot + 1)
                if (address.isEmpty() || !address.all { it.code < 0x80 && (it.isUnreserved() || it in USERINFO_EXTRA) }) {
                    return null
                }
                return "[$literal]"
            }
            val percent = literal.indexOf('%')
            val address = if (percent >= 0) literal.substring(0, percent) else literal
            if (!address.contains(':') || !address.all { it.isHexDigitChar() || it == ':' || it == '.' }) return null
            if (percent < 0) return address.lowercase()
            val zoneStart = if (literal.startsWith("25", percent + 1)) percent + 3 else percent + 1
            val zone = normalizeZoneId(literal.substring(zoneStart)) ?: return null
            return address.lowercase() + "%25" + zone
        }

        /**
         * Dart `_normalizeZoneID`: unreserved characters kept as typed, escapes of unreserved
         * characters decoded (and lower-cased), other escapes upper-cased, everything else
         * UTF-8 percent-encoded. A malformed escape makes the whole URI invalid.
         */
        private fun normalizeZoneId(raw: String): String? {
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '%' -> {
                        if (i + 2 >= raw.length || !raw[i + 1].isHexDigitChar() || !raw[i + 2].isHexDigitChar()) return null
                        val value = raw.substring(i + 1, i + 3).toInt(16)
                        if (value < 0x80 && value.toChar().isUnreserved()) {
                            out.append(value.toChar().lowercaseChar())
                        } else {
                            out.append('%').append(raw.substring(i + 1, i + 3).uppercase())
                        }
                        i += 3
                    }
                    c.code < 0x80 && c.isUnreserved() -> {
                        out.append(c)
                        i++
                    }
                    else -> i += appendUtf8Escaped(out, raw, i)
                }
            }
            return out.toString()
        }

        /**
         * Dart `int.tryParse` on the port text: surrounding whitespace, optional sign, decimal or
         * `0x` hex. `null` if not numeric or outside 64 bit (Dart throws there).
         */
        private fun parsePort(text: String): Long? {
            val m = PORT.matchEntire(text.dartTrim()) ?: return null
            val magnitude = if (m.groupValues[2].isNotEmpty()) BigInteger(m.groupValues[2], 16) else BigInteger(m.groupValues[3])
            val value = if (m.groupValues[1] == "-") magnitude.negate() else magnitude
            return if (value.bitLength() < 64) value.toLong() else null
        }

        /** Dart `Uri._defaultPort`. */
        private fun defaultPort(scheme: String): Long = when (scheme) {
            "http" -> 80L
            "https" -> 443L
            else -> 0L
        }

        private val PORT = Regex("""([+-]?)(?:0[xX]([0-9a-fA-F]+)|([0-9]+))""")

        private const val SUB_DELIMS = "!$&'()*+,;="
        private const val USERINFO_EXTRA = "$SUB_DELIMS:"
        private const val PATH_EXTRA = "$SUB_DELIMS:@/"
        private const val QUERY_EXTRA = "$SUB_DELIMS:@/?"
        private const val FRAGMENT_EXTRA = QUERY_EXTRA

        /** Reg-name: lower-cased, unreserved escapes decoded (and lower-cased), the rest encoded. */
        private fun normalizeRegName(raw: String): String = normalize(raw, SUB_DELIMS, lowerCase = true)

        private fun normalize(raw: String, extraAllowed: String, lowerCase: Boolean = false): String {
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '%') {
                    if (i + 2 < raw.length && raw[i + 1].isHexDigitChar() && raw[i + 2].isHexDigitChar()) {
                        val value = raw.substring(i + 1, i + 3).toInt(16)
                        val decoded = value.toChar()
                        if (value < 0x80 && decoded.isUnreserved()) {
                            out.append(if (lowerCase) decoded.lowercaseChar() else decoded)
                        } else {
                            out.append('%').append(raw.substring(i + 1, i + 3).uppercase())
                        }
                        i += 3
                        continue
                    }
                    out.append("%25")
                    i++
                    continue
                }
                if (c.code < 0x80 && (c.isUnreserved() || c in extraAllowed)) {
                    out.append(if (lowerCase && c in 'A'..'Z') c.lowercaseChar() else c)
                    i++
                    continue
                }
                i += appendUtf8Escaped(out, raw, i)
            }
            return out.toString()
        }

        /**
         * Appends the code point at [index] (a surrogate pair counts as one) as UTF-8 percent
         * escapes; returns the number of chars consumed.
         */
        private fun appendUtf8Escaped(out: StringBuilder, raw: String, index: Int): Int {
            val c = raw[index]
            val cp = raw.codePointAt(index)
            val chars = Character.charCount(cp)
            val bytes = if (Character.isSurrogate(c) && chars == 1) {
                byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte()) // lone surrogate → U+FFFD
            } else {
                String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            }
            for (b in bytes) out.append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            return chars
        }

        /** RFC 3986 §5.2.4 `remove_dot_segments` (Dart `_removeDotSegments` for absolute paths). */
        private fun removeDotSegments(path: String): String {
            if (!path.contains('.')) return path
            if (!path.startsWith("/")) return path
            val output = ArrayList<String>()
            val segments = path.substring(1).split('/')
            var appendSlash = false
            for ((index, segment) in segments.withIndex()) {
                appendSlash = false
                when (segment) {
                    ".." -> {
                        if (output.isNotEmpty()) output.removeAt(output.size - 1)
                        appendSlash = true
                    }
                    "." -> appendSlash = true
                    else -> output.add(segment)
                }
                if (index == segments.size - 1 && segment != "." && segment != "..") appendSlash = false
            }
            if (appendSlash) output.add("")
            return "/" + output.joinToString("/")
        }

        private const val HEX = "0123456789ABCDEF"

        private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
        private fun Char.isAsciiDigit() = this in '0'..'9'
        private fun Char.isHexDigitChar() = isAsciiDigit() || this in 'a'..'f' || this in 'A'..'F'
        private fun Char.isUnreserved() = isAsciiLetter() || isAsciiDigit() || this == '-' || this == '.' || this == '_' || this == '~'
    }
}
