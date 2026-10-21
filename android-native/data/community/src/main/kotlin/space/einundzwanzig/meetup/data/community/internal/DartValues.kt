package space.einundzwanzig.meetup.data.community.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber

/**
 * Dart `value.toString()` for a value decoded by `jsonDecode` (kept as a [JsonElement] by
 * [DartJson.decode]): strings verbatim, ints as digits, doubles via `double.toString()`,
 * `true`/`false`/`null`, maps as `{k: v, …}` and lists as `[a, b]` (Dart collection format).
 */
internal fun JsonElement?.dartToString(): String = when (this) {
    null, JsonNull -> "null"
    is JsonPrimitive -> when {
        isString -> content
        content == "true" || content == "false" -> content
        else -> when (val n = runCatching { DartJson.numberOf(this) }.getOrNull()) {
            is DartNumber.Int -> n.value.toString()
            is DartNumber.Double -> n.value.toDartString()
            null -> content
        }
    }
    is JsonObject -> entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${v.dartToString()}" }
    is JsonArray -> joinToString(", ", "[", "]") { it.dartToString() }
}

/** `true` if [this] is a non-null JSON value (Dart `json[key] != null`). */
internal val JsonElement?.isPresent: Boolean get() = this != null && this !is JsonNull

/**
 * Dart `RegExp` `\s` (ECMAScript whitespace + line terminators). Java's `\s` is ASCII-only, so
 * the set is spelled out (spec 02 §8.4).
 */
internal const val DART_REGEX_WHITESPACE =
    "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
