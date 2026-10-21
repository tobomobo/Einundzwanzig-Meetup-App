package space.einundzwanzig.meetup.core.ui.qr

import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** QR error correction level (qr_flutter `QrErrorCorrectLevel`). */
enum class QrErrorCorrection(internal val zxing: ErrorCorrectionLevel) {
    /** ~7 % recovery; qr_flutter's default, used for the dense reputation QR (spec 04). */
    L(ErrorCorrectionLevel.L),

    /** ~15 %; the organizer's rolling QR (spec 02 §2.3). */
    M(ErrorCorrectionLevel.M),

    /** ~25 %. */
    Q(ErrorCorrectionLevel.Q),

    /** ~30 %. */
    H(ErrorCorrectionLevel.H),
}

/**
 * An encoded QR symbol: [size] × [size] modules, `true` = dark. No quiet zone is included
 * (qr_flutter draws none either; the caller's padding provides it).
 */
class QrMatrix internal constructor(
    /** Modules per side (21 for version 1, +4 per version). */
    val size: Int,
    /** QR version 1..40 chosen automatically (smallest that fits). */
    val version: Int,
    private val modules: BooleanArray,
) {
    /** Whether the module at column [x], row [y] is dark. */
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    companion object {
        /**
         * Encodes [content] with automatic version selection.
         *
         * Payload bytes are identical to the Flutter app (`qr` 3.0.2 `QrByte`): the UTF-8 bytes
         * of the string without an ECI header. ZXing would otherwise default to ISO-8859-1 or
         * add an ECI segment for UTF-8; both would change what older scanners decode.
         *
         * @throws WriterException if the content does not fit into version 40 at [errorCorrection].
         */
        fun encode(content: String, errorCorrection: QrErrorCorrection = QrErrorCorrection.L): QrMatrix {
            val code = Encoder.encode(utf8AsLatin1(content), errorCorrection.zxing)
            val matrix = code.matrix
            val n = matrix.width
            val modules = BooleanArray(n * n) { i -> matrix.get(i % n, i / n).toInt() == 1 }
            return QrMatrix(n, code.version.versionNumber, modules)
        }

        /** Maps each UTF-8 byte of [s] to the Latin-1 char with the same value. */
        internal fun utf8AsLatin1(s: String): String {
            if (s.all { it.code < 0x80 }) return s
            return String(s.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        }
    }
}
