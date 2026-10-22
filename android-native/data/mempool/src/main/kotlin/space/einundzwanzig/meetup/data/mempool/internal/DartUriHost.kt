package space.einundzwanzig.meetup.data.mempool.internal

import space.einundzwanzig.meetup.data.mempool.format.dartTryParseInt

/**
 * Dart's `Uri.tryParse(url)?.host`, ported from `dart:core` `uri.dart` (Dart 3.13) so that
 * [space.einundzwanzig.meetup.data.mempool.MempoolHosts.looksValid] accepts exactly the custom
 * hosts the Flutter app accepts.
 *
 * The port keeps Dart's structure: the generated scanner state machine splits the URL, a
 * "simple" URL returns the raw host substring without further checks (Dart's `_SimpleUri`),
 * any other URL goes through `_makeHost` (IPv6/zone/IPvFuture validation, reg-name escape
 * normalisation), the user-info delimiter check and `int.tryParse` of the port. Every `FormatException` Dart would throw
 * yields `null`.
 *
 * Scope: absolute URLs as produced by `MempoolHosts.normalize` (always `http://` or `https://`).
 * Dart's `data:` special case and the `file` scheme rewrites are not ported (they cannot occur).
 */
internal object DartUriHost {

    /** Dart `Uri.tryParse(uri)?.host`: the host (`""` without authority), or `null` if Dart rejects [uri]. */
    fun hostOrNull(uri: String): String? = try {
        parseHost(uri)
    } catch (_: UriFormatException) {
        null
    }

    private class UriFormatException : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun fail(): Nothing = throw UriFormatException()

    // ------------------------------------------------------------------ Uri.parse driver

    private fun parseHost(uri: String): String {
        val start = 0
        val end = uri.length
        val indices = IntArray(8)
        indices[SCHEME_END] = start - 1
        indices[HOST_START] = start - 1
        indices[NOT_SIMPLE] = start - 1
        indices[PORT_START] = start
        indices[PATH_START] = start
        indices[QUERY_START] = end
        indices[FRAGMENT_START] = end
        var state = scan(uri, start, end, URI_START, indices)
        if (state >= NON_SIMPLE_END_STATES) indices[NOT_SIMPLE] = end
        val schemeEnd = indices[SCHEME_END]
        if (schemeEnd >= start) {
            state = scan(uri, start, schemeEnd, SCHEME_START, indices)
            if (state == SCHEME_START) indices[NOT_SIMPLE] = schemeEnd
        }
        val hostStart = indices[HOST_START] + 1
        var portStart = indices[PORT_START]
        var pathStart = indices[PATH_START]
        var queryStart = indices[QUERY_START]
        val fragmentStart = indices[FRAGMENT_START]
        if (fragmentStart < queryStart) queryStart = fragmentStart
        if (pathStart < hostStart) {
            pathStart = queryStart
        } else if (pathStart <= schemeEnd) {
            pathStart = schemeEnd + 1
        }
        if (portStart < hostStart) portStart = pathStart

        var isSimple = indices[NOT_SIMPLE] < start
        if (isSimple) {
            if (hostStart > schemeEnd + 3) {
                isSimple = false // user info
            } else if (portStart > start && portStart + 1 == pathStart) {
                isSimple = false // empty port
            } else if (uri.startsWith("\\", pathStart) ||
                hostStart > start && (uri.startsWith("\\", hostStart - 1) || uri.startsWith("\\", hostStart - 2))
            ) {
                isSimple = false
            } else if (queryStart < end && (queryStart == pathStart + 2 && uri.startsWith("..", pathStart)) ||
                (queryStart > pathStart + 2 && uri.startsWith("/..", queryStart - 3))
            ) {
                isSimple = false
            }
            // The remaining scheme normalisations (default port removal) never change the host.
        }
        if (isSimple) return if (hostStart > 0) uri.substring(hostStart, portStart) else ""

        // _Uri.notSimple
        if (schemeEnd > start) {
            makeScheme(uri, start, schemeEnd)
        } else if (schemeEnd == start) {
            fail()
        }
        if (hostStart <= start) return ""
        // _makeUserInfo: escapes what it can, but a general delimiter other than ':' throws.
        for (i in schemeEnd + 3 until hostStart - 1) {
            val c = uri[i].code
            if (!isUserinfoChar(c) && isGeneralDelimiter(c)) fail()
        }
        val host = makeHost(uri, hostStart, portStart)
        if (portStart + 1 < pathStart) {
            dartTryParseInt(uri.substring(portStart + 1, pathStart)) ?: fail()
        }
        // _Uri.host: brackets removed except for IPvFuture.
        return if (host.startsWith("[") && !host.startsWith("v", 1)) host.substring(1, host.length - 1) else host
    }

    private fun scan(uri: String, start: Int, end: Int, initial: Int, indices: IntArray): Int {
        var state = initial
        for (i in start until end) {
            var char = uri[i].code xor 0x60
            if (char > 95) char = 0x7F xor 0x60
            val transition = SCANNER_TABLES[state * 96 + char]
            state = transition and 31
            indices[transition shr 5] = i
        }
        return state
    }

    private fun makeScheme(uri: String, start: Int, end: Int) {
        if (!uri[start].isAsciiLetter()) fail()
        for (i in start until end) {
            val c = uri[i]
            if (!(c.isAsciiLetter() || c in '0'..'9' || c == '+' || c == '-' || c == '.')) fail()
        }
    }

    // ------------------------------------------------------------------ host

    /** Dart `_makeHost(host, start, end, strictIPv6: false)`; returns the normalised host. */
    private fun makeHost(host: String, start: Int, end: Int): String {
        if (start == end) return ""
        if (host[start] == '[') {
            if (host[end - 1] != ']') fail()
            var zoneId = ""
            var index = end - 1
            if (host[start + 1] != 'v') {
                index = checkZoneId(host, start + 1, end - 1)
                if (index < end - 1) {
                    val zoneIdStart = if (host.startsWith("25", index + 1)) index + 3 else index + 1
                    zoneId = normalizeZoneId(host, zoneIdStart, end - 1, "%25")
                }
            }
            val isIPv6 = validateIPvAddress(host, start + 1, index)
            var hostChars = host.substring(start + 1, index)
            if (isIPv6) hostChars = hostChars.asciiLowercase()
            return "[$hostChars$zoneId]"
        }
        for (i in start until end) {
            if (host[i] == ':') {
                var zoneId = ""
                val index = checkZoneId(host, start, end)
                if (index < end) {
                    val zoneIdStart = if (host.startsWith("25", index + 1)) index + 3 else index + 1
                    zoneId = normalizeZoneId(host, zoneIdStart, end, "%25")
                }
                parseIPv6Address(host, start, index)
                return "[${host.substring(start, index)}$zoneId]"
            }
        }
        return normalizeRegName(host, start, end)
    }

    private fun checkZoneId(host: String, start: Int, end: Int): Int {
        val index = host.indexOf('%', start)
        return if (index in start until end) index else end
    }

    private fun normalizeZoneId(host: String, start: Int, end: Int, prefix: String): String {
        val buffer = StringBuilder(prefix)
        var sectionStart = start
        var index = start
        var isNormalized = true
        while (index < end) {
            var char = host[index].code
            if (char == '%'.code) {
                var replacement = normalizeEscape(host, index, true)
                if (replacement == null && isNormalized) {
                    index += 3
                    continue
                }
                buffer.append(host, sectionStart, index)
                if (replacement == null) {
                    replacement = host.substring(index, index + 3)
                } else if (replacement == "%") {
                    fail()
                }
                buffer.append(replacement)
                index += 3
                sectionStart = index
                isNormalized = true
            } else if (isUnreserved(char)) {
                if (isNormalized && char in 'A'.code..'Z'.code) {
                    if (sectionStart < index) {
                        buffer.append(host, sectionStart, index)
                        sectionStart = index
                    }
                    isNormalized = false
                }
                index++
            } else {
                var sourceLength = 1
                if ((char and 0xFC00) == 0xD800 && index + 1 < end) {
                    val tail = host[index + 1].code
                    if ((tail and 0xFC00) == 0xDC00) {
                        char = 0x10000 + ((char and 0x3ff) shl 10) + (tail and 0x3ff)
                        sourceLength = 2
                    }
                }
                buffer.append(host, sectionStart, index).append(escapeChar(char))
                index += sourceLength
                sectionStart = index
            }
        }
        if (sectionStart < end) buffer.append(host, sectionStart, end)
        return buffer.toString()
    }

    /** Dart `_normalizeRegName`: lower-cases, decodes unreserved escapes, escapes the rest. */
    private fun normalizeRegName(host: String, start: Int, end: Int): String {
        val buffer = StringBuilder()
        var sectionStart = start
        var index = start
        var isNormalized = true
        fun slice(from: Int, to: Int): String =
            host.substring(from, to).let { if (isNormalized) it else it.asciiLowercase() }
        while (index < end) {
            var char = host[index].code
            if (char == '%'.code) {
                var replacement = normalizeEscape(host, index, true)
                if (replacement == null && isNormalized) {
                    index += 3
                    continue
                }
                buffer.append(slice(sectionStart, index))
                var sourceLength = 3
                if (replacement == null) {
                    replacement = host.substring(index, index + 3)
                } else if (replacement == "%") {
                    replacement = "%25"
                    sourceLength = 1
                }
                buffer.append(replacement)
                index += sourceLength
                sectionStart = index
                isNormalized = true
            } else if (isRegNameChar(char)) {
                if (isNormalized && char in 'A'.code..'Z'.code) {
                    if (sectionStart < index) {
                        buffer.append(host, sectionStart, index)
                        sectionStart = index
                    }
                    isNormalized = false
                }
                index++
            } else if (isGeneralDelimiter(char)) {
                fail()
            } else {
                var sourceLength = 1
                if ((char and 0xFC00) == 0xD800 && index + 1 < end) {
                    val tail = host[index + 1].code
                    if ((tail and 0xFC00) == 0xDC00) {
                        char = 0x10000 + ((char and 0x3ff) shl 10) + (tail and 0x3ff)
                        sourceLength = 2
                    }
                }
                buffer.append(slice(sectionStart, index)).append(escapeChar(char))
                index += sourceLength
                sectionStart = index
            }
        }
        if (sectionStart < end) buffer.append(slice(sectionStart, end))
        return buffer.toString()
    }

    /**
     * Dart `_normalizeEscape`: the decoded (lower-cased) unreserved character, an upper-cased
     * escape, `null` when the escape is kept as is, or `"%"` for an invalid escape.
     */
    private fun normalizeEscape(source: String, index: Int, lowerCase: Boolean): String? {
        if (index + 2 >= source.length) return "%"
        val firstDigit = source[index + 1]
        val secondDigit = source[index + 2]
        val first = hexValue(firstDigit)
        val second = hexValue(secondDigit)
        if (first < 0 || second < 0) return "%"
        var value = first * 16 + second
        if (isUnreserved(value)) {
            if (lowerCase && value in 'A'.code..'Z'.code) value = value or 0x20
            return value.toChar().toString()
        }
        if (firstDigit.code >= 'a'.code || secondDigit.code >= 'a'.code) {
            return source.substring(index, index + 3).asciiUppercase()
        }
        return null
    }

    /** Dart `_escapeChar`: UTF-8 percent-encoding of one code point, upper-case hex. */
    private fun escapeChar(char: Int): String {
        val bytes = String(Character.toChars(char)).toByteArray(Charsets.UTF_8).takeIf { char !in 0xD800..0xDFFF }
            ?: byteArrayOf((0xE0 or (char shr 12)).toByte(), (0x80 or ((char shr 6) and 0x3F)).toByte(), (0x80 or (char and 0x3F)).toByte())
        return buildString { for (b in bytes) append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF]) }
    }

    // ------------------------------------------------------------------ IP literals

    /** Dart `Uri._validateIPvAddress`: `true` for IPv6, `false` for IPvFuture, throws otherwise. */
    private fun validateIPvAddress(host: String, start: Int, end: Int): Boolean {
        if (start == end) fail()
        if (host[start] == 'v') {
            validateIPvFutureAddress(host, start, end)
            return false
        }
        parseIPv6Address(host, start, end)
        return true
    }

    /** Dart `Uri._validateIPvFutureAddress`: `v` hex+ `.` (unreserved / sub-delims / `:`)+. */
    private fun validateIPvFutureAddress(host: String, vAt: Int, end: Int) {
        val start = vAt + 1
        var cursor = start
        while (true) {
            if (cursor >= end) fail() // no '.' (or no hex digit)
            val c = host[cursor++]
            if (c in '0'..'9' || c.code or 0x20 in 'a'.code..'f'.code) continue
            if (c == '.') {
                if (cursor - 1 == start) fail()
                break
            }
            fail()
        }
        if (cursor == end) fail()
        while (cursor < end) {
            if (!isUserinfoChar(host[cursor].code)) fail()
            cursor++
        }
    }

    /** Dart `Uri.parseIPv6Address` (validation only). */
    private fun parseIPv6Address(host: String, start: Int, end: Int) {
        if (end - start < 2) fail()
        var partStart = start
        var wildcardAt = -1
        var partCount = 0
        var decValue = true
        var cursor = start
        if (host[cursor] == ':') {
            if (host[cursor + 1] == ':') {
                wildcardAt = 0
                partCount = 1
                cursor += 2
                partStart = cursor
            } else {
                fail()
            }
        }
        while (true) {
            val char = if (cursor >= end) 0 else host[cursor].code
            val isDigit = char in '0'.code..'9'.code
            val isHexLetter = (char or 0x20) in 'a'.code..'f'.code
            if (isDigit || isHexLetter) {
                if (isHexLetter) decValue = false
                if (cursor < partStart + 4) {
                    cursor++
                    continue
                }
                fail() // more than 4 hex digits
            }
            if (cursor > partStart) {
                if (char == '.'.code) {
                    if (decValue) {
                        if (partCount <= 6) {
                            parseIPv4Address(host, partStart, end)
                            cursor = end
                            partCount += 2
                            break
                        }
                        fail()
                    }
                    break // invalid character
                }
                partCount++
                if (char == ':'.code) {
                    if (partCount < 8) {
                        decValue = true
                        cursor++
                        partStart = cursor
                        continue
                    }
                    fail()
                }
                break
            }
            if (char == ':'.code) {
                if (wildcardAt < 0) {
                    wildcardAt = partCount
                    partCount++
                    partStart = ++cursor
                    continue
                }
                fail() // second wildcard
            }
            if (wildcardAt != partCount - 1) fail() // missing part
            break
        }
        if (cursor < end) fail()
        if (partCount < 8 && wildcardAt < 0) fail()
    }

    /** Dart `Uri._parseIPv4Address`: exactly four decimal octets 0..255 without leading zeros. */
    private fun parseIPv4Address(host: String, start: Int, end: Int) {
        var cursor = start
        var octetStart = cursor
        var octetIndex = 0
        var octetValue = 0
        while (true) {
            val char = if (cursor >= end) 0 else host[cursor].code
            val digit = char xor '0'.code
            if (digit <= 9) {
                if (octetValue != 0 || cursor == octetStart) {
                    octetValue = octetValue * 10 + digit
                    if (octetValue <= 255) {
                        cursor++
                        continue
                    }
                }
                fail()
            }
            if (cursor == octetStart) {
                if (cursor == end) break
                fail()
            }
            octetIndex++
            if (char == '.'.code) {
                if (octetIndex < 4) {
                    octetValue = 0
                    octetStart = ++cursor
                    continue
                }
                break
            }
            if (cursor == end) {
                if (octetIndex == 4) return
                break
            }
            fail()
        }
        fail()
    }

    // ------------------------------------------------------------------ character classes

    private const val UNRESERVED_EXTRA = "-._~"
    private const val SUB_DELIMS = "!$&'()*+,;="
    private const val GEN_DELIMS = ":/?#[]@"
    private const val HEX = "0123456789ABCDEF"

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /** RFC 3986 unreserved: `[A-Za-z0-9\-._~]`. */
    private fun isUnreserved(c: Int): Boolean =
        c < 127 && c.toChar().let { it.isAsciiLetter() || it in '0'..'9' || it in UNRESERVED_EXTRA }

    /** reg-name: unreserved, sub-delims and `%`. */
    private fun isRegNameChar(c: Int): Boolean = isUnreserved(c) || c < 127 && (c.toChar() in SUB_DELIMS || c == '%'.code)

    /** userinfo / IPvFuture address: unreserved, sub-delims and `:`. */
    private fun isUserinfoChar(c: Int): Boolean = isUnreserved(c) || c < 127 && (c.toChar() in SUB_DELIMS || c == ':'.code)

    private fun isGeneralDelimiter(c: Int): Boolean = c < 127 && c.toChar() in GEN_DELIMS

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun String.asciiLowercase(): String =
        buildString(length) { for (c in this@asciiLowercase) append(if (c in 'A'..'Z') c + 32 else c) }

    private fun String.asciiUppercase(): String =
        buildString(length) { for (c in this@asciiUppercase) append(if (c in 'a'..'z') c - 32 else c) }

    // ------------------------------------------------------------------ scanner tables

    private const val SCHEME_END = 1
    private const val HOST_START = 2
    private const val PORT_START = 3
    private const val PATH_START = 4
    private const val QUERY_START = 5
    private const val FRAGMENT_START = 6
    private const val NOT_SIMPLE = 7
    private const val URI_START = 0
    private const val NON_SIMPLE_END_STATES = 14
    private const val SCHEME_START = 20

    /**
     * Dart's generated `_scannerTables` (22 states x 96 transitions, one row per state), hex
     * encoded. Each entry: low 5 bits next state, high 3 bits index written into `indices`.
     */
    private val SCANNER_TABLES: IntArray = (
            "E10101010101010101010101010101010101010101010101010101E1E1E101E1E10101010101010101010101010101010101010101010101010101E1E3E1E101E101E1CD01E101010101010101010E03010101010101010101012201E101E1AC" +
            "E10101010101010101010101010101010101010101010101010101E1E1E101E1E10101010101010101010101010101010101010101010101010101E1EAE1E101E101E1CD01E10101010101010101010A010101010101010101012201E101E1AC" +
            "EB8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8BEBEBEB8BEBEB8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8B8BEB83EBEB8BEB8BEBCD8BEB8B8B8B8B8B8B8B8B92838B8B8B8B8B8B8B8B8B8BEB8BEB8BEBAC" +
            "EB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEBEB0BEBEB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEB44EBEB0BEB0BEBCD0BEB0B0B0B0B0B0B0B0B12440B0B0B0B0B0B0B0B0B0BEB0BEB0BEBAC" +
            "E50505050505050505050505050505050505050505050505050505E5E5E505E544E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E88AE5E505E505E5CD05E50505050505050505058A050505050505050505056605E505E5AC" +
            "E50505050505050505050505050505050505050505050505050505E5E5E505E544E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E58AE5E505E505E5CD05E50505050505050505058A050505050505050505056605E505E5AC" +
            "E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E744E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E78AE7E7E7E7E7E7CDE7E7E7E7E7E7E7E7E7E7E78AE7070707070707070707E7E7E7E7E7AC" +
            "E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E744E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E7E78AE7E7E7E7E7E7CDE7E7E7E7E7E7E7E7E7E7E78A07070707070707070707E7E7E7E7E7AC" +
            "080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080508080808080808080808080808080808080808080808080808080808080808080808" +
            "EB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEBEB0BEBEB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEAEBEB0BEB0BEBCD0BEB0B0B0B0B0B0B0B0B10EA0B0B0B0B0B0B0B0B0B0BEB0BEB0BEBAC" +
            "EB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEBEB0BEBEB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEAEBEB0BEB0BEBCD0BEB0B0B0B0B0B0B0B0B120A0B0B0B0B0B0B0B0B0B0BEB0BEB0BEBAC" +
            "EB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEBEB0BEBEB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEAEBEB0BEB0BEBCD0BEB0B0B0B0B0B0B0B0B0B0A0B0B0B0B0B0B0B0B0B0BEB0BEB0BEBAC" +
            "EC0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0CECECEC0CECEC0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0C0CECECECEC0CEC0CECCD0CEC0C0C0C0C0C0C0C0C0CEC0C0C0C0C0C0C0C0C0C0CEC0CEC0CEC0C" +
            "ED0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0DEDEDED0DEDED0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0D0DEDEDEDED0DED0DEDED0DED0D0D0D0D0D0D0D0D0DED0D0D0D0D0D0D0D0D0D0DED0DED0DED0D" +
            "E10101010101010101010101010101010101010101010101010101E1E1E101E1E10101010101010101010101010101010101010101010101010101E1EAE1E101E101E1CD01E101010101010101010FEA010101010101010101012201E101E1AC" +
            "E10101010101010101010101010101010101010101010101010101E1E1E101E1E10101010101010101010101010101010101010101010101010101E1E9E1E101E101E1CD01E101010101010101010109010101010101010101012201E101E1AC" +
            "EB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEBEB0BEBEB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEAEBEB0BEB0BEBCD0BEB0B0B0B0B0B0B0B0B11EA0B0B0B0B0B0B0B0B0B0BEB0BEB0BEBAC" +
            "EB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEBEB0BEBEB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBE9EBEB0BEB0BEBCD0BEB0B0B0B0B0B0B0B0B0B090B0B0B0B0B0B0B0B0B0BEB0BEB0BEBAC" +
            "EB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEBEB0BEBEB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEAEBEB0BEB0BEBCD0BEB0B0B0B0B0B0B0B0B13EA0B0B0B0B0B0B0B0B0B0BEB0BEB0BEBAC" +
            "EB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEBEB0BEBEB0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0BEBEAEBEB0BEB0BEBCD0BEB0B0B0B0B0B0B0B0B0BEA0B0B0B0B0B0B0B0B0B0BEB0BEB0BEBAC" +
            "F51515151515151515151515151515151515151515151515151515F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5" +
            "F51515151515151515151515151515151515151515151515151515F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F5F515F51515F515151515151515151515F5F5F5F5F5F5"
        ).chunked(2).map { it.toInt(16) }.toIntArray()
}
