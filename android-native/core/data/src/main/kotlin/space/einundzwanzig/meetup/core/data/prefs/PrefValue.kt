package space.einundzwanzig.meetup.core.data.prefs

/**
 * A typed value in a [KeyValueStore], mirroring the five value types of Flutter's
 * `SharedPreferences`.
 *
 * Dart `int` is 64-bit, so integers are [Long]. Lists keep their order and may contain
 * duplicates. The Flutter -> native migration maps every decoded Flutter entry onto one of these
 * cases and writes it with [KeyValueStore.set] under the same key name (minus `flutter.`).
 */
sealed interface PrefValue {
    /** Dart `prefs.setString`. */
    data class StringValue(val value: String) : PrefValue

    /** Dart `prefs.setBool`. */
    data class BoolValue(val value: Boolean) : PrefValue

    /** Dart `prefs.setInt` (64-bit). */
    data class IntValue(val value: Long) : PrefValue

    /** Dart `prefs.setDouble`. */
    data class DoubleValue(val value: Double) : PrefValue

    /** Dart `prefs.setStringList`: order is preserved, duplicates are allowed. */
    data class StringListValue(val value: List<String>) : PrefValue
}

/** The string payload, or `null` when this is not a [PrefValue.StringValue]. */
val PrefValue?.asString: String? get() = (this as? PrefValue.StringValue)?.value

/** The boolean payload, or `null` when this is not a [PrefValue.BoolValue]. */
val PrefValue?.asBool: Boolean? get() = (this as? PrefValue.BoolValue)?.value

/** The integer payload, or `null` when this is not a [PrefValue.IntValue]. */
val PrefValue?.asInt: Long? get() = (this as? PrefValue.IntValue)?.value

/** The double payload, or `null` when this is not a [PrefValue.DoubleValue]. */
val PrefValue?.asDouble: Double? get() = (this as? PrefValue.DoubleValue)?.value

/** The list payload, or `null` when this is not a [PrefValue.StringListValue]. */
val PrefValue?.asStringList: List<String>? get() = (this as? PrefValue.StringListValue)?.value
