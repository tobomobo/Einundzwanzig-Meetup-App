@file:JvmName("DartCompat")

package space.einundzwanzig.meetup.core.common.dart

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.truncate

// Dart VM number and string semantics that Kotlin/Java do differently. Everything here was
// checked against Dart 3.13.5 (see DartCompatTest for the vectors).

/**
 * Dart (VM) `double.toString()`.
 *
 * Shortest digits that read back to the same double (closest one if there are several),
 * printed in plain notation while the decimal exponent is in `-6..20`, otherwise as
 * `d.ddde±x`. Plain integers keep a trailing `.0`.
 *
 * `0.0`, `-0.0`, `100.0`, `0.000001`, `1e-7`, `48.2`, `100000000000000000000.0`, `1e+21`,
 * `NaN`, `Infinity`, `-Infinity`.
 *
 * Java's `Double.toString` is not a substitute: it switches to `E` notation at 1e7, writes
 * `1.0E-7` and, before JDK 19 and on Android, is not always the shortest representation.
 */
fun Double.toDartString(): String {
    if (isNaN()) return "NaN"
    if (isInfinite()) return if (this > 0) "Infinity" else "-Infinity"
    if (this == 0.0) return if (1.0 / this < 0) "-0.0" else "0.0"
    val sign = if (this < 0) "-" else ""
    val (digits, decimalPoint) = shortestDigits(abs(this))
    val exponent = decimalPoint - 1
    val body = if (exponent in -6 until 21) {
        when {
            decimalPoint <= 0 -> "0." + "0".repeat(-decimalPoint) + digits
            decimalPoint >= digits.length -> digits + "0".repeat(decimalPoint - digits.length) + ".0"
            else -> digits.substring(0, decimalPoint) + "." + digits.substring(decimalPoint)
        }
    } else {
        val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
        mantissa + "e" + (if (exponent < 0) "-" else "+") + abs(exponent)
    }
    return sign + body
}

/**
 * Shortest decimal digits of a positive finite [value]: returns `digits` (no leading or
 * trailing zeros) and the decimal point position, so that `value ≈ 0.digits × 10^point`.
 *
 * For each precision the decimal closest to the exact binary value is tried first; at a power
 * of two the round-trip interval is asymmetric, so the neighbour on the other side is tried as
 * well before moving to the next precision. This is what double-conversion's `ToShortest`
 * (used by the Dart VM) produces.
 */
private fun shortestDigits(value: Double): Pair<String, Int> {
    val exact = BigDecimal(value)
    for (precision in 1..17) {
        val nearest = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
        val chosen = when {
            roundTrips(nearest, value) -> nearest
            else -> {
                val step = nearest.ulp()
                val other = if (nearest < exact) nearest + step else nearest - step
                other.takeIf { it.signum() > 0 && roundTrips(it, value) }
            }
        } ?: continue
        val stripped = chosen.stripTrailingZeros()
        val digits = stripped.unscaledValue().toString()
        return digits to (digits.length - stripped.scale())
    }
    // Unreachable: 17 significant digits always identify a double.
    val stripped = exact.round(MathContext(17, RoundingMode.HALF_EVEN)).stripTrailingZeros()
    val digits = stripped.unscaledValue().toString()
    return digits to (digits.length - stripped.scale())
}

private fun roundTrips(candidate: BigDecimal, value: Double): Boolean =
    candidate.toString().toDouble() == value

/**
 * Dart `double.toStringAsFixed(fractionDigits)`.
 *
 * Rounds the **exact binary value** half away from zero (`2.75 → "2.8"`, `0.35 → "0.3"`,
 * `1.005 → "1.00"`, `0.125 → "0.13"`), keeps the minus sign of negative values that round to
 * zero (`-0.001 → "-0.00"`, `-0.0 → "-0.0"`), always uses `.`, and falls back to
 * [toDartString] for `|x| >= 1e21`, NaN and infinities.
 *
 * Do not use `String.format("%.1f")`: it is locale dependent and rounds the shortest decimal
 * representation instead of the binary value.
 *
 * @throws IllegalArgumentException if [fractionDigits] is outside `0..20` (Dart: RangeError).
 */
fun Double.toStringAsFixed(fractionDigits: Int): String {
    require(fractionDigits in 0..20) { "fractionDigits must be in 0..20: $fractionDigits" }
    if (isNaN() || isInfinite() || abs(this) >= 1e21) return toDartString()
    val digits = BigDecimal(abs(this)).setScale(fractionDigits, RoundingMode.HALF_UP).toPlainString()
    val negative = this < 0 || (this == 0.0 && 1.0 / this < 0)
    return if (negative) "-$digits" else digits
}

/** Dart `int.toStringAsFixed` (converts to double first, exactly like Dart). */
fun Long.toStringAsFixed(fractionDigits: Int): String = toDouble().toStringAsFixed(fractionDigits)

/** Dart `int.toStringAsFixed` (converts to double first, exactly like Dart). */
fun Int.toStringAsFixed(fractionDigits: Int): String = toDouble().toStringAsFixed(fractionDigits)

/**
 * Dart `double.round()`: half away from zero (`2.5 → 3`, `-2.5 → -3`,
 * `0.49999999999999994 → 0`), saturating at the 64-bit range like the Dart VM.
 *
 * Kotlin's `roundToLong()`/`Math.round` round half towards positive infinity (`-2.5 → -2`),
 * `kotlin.math.round` rounds half to even.
 *
 * @throws UnsupportedOperationException for NaN and infinities (Dart: UnsupportedError).
 */
fun Double.dartRound(): Long {
    if (isNaN() || isInfinite()) throw UnsupportedOperationException("$this.round()")
    val whole = truncate(this)
    val fraction = this - whole // exact for every double
    val rounded = if (abs(fraction) >= 0.5) whole + (if (this < 0) -1.0 else 1.0) else whole
    return rounded.toLong()
}

/**
 * Dart `double.toInt()` / `num.toInt()`: truncates towards zero, saturating at the 64-bit
 * range like the Dart VM.
 *
 * @throws UnsupportedOperationException for NaN and infinities (Kotlin would return 0/MAX).
 */
fun Double.dartToInt(): Long {
    if (isNaN() || isInfinite()) throw UnsupportedOperationException("$this.toInt()")
    return toLong()
}

/**
 * Dart `String.trim()`: strips Unicode `White_Space` plus the BOM from both ends.
 *
 * Kotlin's `trim()` differs: it also strips U+001C..U+001F and keeps U+0085 and U+FEFF.
 */
fun String.dartTrim(): String {
    var start = 0
    var end = length
    while (start < end && isDartWhitespace(this[start])) start++
    while (end > start && isDartWhitespace(this[end - 1])) end--
    return if (start == 0 && end == length) this else substring(start, end)
}

/** The characters Dart's `String.trim()` removes. */
fun isDartWhitespace(c: Char): Boolean = when (c.code) {
    in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF,
    -> true
    else -> false
}
