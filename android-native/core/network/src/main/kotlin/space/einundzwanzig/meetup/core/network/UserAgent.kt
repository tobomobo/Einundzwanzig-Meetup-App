package space.einundzwanzig.meetup.core.network

import okhttp3.Interceptor
import okhttp3.Response

/** Builds the default User-Agent `Einundzwanzig-Meetup-App/<versionName> (Android)`. */
object UserAgent {
    /** Product token of the default User-Agent. */
    const val PRODUCT = "Einundzwanzig-Meetup-App"

    /**
     * The default User-Agent for [versionName]. Whitespace and characters that are not
     * printable ASCII (not allowed in header values) become `_`; a blank version is `unknown`.
     */
    fun forVersion(versionName: String?): String {
        val version = versionName?.trim().orEmpty().ifEmpty { "unknown" }
            .map { if (it in '!'..'~') it else '_' }
            .joinToString("")
        return "$PRODUCT/$version (Android)"
    }
}

/**
 * Adds [userAgent] to requests that do not set their own `User-Agent`. Specs that require a
 * byte-exact UA (mempool `21Meetup/1.3 (Einundzwanzig Meetup App)`, Nominatim, news feed) set the
 * header on the request and win.
 */
internal class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
    }
}
