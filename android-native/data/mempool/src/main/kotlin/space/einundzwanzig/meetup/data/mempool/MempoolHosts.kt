package space.einundzwanzig.meetup.data.mempool

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Host constants and the pure host helpers of `lib/services/mempool_config.dart` (spec 13 §5.1, §7.1).
 *
 * Hosts are base URLs **without** `/api` and without a trailing slash; the API base is `"$host/api"`.
 */
object MempoolHosts {
    /** Default clearnet instance. */
    const val CLEARNET: String = "https://mempool.space"

    /** Official mempool.space onion (from its `onion-location` header). Plain `http`, not https. */
    const val TOR: String = "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"

    /** Public mirrors tried in this order when mempool.space delivers nothing (clearnet mode only). */
    val FALLBACKS: List<String> = listOf("https://mempool.emzy.de", "https://mempool.ninja")

    /** Whole-call timeout for clearnet hosts. */
    val CLEARNET_TIMEOUT: Duration = 20.seconds

    /** Whole-call timeout for onion hosts (Tor circuits are slow). */
    val ONION_TIMEOUT: Duration = 45.seconds

    /**
     * Dart `MempoolConfig.normalize`, verbatim: trims, returns [CLEARNET] for empty input, adds
     * `http://` for `.onion` and `https://` otherwise when no (lower-case) scheme is present,
     * strips trailing slashes, strips one trailing `/api` (case-insensitive), strips trailing
     * slashes again.
     */
    fun normalize(raw: String): String {
        var s = raw.dartTrim()
        if (s.isEmpty()) return CLEARNET
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = if (s.contains(".onion")) "http://$s" else "https://$s"
        }
        s = s.trimEnd('/')
        if (s.lowercase().endsWith("/api")) s = s.substring(0, s.length - 4)
        return s.trimEnd('/')
    }

    /**
     * Dart `MempoolConfig.looksValid`: the normalized URL has a non-empty host containing a `.`.
     * Note the quirks kept for parity: `""` is valid (it normalizes to [CLEARNET]),
     * `HTTPS://x.y` is invalid, raw LAN IPs are valid, IPv6 literals are invalid.
     */
    fun looksValid(raw: String): Boolean {
        val host = hostOf(normalize(raw)) ?: return false
        return host.isNotEmpty() && host.contains('.')
    }

    /** `true` if [host] contains `.onion` (Dart `host.contains('.onion')`, case-sensitive). */
    fun isOnion(host: String): Boolean = host.contains(".onion")

    /** [ONION_TIMEOUT] for onion hosts, else [CLEARNET_TIMEOUT]. */
    fun timeoutFor(host: String): Duration = if (isOnion(host)) ONION_TIMEOUT else CLEARNET_TIMEOUT

    /**
     * Dashboard footer label (Dart `_hostLabel`): scheme stripped (first `https://`, then first
     * `http://`), hosts longer than 28 chars shortened to `first12…last12`.
     */
    fun label(host: String): String {
        val h = host.replaceFirst("https://", "").replaceFirst("http://", "")
        return if (h.length > 28) "${h.substring(0, 12)}…${h.substring(h.length - 12)}" else h
    }

    /**
     * Host part the way Dart's `Uri.tryParse(s).host` sees it for the URLs [normalize] produces;
     * `null` when Dart's parser would reject the URL (non-numeric port, unterminated IPv6).
     */
    internal fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val rest = url.substring(schemeEnd + 3)
        val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        var authority = rest.substring(0, end)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val port = authority.substring(close + 1)
            if (port.isNotEmpty() && !(port.startsWith(":") && port.drop(1).all { it in '0'..'9' })) return null
            return authority.substring(1, close)
        }
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            val port = authority.substring(colon + 1)
            if (!port.all { it in '0'..'9' }) return null
            authority = authority.substring(0, colon)
        }
        return authority
    }
}
