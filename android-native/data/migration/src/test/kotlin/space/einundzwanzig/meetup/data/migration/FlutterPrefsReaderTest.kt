package space.einundzwanzig.meetup.data.migration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.migration.legacy.FlutterPrefs
import space.einundzwanzig.meetup.data.migration.legacy.SharedPrefsFiles
import space.einundzwanzig.meetup.data.migration.legacy.decodeFlutterPrefs
import space.einundzwanzig.meetup.data.migration.legacy.decodeFlutterString
import space.einundzwanzig.meetup.data.migration.legacy.decodeJavaSerializedList

/** Spec 14 §4.2 / §4.6.1 / §4.11: Flutter prefs on-disk format, fixture `flutter_shared_prefs`. */
@RunWith(AndroidJUnit4::class)
class FlutterPrefsReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = FlutterDevice(context)
    private val fixture = MigrationFixtures.vectors.obj("flutter_shared_prefs")

    @Before
    fun setUp() = device.reset()

    private fun readDecoded() = decodeFlutterPrefs(SharedPrefsFiles(context).read(FlutterPrefs.FILE))

    @Test
    fun fixtureXmlDecodesToExpectedTypedMap() {
        device.writeRaw(FlutterPrefs.FILE, fixture.str("xml"))
        val decoded = readDecoded()
        val expected = fixture.obj("expectedDecoded")
        assertEquals(expected.keys, decoded.values.keys)
        for ((key, spec) in expected) {
            val type = spec.str("type")
            val v = spec.jsonObject.getValue("value")
            val want: PrefValue = when (type) {
                "String" -> PrefValue.StringValue(v.jsonPrimitive.content)
                "Boolean" -> PrefValue.BoolValue(v.jsonPrimitive.boolean)
                "Long" -> PrefValue.IntValue(v.jsonPrimitive.long)
                "Double" -> PrefValue.DoubleValue(v.jsonPrimitive.double)
                "StringList" -> PrefValue.StringListValue(v.jsonArray.map { it.jsonPrimitive.content })
                else -> error(type)
            }
            assertEquals("key $key", want, decoded.values[key])
        }
        assertTrue(decoded.undecodable.isEmpty())
    }

    @Test
    fun prefixLessKeysAreIgnored() {
        device.writeRaw(FlutterPrefs.FILE, fixture.str("xml"))
        val decoded = readDecoded()
        assertTrue(decoded.values.keys.none { it.contains("not_flutter") })
        assertNull(decoded.values["other.not_flutter"])
    }

    @Test
    fun nonAsciiJsonListIsNotEscaped() {
        device.writeRaw(FlutterPrefs.FILE, fixture.str("xml"))
        assertEquals(PrefValue.StringListValue(listOf("Berlin", "München")), readDecoded().values["favorite_meetups"])
    }

    @Test
    fun legacyJavaSerializedListDecodes() {
        val v = fixture.obj("legacyJavaSerializedList")
        val stored = v.str("storedString")
        assertEquals(
            PrefValue.StringListValue(v.arr("value").map { it.jsonPrimitive.content }),
            decodeFlutterString(stored),
        )
        // The same value through the XML file (Base64 lines with '\n' as written by Android).
        device.write(FlutterPrefs.FILE, mapOf("flutter.favorite_meetups" to stored))
        assertEquals(listOf("Berlin", "München"), (readDecoded().values["favorite_meetups"] as PrefValue.StringListValue).value)
    }

    @Test
    fun serializedListWithForeignClassIsRejected() {
        // java.util.HashMap is not on the plugin's allow-list.
        val bos = java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(bos).use { it.writeObject(java.util.HashMap(mapOf("a" to "b"))) }
        val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.DEFAULT)
        assertTrue(runCatching { decodeJavaSerializedList(b64) }.isFailure)
        device.write(FlutterPrefs.FILE, mapOf("flutter.x" to FlutterPrefs.LIST + b64))
        assertEquals(listOf("x"), readDecoded().undecodable)
    }

    @Test
    fun doubleEncodings() {
        for (case in fixture.obj("doubleEncoding").arr("cases")) {
            val value = case.jsonArray[0].jsonPrimitive.double
            val stored = case.jsonArray[1].jsonPrimitive.content
            assertEquals(stored, PrefValue.DoubleValue(value), decodeFlutterString(stored))
            // Java Double.toString is the writer.
            assertEquals(stored, FlutterPrefs.DOUBLE + java.lang.Double.toString(value))
        }
    }

    @Test
    fun prefixesMatchFixture() {
        val p = fixture.obj("prefixes")
        assertEquals(p.str("keyPrefix"), FlutterPrefs.KEY_PREFIX)
        assertEquals(p.str("listPrefix"), FlutterPrefs.LIST)
        assertEquals(p.str("jsonListPrefix"), FlutterPrefs.JSON_LIST)
        assertEquals(p.str("doublePrefix"), FlutterPrefs.DOUBLE)
        assertEquals(p.str("bigIntegerPrefix"), FlutterPrefs.BIG_INTEGER)
        assertEquals(fixture.obj("legacyJavaSerializedList").arr("allowedClasses").map { it.jsonPrimitive.content }.toSet(), FlutterPrefs.SERIALIZED_LIST_CLASSES)
    }

    @Test
    fun corruptListJsonAndBigIntegerAreUndecodableNotFatal() {
        device.write(
            FlutterPrefs.FILE,
            linkedMapOf(
                "flutter.tile_order" to FlutterPrefs.JSON_LIST + "[\"a\",",
                "flutter.tile_hidden" to FlutterPrefs.JSON_LIST + "[1,2]",
                "flutter.big" to FlutterPrefs.BIG_INTEGER + "zz",
                "flutter.nickname" to "Anon",
            ),
        )
        val decoded = readDecoded()
        assertEquals(listOf("tile_order", "tile_hidden", "big"), decoded.undecodable)
        assertEquals(PrefValue.StringValue("Anon"), decoded.values["nickname"])
    }

    @Test
    fun allAndroidTypesMap() {
        device.write(
            FlutterPrefs.FILE,
            linkedMapOf(
                "flutter.i" to 7,
                "flutter.l" to 1786000000123L,
                "flutter.f" to 1.5f,
                "flutter.s" to setOf("a", "b"),
                "flutter.b" to true,
                "flutter.text" to "a < b & \"c\"\nzwei",
                "flutter.empty" to "",
            ),
        )
        val v = readDecoded().values
        assertEquals(PrefValue.IntValue(7), v["i"])
        assertEquals(PrefValue.IntValue(1786000000123L), v["l"])
        assertEquals(PrefValue.DoubleValue(1.5), v["f"])
        assertEquals(PrefValue.StringListValue(listOf("a", "b")), v["s"])
        assertEquals(PrefValue.BoolValue(true), v["b"])
        assertEquals(PrefValue.StringValue("a < b & \"c\"\nzwei"), v["text"])
        assertEquals(PrefValue.StringValue(""), v["empty"])
    }

    @Test
    fun interruptedWriteBackupIsPreferredLikeAndroid() {
        device.write(FlutterPrefs.FILE, mapOf("flutter.nickname" to "half-written"))
        device.write(FlutterPrefs.FILE, mapOf("flutter.nickname" to "complete"), bak = true)
        assertEquals(PrefValue.StringValue("complete"), readDecoded().values["nickname"])
        // Reading never touches the files.
        assertTrue(java.io.File(device.sharedPrefs, "FlutterSharedPreferences.xml.bak").exists())
    }

    @Test
    fun missingFileIsEmpty() {
        assertTrue(readDecoded().values.isEmpty())
    }

    @Test
    fun homeWidgetFixtureParsesWithNativeTypes() {
        val v = MigrationFixtures.vectors.obj("home_widget_prefs")
        device.writeRaw("HomeWidgetPreferences", v.str("xml"))
        val raw = SharedPrefsFiles(context).read("HomeWidgetPreferences")
        assertEquals(14, raw.size)
        assertEquals(true, raw["newsIsNew"])
        assertEquals("€", raw["priceSymbol"])
        assertEquals("2·3·5", raw["fees"])
        assertEquals(v.obj("rendered").str("block"), raw["block"])
        assertEquals(13, raw.values.count { it is String })
        // Unknown primitive types never sneak in.
        assertTrue(raw.values.all { it is String || it is Boolean })
        assertEquals(JsonPrimitive("Berlin"), v.obj("rendered").getValue("meetupCity"))
    }
}
