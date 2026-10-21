package space.einundzwanzig.meetup.data.mempool

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import java.io.IOException

/**
 * A mempool response with a status other than 200, or (from [MempoolService.testHost]) a 200
 * response whose body is not a positive integer (Dart `MempoolHttpException`).
 *
 * @property path API path without `/api`, e.g. `/blocks/tip/height`.
 * @property statusCode HTTP status (`200` for the "not a number" case of `testHost`).
 */
class MempoolHttpException(
    val path: String,
    val statusCode: Int,
    body: String,
) : IOException() {

    /** The body with whitespace runs collapsed to one space, trimmed, cut to 120 chars + `…`. */
    val snippet: String = snip(body)

    /**
     * Typical block answers (Cloudflare / rate limit / WAF): status 403, 429 or 503, or a
     * snippet mentioning `cloudflare` or starting an HTML document.
     */
    val looksBlocked: Boolean
        get() {
            val lower = snippet.lowercase()
            return statusCode == 403 || statusCode == 429 || statusCode == 503 ||
                lower.contains("cloudflare") || lower.contains("<!doctype html")
        }

    /** `HTTP <status> bei <path> — <snippet>` (Dart `toString()`). */
    override val message: String
        get() = "HTTP $statusCode bei $path — $snippet"

    override fun toString(): String = message

    private companion object {
        /** Dart's `\s` (JavaScript semantics): ASCII and Unicode whitespace plus the BOM. */
        val WHITESPACE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")
        const val MAX_SNIPPET = 120

        fun snip(body: String): String {
            val flat = body.replace(WHITESPACE, " ").dartTrim()
            return if (flat.length > MAX_SNIPPET) "${flat.substring(0, MAX_SNIPPET)}…" else flat
        }
    }
}
