package space.einundzwanzig.meetup.data.proofs.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber
import java.time.Instant
import java.time.ZoneId

/** Dart `value.toString()` of a decoded JSON value; `null` for an absent or JSON `null` value. */
internal fun JsonElement?.dartToStringOrNull(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        else -> when (val n = runCatching { DartJson.numberOf(this) }.getOrNull()) {
            is DartNumber.Double -> n.value.toDartString()
            is DartNumber.Int -> n.value.toString()
            null -> content // true / false
        }
    }
    // Dart prints maps/lists as `{a: 1}` / `[1, 2]`; only reached for malformed input.
    else -> toString()
}

/** Dart `(x ?? '').toString()`. */
internal fun JsonElement?.dartToStringOrEmpty(): String = dartToStringOrNull() ?: ""

/** Dart `x == 9735` with Dart number equality (`9735.0 == 9735` is true). */
internal fun JsonElement?.dartNumEquals(value: Long): Boolean {
    if (this !is JsonPrimitive || isString) return false
    return when (val n = runCatching { DartJson.numberOf(this) }.getOrNull()) {
        is DartNumber.Int -> n.value == value
        is DartNumber.Double -> n.value == value.toDouble()
        null -> false
    }
}

/** `"${d.day}.${d.month}.${d.year}"` of Unix [seconds] in [zone] (no zero padding, Dart local time). */
internal fun dartDayMonthYear(seconds: Long, zone: ZoneId): String {
    val d = Instant.ofEpochMilli(seconds * 1000).atZone(zone)
    return "${d.dayOfMonth}.${d.monthValue}.${d.year}"
}
