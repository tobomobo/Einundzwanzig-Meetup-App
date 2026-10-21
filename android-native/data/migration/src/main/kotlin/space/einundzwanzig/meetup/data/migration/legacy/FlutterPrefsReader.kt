package space.einundzwanzig.meetup.data.migration.legacy

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import java.io.ByteArrayInputStream
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectStreamClass

/** shared_preferences_android 2.4.20 constants (spec 14 §4.2, fixture `flutter_shared_prefs.prefixes`). */
internal object FlutterPrefs {
    const val FILE = "FlutterSharedPreferences"
    const val KEY_PREFIX = "flutter."
    const val LIST = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
    const val JSON_LIST = "$LIST!"
    const val DOUBLE = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu"
    const val BIG_INTEGER = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBCaWdJbnRlZ2Vy"

    /** Classes the plugin's `StringListObjectInputStream` accepts (no deserialization gadgets). */
    val SERIALIZED_LIST_CLASSES = setOf(
        "java.util.ArrayList",
        "java.util.Arrays\$ArrayList",
        "java.lang.String",
        "[Ljava.lang.String;",
    )
}

/**
 * Decoded Flutter entries (keys without `flutter.`), same five types as `PrefValue`.
 * [undecodable] lists key names whose value could not be decoded (corrupt list JSON, unknown
 * serialized class, BigInteger); they are reported, never copied.
 */
internal data class DecodedPrefs(val values: Map<String, PrefValue>, val undecodable: List<String>)

/** Decodes the raw `FlutterSharedPreferences.xml` map (spec 14 §4.6.1). */
internal fun decodeFlutterPrefs(raw: Map<String, Any>): DecodedPrefs {
    val out = LinkedHashMap<String, PrefValue>()
    val bad = mutableListOf<String>()
    for ((name, value) in raw) {
        if (!name.startsWith(FlutterPrefs.KEY_PREFIX)) continue // invisible to Dart
        val key = name.removePrefix(FlutterPrefs.KEY_PREFIX)
        val decoded: PrefValue? = try {
            when (value) {
                is String -> decodeFlutterString(value)
                is Boolean -> PrefValue.BoolValue(value)
                is Long -> PrefValue.IntValue(value)
                is Int -> PrefValue.IntValue(value.toLong())
                is Float -> PrefValue.DoubleValue(value.toDouble())
                is Set<*> -> PrefValue.StringListValue(value.filterIsInstance<String>())
                else -> null
            }
        } catch (e: Exception) {
            null
        }
        if (decoded == null) bad += key else out[key] = decoded
    }
    return DecodedPrefs(out, bad)
}

/** One `<string>` value: JSON list, legacy Java-serialized list, double, BigInteger or plain string. */
internal fun decodeFlutterString(s: String): PrefValue? = when {
    s.startsWith(FlutterPrefs.JSON_LIST) -> {
        val array = DartJson.decode(s.substring(FlutterPrefs.JSON_LIST.length)) as JsonArray
        PrefValue.StringListValue(
            array.map { e ->
                val p = e as? JsonPrimitive
                require(p != null && p.isString) { "List element is not a string" }
                p.content
            },
        )
    }
    s.startsWith(FlutterPrefs.LIST) -> PrefValue.StringListValue(decodeJavaSerializedList(s.substring(FlutterPrefs.LIST.length)))
    s.startsWith(FlutterPrefs.DOUBLE) -> PrefValue.DoubleValue(java.lang.Double.parseDouble(s.substring(FlutterPrefs.DOUBLE.length)))
    s.startsWith(FlutterPrefs.BIG_INTEGER) -> null // never written by this app
    else -> PrefValue.StringValue(s)
}

/** Pre-2.3 plugin list format, with the plugin's class allow-list. */
internal fun decodeJavaSerializedList(b64: String): List<String> {
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    val input = object : ObjectInputStream(ByteArrayInputStream(bytes)) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> {
            if (desc.name !in FlutterPrefs.SERIALIZED_LIST_CLASSES) throw InvalidClassException(desc.name)
            return super.resolveClass(desc)
        }
    }
    return input.use { (it.readObject() as List<*>).map { e -> e as String } }
}
