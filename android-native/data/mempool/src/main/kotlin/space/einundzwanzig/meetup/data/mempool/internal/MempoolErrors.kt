package space.einundzwanzig.meetup.data.mempool.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartNumOrNull
import java.io.InterruptedIOException

/**
 * Whole-call timeout of a mempool request. Named like Dart's `TimeoutException` on purpose:
 * the simple class name is shown verbatim as `lastError` in the dashboard banner and in the
 * connection test result, exactly as in the Flutter app.
 */
internal class TimeoutException(message: String) : InterruptedIOException(message)

/**
 * A JSON value of the wrong type where Dart casts (`as Map<String, dynamic>`, `as num?`).
 * Counterpart of Dart's `TypeError`; the source counts as failed.
 */
internal class TypeError(message: String) : RuntimeException(message)

/** Dart `jsonDecode(body) as Map<String, dynamic>`. */
internal fun decodeObject(body: String): JsonObject =
    DartJson.decode(body) as? JsonObject ?: throw TypeError("JSON-Wurzel ist kein Objekt")

/** Dart `(obj[key] as num?)?.toDouble()`: `null` if absent or JSON null, throws for non-numbers. */
internal fun JsonObject.num(key: String): Double? = numOf(this[key], key)

internal fun numOf(element: JsonElement?, key: String): Double? {
    if (element == null || element is JsonNull) return null
    return element.dartNumOrNull() ?: throw TypeError("'$key' ist keine Zahl")
}
