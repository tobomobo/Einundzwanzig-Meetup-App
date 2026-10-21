package space.einundzwanzig.meetup.data.mempool

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.data.mempool.internal.DartUriHost
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
     * strips trailing slashes, strips one trailing `/api` (case-insensitive with Dart's
     * per-character mapping, so `/APİ` counts too), strips trailing slashes again.
     */
    fun normalize(raw: String): String {
        var s = raw.dartTrim()
        if (s.isEmpty()) return CLEARNET
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = if (s.contains(".onion")) "http://$s" else "https://$s"
        }
        s = s.trimEnd('/')
        // Dart `s.toLowerCase().endsWith('/api')`. Dart maps each UTF-16 unit on its own
        // ('İ' -> 'i'); Java's full mapping would turn 'İ' into two chars.
        if (s.length >= 4 && s.regionMatchesDartLower(s.length - 4, "/api")) s = s.substring(0, s.length - 4)
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
     * Dart `Uri.tryParse(url)?.host` (see [DartUriHost]); `null` when Dart's parser rejects [url].
     */
    internal fun hostOf(url: String): String? = DartUriHost.hostOrNull(url)

    /**
     * [host] for diagnostic log lines: any user info (`user:pass@`) in the authority is
     * dropped so credentials of a custom instance never reach the exportable log. The
     * persisted value and the request URL are not affected.
     */
    internal fun forLog(host: String): String {
        val schemeEnd = host.indexOf("://")
        val authorityStart = if (schemeEnd < 0) 0 else schemeEnd + 3
        val authorityEnd = host.indexOfAny(charArrayOf('/', '?', '#', '\\'), authorityStart).let { if (it < 0) host.length else it }
        val at = host.lastIndexOf('@', authorityEnd - 1)
        if (at < authorityStart) return host
        return host.substring(0, authorityStart) + host.substring(at + 1)
    }

    /** `true` if `this[start..]` lower-cased per UTF-16 unit (Dart `toLowerCase`) equals [lower]. */
    private fun String.regionMatchesDartLower(start: Int, lower: String): Boolean =
        lower.indices.all { this[start + it].lowercaseChar() == lower[it] }
}
