package space.einundzwanzig.meetup.core.common.log

import java.security.MessageDigest

/**
 * Last line of defence against secrets in the diagnostic log (Security Audit H1/N5). Every
 * message and tag passes through [redact] before it is stored, shown or echoed to logcat.
 *
 * Callers must still never log secrets on purpose — this catches mistakes:
 *
 * - bech32 secrets: `nsec1…`, `ncryptsec1…` (any case);
 * - URL parameters with secret names (`secret=`, `token=`, `password=`, `key=`, `code=`, …),
 *   which covers `bunker://…&secret=…` and `nostrconnect://…&secret=…`;
 * - HTTP credentials: `Bearer …`, `Basic …`, NIP-98 `Nostr eyJ…`;
 * - labelled values: `privkey=…`, `"private_key": "…"`, `secret: …`, `token: …`, `seed: …`,
 *   `password: …`, `nsec: …` (value of 6+ characters);
 * - **registered secrets** ([registerSecret]): exact occurrences of values the app knows are
 *   secret, such as the active private key hex (which a pattern cannot tell apart from a public
 *   key or event id), a NIP-46 connection secret or the portal token.
 *
 * Registered secrets are not kept in clear text: only their length, a 64-bit rolling hash and a
 * SHA-256 digest are stored (case-insensitive for ASCII letters, so upper-case hex is caught).
 *
 * Thread-safe.
 */
class SecretRedactor {

    private class Registered(val rolling: Long, val digest: ByteArray)

    private val lock = Any()

    @Volatile
    private var registered: Map<Int, List<Registered>> = emptyMap()

    /** Returns [text] with every recognised secret replaced by [REDACTED]. */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = redactRegistered(text)
        for ((pattern, replacement) in RULES) {
            if (pattern.containsMatchIn(out)) out = pattern.replace(out, replacement)
        }
        return out
    }

    /**
     * Redacts every later occurrence of [secret] (ASCII case-insensitive). Values shorter than
     * [MIN_REGISTERED_LENGTH] characters are ignored (too many false positives).
     */
    fun registerSecret(secret: String) {
        if (secret.length < MIN_REGISTERED_LENGTH) return
        val normalized = lowerAscii(secret)
        val entry = Registered(rollingHash(normalized, 0, normalized.length), sha256(normalized))
        synchronized(lock) {
            val sameLength = registered[normalized.length].orEmpty()
            if (sameLength.any { MessageDigest.isEqual(it.digest, entry.digest) }) return
            registered = registered + (normalized.length to sameLength + entry)
        }
    }

    /** Stops redacting [secret] (e.g. after the key was deleted). */
    fun unregisterSecret(secret: String) {
        if (secret.length < MIN_REGISTERED_LENGTH) return
        val digest = sha256(lowerAscii(secret))
        synchronized(lock) {
            val remaining = registered[secret.length].orEmpty().filterNot { MessageDigest.isEqual(it.digest, digest) }
            registered = if (remaining.isEmpty()) registered - secret.length else registered + (secret.length to remaining)
        }
    }

    /** Forgets all registered secrets (app reset). */
    fun clearRegisteredSecrets() {
        synchronized(lock) { registered = emptyMap() }
    }

    private fun redactRegistered(text: String): String {
        val table = registered
        if (table.isEmpty()) return text
        val lower = lowerAscii(text)
        var hits: MutableList<IntRange>? = null
        for ((length, secrets) in table) {
            if (length > lower.length) continue
            val power = pow(length - 1)
            var hash = rollingHash(lower, 0, length)
            var i = 0
            while (true) {
                if (secrets.any { it.rolling == hash }) {
                    val digest = sha256(lower.substring(i, i + length))
                    if (secrets.any { it.rolling == hash && MessageDigest.isEqual(it.digest, digest) }) {
                        (hits ?: mutableListOf<IntRange>().also { hits = it }) += i until i + length
                    }
                }
                if (i + length >= lower.length) break
                hash = (hash - lower[i].code * power) * BASE + lower[i + length].code
                i++
            }
        }
        val ranges = hits ?: return text
        ranges.sortBy { it.first }
        val out = StringBuilder(text.length)
        var cursor = 0
        var openEnd = -1
        for (range in ranges) {
            if (range.first <= openEnd) {
                openEnd = maxOf(openEnd, range.last)
                continue
            }
            if (openEnd >= 0) cursor = openEnd + 1
            out.append(text, cursor, range.first).append(REDACTED)
            openEnd = range.last
        }
        if (openEnd >= 0) cursor = openEnd + 1
        out.append(text, cursor, text.length)
        return out.toString()
    }

    companion object {
        /** Replacement text for a removed secret. */
        const val REDACTED = "[REDACTED]"

        /** Shortest value [registerSecret] accepts. */
        const val MIN_REGISTERED_LENGTH = 8

        /** The process-wide instance used by [AppLogger]. */
        val Default: SecretRedactor = SecretRedactor()

        private const val BASE = 1_000_003L

        private val RULES: List<Pair<Regex, String>> = listOf(
            // NIP-19 / NIP-49 secrets. Real values are 58+ chars after the prefix.
            Regex("""(?i)(nsec1|ncryptsec1)[ac-hj-np-z02-9]{20,}""") to "$1$REDACTED",
            // Secret-looking URL parameters (bunker:// and nostrconnect:// carry `secret=`).
            Regex(
                """(?i)([?&#;]\s*(?:secret|token|access_token|refresh_token|id_token|auth_token|auth|password|passwd|pwd|""" +
                    """pass|apikey|api_key|key|privkey|private_key|nsec|session|code)=)[^&#\s"'<>]+""",
            ) to "$1$REDACTED",
            // HTTP credentials. NIP-98 headers are base64 JSON, so they start with "eyJ".
            Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=|-]{8,}""") to "$1 $REDACTED",
            Regex("""\b(Nostr)\s+eyJ[A-Za-z0-9+/=_-]{16,}""") to "$1 $REDACTED",
            // Labelled values in prose, key=value and JSON.
            Regex(
                """(?i)(\b(?:priv(?:ate)?[_\- ]?key|privkey|priv[_\- ]?hex|secret[_\- ]?key|client[_\- ]?secret|secret|seed|""" +
                    """mnemonic|passphrase|password|passwort|nsec|token|access[_\- ]?token|api[_\- ]?key)\b["']?\s*[:=]\s*["']?)""" +
                    """(?!\[REDACTED])[^\s"',;&}\]]{6,}""",
            ) to "$1$REDACTED",
        )

        private fun lowerAscii(s: String): String {
            if (s.none { it in 'A'..'Z' }) return s
            val chars = CharArray(s.length) { i -> s[i].let { if (it in 'A'..'Z') it + 32 else it } }
            return String(chars)
        }

        private fun rollingHash(s: String, start: Int, length: Int): Long {
            var h = 0L
            for (i in start until start + length) h = h * BASE + s[i].code
            return h
        }

        private fun pow(exponent: Int): Long {
            var result = 1L
            repeat(exponent) { result *= BASE }
            return result
        }

        private fun sha256(s: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    }
}
