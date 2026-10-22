package space.einundzwanzig.meetup.core.common.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger

// Helpers to write Dart map/list literals in Kotlin while keeping key order and the int/double
// distinction:
//
//   val content = jsonObjectOf("v" to 2, "t" to "B", "m" to meetupId, "b" to block, "x" to expiresAt)
//   DartJson.canonicalJsonEncode(content)
//
// Int/Long → Dart int, Double/Float → Dart double (4.0 prints "4.0"), Map → ordered object.

/** An ordered [JsonObject] from Dart-map-literal-like pairs; values go through [toJsonElement]. */
fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject {
    val content = LinkedHashMap<String, JsonElement>(pairs.size)
    for ((key, value) in pairs) content[key] = value.toJsonElement()
    return JsonObject(content)
}

/** A [JsonArray] from values; each goes through [toJsonElement]. */
fun jsonArrayOf(vararg values: Any?): JsonArray = JsonArray(values.map { it.toJsonElement() })

/**
 * Converts a plain Kotlin value to a [JsonElement], keeping map order and number types:
 * `null`, [String], [Boolean], [Number] ([Int]/[Long]/[Short]/[Byte]/[BigInteger] as int,
 * [Double]/[Float] as double), [Map] with String keys, [Iterable], arrays and [JsonElement]s.
 *
 * @throws IllegalArgumentException for a [BigInteger] beyond 64 bits (no Dart `int`) and for
 *   anything else (Dart would throw `JsonUnsupportedObjectError` when encoding it).
 */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Int, is Long, is Short, is Byte, is Double -> JsonPrimitive(this as Number)
    // Same limit as DartJson.encode: beyond 64 bits the literal would be re-read as a double.
    is BigInteger -> {
        require(bitLength() < 64) { "Not JSON-encodable: BigInteger beyond 64 bits" }
        JsonPrimitive(this)
    }
    // Widen like DartJson.encode does; JsonPrimitive(Float) would keep the float's short digits.
    is Float -> JsonPrimitive(toDouble())
    is Map<*, *> -> {
        val content = LinkedHashMap<String, JsonElement>(size)
        for ((key, value) in this) {
            require(key is String) { "Map keys must be strings: $key" }
            content[key] = value.toJsonElement()
        }
        JsonObject(content)
    }
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    is IntArray -> JsonArray(map { JsonPrimitive(it) })
    is LongArray -> JsonArray(map { JsonPrimitive(it) })
    is DoubleArray -> JsonArray(map { JsonPrimitive(it) })
    is BooleanArray -> JsonArray(map { JsonPrimitive(it) })
    else -> throw IllegalArgumentException("Not JSON-encodable: ${this::class.simpleName}")
}

/** `true` if Dart's `jsonDecode` would have produced an `int` here (`x is int`). */
val JsonElement.isDartInt: Boolean
    get() = this is JsonPrimitive && runCatching { DartJson.numberOf(this) }.getOrNull() is DartNumber.Int

/**
 * The value if Dart would see an `int` (`data['c'] as int`), `null` for doubles, strings and
 * everything else. A double like `1.0` is **not** an int — Dart's cast would throw.
 */
fun JsonElement?.dartIntOrNull(): Long? {
    if (this !is JsonPrimitive) return null
    return (runCatching { DartJson.numberOf(this) }.getOrNull() as? DartNumber.Int)?.value
}

/** The value of any Dart `num` (`(x as num).toDouble()`), `null` for non-numbers. */
fun JsonElement?.dartNumOrNull(): Double? {
    if (this !is JsonPrimitive) return null
    return runCatching { DartJson.numberOf(this) }.getOrNull()?.toDouble()
}

/** The string if Dart would see a `String` (`x as String?`), otherwise `null`. */
fun JsonElement?.dartStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
