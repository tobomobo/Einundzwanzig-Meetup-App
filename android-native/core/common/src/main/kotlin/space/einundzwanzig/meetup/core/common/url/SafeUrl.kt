package space.einundzwanzig.meetup.core.common.url

import space.einundzwanzig.meetup.core.common.dart.dartTrim

/**
 * Scheme whitelist for external links (Dart `safe_url_launcher.dart`, Security Audit M2).
 *
 * Links from foreign content (Markdown, calendar events, chat, portal data, NIP-46 `auth_url`)
 * may only open `http`/`https` URLs with a host. `javascript:`, `file:`, `intent:`, `tel:`,
 * `sms:` and arbitrary app schemes are rejected, so a manipulated event cannot start another
 * app or a system function. Hard-coded own schemes (`nostr:`, `nostrsigner:`, `bunker://`) do
 * not go through here.
 *
 * This is the pure decision; the UI layer opens [SafeHttpUrl.url] with `Intent.ACTION_VIEW`.
 *
 * Compared with Dart's `Uri.tryParse`, this parser is deliberately **stricter** in the
 * authority: whitespace, control characters or backslashes in the host part make the URL
 * invalid (Dart percent-encodes or rewrites them). The scheme decision is the same.
 */
object SafeUrl {

    /**
     * Returns the URL to open if [url] (trimmed like Dart's `trim()`) is an `http`/`https` URL
     * with a non-empty host, otherwise `null` (Dart `launchHttpUrl` returning `false` before
     * launching).
     */
    fun httpUrlOrNull(url: String): SafeHttpUrl? {
        val trimmed = url.dartTrim()
        val parts = UriParts.parse(trimmed) ?: return null
        val scheme = parts.scheme.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = parts.host ?: return null
        if (host.isEmpty()) return null
        return SafeHttpUrl(url = encodeUnsafeAscii(trimmed), scheme = scheme, host = host)
    }

    /** `true` if [url] may be opened externally, see [httpUrlOrNull]. */
    fun isAllowedHttpUrl(url: String): Boolean = httpUrlOrNull(url) != null

    /**
     * The host of [url] for log messages, or `"unbekannt"` (Dart `main.dart` `_hostOf`).
     * Log only this — never a full `auth_url`, it carries the approval token (Audit N5).
     */
    fun hostForLog(url: String): String =
        UriParts.parse(url.dartTrim())?.host?.takeIf { it.isNotEmpty() } ?: "unbekannt"

    /** Percent-encodes ASCII control characters and spaces (as Dart's `Uri` does when printing). */
    private fun encodeUnsafeAscii(url: String): String {
        if (url.none { it.code <= 0x20 || it.code == 0x7F }) return url
        return buildString(url.length + 8) {
            for (c in url) {
                if (c.code <= 0x20 || c.code == 0x7F) {
                    append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
                } else {
                    append(c)
                }
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

/** An external link that passed [SafeUrl]. [host] is lower-cased and percent-decoded. */
data class SafeHttpUrl(val url: String, val scheme: String, val host: String)

/** Minimal RFC 3986 split: scheme, and the host when an authority (`//…`) is present. */
private class UriParts(val scheme: String, val host: String?) {
    companion object {
        private val SCHEME = Regex("""([A-Za-z][A-Za-z0-9+.\-]*):""")
        private val PORT = Regex("""[0-9]*""")

        fun parse(text: String): UriParts? {
            val schemeMatch = SCHEME.matchAt(text, 0) ?: return null
            val scheme = schemeMatch.groupValues[1]
            val rest = text.substring(schemeMatch.range.last + 1)
            if (!rest.startsWith("//")) return UriParts(scheme, null)
            val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#'), startIndex = 2).let { if (it < 0) rest.length else it }
            val authority = rest.substring(2, authorityEnd)
            if (authority.any { it.code <= 0x20 || it.code == 0x7F || it == '\\' }) return null
            val hostPort = authority.substringAfterLast('@')
            val host: String
            val port: String
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                host = hostPort.substring(1, close)
                if (host.isEmpty() || !host.all { it.isHex() || it == ':' || it == '.' }) return null
                val after = hostPort.substring(close + 1)
                if (after.isNotEmpty() && !after.startsWith(":")) return null
                port = after.removePrefix(":")
            } else {
                val colon = hostPort.lastIndexOf(':')
                host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
                port = if (colon >= 0) hostPort.substring(colon + 1) else ""
            }
            if (!PORT.matches(port)) return null
            val decoded = percentDecode(host) ?: return null
            return UriParts(scheme, decoded.lowercase())
        }

        /**
         * Decodes `%XX` sequences (runs of escapes as UTF-8); a malformed escape is kept
         * literally. `null` if the decoded host contains whitespace or control characters.
         */
        private fun percentDecode(s: String): String? {
            if ('%' !in s) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                if (isEscapeAt(s, i)) {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (isEscapeAt(s, i)) {
                        bytes.write((s[i + 1].digitToInt(16) shl 4) or s[i + 2].digitToInt(16))
                        i += 3
                    }
                    out.append(bytes.toByteArray().toString(Charsets.UTF_8))
                } else {
                    out.append(s[i++])
                }
            }
            return out.toString().takeIf { decoded -> decoded.none { it.code <= 0x20 || it.code == 0x7F } }
        }

        private fun isEscapeAt(s: String, i: Int): Boolean =
            i + 2 < s.length && s[i] == '%' && s[i + 1].isHex() && s[i + 2].isHex()

        private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
