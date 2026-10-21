package space.einundzwanzig.meetup.data.calendar.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber
import java.math.BigInteger

/** `true` for a missing value or JSON `null` (Dart `x == null`). */
internal val JsonElement?.isDartNull: Boolean get() = this == null || this is JsonNull

/**
 * Dart `value.toString()` of a decoded JSON value: strings verbatim, ints/doubles as Dart prints
 * them, booleans `true`/`false`, `null` → `"null"`. Objects/arrays fall back to their JSON text
 * (Dart would print `{a: 1}`; only reachable with malformed data).
 */
internal fun JsonElement.dartToString(): String = when (this) {
    JsonNull -> "null"
    is JsonPrimitive -> if (isString) {
        content
    } else {
        when (val n = runCatching { DartJson.numberOf(this) }.getOrNull()) {
            is DartNumber.Int -> n.value.toString()
            is DartNumber.Double -> n.value.toDartString()
            null -> content
        }
    }
    else -> DartJson.encode(this)
}

/** Dart `(json[key] ?? '').toString()`. */
internal fun JsonObject.stringOrEmpty(key: String): String =
    this[key].takeUnless { it.isDartNull }?.dartToString() ?: ""

/** Dart `json[key] is int ? json[key] : null`, narrowed to [Int]. */
internal fun JsonElement?.dartIntValue(): Int? {
    if (this !is JsonPrimitive || isString) return null
    val n = runCatching { DartJson.numberOf(this) }.getOrNull() as? DartNumber.Int ?: return null
    return n.value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}

/** Dart `json[key] is int ? json[key] : null` as [Long]. */
internal fun JsonElement?.dartLongValue(): Long? {
    if (this !is JsonPrimitive || isString) return null
    return (runCatching { DartJson.numberOf(this) }.getOrNull() as? DartNumber.Int)?.value
}

/**
 * Dart `String.toLowerCase()` approximated per code point with the simple (1:1, locale-free)
 * Unicode mapping: no final-sigma rule, `İ` → `i` (spec 00 item 12). Used for comparisons only,
 * never for hashed or signed values.
 */
internal fun String.dartLower(): String = mapCodePoints { Character.toLowerCase(it) }

/** Dart `String.toUpperCase()`, same per-code-point approach (`ß` stays `ß`). */
internal fun String.dartUpper(): String = mapCodePoints { Character.toUpperCase(it) }

private inline fun String.mapCodePoints(map: (Int) -> Int): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        val mapped = map(cp)
        // Keep the UTF-16 length unchanged (Dart maps BMP to BMP only).
        if (Character.charCount(mapped) == Character.charCount(cp)) out.appendCodePoint(mapped) else out.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    return out.toString()
}

private val INT64_MIN: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
private val INT64_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
private val DART_INT = Regex("""([+-]?)(0[xX][0-9a-fA-F]+|[0-9]+)""")

/**
 * Dart `int.tryParse(s)` (no radix): surrounding Dart whitespace is ignored, optional sign,
 * decimal digits or a `0x` hex literal; `null` for anything else or values outside 64 bit.
 */
internal fun dartTryParseInt(s: String): Long? {
    val m = DART_INT.matchEntire(s.dartTrim()) ?: return null
    val digits = m.groupValues[2]
    val magnitude = if (digits.length > 2 && (digits[1] == 'x' || digits[1] == 'X')) {
        BigInteger(digits.substring(2), 16)
    } else {
        BigInteger(digits)
    }
    val value = if (m.groupValues[1] == "-") magnitude.negate() else magnitude
    return if (value < INT64_MIN || value > INT64_MAX) null else value.toLong()
}

private val DART_DOUBLE = Regex("""[+-]?(NaN|Infinity|(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?)""")

/**
 * Dart `double.tryParse(s)`: surrounding Dart whitespace is ignored; decimal and exponent
 * notation, `NaN` and `Infinity` with an optional sign; Java-only forms (`1d`, hex floats) fail.
 */
internal fun dartTryParseDouble(s: String): Double? {
    val t = s.dartTrim()
    if (!DART_DOUBLE.matches(t)) return null
    return when (t.trimStart('+', '-')) {
        "NaN" -> Double.NaN
        "Infinity" -> if (t.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        else -> t.toDoubleOrNull()
    }
}

/** `n` as at least two digits (Dart `toString().padLeft(2, '0')`). */
internal fun two(n: Int): String = n.toString().padStart(2, '0')
