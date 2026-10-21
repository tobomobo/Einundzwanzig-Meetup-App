package space.einundzwanzig.meetup.core.ui.badge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generative badge picture for [seed] (`"$meetupName:$blockHeight"`, see
 * [BadgeArtSpec.seedFor]); used on wallet cards, compact cards, the details hero fallback
 * and the share card. Fills the given size.
 */
@Composable
fun BadgeArt(seed: String, modifier: Modifier = Modifier) {
    val spec = remember(seed) { BadgeArtSpec.from(seed) }
    Canvas(modifier) { drawBadgeArt(spec) }
}

/**
 * Paints [spec] exactly like Flutter's `BadgeArtPainter` (spec 03 §8.6). The algorithm works in
 * logical pixels, so the canvas is scaled by the density and all geometry is computed in dp.
 */
fun DrawScope.drawBadgeArt(spec: BadgeArtSpec) {
    val w = (size.width / density).toDouble()
    val h = (size.height / density).toDouble()
    if (w <= 0.0 || h <= 0.0) return
    scale(density, density, pivot = Offset.Zero) {
        val area = Size(w.toFloat(), h.toFloat())
        // 1. Background gradient.
        drawRect(
            brush = Brush.linearGradient(
                colors = spec.background.map { it.toColor() },
                start = Offset.Zero,
                end = Offset(area.width, area.height),
            ),
            size = area,
        )
        // 2. Shapes.
        spec.shapes.forEach { shape -> drawShape(shape, w, h) }
        // 3. Fine grid.
        val gridColor = Color.White.copy(alpha = 0.03f)
        val g = spec.gridSize.toDouble()
        var x = 0.0
        while (x < w) {
            drawLine(gridColor, Offset(x.toFloat(), 0f), Offset(x.toFloat(), area.height), strokeWidth = 0.5f)
            x += g
        }
        var y = 0.0
        while (y < h) {
            drawLine(gridColor, Offset(0f, y.toFloat()), Offset(area.width, y.toFloat()), strokeWidth = 0.5f)
            y += g
        }
    }
}

private fun DrawScope.drawShape(shape: BadgeArtShape, w: Double, h: Double) {
    val cx = shape.cxFraction * w
    val cy = shape.cyFraction * h
    val r = shape.radius(w)
    val color = shape.color.toColor()
    val style: DrawStyle = if (shape.stroke) Stroke(width = 1.5f) else Fill
    when (shape.type) {
        BadgeArtShapeType.Circle -> drawCircle(color, r.toFloat(), Offset(cx.toFloat(), cy.toFloat()), style = style)
        BadgeArtShapeType.Rhombus -> {
            val path = Path().apply {
                moveTo(cx.toFloat(), (cy - r * 0.6).toFloat())
                lineTo((cx + r * 0.4).toFloat(), cy.toFloat())
                lineTo(cx.toFloat(), (cy + r * 0.6).toFloat())
                lineTo((cx - r * 0.4).toFloat(), cy.toFloat())
                close()
            }
            drawPath(path, color, style = style)
        }
        BadgeArtShapeType.Hexagon -> {
            val path = Path()
            for (j in 0 until 6) {
                val angle = (PI / 3) * j - PI / 6
                val px = (cx + r * 0.5 * cos(angle)).toFloat()
                val py = (cy + r * 0.5 * sin(angle)).toFloat()
                if (j == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            drawPath(path, color, style = style)
        }
        BadgeArtShapeType.Lines -> for (l in 0 until 4) {
            val offset = l * r * 0.3
            drawLine(
                color = color,
                start = Offset((cx - r + offset).toFloat(), (cy - r).toFloat()),
                end = Offset((cx + r + offset).toFloat(), (cy + r).toFloat()),
                strokeWidth = 1f,
            )
        }
    }
}

@Preview(name = "BadgeArt", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun BadgeArtPreview() {
    EinundzwanzigTheme {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Aschaffenburg:875000", "Wien:880001", "Zürich:0").forEach { seed ->
                BadgeArt(
                    seed,
                    Modifier
                        .size(110.dp, 150.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
        }
    }
}
