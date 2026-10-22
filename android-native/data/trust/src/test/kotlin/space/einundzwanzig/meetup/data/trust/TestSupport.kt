package space.einundzwanzig.meetup.data.trust

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/** The shared spec vectors (`spec/fixtures/04-trust-reputation-vectors.json`). */
object Fixtures {
    val trust: JsonObject by lazy {
        DartJson.decode(File("../../spec/fixtures/04-trust-reputation-vectors.json").readText()).jsonObject
    }

    operator fun get(key: String): JsonElement = trust.getValue(key)
}

/** Mutable clock (microsecond resolution like Dart `DateTime.now()`). */
class FakeTime(var micros: Long) : TimeSource {
    override fun nowMillis(): Long = Math.floorDiv(micros, 1000L)
    override fun nowMicros(): Long = micros
}

val UTC_ZONE: ZoneIdProvider = ZoneIdProvider { ZoneOffset.UTC }

/**
 * Pins the JVM default zone (the badge identity key reads it for local dates, like Dart) and
 * restores it afterwards.
 */
inline fun <T> withDefaultZone(zone: ZoneId, block: () -> T): T {
    val previous = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone(zone))
    try {
        return block()
    } finally {
        TimeZone.setDefault(previous)
    }
}
