package space.einundzwanzig.meetup.core.nostr.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber

/**
 * Dart `==` on values produced by `jsonDecode`.
 *
 * Strings and booleans compare by value, numbers by numeric value (`1 == 1.0` is `true` in
 * Dart), `null == null`. Lists and maps compare by identity in Dart, so two decoded containers
 * are never equal unless they are the same instance.
 */
internal fun dartEquals(a: JsonElement?, b: JsonElement?): Boolean {
    val aNull = a == null || a is JsonNull
    val bNull = b == null || b is JsonNull
    if (aNull || bNull) return aNull && bNull
    if (a is JsonArray || a is JsonObject || b is JsonArray || b is JsonObject) return a === b
    a as JsonPrimitive
    b as JsonPrimitive
    if (a.isString || b.isString) return a.isString && b.isString && a.content == b.content
    val na = numberOrNull(a)
    val nb = numberOrNull(b)
    if (na == null || nb == null) return na == null && nb == null && a.content == b.content // booleans
    return when {
        na is DartNumber.Int && nb is DartNumber.Int -> na.value == nb.value
        else -> na.toDouble() == nb.toDouble()
    }
}

/** `list.contains(value)` with Dart equality. */
internal fun JsonArray.dartContains(value: JsonElement?): Boolean = any { dartEquals(it, value) }

/**
 * Dart `toString()` of a decoded JSON scalar: strings verbatim, ints in decimal, doubles like
 * `double.toString()`, `true`/`false`, `null`. Containers return `null` (their Dart rendering,
 * e.g. `[1, 2]` or `{a: 1}`, is never needed on a wire path).
 */
internal fun dartScalarToString(value: JsonElement?): String? = when {
    value == null || value is JsonNull -> "null"
    value is JsonPrimitive && value.isString -> value.content
    value is JsonPrimitive -> when (val n = numberOrNull(value)) {
        is DartNumber.Int -> n.value.toString()
        is DartNumber.Double -> n.value.toDartString()
        null -> value.content // true / false
    }
    else -> null
}

/** Like [dartScalarToString], with a best-effort rendering for containers (log text only). */
internal fun dartToLogString(value: JsonElement?): String = dartScalarToString(value) ?: value.toString()

private fun numberOrNull(p: JsonPrimitive): DartNumber? = runCatching { DartJson.numberOf(p) }.getOrNull()
