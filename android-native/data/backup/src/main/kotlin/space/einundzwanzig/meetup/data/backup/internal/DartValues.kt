package space.einundzwanzig.meetup.data.backup.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber
import space.einundzwanzig.meetup.data.backup.BackupFormatException

// Dart cast semantics for decoded backup JSON. A wrong type throws BackupFormatException where
// Flutter's typed assignments (`String x = map['k'] ?? ''`, `as bool?`, `as List`) throw.

/** `null` for an absent key or JSON `null` (Dart `map[k] == null`). */
internal fun JsonObject.nonNull(key: String): JsonElement? = get(key)?.takeUnless { it is JsonNull }

/** Dart `value.toString()` of a decoded JSON value; `null` for absent/JSON `null`. */
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
    // Dart prints maps/lists differently; only reached for malformed input.
    else -> toString()
}

/** Dart `(x ?? '').toString()`. */
internal fun JsonElement?.dartToStringOrEmpty(): String = dartToStringOrNull() ?: ""

/** Dart `String v = map[key] ?? default` (a non-string throws). */
internal fun JsonObject.strictString(key: String, default: String, path: String = key): String {
    val v = nonNull(key) ?: return default
    return (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw BackupFormatException("$path ist kein Text")
}

/** Dart `bool v = map[key] ?? default` / `as bool? ?? default` (a non-bool throws). */
internal fun JsonObject.strictBool(key: String, default: Boolean, path: String = key): Boolean {
    val v = nonNull(key) ?: return default
    return (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: throw BackupFormatException("$path ist kein Wahrheitswert")
}

/** Dart `map[key] as List?` (a non-list throws). */
internal fun JsonObject.strictListOrNull(key: String, path: String = key): JsonArray? {
    val v = nonNull(key) ?: return null
    return v as? JsonArray ?: throw BackupFormatException("$path ist keine Liste")
}

/** Dart `map[key] as Map?` (a non-map throws). */
internal fun JsonObject.strictObjectOrNull(key: String, path: String = key): JsonObject? {
    val v = nonNull(key) ?: return null
    return v as? JsonObject ?: throw BackupFormatException("$path ist kein Objekt")
}
