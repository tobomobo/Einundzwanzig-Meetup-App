package space.einundzwanzig.meetup.core.nostr.relay

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.nostr.internal.DartRelayUri
import space.einundzwanzig.meetup.core.nostr.internal.dartIntTryParse as dartIntTryParseImpl

/**
 * The two relay URL validators of the Flutter app (spec 07 §8.2). They differ on purpose; keep
 * both. Both follow Dart's `Uri.tryParse` (lower-cased scheme and host, percent-encoding,
 * dot-segment removal), not `java.net.URI`.
 */
object RelayUrls {

    /**
     * User input check of the relay settings (`RelayConfig.isWellFormedRelayUrl`), on the
     * Dart-trimmed value: literally starts with `wss://`; parses; host non-empty, contains `.`,
     * does not start or end with `.`; path empty or `/`; no query; no fragment. User-info and
     * port are not checked.
     */
    fun isWellFormedRelayUrl(url: String): Boolean {
        val trimmed = url.dartTrim()
        if (!trimmed.startsWith("wss://")) return false
        val uri = DartRelayUri.parse(trimmed) ?: return false
        if (uri.scheme != "wss") return false
        val host = uri.host
        if (host.isEmpty()) return false
        if (!host.contains('.')) return false
        if (host.startsWith('.') || host.endsWith('.')) return false
        if (host.contains(' ')) return false
        if (uri.path.isNotEmpty() && uri.path != "/") return false
        if (uri.hasQuery || uri.hasFragment) return false
        return true
    }

    /**
     * Profile-lookup normalisation (`NostrProfileLookup.relayUrl`): `null` unless the value is
     * a `wss` URI with a host, no user-info and no fragment. With [discovered] (relays taken
     * from someone's NIP-65 list) local names, private/reserved IPv4 literals and IPv6 literals
     * are rejected as well. Returns Dart's `uri.toString()`, with a lone `/` path removed
     * (`wss://relay.example/` → `wss://relay.example`).
     */
    fun normalize(value: String, discovered: Boolean = false): String? {
        val uri = DartRelayUri.parse(value.dartTrim()) ?: return null
        if (uri.scheme != "wss" || uri.host.isEmpty() || uri.userInfo.isNotEmpty() || uri.hasFragment) return null
        val host = uri.host.lowercase().removeSuffix(".")
        if (discovered && !isPublicDiscoveredHost(host)) return null
        return if (uri.path == "/" && !uri.hasQuery) uri.copy(path = "").toString() else uri.toString()
    }

    private fun isPublicDiscoveredHost(host: String): Boolean {
        if (!host.contains('.') || host == "localhost" || host.endsWith(".local") ||
            host.endsWith(".localdomain") || host.endsWith(".localhost") ||
            host.contains(':') || host.contains('[')
        ) {
            return false
        }
        val parts = host.split('.').map(::dartIntTryParse)
        if (parts.all { it != null }) {
            if (parts.size != 4 || parts.any { it!! < 0 || it > 255 }) return false
            val a = parts[0]!!
            val b = parts[1]!!
            if (a == 0L || a == 10L || a == 127L || a >= 224 || (a == 169L && b == 254L) ||
                (a == 172L && b in 16..31) || (a == 192L && b == 168L) || (a == 100L && b in 64..127)
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Dart `int.tryParse(s)` (see the internal `dartIntTryParse`): `null` for decimal overflow
     * and hex beyond 64 bit, so such a label makes the host *not* an IPv4 literal, as in Dart.
     */
    internal fun dartIntTryParse(s: String): Long? = dartIntTryParseImpl(s)
}
