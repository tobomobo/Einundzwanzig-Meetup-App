package space.einundzwanzig.meetup.data.reputation.internal

import kotlinx.serialization.json.JsonElement
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartNumOrNull

/**
 * A Dart int, or an integral finite double within the `Long` range (Dart's `as int?` would
 * throw for `3.0`); anything else `null`. Reader-side leniency for wire data (spec 04 §3.8).
 */
internal fun JsonElement?.lenientLong(): Long? {
    dartIntOrNull()?.let { return it }
    val d = dartNumOrNull() ?: return null
    if (d.isNaN() || d.isInfinite() || d != Math.floor(d)) return null
    if (d < Long.MIN_VALUE.toDouble() || d >= Long.MAX_VALUE.toDouble()) return null
    return d.toLong()
}

/** [lenientLong] saturated into the `Int` range. */
internal fun JsonElement?.lenientInt(): Int? =
    lenientLong()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
