package space.einundzwanzig.meetup.data.proofs.platform

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.crypto.Nip19

/** The parts of a `21rep::` verify string (Dart `ParsedVerifyString`). */
data class ParsedVerifyString(
    val npub: String,
    val platform: String,
    val username: String,
    /** The 128-char signature part after `sig=`. */
    val sig: String,
)

/**
 * The `21rep` v2 verify string: `21rep::<npub>::<platform>::<username>::sig=<128 hex>`
 * (spec 06 §7.1). A wire format that may be pasted from old installs: keep [parse] exactly as is.
 */
object VerifyStringCodec {

    /** Prefix of every verify string. */
    const val PREFIX: String = "21rep::"

    private const val SIG_LENGTH = 128

    /**
     * ECMAScript `\s` as used by Dart's `RegExp` (WhiteSpace + LineTerminator; unlike Dart
     * `String.trim` it does not contain U+0085).
     */
    private const val JS_SPACE = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

    /**
     * The radix-16 subset of Dart `_BigIntImpl._parseRE`
     * (`^\s*([+-]?)(…|([a-z0-9]+))\s*$`, case-insensitive): surrounding whitespace is accepted,
     * a `0x` prefix is not (the `x` is no hex digit for an explicit radix).
     */
    private val SIGNED_HEX = Regex("^$JS_SPACE*[+-]?[0-9a-fA-F]+$JS_SPACE*$")

    /** Dart `'21rep::$npub::$platform::$trimmedUsername::sig=${signed.sig}'`. */
    fun build(npub: String, platform: String, username: String, sigHex: String): String =
        "21rep::$npub::$platform::$username::sig=$sigHex"

    /**
     * Dart `parseVerifyString`, verbatim: trim, `21rep::` prefix, `split("::")` with ≥ 5 parts
     * (extra parts are ignored), `npub1` prefix, `sig=` prefix and a 128-char signature.
     * A username or platform containing `::` therefore yields an unparseable string.
     */
    fun parse(input: String): ParsedVerifyString? {
        val trimmed = input.dartTrim()
        if (!trimmed.startsWith(PREFIX)) return null
        val parts = trimmed.split("::")
        if (parts.size < 5) return null
        val npub = parts[1]
        val platform = parts[2]
        val username = parts[3]
        val sigPart = parts[4]
        if (!npub.startsWith("npub1")) return null
        if (!sigPart.startsWith("sig=")) return null
        val sig = sigPart.substring(4)
        if (sig.length != SIG_LENGTH) return null
        return ParsedVerifyString(npub = npub, platform = platform, username = username, sig = sig)
    }

    /**
     * Dart `_verifySignature`: a **format** check only (not cryptographic). The npub decodes as
     * bech32 `npub` to a non-empty hex string, the signature has 128 chars and parses as a
     * (signed) hex integer like Dart `BigInt.parse(sig, radix: 16)`, which tolerates leading and
     * trailing whitespace (e.g. `sig= <127 hex>` passes).
     */
    fun isWellFormed(parsed: ParsedVerifyString): Boolean {
        val pubkeyHex = try {
            Nip19.decodeNpub(parsed.npub)
        } catch (_: Exception) {
            return false
        }
        if (parsed.sig.length != SIG_LENGTH) return false
        if (pubkeyHex.isEmpty()) return false
        return SIGNED_HEX.matches(parsed.sig)
    }
}
