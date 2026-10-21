package space.einundzwanzig.meetup.data.mempool.currency

import space.einundzwanzig.meetup.core.common.dart.dartRound
import space.einundzwanzig.meetup.data.mempool.MempoolService

/**
 * Pure currency helpers of `lib/services/currency_service.dart` (spec 13 §3.4, §7.2).
 * Grouping is always `.` regardless of the app language; never use `NumberFormat`.
 */
object CurrencyFormat {
    /** Default display currency. */
    const val FALLBACK: String = "EUR"

    /** Selectable currencies in UI order (= [MempoolService.SUPPORTED_CURRENCIES]). */
    val supported: List<String> get() = MempoolService.SUPPORTED_CURRENCIES

    /** Whether [code] is selectable. */
    fun isSupported(code: String): Boolean = code in MempoolService.SUPPORTED_CURRENCIES

    /** Display symbol: `€ $ £ ¥ CA$ A$`; CHF and unknown codes return the code itself. */
    fun symbol(code: String): String = when (code) {
        "EUR" -> "€"
        "USD" -> "$"
        "GBP" -> "£"
        "JPY" -> "¥"
        "CAD" -> "CA$"
        "AUD" -> "A$"
        else -> code
    }

    /** Integer with `.` every three digits, negatives keep their sign: `95000 → "95.000"`, `-1234 → "-1.234"`. */
    fun groupInt(v: Long): String {
        val digits = if (v == Long.MIN_VALUE) "9223372036854775808" else kotlin.math.abs(v).toString()
        val sb = StringBuilder(digits.length + digits.length / 3 + 1)
        if (v < 0) sb.append('-')
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append('.')
            sb.append(c)
        }
        return sb.toString()
    }

    /** [groupInt] for Int values. */
    fun groupInt(v: Int): String = groupInt(v.toLong())

    /** Price with symbol, rounded half away from zero: `formatPrice(95000.4, "EUR") == "95.000 €"`. */
    fun formatPrice(price: Double, code: String): String = "${groupInt(price.dartRound())} ${symbol(code)}"
}
