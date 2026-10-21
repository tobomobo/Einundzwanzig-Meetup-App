package space.einundzwanzig.meetup.core.network

import okhttp3.Headers
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Outcome of a capped HTTP exchange ([getCapped], [postJson], [sendJson], [executeCapped]).
 * Never thrown; callers `when` over it.
 */
sealed interface HttpResult {

    /**
     * The server answered with [status] (any status, not only 2xx) and a body of at most the
     * requested cap. [body] is the decoded (un-gzipped) payload.
     */
    class Response(
        val status: Int,
        val headers: Headers,
        val body: ByteArray,
        /** The final URL after redirects. */
        val url: String,
    ) : HttpResult {
        /** `true` for 2xx. Many specs only accept exactly 200; check [status] for those. */
        val isSuccessful: Boolean get() = status in 200..299

        /**
         * [body] decoded as UTF-8 regardless of the `Content-Type` charset; malformed sequences
         * become U+FFFD (Dart `utf8.decode(bodyBytes, allowMalformed: true)`). For strict
         * decoding use `body.decodeToString(throwOnInvalidSequence = true)`.
         */
        fun text(): String = body.decodeToString()

        /** The first value of header [name] (case-insensitive), or `null`. */
        fun header(name: String): String? = headers[name]

        override fun toString(): String = "Response(status=$status, bytes=${body.size})"
    }

    /** The body exceeded the cap of [maxBytes]; reading was aborted. [status] is the HTTP status. */
    data class TooLarge(val status: Int, val maxBytes: Long) : HttpResult

    /** No usable response: invalid request, DNS/connect/TLS error, timeout, cancellation, policy. */
    data class Failure(val cause: IOException) : HttpResult {
        /** `true` if the call or a socket read/connect timed out. */
        val isTimeout: Boolean get() = cause is InterruptedIOException
    }
}

/** The [HttpResult.Response] if this result is one with exactly [status], else `null`. */
fun HttpResult.responseWithStatus(status: Int): HttpResult.Response? =
    (this as? HttpResult.Response)?.takeIf { it.status == status }
