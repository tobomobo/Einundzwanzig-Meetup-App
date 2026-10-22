package space.einundzwanzig.meetup.data.mempool.format

import space.einundzwanzig.meetup.core.common.dart.dartTrim

private val DECIMAL = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
private val SPECIAL = Regex("^[+-]?(NaN|Infinity)$")

/**
 * Dart `double.tryParse(s)`: surrounding whitespace is ignored; accepts `1.`, `.5`, `+5`,
 * `1e3`, `NaN`, `Infinity`; rejects `1.2.3`, `.`, `-`, `0x10`, `1d`, `1_000`, `""`.
 * (Kotlin's `toDoubleOrNull` would accept `1d` and hex floats.)
 */
fun dartTryParseDouble(s: String): Double? {
    val t = s.dartTrim()
    if (DECIMAL.matches(t)) return t.toDouble()
    if (SPECIAL.matches(t)) {
        val negative = t.startsWith("-")
        return when {
            t.endsWith("NaN") -> Double.NaN
            negative -> Double.NEGATIVE_INFINITY
            else -> Double.POSITIVE_INFINITY
        }
    }
    return null
}

private val DART_INT = Regex("^([+-]?)(?:0[xX]([0-9a-fA-F]+)|([0-9]+))$")

/**
 * Dart (VM, 64-bit) `int.tryParse(s)`: surrounding whitespace is ignored; optional sign;
 * ASCII decimal digits or `0x` hex. Decimal values outside the signed 64-bit range give `null`;
 * hex magnitudes below 2^64 wrap to two's complement as in Dart. Non-ASCII digits (`"٩"`),
 * `_` separators and empty input give `null` (Kotlin's `toLongOrNull` accepts Unicode digits).
 */
internal fun dartTryParseInt(s: String): Long? {
    val m = DART_INT.matchEntire(s.dartTrim()) ?: return null
    val negative = m.groupValues[1] == "-"
    val hex = m.groupValues[2]
    val magnitude = if (hex.isNotEmpty()) java.math.BigInteger(hex, 16) else java.math.BigInteger(m.groupValues[3])
    val value = if (negative) magnitude.negate() else magnitude
    return when {
        value.bitLength() < 64 -> value.toLong()
        hex.isNotEmpty() && !negative && magnitude.bitLength() <= 64 -> magnitude.toLong()
        else -> null
    }
}
