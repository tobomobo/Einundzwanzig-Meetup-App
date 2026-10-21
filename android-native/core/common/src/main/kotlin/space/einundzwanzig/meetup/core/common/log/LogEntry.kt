package space.einundzwanzig.meetup.core.common.log

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.dartToInt
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** The level codes stored in [LogEntry.level] (Dart `LogEntry.level`). */
enum class LogLevel(val code: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),

    /** Security-relevant event (`AppLogger.security`). Hidden by the "problems only" filter. */
    SEC("SEC"),
    WARN("WARN"),
    ERROR("ERROR"),
    ;

    companion object {
        /** The level for a stored code, `null` for unknown codes. */
        fun fromCode(code: String): LogLevel? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One diagnostic log line (Dart `LogEntry`).
 *
 * Persisted as `{"t":<epoch millis>,"l":"<level>","g":"<tag>","m":"<message>"}` — the same keys
 * and the same `t` semantics (milliseconds since the epoch, shown in local time) as the Flutter
 * app, so a migrated buffer reads back unchanged.
 *
 * @property timeMillis milliseconds since the epoch (`t`).
 * @property level `DEBUG`, `INFO`, `SEC`, `WARN` or `ERROR` (`l`); unknown codes are kept.
 * @property tag component tag (`g`).
 * @property message the (already redacted) message (`m`).
 */
data class LogEntry(
    val timeMillis: Long,
    val level: String,
    val tag: String,
    val message: String,
) {
    /** [level] as an enum, `null` for unknown codes. */
    val logLevel: LogLevel? get() = LogLevel.fromCode(level)

    /** `true` for `ERROR` and `WARN` — the log screen's "problems only" filter (`SEC` is not a problem). */
    val isProblem: Boolean get() = level == LogLevel.ERROR.code || level == LogLevel.WARN.code

    /** Local wall time of the entry. */
    fun localTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMillis), zone)

    /**
     * Export format (Dart `LogEntry.format()`): `dd.MM HH:mm:ss  LEVEL [tag] message`, the level
     * padded to five characters. Used by copy/share.
     */
    fun format(zone: ZoneId = ZoneId.systemDefault()): String {
        val t = localTime(zone)
        return "${two(t.dayOfMonth)}.${two(t.monthValue)} ${timeOfDay(t)}  ${level.padEnd(5)} [$tag] $message"
    }

    /** `HH:mm:ss` — the time column of the log screen. */
    fun timeOfDay(zone: ZoneId = ZoneId.systemDefault()): String = timeOfDay(localTime(zone))

    /**
     * One line of the log screen (Dart `LogScreen`): `HH:mm:ss  LEVEL  tag: message`, the level
     * padded to five characters. The screen renders the four parts in different colours.
     */
    fun screenLine(zone: ZoneId = ZoneId.systemDefault()): String =
        "${timeOfDay(zone)}  ${level.padEnd(5)}  $tag: $message"

    /** Dart `toJson()`: keys `t`, `l`, `g`, `m` in this order. */
    fun toJson(): JsonObject = jsonObjectOf("t" to timeMillis, "l" to level, "g" to tag, "m" to message)

    /** [toJson] as compact Dart JSON (one line, never contains a raw newline). */
    fun toJsonLine(): String = DartJson.encode(toJson())

    companion object {
        /**
         * Dart `LogEntry.fromJson`: `t` must be a number (doubles are truncated), missing `l` is
         * `INFO`, missing `g`/`m` are empty.
         *
         * @throws IllegalArgumentException where the Dart cast would throw.
         */
        fun fromJson(json: JsonObject): LogEntry {
            val t = json["t"] as? JsonPrimitive ?: throw IllegalArgumentException("t missing")
            val time = when (val n = DartJson.numberOf(t)) {
                is DartNumber.Int -> n.value
                is DartNumber.Double -> n.value.dartToInt()
                null -> throw IllegalArgumentException("t is not a number")
            }
            return LogEntry(
                timeMillis = time,
                level = optionalString(json, "l") ?: LogLevel.INFO.code,
                tag = optionalString(json, "g") ?: "",
                message = optionalString(json, "m") ?: "",
            )
        }

        /**
         * Reads the Flutter buffer (`SharedPreferences` key `diagnostic_log`, a JSON array of
         * entries). Used by the data migration.
         *
         * @throws IllegalArgumentException (or a serialization exception) for a corrupt buffer,
         *   like Dart's `jsonDecode`/cast in `AppLogger.init`.
         */
        fun listFromDartJson(raw: String): List<LogEntry> {
            val array = DartJson.decode(raw) as? JsonArray ?: throw IllegalArgumentException("not a JSON array")
            return array.map { fromJson(it as? JsonObject ?: throw IllegalArgumentException("entry is not an object")) }
        }

        private fun optionalString(json: JsonObject, key: String): String? = when (val v = json[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> if (v.isString) v.content else throw IllegalArgumentException("$key is not a string")
            else -> throw IllegalArgumentException("$key is not a string")
        }

        private fun timeOfDay(t: LocalDateTime) = "${two(t.hour)}:${two(t.minute)}:${two(t.second)}"

        private fun two(n: Int) = if (n < 10) "0$n" else n.toString()
    }
}
