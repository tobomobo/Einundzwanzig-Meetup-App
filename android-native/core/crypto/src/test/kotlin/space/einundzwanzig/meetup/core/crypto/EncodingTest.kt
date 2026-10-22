package space.einundzwanzig.meetup.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hex, Base64 and UTF-8 parity (the JSON cases live in `:core:common` `DartJsonTest`). Expected values marked "Dart" were produced by running the
 * app's Dart SDK (3.13) / packages on the same inputs.
 */
class EncodingTest {
    @Test
    fun hex() {
        assertEquals("00ff10ab", byteArrayOf(0, -1, 16, -85).toHex())
        assertArrayEquals(byteArrayOf(0, -1, 16, -85), "00FF10aB".hexToBytes())
        assertThrows(IllegalArgumentException::class.java) { "abc".hexToBytes() }
        assertThrows(IllegalArgumentException::class.java) { "zz".hexToBytes() }
        assertNull(Hex.decodeOrNull("0g"))
        assertTrue(Hex.isHexOfLength("aB".repeat(32), 64))
        assertFalse(Hex.isHexOfLength("aB".repeat(32), 128))
        assertFalse(Hex.isHex(""))
    }

    @Test
    fun base64DecodeLikeDart() {
        // Dart base64.decode results
        val ok = mapOf("YQ==" to "61", "YWI=" to "6162", "-_8=" to "fbff", "+/8=" to "fbff", "" to "")
        for ((input, hex) in ok) assertEquals(input, hex, DartBase64.decode(input).toHex())
        for (bad in listOf("YQ", "YQ=", "YWI", "Y Q==", "YWJj\n", "Y", "YQ==YQ==", "YQ==\n", "====", "YW%3D", "YR==", "YWJ=")) {
            assertThrows(bad, IllegalArgumentException::class.java) { DartBase64.decode(bad) }
        }
        assertEquals("YWJj", DartBase64.encode("abc".toByteArray()))
    }

    @Test
    fun base64UrlHelpersOfThePasskeyService() {
        val salt = DartUtf8.encode("21meetup-easyauth-prf-v1")
        val prf = Fixtures.identity.obj("passkeyPrf")
        assertEquals(prf.str("saltBase64UrlNoPad"), DartBase64.encodeUrlNoPadding(salt))
        assertArrayEquals(salt, DartBase64.decodeUrlLenient(prf.str("saltBase64UrlNoPad")))
        val bytes = byteArrayOf(-5, -1)
        assertEquals("-_8=", DartBase64.encodeUrl(bytes))
        assertEquals("-_8", DartBase64.encodeUrlNoPadding(bytes))
        assertArrayEquals(bytes, DartBase64.decodeUrlLenient("-_8"))
    }

    @Test
    fun utf8LikeDart() {
        // Dart: utf8.encode('a\uD800b\uDC00') = 61 efbfbd 62 efbfbd
        assertEquals("61efbfbd62efbfbd", DartUtf8.encode("a\uD800b\uDC00").toHex())
        assertEquals("f09f9880", DartUtf8.encode("😀").toHex())
        // Dart: utf8.decode([61 ff 62 ed a0 80 c0 af e2 82], allowMalformed: true)
        val lenient = DartUtf8.decodeLenient("61ff62eda080c0afe282".hexToBytes())
        assertEquals("a\uFFFDb\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD", lenient)
        assertThrows(Exception::class.java) { DartUtf8.decodeStrict("61ff".hexToBytes()) }
        assertThrows(Exception::class.java) { DartUtf8.decodeStrict("eda080".hexToBytes()) }
    }

    @Test
    fun lenientUtf8DecodeVectorsFromDart() {
        // Dart: utf8.decode(bytes, allowMalformed: true).codeUnits
        val cases = mapOf(
            "f0908080" to "d800 dc00",
            "f09080" to "fffd",
            "f4908080" to "fffd fffd fffd fffd",
            "e0808061" to "fffd fffd fffd 0061",
            "eda080" to "fffd fffd fffd",
            "edbfbf41" to "fffd fffd fffd 0041",
            "c2" to "fffd",
            "c241" to "fffd 0041",
            "80bf" to "fffd fffd",
            "f8888080" to "fffd fffd fffd fffd",
            "efbbbf61" to "0061",
            "f09f988041" to "d83d de00 0041",
            "e28280" to "2080",
            "fe" to "fffd",
            "c0af" to "fffd fffd",
            "f0" to "fffd",
            "e0a0" to "fffd",
        )
        for ((hex, units) in cases) {
            val decoded = DartUtf8.decodeLenient(hex.hexToBytes())
            assertEquals(hex, units, decoded.map { "%04x".format(it.code) }.joinToString(" "))
        }
    }

    @Test
    fun leadingBomIsDroppedLikeDart() {
        // Dart: utf8.decode([ef bb bf 61]) == "a", a BOM later in the text stays.
        assertEquals("a", DartUtf8.decodeStrict("efbbbf61".hexToBytes()))
        assertEquals("a\uFEFFa", DartUtf8.decodeStrict("61efbbbf61".hexToBytes()))
        assertEquals("\uFEFF", DartUtf8.decodeStrict("efbbbfefbbbf".hexToBytes()))
        assertEquals("", DartUtf8.decodeLenient("efbbbf".hexToBytes()))
    }
}
