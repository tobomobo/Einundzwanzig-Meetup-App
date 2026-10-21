package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber

/** `true` for a missing value or JSON `null` (Dart `x == null`). */
internal val JsonElement?.isDartNull: Boolean get() = this == null || this is JsonNull

/**
 * Dart `value.toString()` of a decoded JSON value: strings verbatim, ints/doubles as Dart prints
 * them, booleans `true`/`false`. Objects/arrays fall back to their JSON text (Dart would print
 * `{a: 1}`; only reachable with malformed portal data).
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

/** Dart `json[key]?.toString()`: `null` for a missing key or JSON `null`. */
internal fun JsonObject.stringOrNull(key: String): String? = this[key].takeUnless { it.isDartNull }?.dartToString()

/** Dart `(json[key] ?? '').toString()`. */
internal fun JsonObject.stringOrEmpty(key: String): String = stringOrNull(key) ?: ""

/** Dart `int`: the value if Dart's `jsonDecode` typed it as an int that fits into [Int]. */
internal fun JsonElement?.dartInt(): Int? {
    if (this !is JsonPrimitive) return null
    val n = runCatching { DartJson.numberOf(this) }.getOrNull() as? DartNumber.Int ?: return null
    return n.value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}

/**
 * Dart `String.toLowerCase()` approximated per code point with the simple (1:1, context-free,
 * locale-free) Unicode mapping. Unlike Kotlin `lowercase()` it never applies final-sigma or
 * special casing (`İ` → `i`, `Σ` → `σ`), like Dart. Only used for display/lookup comparisons in
 * this module, never for hashed or signed values.
 */
internal fun String.dartLower(): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        val lower = Character.toLowerCase(cp)
        // Keep the UTF-16 length unchanged (Dart maps BMP to BMP only).
        if (Character.charCount(lower) == Character.charCount(cp)) out.appendCodePoint(lower) else out.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    return out.toString()
}

/** Dart `String.toUpperCase()`, same per-code-point approach as [dartLower] (`ß` stays `ß`). */
internal fun String.dartUpper(): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        val upper = Character.toUpperCase(cp)
        if (Character.charCount(upper) == Character.charCount(cp)) out.appendCodePoint(upper) else out.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    return out.toString()
}

private val DART_DOUBLE = Regex("""[+-]?(NaN|Infinity|(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?)""")

/**
 * Dart `double.tryParse(s)`: surrounding Dart whitespace is ignored; accepts decimal and
 * exponent notation, `NaN` and `Infinity` with an optional sign; rejects Java-only forms
 * (`1d`, `1f`, hex floats).
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
