package space.einundzwanzig.meetup.core.ui.badge

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import space.einundzwanzig.meetup.core.common.dart.dartRound
import java.security.MessageDigest

/** An RGB colour with the exact double opacity the Dart code computes. */
@Immutable
data class ArtColor(val red: Int, val green: Int, val blue: Int, val alpha: Double) {
    /** Compose colour (alpha narrowed to float only here). */
    fun toColor(): Color = Color(red, green, blue).copy(alpha = alpha.toFloat())
}

/** Shape kinds of the generative badge art (`byte(idx) % 4`). */
enum class BadgeArtShapeType {
    /** 0: circle of radius r. */
    Circle,

    /** 1: rhombus 0.4r wide, 0.6r tall (half extents). */
    Rhombus,

    /** 2: hexagon with circumradius 0.5r, flat sides left/right (first vertex at −30°). */
    Hexagon,

    /** 3: four diagonal lines, always stroked with `color(idx, 0.06)` and width 1. */
    Lines,
}

/**
 * One shape. Positions are fractions of the canvas: `cx = cxFraction · w`,
 * `cy = cyFraction · h`, `r = 10 + radiusFraction · (w · 0.35)` (logical pixels).
 */
@Immutable
data class BadgeArtShape(
    val type: BadgeArtShapeType,
    val cxFraction: Double,
    val cyFraction: Double,
    val radiusFraction: Double,
    /** Paint colour; for [BadgeArtShapeType.Lines] this is the line colour (alpha 0.06). */
    val color: ArtColor,
    /** Stroke (width 1.5) instead of fill; meaningless for [BadgeArtShapeType.Lines]. */
    val stroke: Boolean,
) {
    /** Radius in logical pixels for a canvas [width]. */
    fun radius(width: Double): Double = 10 + radiusFraction * (width * 0.35)
}

/**
 * Pure description of the generative badge picture (Flutter `BadgeArtPainter`, spec 03 §8.6),
 * computed from SHA-256 of the seed. Draw it with [drawBadgeArt]; it must stay bit-identical
 * to the Dart algorithm so a badge looks the same in both apps and on shared images.
 */
@Immutable
data class BadgeArtSpec(
    /** Background gradient colours, top-left → bottom-right, stops 0 / 0.5 / 1. */
    val background: List<ArtColor>,
    /** 5..10 shapes in drawing order. */
    val shapes: List<BadgeArtShape>,
    /** Spacing of the white 3 % grid, 12..19 logical pixels. */
    val gridSize: Int,
) {
    companion object {
        /** The seed used everywhere for a badge: `"$meetupName:$blockHeight"` (spec 03 §7.4). */
        fun seedFor(meetupName: String, blockHeight: Long): String = "$meetupName:$blockHeight"

        /** Spec for [seed] (UTF-8, SHA-256). */
        fun from(seed: String): BadgeArtSpec =
            fromHash(MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8)))

        /** Spec for precomputed hash bytes (read as unsigned, index modulo length). */
        fun fromHash(hash: ByteArray): BadgeArtSpec {
            require(hash.isNotEmpty()) { "hash must not be empty" }
            fun byte(i: Int): Int = hash[i % hash.size].toInt() and 0xFF
            fun color(offset: Int, alpha: Double) = channelColor(byte(offset), byte(offset + 1), byte(offset + 2), alpha)

            val background = listOf(color(0, 0.3), color(3, 0.15), color(6, 0.25))
            val shapeCount = 5 + byte(9) % 6
            val shapes = List(shapeCount) { i ->
                val idx = 10 + i * 3
                val type = BadgeArtShapeType.entries[byte(idx) % 4]
                BadgeArtShape(
                    type = type,
                    cxFraction = byte(idx) / 255.0,
                    cyFraction = byte(idx + 1) / 255.0,
                    radiusFraction = byte(idx + 2) / 255.0,
                    color = if (type == BadgeArtShapeType.Lines) {
                        color(idx, 0.06)
                    } else {
                        color(idx, 0.08 + (byte(idx + 2) % 10) * 0.01)
                    },
                    stroke = byte(idx + 1) % 3 == 0,
                )
            }
            return BadgeArtSpec(background, shapes, gridSize = 12 + byte(30) % 8)
        }

        /**
         * Warm Bitcoin palette from three bytes (Dart `_colorFromHash`), with Dart rounding
         * (ties away from zero):
         * r = clamp(round(r·0.5 + 0.5·247), 100, 255), g = clamp(round(g·0.35 + 0.2·147), 30, 200),
         * b = clamp(round(b·0.2), 0, 80).
         */
        fun channelColor(r: Int, g: Int, b: Int, alpha: Double): ArtColor = ArtColor(
            red = (r * 0.5 + 0.5 * 247).dartRound().toInt().coerceIn(100, 255),
            green = (g * 0.35 + 0.2 * 147).dartRound().toInt().coerceIn(30, 200),
            blue = (b * 0.2).dartRound().toInt().coerceIn(0, 80),
            alpha = alpha,
        )
    }
}
