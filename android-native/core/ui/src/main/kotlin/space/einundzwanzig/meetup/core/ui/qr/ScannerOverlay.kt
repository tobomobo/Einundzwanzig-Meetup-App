package space.einundzwanzig.meetup.core.ui.qr

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/**
 * Drawing-only overlay for the camera preview (port of `lib/widgets/scanner_overlay.dart`,
 * spec 02 §2.7): the surroundings are dimmed (black 54 %), a [windowSize] square window with
 * radius 20 stays clear, four 30 dp corner brackets (stroke 3, outer radius 18) frame it and a
 * glowing scan line wanders up and down (2.2 s, reversing). The line stands still with
 * reduced motion. The overlay consumes no touches; the camera lives in the feature.
 */
@Composable
fun ScannerOverlay(
    modifier: Modifier = Modifier,
    windowSize: Dp = 260.dp,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val reduced = rememberReducedMotion()
    val progress: State<Float>? = if (reduced) {
        null
    } else {
        rememberInfiniteTransition(label = "scannerLine").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse),
            label = "scannerLineProgress",
        )
    }
    // One native paint for the glow, reused on every animation frame.
    val glowPaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }
    Canvas(modifier.fillMaxSize()) {
        drawScannerOverlay(windowSize.toPx(), accent, progress?.value ?: 0f, glowPaint)
    }
}

/**
 * The overlay's drawing; [lineProgress] 0..1 positions the scan line. Pass a reusable
 * [glowPaint] when drawing every frame (it is reconfigured here).
 */
fun DrawScope.drawScannerOverlay(
    windowPx: Float,
    accent: Color,
    lineProgress: Float,
    glowPaint: android.graphics.Paint = android.graphics.Paint().apply { isAntiAlias = true },
) {
    val window = Rect(
        offset = Offset((size.width - windowPx) / 2f, (size.height - windowPx) / 2f),
        size = Size(windowPx, windowPx),
    )
    // 1) Dim everything except the rounded window.
    val dim = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addRoundRect(RoundRect(window, CornerRadius(20.dp.toPx())))
    }
    drawPath(dim, Color.Black.copy(alpha = 0.54f))

    // 2) Corner brackets: stroke inside a 30 x 30 box, outer radius 18.
    val len = 30.dp.toPx()
    val thick = 3.dp.toPx()
    val radius = 18.dp.toPx() - thick / 2f
    val half = thick / 2f
    val stroke = Stroke(width = thick, cap = StrokeCap.Butt)
    fun corner(originX: Float, originY: Float, dirX: Float, dirY: Float) {
        // (originX, originY) is the window corner; dir points into the window.
        val x0 = originX + dirX * half
        val y0 = originY + dirY * half
        val path = Path().apply {
            moveTo(x0, originY + dirY * len)
            lineTo(x0, y0 + dirY * radius)
            quadraticTo(x0, y0, x0 + dirX * radius, y0)
            lineTo(originX + dirX * len, y0)
        }
        drawPath(path, accent, style = stroke)
    }
    corner(window.left, window.top, 1f, 1f)
    corner(window.right, window.top, -1f, 1f)
    corner(window.left, window.bottom, 1f, -1f)
    corner(window.right, window.bottom, -1f, -1f)

    // 3) Scan line with glow: top = 12 + (size - 24) * t, inset 14 left/right, 2 dp tall.
    val lineTop = window.top + 12.dp.toPx() + (windowPx - 24.dp.toPx()) * lineProgress
    val lineRect = Rect(
        left = window.left + 14.dp.toPx(),
        top = lineTop,
        right = window.right - 14.dp.toPx(),
        bottom = lineTop + 2.dp.toPx(),
    )
    drawIntoCanvas { canvas ->
        glowPaint.color = accent.copy(alpha = 0.55f).toArgb()
        val blur = 9.dp.toPx()
        if ((glowPaint.maskFilter as? GlowBlur)?.radius != blur) glowPaint.maskFilter = GlowBlur(blur)
        canvas.nativeCanvas.drawRect(lineRect.left, lineRect.top, lineRect.right, lineRect.bottom, glowPaint)
    }
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(accent.copy(alpha = 0f), accent, accent.copy(alpha = 0f)),
            startX = lineRect.left,
            endX = lineRect.right,
        ),
        topLeft = lineRect.topLeft,
        size = lineRect.size,
    )
}

/** Blur mask that remembers its radius, so the glow paint is only reconfigured on change. */
private class GlowBlur(val radius: Float) : BlurMaskFilter(radius, Blur.NORMAL)

@Preview(name = "ScannerOverlay", showBackground = true, widthDp = 360, heightDp = 560)
@Composable
private fun ScannerOverlayPreview() {
    EinundzwanzigTheme {
        ScannerOverlay(Modifier.background(Color(0xFF3A4A5A)))
    }
}
