package space.einundzwanzig.meetup.core.ui.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.WriterException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QrMatrixTest {

    /** Renders the matrix with a 4-module quiet zone and decodes it with ZXing's reader. */
    private fun decode(matrix: QrMatrix): com.google.zxing.Result {
        val scale = 4
        val quiet = 4
        val side = (matrix.size + 2 * quiet) * scale
        val pixels = IntArray(side * side) { i ->
            val x = (i % side) / scale - quiet
            val y = (i / side) / scale - quiet
            val dark = x in 0 until matrix.size && y in 0 until matrix.size && matrix[x, y]
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true))
    }

    @Test
    fun `round trip of an ascii payload`() {
        val payload = "21:eyJtIjoiQXNjaGFmZmVuYnVyZyIsImIiOjg3NTAwMH0.c2lnbmF0dXJl"
        val matrix = QrMatrix.encode(payload, QrErrorCorrection.M)
        assertEquals(17 + 4 * matrix.version, matrix.size)
        assertEquals(payload, decode(matrix).text)
    }

    @Test
    fun `non ascii content is encoded as raw utf-8 bytes without ECI`() {
        val payload = """{"m":"Zürich","n":"Gießen ⚡"}"""
        val result = decode(QrMatrix.encode(payload))
        assertEquals(payload, result.text)
        @Suppress("UNCHECKED_CAST")
        val segments = result.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>
        assertArrayEquals(payload.toByteArray(Charsets.UTF_8), segments.single())
        // An ECI header would surface as a symbology identifier modifier other than 1 (]Q1).
        assertEquals("]Q1", result.resultMetadata[ResultMetadataType.SYMBOLOGY_IDENTIFIER])
    }

    @Test
    fun `smallest version is chosen (qr_flutter QrVersions_auto)`() {
        // Byte mode capacity: version 1 holds 17 bytes at L and 14 at M.
        assertEquals(1, QrMatrix.encode("a".repeat(17), QrErrorCorrection.L).version)
        assertEquals(2, QrMatrix.encode("a".repeat(18), QrErrorCorrection.L).version)
        assertEquals(1, QrMatrix.encode("a".repeat(14), QrErrorCorrection.M).version)
        assertEquals(2, QrMatrix.encode("a".repeat(15), QrErrorCorrection.M).version)
        assertEquals(21, QrMatrix.encode("a", QrErrorCorrection.L).size)
    }

    @Test
    fun `reputation sized payload fits at L`() {
        // ~1000 characters at EC L (spec 04 R-15).
        val payload = "21:" + "x".repeat(997)
        val matrix = QrMatrix.encode(payload, QrErrorCorrection.L)
        assertEquals(payload, decode(matrix).text)
    }

    @Test
    fun `utf8 to latin1 mapping is identity for ascii`() {
        assertEquals("abc", QrMatrix.utf8AsLatin1("abc"))
        assertEquals("Ã¼", QrMatrix.utf8AsLatin1("ü"))
    }

    @Test(expected = WriterException::class)
    fun `oversized content throws`() {
        QrMatrix.encode("x".repeat(3000), QrErrorCorrection.H)
    }

    @Test
    fun `finder pattern corners are dark`() {
        val m = QrMatrix.encode("einundzwanzig")
        listOf(0 to 0, m.size - 1 to 0, 0 to m.size - 1).forEach { (x, y) -> assertEquals(true, m[x, y]) }
        assertFalse(m[7, 7]) // separator around the top-left finder is light
    }
}
