package space.einundzwanzig.meetup.core.common.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Expected outputs computed with Dart 3.13.5 `jsonEncode` / `JsonEncoder.withIndent('  ')`. */
class DartJsonTest {

    @Test
    fun `string escaping matches Dart`() {
        val bs = "\\"
        val q = "\""
        val cases = listOf(
            "ä\"\\\n\u0001/< \t" to "${q}ä$bs$q$bs$bs${bs}n${bs}u0001/< ${bs}t$q",
            "\u0000\u0007\b\u000C\r\u001F\u007F\u0080" to "$q${bs}u0000${bs}u0007${bs}b${bs}f${bs}r${bs}u001f\u007F\u0080$q",
            "  " to "$q  $q",
            "emoji 😀" to "${q}emoji 😀$q",
            "lone \uD800 x" to "${q}lone ${bs}ud800 x$q",
            "lone trail \uDC00 y" to "${q}lone trail ${bs}udc00 y$q",
            "pair 😀" to "${q}pair 😀$q",
            "rev \uDE00\uD83D" to "${q}rev ${bs}ude00${bs}ud83d$q",
            "<script>&amp;</script>" to "$q<script>&amp;</script>$q",
            "single ' quote" to "${q}single ' quote$q",
            "end\uD800" to "${q}end${bs}ud800$q",
            "" to "$q$q",
        )
        for ((input, expected) in cases) {
            assertEquals(input, expected, DartJson.quote(input))
            assertEquals(input, expected, DartJson.encode(input))
            assertEquals(input, expected, DartJson.encode(JsonPrimitive(input)))
        }
    }

    @Test
    fun `numbers keep the Dart int and double distinction`() {
        assertEquals("[1,1.0,4.0,-0.0,1e+21,1e-7,100.0,0.1,9223372036854775807]", DartJson.encode(listOf(1, 1.0, 4.0, -0.0, 1e21, 1e-7, 100.0, 0.1, Long.MAX_VALUE)))
        val element = jsonArrayOf(1, 1.0, 4.0f, 5L, 48.2)
        assertEquals("[1,1.0,4.0,5,48.2]", DartJson.encode(element))
        // A Kotlin Float widens to its exact double, as a Dart double would hold it.
        assertEquals("[0.10000000149011612]", DartJson.encode(listOf(0.1f)))
        assertEquals("[0.10000000149011612]", DartJson.encode(jsonArrayOf(0.1f)))
    }

    @Test
    fun `decode then encode round-trips like Dart jsonDecode then jsonEncode`() {
        val cases = listOf(
            "1" to "1", "-0" to "0", "0" to "0", "1.0" to "1.0", "1e2" to "100.0", "1E2" to "100.0",
            "-0.0" to "-0.0", "1.5e300" to "1.5e+300", "12345678901234567890" to "12345678901234567000.0",
            "9223372036854775807" to "9223372036854775807", "9223372036854775808" to "9223372036854776000.0",
            "-9223372036854775808" to "-9223372036854775808", "-9223372036854775809" to "-9223372036854776000.0",
            "1e-7" to "1e-7", "0.1e1" to "1.0", "100000000000000000000" to "100000000000000000000.0",
            "123456789012345678901234567890" to "1.2345678901234568e+29", "1.0E+2" to "100.0", "5e-324" to "5e-324",
        )
        for ((literal, expected) in cases) {
            assertEquals(literal, "[$expected]", DartJson.encode(DartJson.decode("[$literal]")))
        }
    }

    @Test
    fun `compact and pretty output match Dart`() {
        val value = jsonObjectOf(
            "a" to 1,
            "b" to listOf(1, 2.5, mapOf("c" to null, "d" to true)),
            "e" to emptyMap<String, Any?>(),
            "f" to emptyList<Any?>(),
            "g" to "x",
            "h" to listOf(emptyList<Any?>()),
            "i" to mapOf("j" to emptyMap<String, Any?>()),
        )
        assertEquals(
            """{"a":1,"b":[1,2.5,{"c":null,"d":true}],"e":{},"f":[],"g":"x","h":[[]],"i":{"j":{}}}""",
            DartJson.encode(value),
        )
        val pretty = """
            {
              "a": 1,
              "b": [
                1,
                2.5,
                {
                  "c": null,
                  "d": true
                }
              ],
              "e": {},
              "f": [],
              "g": "x",
              "h": [
                []
              ],
              "i": {
                "j": {}
              }
            }
        """.trimIndent()
        assertEquals(pretty, DartJson.encodePretty(value))
        assertEquals("[]", DartJson.encodePretty(emptyList<Any>()))
        assertEquals("{}", DartJson.encodePretty(emptyMap<String, Any>()))
        assertEquals("5", DartJson.encodePretty(5))
        assertEquals("[\n  1\n]", DartJson.encodePretty(listOf(1)))
    }

    @Test
    fun `canonicalJsonEncode sorts only top-level keys`() {
        assertEquals("""{"a":2,"b":4,"m":3,"z":1}""", DartJson.canonicalJsonEncode(jsonObjectOf("z" to 1, "a" to 2, "m" to 3, "b" to 4)))
        assertEquals(
            """{"a":{"y":true,"x":false},"b":[1,2,3]}""",
            DartJson.canonicalJsonEncode(mapOf("b" to listOf(1, 2, 3), "a" to mapOf("y" to true, "x" to false))),
        )
        // Badge content (spec 02 §7.2) and claim content (§7.4) orders.
        assertEquals(
            """{"b":875000,"m":"aschaffenburg-de","t":"B","v":2,"x":1700014400}""",
            DartJson.canonicalJsonEncode(jsonObjectOf("v" to 2, "t" to "B", "m" to "aschaffenburg-de", "b" to 875000, "x" to 1700014400)),
        )
        assertEquals(
            """{"action":"claim_badge","block_height":1,"claimed_at":2,"org_event_id":"e","org_pubkey":"p","org_sig":"s"}""",
            DartJson.canonicalJsonEncode(
                jsonObjectOf("action" to "claim_badge", "org_sig" to "s", "org_event_id" to "e", "org_pubkey" to "p", "block_height" to 1, "claimed_at" to 2),
            ),
        )
        // UTF-16 code unit order: upper case before lower case, "Z" < "a" < "ä".
        assertEquals("""{"Z":1,"a":2,"ä":3}""", DartJson.canonicalJsonEncode(mapOf("ä" to 3, "a" to 2, "Z" to 1)))
    }

    @Test
    fun `spec fixture canonicalJson`() {
        val fixture = fixture("02-badges-claim-vectors.json")
        val cases = fixture.jsonObject["canonicalJson"]!!.jsonArray
        assertEquals(4, cases.size)
        for (case in cases) {
            val input = case.jsonObject["input_insertion_order"]!!.jsonObject
            val expected = case.jsonObject["expected"]!!.jsonPrimitive.content
            assertEquals(expected, DartJson.canonicalJsonEncode(input))
        }
    }

    @Test
    fun `spec fixture event id escaping`() {
        val fixture = fixture("07-nostr-infra-vectors.json")
        val v = fixture.jsonObject["event_id_escaping"]!!.jsonObject
        val serialized = DartJson.encode(
            listOf(
                0,
                v["pubkey"]!!.jsonPrimitive.content,
                v["created_at"]!!.jsonPrimitive.content.toLong(),
                v["kind"]!!.jsonPrimitive.content.toInt(),
                v["tags"]!!,
                v["content"]!!.jsonPrimitive.content,
            ),
        )
        assertEquals(v["expected_serialization_utf8"]!!.jsonPrimitive.content, serialized)
        val id = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(v["expected_id"]!!.jsonPrimitive.content, id)
    }

    @Test
    fun `log entry JSON matches the Dart persisted form`() {
        assertEquals(
            """{"t":1000,"l":"INFO","g":"alt","m":"Eintrag von gestern"}""",
            DartJson.encode(jsonObjectOf("t" to 1000, "l" to "INFO", "g" to "alt", "m" to "Eintrag von gestern")),
        )
    }

    @Test
    fun `decode rejects everything Dart jsonDecode rejects`() {
        val invalid = listOf(
            "[abc]", "{\"a\":abc}", "[True]", "[FALSE]", "[nul]", "[true1]", "[01]", "[-01]", "[+1]", "[.5]", "[1.]",
            "[1e]", "[1e+]", "[-]", "[NaN]", "[Infinity]", "[-Infinity]", "['x']", "abc", "",
            "[\"a\nb\"]", "[\"a\tb\"]", "[\"a\u0000b\"]", "{\"a\nb\":1}", "[\"\\x\"]", "[\"\\u12\"]",
            "[1,]", "[,1]", "{\"a\":1,}", "{a:1}", "[1] x", "[1]]", "[1,\u000C2]", "\uFEFF[1]", "[1 // c\n]",
        )
        for (text in invalid) {
            val e = assertThrows(text, DartJsonFormatException::class.java) { DartJson.decode(text) }
            assertTrue("a SerializationException for existing callers", e is kotlinx.serialization.SerializationException)
            assertFalse("never echoes the input", text.length > 3 && e.message.orEmpty().contains(text))
        }
        assertEquals(
            "Control character in string (at character 4)",
            assertThrows(DartJsonFormatException::class.java) { DartJson.decode("[\"a\nb\"]") }.message,
        )
    }

    @Test
    fun `decode accepts what Dart jsonDecode accepts`() {
        val element = DartJson.decode(" [\"a\\nb\", \"\\\"\", \"\\/\",\ttrue,\rfalse,\nnull, -0.5e-3, {}] ")
        assertEquals("""["a\nb","\"","/",true,false,null,-0.0005,{}]""", DartJson.encode(element))
        // Escaped control characters, raw non-ASCII and raw DEL are fine.
        assertEquals("\u0001ä\u007F", DartJson.decode("\"\\u0001ä\u007F\"").jsonPrimitive.content)
        // Top-level scalars.
        assertEquals("1", DartJson.encode(DartJson.decode("1")))
        assertEquals("null", DartJson.encode(DartJson.decode(" null ")))
        // Dart decodes 1e400 to Infinity; only encoding it fails.
        val huge = DartJson.decode("[1e400]").jsonArray.single().jsonPrimitive
        assertEquals(DartNumber.Double(Double.POSITIVE_INFINITY), DartJson.numberOf(huge))
        assertThrows(IllegalArgumentException::class.java) { DartJson.encode(huge) }
    }

    @Test
    fun `decode handles nesting beyond the recursive limit`() {
        // Deeper than kotlinx' recursive reader (200); far deeper input hits kotlinx'
        // maxNestingDepth, a documented divergence from Dart (which has no limit).
        val depth = 250
        val text = "[".repeat(depth) + "]".repeat(depth)
        var element = DartJson.decode(text)
        var levels = 1
        while (element is JsonArray && element.isNotEmpty()) {
            element = element.single()
            levels++
        }
        assertEquals(depth, levels)
        assertThrows(DartJsonFormatException::class.java) { DartJson.decode("[".repeat(depth) + "x" + "]".repeat(depth)) }
    }

    @Test
    fun `unsupported values throw like Dart`() {
        assertThrows(IllegalArgumentException::class.java) { DartJson.encode(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { DartJson.encode(listOf(Double.POSITIVE_INFINITY)) }
        assertThrows(IllegalArgumentException::class.java) { DartJson.encode(JsonPrimitive(Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { DartJson.encode(mapOf(1 to "x")) }
        assertThrows(IllegalArgumentException::class.java) { DartJson.encode(Any()) }
        assertThrows(IllegalArgumentException::class.java) { DartJson.encode(java.math.BigInteger.TWO.pow(64)) }
        assertThrows(IllegalArgumentException::class.java) { jsonObjectOf("x" to java.math.BigInteger.TWO.pow(64)) }
        assertThrows(IllegalArgumentException::class.java) { jsonArrayOf(java.math.BigInteger.TWO.pow(63)) }
        assertEquals("[9223372036854775807]", DartJson.encode(jsonArrayOf(java.math.BigInteger.valueOf(Long.MAX_VALUE))))
    }

    @Test
    fun `typed accessors follow Dart casts`() {
        val o = DartJson.decode("""{"c":1700000000,"d":1.0,"s":"x","n":null,"b":true,"big":12345678901234567890}""").jsonObject
        assertEquals(1700000000L, o["c"].dartIntOrNull())
        assertTrue(o["c"]!!.isDartInt)
        assertNull("1.0 is a Dart double, `as int` would throw", o["d"].dartIntOrNull())
        assertFalse(o["d"]!!.isDartInt)
        assertEquals(1.0, o["d"].dartNumOrNull()!!, 0.0)
        assertNull(o["s"].dartIntOrNull())
        assertEquals("x", o["s"].dartStringOrNull())
        assertNull(o["c"].dartStringOrNull())
        assertNull(o["n"].dartIntOrNull())
        assertNull(o["b"].dartNumOrNull())
        assertNull(o["missing"].dartIntOrNull())
        assertNull("beyond 64 bits Dart decodes a double", o["big"].dartIntOrNull())
        assertEquals(DartNumber.Double(1.2345678901234567e19), DartJson.numberOf(o["big"]!!.jsonPrimitive))
        assertEquals(DartNumber.Int(0), DartJson.numberOf(JsonPrimitive(-0)))
    }

    @Test
    fun `builders keep insertion order and accept kotlinx elements`() {
        val nested = buildJsonObject { put("k", 1) }
        val o = jsonObjectOf("z" to 1, "a" to listOf("x", null), "n" to nested, "arr" to intArrayOf(1, 2))
        assertEquals(listOf("z", "a", "n", "arr"), o.keys.toList())
        assertEquals("""{"z":1,"a":["x",null],"n":{"k":1},"arr":[1,2]}""", DartJson.encode(o))
        assertTrue(o["arr"] is JsonArray)
        assertTrue(o["n"] is JsonObject)
        assertThrows(IllegalArgumentException::class.java) { jsonObjectOf("x" to Any()) }
    }

    // ---- Cases merged from the former :core:crypto DartJson (Dart 3.13 jsonEncode outputs) ----

    @Test
    fun `doubles print like Dart double toString`() {
        val input = listOf(
            1.0, 0.1, 1e21, 1e20, 1e-7, 1e-6, 1.5e300, -0.0, 0.000001, 100.0, 2.5, 12.3, 1.0 / 3, 5e-324,
            1.7976931348623157e308, 123456.789, 0.3, 2.0 / 3 * 100, 9007199254740993.0, 4.35, 1e22, 1.0e-10, 49.97, 9.15,
        )
        assertEquals(
            "[1.0,0.1,1e+21,100000000000000000000.0,1e-7,0.000001,1.5e+300,-0.0,0.000001,100.0,2.5,12.3," +
                "0.3333333333333333,5e-324,1.7976931348623157e+308,123456.789,0.3,66.66666666666666," +
                "9007199254740992.0,4.35,1e+22,1e-10,49.97,9.15]",
            DartJson.encode(input),
        )
        assertThrows(IllegalArgumentException::class.java) { DartJson.encode(listOf(Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { DartJson.encode(Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `ints strings and nesting match Dart`() {
        assertEquals(
            "[0,-1,9007199254740993,-9223372036854775808]",
            DartJson.encode(listOf(0, -1, 9007199254740993L, Long.MIN_VALUE)),
        )
        assertEquals(
            "[\"a\\ud800b\",\"\\udc00\",\"😀\",\"\\u0000\\u001f\u007f\u2028/<>&\"]",
            DartJson.encode(listOf("a\uD800b", "\uDC00", "\uD83D\uDE00", "\u0000\u001f\u007f\u2028/<>&")),
        )
        assertEquals(
            "{\"b\":[1,2.0,null,true,\"x\"],\"a\":{\"z\":1,\"y\":\"q\"}}",
            DartJson.encode(linkedMapOf("b" to listOf(1, 2.0, null, true, "x"), "a" to linkedMapOf("z" to 1, "y" to "q"))),
        )
        assertEquals("\"\\b\\t\\n\\f\\r\\u000b\\\"\\\\\"", DartJson.quote("\b\t\n\u000c\r\u000b\"\\"))
        assertThrows(IllegalArgumentException::class.java) { DartJson.encode(mapOf(1 to 2)) }
    }

    @Test
    fun `kotlinx parsed numbers are re-read like Dart jsonDecode`() {
        val obj = Json.parseToJsonElement(
            """{"i":10,"d":1.0,"e":1e2,"n":-0,"m":-0.0,"s":"1.0","b":false,"z":null,"big":123456789012345678901234567890}""",
        ).jsonObject
        // Dart: jsonEncode(jsonDecode(same input))
        assertEquals(
            """{"i":10,"d":1.0,"e":100.0,"n":0,"m":-0.0,"s":"1.0","b":false,"z":null,"big":1.2345678901234568e+29}""",
            DartJson.encode(obj),
        )
        assertEquals(
            """{"b":false,"big":1.2345678901234568e+29,"d":1.0,"e":100.0,"i":10,"m":-0.0,"n":0,"s":"1.0","z":null}""",
            DartJson.canonicalJsonEncode(obj),
        )
    }

    @Test
    fun `canonicalJsonEncode is independent of insertion order`() {
        val a = DartJson.canonicalJsonEncode(linkedMapOf("x" to 1, "a" to 2, "c" to 3))
        val b = DartJson.canonicalJsonEncode(linkedMapOf("c" to 3, "x" to 1, "a" to 2))
        val c = DartJson.canonicalJsonEncode(linkedMapOf("a" to 2, "x" to 1, "c" to 3))
        assertEquals(a, b)
        assertEquals(b, c)
        assertEquals("{}", DartJson.canonicalJsonEncode(emptyMap()))
    }

    @Test
    fun `spec fixture compact badge content is canonical JSON`() {
        val badge = fixture("02-badges-claim-vectors.json").jsonObject["compactBadge"]!!.jsonObject
        val content = linkedMapOf<String, Any?>("v" to 2, "t" to "B", "m" to "bad-neuenahr-de", "b" to 917000, "x" to 1760014400)
        assertEquals(badge["content"]!!.jsonPrimitive.content, DartJson.canonicalJsonEncode(content))
    }

    private fun fixture(name: String): kotlinx.serialization.json.JsonElement {
        // Unit tests run with the module directory as working directory.
        val file = File("../../spec/fixtures/$name")
        assertTrue("missing ${file.absolutePath}", file.exists())
        return DartJson.decode(file.readText())
    }
}
