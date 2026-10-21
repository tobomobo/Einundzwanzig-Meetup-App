package space.einundzwanzig.meetup.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.net.UnknownServiceException

/**
 * Which hosts may be reached over plain `http://`.
 *
 * Mirrors the app's `network_security_config.xml` (spec 13 §5.8), which Android enforces for
 * OkHttp on the device: cleartext is forbidden except for `*.onion` (the official mempool.space
 * Tor host is `http://…onion`, reached through Orbot), `*.local` and the loopback names
 * `127.0.0.1` / `localhost`. Raw LAN IPs are intentionally blocked. The [CleartextGuard]
 * enforces the same rule inside OkHttp, so it holds in JVM tests and if the manifest config
 * ever regresses.
 */
object CleartextPolicy {

    /** Whether a cleartext request to [host] is allowed. Matching is case-insensitive. */
    fun isCleartextPermitted(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == "onion" || h.endsWith(".onion") ||
            h == "local" || h.endsWith(".local") ||
            h == "127.0.0.1" || h == "localhost"
    }
}

/**
 * Rejects `http://` requests to hosts not allowed by [CleartextPolicy] with the same exception
 * type OkHttp/Android use (`UnknownServiceException: CLEARTEXT communication … not permitted`).
 *
 * Installed as an application interceptor (fails before connecting) and as a network
 * interceptor (also covers redirect hops, before any request byte is written).
 */
internal class CleartextGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !CleartextPolicy.isCleartextPermitted(url.host)) {
            throw UnknownServiceException("CLEARTEXT communication to ${url.host} not permitted by app policy")
        }
        return chain.proceed(chain.request())
    }
}
