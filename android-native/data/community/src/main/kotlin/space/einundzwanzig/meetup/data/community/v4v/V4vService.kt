package space.einundzwanzig.meetup.data.community.v4v

import space.einundzwanzig.meetup.core.common.dart.dartTrim

/** Lightning address that receives Value-for-Value donations to the project (`kV4VLightningAddress`). */
const val V4V_LIGHTNING_ADDRESS: String = "wolpertinger1@timecatcher.lnbits.de"

/** Comment the V4V screen sends with every donation (en dash U+2013). */
const val V4V_DEFAULT_COMMENT: String = "V4V – 21Meetup"

/**
 * Value-for-Value donation via LNURL-pay (LUD-06/LUD-16), port of `lib/services/v4v_service.dart`
 * (spec 11 §2.10, §5 N4/N5, §8.10).
 *
 * 1. `GET https://<domain>/.well-known/lnurlp/<user>` → callback + limits
 * 2. `GET <callback>{?|&}amount=<msat>[&comment=<form-encoded>]` → `{"pr": "<bolt11>"}`
 *
 * The invoice is not checked against the request (amount/description hash), as in Flutter.
 */
interface V4vService {
    /**
     * Requests a bolt11 invoice over [amountSats] from [address]. [comment] is sent only if the
     * recipient allows comments (`commentAllowed > 0`) and is cut to that many UTF-16 units.
     * Never throws (except for cancellation).
     */
    suspend fun createInvoice(
        amountSats: Long,
        address: String = V4V_LIGHTNING_ADDRESS,
        comment: String? = null,
    ): V4vResult
}

/** Outcome of [V4vService.createInvoice] (Dart `V4VResult`). */
sealed interface V4vResult {
    /** `true` for [Success] (Dart `ok`). */
    val ok: Boolean

    /** The payable bolt11 [invoice]. */
    data class Success(val invoice: String) : V4vResult {
        override val ok: Boolean get() = true
    }

    /**
     * Failure with a machine [code] — one of the [V4vError] constants, or the verbatim `reason`
     * text of an LNURL `{"status":"ERROR"}` response.
     */
    data class Failure(val code: String) : V4vResult {
        override val ok: Boolean get() = false

        /** The UI message class for [code] (spec 11 §2.10 `_donate`). */
        val kind: V4vErrorKind get() = V4vError.kindOf(code)
    }
}

/** Error codes of [V4vResult.Failure.code] (Dart string literals). */
object V4vError {
    const val INVALID_AMOUNT: String = "invalid_amount"
    const val INVALID_ADDRESS: String = "invalid_address"
    const val UNREACHABLE: String = "unreachable"
    const val NOT_LNURLP: String = "not_lnurlp"
    const val BELOW_MIN: String = "below_min"
    const val ABOVE_MAX: String = "above_max"
    const val INVOICE_FAILED: String = "invoice_failed"

    /** Maps a code to the message the V4V screen shows; everything unknown is [V4vErrorKind.Generic]. */
    fun kindOf(code: String): V4vErrorKind = when (code) {
        INVALID_AMOUNT -> V4vErrorKind.InvalidAmount
        BELOW_MIN -> V4vErrorKind.BelowMin
        ABOVE_MAX -> V4vErrorKind.AboveMax
        UNREACHABLE -> V4vErrorKind.Unreachable
        else -> V4vErrorKind.Generic
    }
}

/**
 * UI message class of a V4V failure. Feature mapping (ARB keys): InvalidAmount → `v4vErrInvalidAmount`,
 * BelowMin → `v4vErrBelowMin`, AboveMax → `v4vErrAboveMax`, Unreachable → `v4vErrUnreachable`,
 * Generic → `v4vErrGeneric`.
 */
enum class V4vErrorKind { InvalidAmount, BelowMin, AboveMax, Unreachable, Generic }

/**
 * Parses the V4V amount field exactly like `_donate()`: `trim`, drop `.` and `,`, `int.tryParse`,
 * `0` on failure. A result ≤ 0 means "invalid amount".
 */
fun parseV4vAmount(text: String): Long {
    val raw = text.dartTrim()
        .replace(".", "")
        .replace(",", "")
    return parseDartInt(raw) ?: 0L
}

/**
 * Dart `int.tryParse` for decimal input: optional sign, ASCII digits, surrounding Dart whitespace
 * allowed; `null` on overflow (Dart: values outside 64 bits fail) or anything else. Dart's `0x`
 * hex form is not accepted; the amount field only lets digits through.
 */
private fun parseDartInt(s: String): Long? {
    val t = s.dartTrim()
    if (!DART_INT.matches(t)) return null
    return t.toLongOrNull()
}

private val DART_INT = Regex("[+-]?[0-9]+")
