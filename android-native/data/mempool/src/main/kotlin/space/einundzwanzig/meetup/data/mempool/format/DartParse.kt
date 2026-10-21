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
