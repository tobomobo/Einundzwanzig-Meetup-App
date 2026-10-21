package space.einundzwanzig.meetup.core.ui.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.WriterException
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.util.UiLog
import kotlin.math.floor
import kotlin.math.min

/** Defaults matching qr_flutter's `QrImageView`. */
object QrCodeDefaults {
    /** qr_flutter's default inner padding (acts as the quiet zone). */
    val Padding: Dp = 10.dp
}

/**
 * Renders [content] as a QR code (replacement for qr_flutter's `QrImageView`):
 * automatic version, square modules, no gaps, [padding] around the symbol on
 * [backgroundColor]. Modules are snapped to whole pixels for crisp edges; the symbol is
 * centred in the remaining space.
 *
 * Used with: rolling QR `size 260, EC M` inside a white card (spec 02 §2.3); reputation QR
 * `size 220, EC L, white background` (spec 04 §2.1); NIP-46 pairing `size 200, EC L, white`.
 * Keep the background light: scanners expect dark modules on a light quiet zone.
 *
 * Content that does not fit (version 40) renders an empty box and is logged.
 */
@Composable
fun QrCode(
    content: String,
    size: Dp,
    modifier: Modifier = Modifier,
    errorCorrection: QrErrorCorrection = QrErrorCorrection.L,
    padding: Dp = QrCodeDefaults.Padding,
    backgroundColor: Color = Color.Transparent,
    moduleColor: Color = Color.Black,
    contentDescription: String? = null,
) {
    val matrix = remember(content, errorCorrection) {
        try {
            QrMatrix.encode(content, errorCorrection)
        } catch (e: WriterException) {
            UiLog.warn("QrCode", "Inhalt passt nicht in einen QR-Code (${content.length} Zeichen)", e)
            null
        }
    }
    Canvas(
        modifier
            .size(size)
            .background(backgroundColor)
            .padding(padding)
            .semantics {
                role = Role.Image
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        if (matrix != null) drawQrMatrix(matrix, moduleColor)
    }
}

/**
 * Draws [matrix] centred in the draw area with whole-pixel modules (fallback: fractional
 * modules if the area is smaller than one pixel per module). Horizontal runs of dark modules
 * are merged into one rectangle.
 */
fun DrawScope.drawQrMatrix(matrix: QrMatrix, color: Color) {
    val n = matrix.size
    val side = min(size.width, size.height)
    val whole = floor(side / n)
    val module = if (whole >= 1f) whole else side / n
    val content = module * n
    val left = (size.width - content) / 2f
    val top = (size.height - content) / 2f
    for (y in 0 until n) {
        var x = 0
        while (x < n) {
            if (!matrix[x, y]) {
                x++
                continue
            }
            val start = x
            while (x < n && matrix[x, y]) x++
            drawRect(
                color = color,
                topLeft = Offset(left + start * module, top + y * module),
                size = Size((x - start) * module, module),
            )
        }
    }
}

@Preview(name = "QrCode", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun QrCodePreview() {
    EinundzwanzigTheme {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp)) {
                QrCode("21:rolling-qr-preview-payload", size = 160.dp, errorCorrection = QrErrorCorrection.M)
            }
            QrCode(
                "nostrconnect://f00d?relay=wss%3A%2F%2Frelay.example&secret=abc",
                size = 160.dp,
                backgroundColor = Color.White,
            )
        }
    }
}
