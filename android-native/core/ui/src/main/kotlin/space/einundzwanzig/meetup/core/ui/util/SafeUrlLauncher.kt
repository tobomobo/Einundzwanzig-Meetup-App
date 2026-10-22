package space.einundzwanzig.meetup.core.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Scheme whitelist for links from foreign content (Security Audit M2, port of
 * `lib/services/safe_url_launcher.dart`): Markdown, calendar events, chat and portal data may
 * only open `http(s)` URLs with a host. `javascript:`, `file:`, `intent:`, `tel:`, app schemes
 * etc. are rejected. Hard-coded own schemes (`nostr:`, `nostrsigner:`) do not go through here.
 */
object SafeUrl {
    private val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*$")

    /** True if [url] (trimmed) is an `http`/`https` URL (scheme case-insensitive) with a host. */
    fun isHttpUrl(url: String): Boolean = httpHost(url) != null

    /** The host of an http(s) [url], or null if the URL is not allowed. */
    fun httpHost(url: String): String? {
        val value = url.trim()
        val colon = value.indexOf(':')
        if (colon <= 0) return null
        val scheme = value.substring(0, colon)
        if (!schemePattern.matches(scheme)) return null
        val lower = scheme.lowercase()
        if (lower != "http" && lower != "https") return null
        val rest = value.substring(colon + 1)
        if (!rest.startsWith("//")) return null
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#'), startIndex = 2).let { if (it < 0) rest.length else it }
        val authority = rest.substring(2, authorityEnd)
        val hostAndPort = authority.substringAfterLast('@')
        val host = if (hostAndPort.startsWith("[")) {
            val close = hostAndPort.indexOf(']')
            if (close < 0) return null
            val port = hostAndPort.substring(close + 1)
            if (port.isNotEmpty() && !(port.startsWith(":") && port.drop(1).all { it in '0'..'9' })) return null
            hostAndPort.substring(0, close + 1)
        } else {
            val portSep = hostAndPort.lastIndexOf(':')
            if (portSep >= 0) {
                if (!hostAndPort.substring(portSep + 1).all { it in '0'..'9' }) return null
                hostAndPort.substring(0, portSep)
            } else {
                hostAndPort
            }
        }
        return host.takeIf { it.isNotEmpty() && it != "[]" }
    }
}

/** Opens whitelisted links in an external app (browser). */
object SafeUrlLauncher {
    /**
     * Opens [url] with `ACTION_VIEW` if [SafeUrl.isHttpUrl]. Returns false for a rejected
     * scheme, a broken URL or when no app can handle it (Flutter `launchHttpUrl`).
     */
    fun open(context: Context, url: String): Boolean {
        if (!SafeUrl.isHttpUrl(url)) return false
        val intent = Intent(Intent.ACTION_VIEW, url.trim().toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            UiLog.warn("SafeUrl", "Keine App für Link von ${SafeUrl.httpHost(url)}", e)
            false
        } catch (e: SecurityException) {
            UiLog.warn("SafeUrl", "Link konnte nicht geöffnet werden (${SafeUrl.httpHost(url)})", e)
            false
        }
    }
}
