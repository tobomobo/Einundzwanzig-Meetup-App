package space.einundzwanzig.meetup.data.identity.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber

/**
 * Dart `value.toString()` of a decoded JSON value: strings verbatim, ints/doubles as Dart prints
 * them, `null` (Kotlin `null` or [JsonNull]) as `"null"`. Objects/arrays fall back to their JSON
 * text (Dart would print `{a: 1}`; only reachable with malformed signer answers).
 */
internal fun JsonElement?.dartToString(): String = when (this) {
    null, JsonNull -> "null"
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

/** Dart `(value ?? '').toString()`. */
internal fun JsonElement?.dartToStringOrEmpty(): String = if (this == null || this is JsonNull) "" else dartToString()
