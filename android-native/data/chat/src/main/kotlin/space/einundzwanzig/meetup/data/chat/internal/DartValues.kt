package space.einundzwanzig.meetup.data.chat.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import java.math.BigInteger
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

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
    // Dart prints maps/lists as `{a: 1}` / `[1, 2]`; never reached for verified events.
    else -> toString()
}

/** Dart `x is int ? x : null`. */
internal fun JsonElement?.dartIntOrNullOf(): Long? = dartIntOrNull()

/** The `tags` of a raw event as arrays (Dart `(event['tags'] as List?)?.cast<List>() ?? []`). */
internal fun JsonObject.tagLists(): List<JsonArray> = (this["tags"] as? JsonArray)?.filterIsInstance<JsonArray>() ?: emptyList()

/** Element [i] if it is a JSON string (Dart `t[i] == 'name'` only matches strings). */
internal fun JsonArray.tagName(i: Int): String? = (getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Dart `t[i].toString()`. */
internal fun JsonArray.tagValue(i: Int): String = getOrNull(i).dartToStringOrNull() ?: "null"

/** Dart `event['key'] ?? ''` followed by `toString()`. */
internal fun JsonObject.stringField(key: String): String = this[key].dartToStringOrNull() ?: ""

/**
 * Dart (VM) `int.tryParse(s)`: optional surrounding Dart whitespace, optional sign, decimal or
 * `0x` hex digits, signed 64-bit range (hex magnitudes up to 2^64 as two's complement).
 *
 * Module-local because no shared public helper exists yet (the copies in `:core:nostr` and
 * `:data:meetups` are `internal`); replace it once `:core:common` offers one.
 */
internal fun dartIntTryParse(s: String): Long? {
    val m = DART_INT.matchEntire(s.dartTrim()) ?: return null
    val negative = m.groupValues[1] == "-"
    val hex = m.groupValues[2]
    val magnitude = if (hex.isNotEmpty()) BigInteger(hex, 16) else BigInteger(m.groupValues[3])
    val value = if (negative) magnitude.negate() else magnitude
    return when {
        value.bitLength() < 64 -> value.toLong()
        hex.isNotEmpty() && !negative && magnitude.bitLength() <= 64 -> magnitude.toLong()
        else -> null
    }
}

private val DART_INT = Regex("""([+-]?)(?:0[xX]([0-9a-fA-F]+)|([0-9]+))""")

/**
 * Dart `ChatService._looksOffline`: the connect failure means "no network" rather than "relay
 * down". Dart matched the error text (`Failed host lookup`, `Network is unreachable`,
 * `No address associated with hostname`, `Connection failed`); the JVM equivalents are
 * [UnknownHostException] (Android: "Unable to resolve host … No address associated with
 * hostname"), `ENETUNREACH` and [NoRouteToHostException]. The whole cause chain is checked.
 */
internal fun looksOffline(error: Throwable?): Boolean {
    var e = error
    var depth = 0
    while (e != null && depth < 8) {
        if (e is UnknownHostException || e is NoRouteToHostException) return true
        val text = e.toString()
        if (OFFLINE_MARKERS.any { text.contains(it) }) return true
        if (e is ConnectException && text.contains("ENETUNREACH")) return true
        e = e.cause
        depth++
    }
    return false
}

private val OFFLINE_MARKERS = listOf(
    "Failed host lookup",
    "Network is unreachable",
    "No address associated with hostname",
    "Connection failed",
    "Unable to resolve host",
)
