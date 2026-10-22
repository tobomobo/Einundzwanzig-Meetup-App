package space.einundzwanzig.meetup.core.data.prefs

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import space.einundzwanzig.meetup.core.data.internal.DataLog

/**
 * Maps [PrefValue]s onto DataStore preference entries.
 *
 * DataStore has no ordered list type (`stringSetPreferencesKey` loses order and duplicates), so a
 * [PrefValue.StringListValue] is stored as a byte-array entry `[LIST_FORMAT_JSON] + UTF-8 JSON
 * array`. Byte arrays are never used for anything else, so the stored type alone tells lists
 * and strings apart and plain strings need no escaping.
 */
internal object PreferencesCodec {
    private const val TAG = "PreferencesCodec"

    /** Format byte of an encoded string list: UTF-8 JSON array follows. */
    const val LIST_FORMAT_JSON: Byte = 0x01

    private val listSerializer = ListSerializer(String.serializer())
    private val json = Json

    /** Decodes the entry named [key], or `null` when absent or of an unsupported type. */
    fun decode(preferences: Preferences, key: String): PrefValue? =
        decodeRaw(key, preferences.asMap()[stringPreferencesKey(key)])

    /** Decodes every supported entry. */
    fun decodeAll(preferences: Preferences): Map<String, PrefValue> = buildMap {
        for ((k, raw) in preferences.asMap()) {
            decodeRaw(k.name, raw)?.let { put(k.name, it) }
        }
    }

    /** Replaces whatever [key] holds with [value]. */
    fun write(preferences: MutablePreferences, key: String, value: PrefValue) {
        // Keys compare by name only; drop the old entry so the new type is stored cleanly.
        preferences.remove(stringPreferencesKey(key))
        when (value) {
            is PrefValue.StringValue -> preferences[stringPreferencesKey(key)] = value.value
            is PrefValue.BoolValue -> preferences[booleanPreferencesKey(key)] = value.value
            is PrefValue.IntValue -> preferences[longPreferencesKey(key)] = value.value
            is PrefValue.DoubleValue -> preferences[doublePreferencesKey(key)] = value.value
            is PrefValue.StringListValue -> preferences[byteArrayPreferencesKey(key)] = encodeList(value.value)
        }
    }

    /** Removes [key] regardless of its stored type. */
    fun remove(preferences: MutablePreferences, key: String) {
        preferences.remove(stringPreferencesKey(key))
    }

    fun encodeList(values: List<String>): ByteArray {
        val payload = json.encodeToString(listSerializer, values).encodeToByteArray()
        return ByteArray(payload.size + 1).also {
            it[0] = LIST_FORMAT_JSON
            payload.copyInto(it, destinationOffset = 1)
        }
    }

    fun decodeList(bytes: ByteArray): List<String>? {
        if (bytes.isEmpty() || bytes[0] != LIST_FORMAT_JSON) return null
        return try {
            json.decodeFromString(listSerializer, bytes.decodeToString(startIndex = 1))
        } catch (e: SerializationException) {
            // The exception message quotes the stored input: log the type only.
            DataLog.e(TAG, "Stored string list is not a JSON string array (${e.javaClass.simpleName})")
            null
        } catch (e: IllegalArgumentException) {
            DataLog.e(TAG, "Stored string list is not a JSON string array (${e.javaClass.simpleName})")
            null
        }
    }

    private fun decodeRaw(key: String, raw: Any?): PrefValue? = when (raw) {
        null -> null
        is String -> PrefValue.StringValue(raw)
        is Boolean -> PrefValue.BoolValue(raw)
        is Long -> PrefValue.IntValue(raw)
        is Int -> PrefValue.IntValue(raw.toLong())
        is Double -> PrefValue.DoubleValue(raw)
        is Float -> PrefValue.DoubleValue(raw.toDouble())
        is ByteArray -> decodeList(raw)?.let(PrefValue::StringListValue)
        else -> {
            DataLog.w(TAG, "Ignoring entry '$key' of unsupported type ${raw::class.java.simpleName}")
            null
        }
    }
}
