package space.einundzwanzig.meetup.data.identity.nip46

import space.einundzwanzig.meetup.core.common.dart.DartUri
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.crypto.Nip19
import java.io.ByteArrayOutputStream

/**
 * Address of a remote signer (Dart `BunkerPointer`, spec 01 §3.4/§6.3).
 *
 * @property remoteSignerPubkey 64 lowercase hex: the **signer's** key (differs from the user's
 *   key for nsec.app & co.).
 * @property relays `wss://` relays of the signer (at least one).
 * @property secret one-time secret, sent only in the first `connect`; never persisted.
 */
data class BunkerPointer(
    val remoteSignerPubkey: String,
    val relays: List<String>,
    val secret: String? = null,
) {
    /**
     * Persisted form without the secret:
     * `bunker://<hex>?relay=<encodeQueryComponent>&relay=…`.
     */
    fun toBunkerUri(): String =
        "bunker://$remoteSignerPubkey?" + relays.joinToString("&") { "relay=${DartUri.encodeQueryComponent(it)}" }

    override fun toString(): String = "BunkerPointer(remoteSignerPubkey=$remoteSignerPubkey, relays=$relays)"

    companion object {
        private const val SCHEME = "bunker://"

        /**
         * Parses `bunker://<hex|npub>?relay=wss://…&secret=…` (input trimmed, scheme
         * case-insensitive). Only `wss://` relays are kept (Security Audit N2).
         *
         * @throws Nip46Exception with the user-facing reason.
         */
        fun parse(input: String): BunkerPointer {
            val trimmed = input.dartTrim()
            if (!trimmed.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) {
                throw Nip46Exception("Das ist keine Bunker-Adresse — sie muss mit \"bunker://\" beginnen.")
            }
            val uri = DartLikeUri.parse(trimmed) ?: throw Nip46Exception("Die Bunker-Adresse ist unlesbar.")

            var key = uri.host
            if (key.isEmpty() && uri.pathSegments.isNotEmpty()) key = uri.pathSegments.first()

            val relays = uri.queryAll("relay").map { it.dartTrim() }.filter(::isRelayUrl)
            if (relays.isEmpty()) {
                throw Nip46Exception(
                    "In der Bunker-Adresse fehlt ein Relay (relay=wss://…). " +
                        "Ohne Relay ist der Signer nicht erreichbar.",
                )
            }
            // Dart `uri.queryParameters['secret']` (Uri.splitQueryString): the last value of a repeated key wins.
            val secret = uri.queryAll("secret").lastOrNull()?.dartTrim()
            return BunkerPointer(
                remoteSignerPubkey = normalizePubkey(key),
                relays = relays.toList(),
                secret = secret?.takeIf { it.isNotEmpty() },
            )
        }

        /**
         * `npub` or hex → 64 lowercase hex.
         *
         * @throws Nip46Exception for anything else.
         */
        fun normalizePubkey(value: String): String {
            var key = value.dartTrim()
            if (key.regionMatches(0, "npub1", 0, 5, ignoreCase = true)) {
                key = try {
                    Nip19.decodeNpub(key)
                } catch (e: Exception) {
                    throw Nip46Exception("Der npub des Signers ist ungültig.")
                }
            }
            key = key.lowercase()
            if (key.length != 64 || !key.all { it in '0'..'9' || it in 'a'..'f' }) {
                throw Nip46Exception("Der Schlüssel des Signers ist kein 64-stelliger Hex-Wert.")
            }
            return key
        }

        /**
         * The `nostrconnect://` URI the app shows for client-initiated pairing, byte-identical to
         * Dart `buildNostrConnectUri` (query components with `encodeQueryComponent`, the app
         * name with `encodeComponent` so it carries `%20` instead of `+`).
         */
        fun buildNostrConnectUri(
            clientPubkeyHex: String,
            relays: List<String>,
            secret: String,
            perms: String,
            appName: String,
            appUrl: String? = null,
            appImage: String? = null,
        ): String {
            val params = buildList {
                for (r in relays) add("relay=${DartUri.encodeQueryComponent(r)}")
                add("secret=${DartUri.encodeQueryComponent(secret)}")
                add("perms=${DartUri.encodeQueryComponent(perms)}")
                add("name=${DartUri.encodeComponent(appName)}")
                if (appUrl != null) add("url=${DartUri.encodeQueryComponent(appUrl)}")
                if (appImage != null) add("image=${DartUri.encodeQueryComponent(appImage)}")
            }
            return "nostrconnect://$clientPubkeyHex?${params.joinToString("&")}"
        }

        private fun isRelayUrl(value: String): Boolean = value.startsWith("wss://")
    }
}

/**
 * The subset of Dart `Uri.parse` needed for `scheme://authority/path?query#fragment`: host
 * (userinfo and port removed, ASCII lower-cased), decoded path segments and the form-decoded
 * query parameters (`+` = space) in order.
 */
internal class DartLikeUri private constructor(
    val host: String,
    val pathSegments: List<String>,
    private val query: List<Pair<String, String>>,
) {
    fun queryAll(name: String): List<String> = query.filter { it.first == name }.map { it.second }

    companion object {
        fun parse(text: String): DartLikeUri? = try {
            val schemeEnd = text.indexOf("://")
            require(schemeEnd > 0)
            val rest = text.substring(schemeEnd + 3)
            val fragmentStart = rest.indexOf('#').let { if (it < 0) rest.length else it }
            val beforeFragment = rest.substring(0, fragmentStart)
            val queryStart = beforeFragment.indexOf('?')
            val hierarchical = if (queryStart < 0) beforeFragment else beforeFragment.substring(0, queryStart)
            val rawQuery = if (queryStart < 0) null else beforeFragment.substring(queryStart + 1)
            val pathStart = hierarchical.indexOf('/').let { if (it < 0) hierarchical.length else it }
            val authority = hierarchical.substring(0, pathStart)
            val path = hierarchical.substring(pathStart)

            var host = authority.substringAfterLast('@')
            val colon = host.lastIndexOf(':')
            if (colon >= 0 && !host.startsWith("[")) {
                val port = host.substring(colon + 1)
                require(port.all { it in '0'..'9' })
                host = host.substring(0, colon)
            }
            host = percentDecode(host, plusToSpace = false).asciiLowercase()

            val segments = path.split('/').filter { it.isNotEmpty() }.map { percentDecode(it, plusToSpace = false) }
            val params = rawQuery?.split('&')?.filter { it.isNotEmpty() }?.map { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) {
                    percentDecode(pair, plusToSpace = true) to ""
                } else {
                    percentDecode(pair.substring(0, eq), true) to percentDecode(pair.substring(eq + 1), true)
                }
            }.orEmpty()
            DartLikeUri(host, segments, params)
        } catch (e: IllegalArgumentException) {
            null
        }

        /** Dart `_hexCharPairToByte`: only ASCII `0-9a-fA-F`, never other Unicode digits. */
        private fun asciiHexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

        private fun String.asciiLowercase(): String =
            String(CharArray(length) { i -> this[i].let { c -> if (c in 'A'..'Z') c + 32 else c } })

        /**
         * Dart `Uri.parse` + `decodeQueryComponent` / `decodeComponent`: valid `%XX` escapes (ASCII
         * hex only) are decoded as strict UTF-8, an invalid escape stays a literal `%`.
         */
        private fun percentDecode(s: String, plusToSpace: Boolean): String {
            if (s.indexOf('%') < 0 && (!plusToSpace || s.indexOf('+') < 0)) return s
            val out = ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '%' -> {
                        val hi = if (i + 2 < s.length) asciiHexDigit(s[i + 1]) else -1
                        val lo = if (i + 2 < s.length) asciiHexDigit(s[i + 2]) else -1
                        if (hi >= 0 && lo >= 0) {
                            out.write(hi * 16 + lo)
                            i += 3
                        } else {
                            // Dart `Uri.parse` turns an invalid escape into `%25`: a literal '%'.
                            out.write('%'.code)
                            i++
                        }
                    }
                    c == '+' && plusToSpace -> {
                        out.write(' '.code)
                        i++
                    }
                    else -> {
                        val end = if (Character.isHighSurrogate(c) && i + 1 < s.length) i + 2 else i + 1
                        out.write(DartUtf8.encode(s.substring(i, end)))
                        i = end
                    }
                }
            }
            return try {
                DartUtf8.decodeStrict(out.toByteArray())
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid UTF-8", e)
            }
        }
    }
}
