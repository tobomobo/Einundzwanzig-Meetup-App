package space.einundzwanzig.meetup.data.badgesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.DartBase64
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Port of `test/badge_sync_codec_test.dart` plus wire checks (spec 08 §7.3). */
class BadgeSyncCodecTest {

    @Test
    fun `gzip hin und zurueck`() {
        val text = """{"v":1,"badges":[{"id":"x"}]}"""
        val sealed = BadgeSyncCodec.deflate(text)
        assertFalse(sealed.contentEquals(DartUtf8.encode(text)))
        assertEquals(text, BadgeSyncCodec.inflate(sealed))
    }

    @Test
    fun `groesserer Text bleibt lesbar`() {
        val text = List(200) { "meetup" }.joinToString(",")
        val sealed = BadgeSyncCodec.deflate(text)
        assertTrue(sealed.size < DartUtf8.encode(text).size)
        assertEquals(text, BadgeSyncCodec.inflate(sealed))
    }

    @Test
    fun `output is RFC 1952 gzip and foreign gzip (other level, umlauts) decodes`() {
        val sealed = BadgeSyncCodec.deflate("x")
        assertEquals(0x1f, sealed[0].toInt() and 0xff)
        assertEquals(0x8b, sealed[1].toInt() and 0xff)

        val text = """{"v":1,"badges":[{"meetupName":"Würzburg 😀"}]}"""
        val out = ByteArrayOutputStream()
        object : GZIPOutputStream(out) {
            init { def.setLevel(9) }
        }.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        assertEquals(text, BadgeSyncCodec.inflate(out.toByteArray()))
    }

    @Test
    fun `fixture envelope survives the base64 gzip wire path`() {
        val envelope = SyncFixtures.sync["envelope_plaintext"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        val wire = DartBase64.encode(BadgeSyncCodec.deflate(envelope))
        assertTrue(wire.endsWith("=") || wire.length % 4 == 0)
        assertEquals(envelope, BadgeSyncCodec.inflate(DartBase64.decode(wire)))
    }

    @Test(expected = java.io.IOException::class)
    fun `not gzip throws`() {
        BadgeSyncCodec.inflate(byteArrayOf(1, 2, 3, 4))
    }

    @Test(expected = java.io.IOException::class)
    fun `gzip bomb above the output limit throws instead of exhausting memory`() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            val zeros = ByteArray(1024 * 1024)
            repeat(BadgeSyncCodec.MAX_INFLATED_BYTES / zeros.size + 1) { gz.write(zeros) }
        }
        BadgeSyncCodec.inflate(out.toByteArray())
    }
}
