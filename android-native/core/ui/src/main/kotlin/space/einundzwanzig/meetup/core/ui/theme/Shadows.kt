package space.einundzwanzig.meetup.core.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Port of `shadowForElevation` (`lib/widgets/shadows.dart`): slim, accent-tinted shadows
 * instead of Material's grey elevation.
 *
 * | elevation | shadow |
 * |---|---|
 * | `<= 0` | none |
 * | `<= 2` | accent @ 0.08, blur 10, spread -3, offset (0, 3) |
 * | `<= 4` | accent @ 0.10, blur 16, spread -4, offset (0, 6) |
 * | else | accent @ 0.14, blur 24, spread -5, offset (0, 10) |
 *
 * @return the shadow to draw or `null` for no shadow.
 */
fun shadowForElevation(elevation: Float, accent: Color = BrandColors.Orange): Shadow? = when {
    elevation <= 0f -> null
    elevation <= 2f -> Shadow(
        radius = 10.dp,
        color = accent.copy(alpha = 0.08f),
        spread = (-3).dp,
        offset = DpOffset(0.dp, 3.dp),
    )
    elevation <= 4f -> Shadow(
        radius = 16.dp,
        color = accent.copy(alpha = 0.10f),
        spread = (-4).dp,
        offset = DpOffset(0.dp, 6.dp),
    )
    else -> Shadow(
        radius = 24.dp,
        color = accent.copy(alpha = 0.14f),
        spread = (-5).dp,
        offset = DpOffset(0.dp, 10.dp),
    )
}

/**
 * Draws the brand shadow for [elevation] (see [shadowForElevation]) behind the content,
 * following [shape]. No-op for elevation `<= 0`.
 */
fun Modifier.elevationShadow(
    elevation: Float,
    shape: Shape,
    accent: Color = BrandColors.Orange,
): Modifier {
    val shadow = shadowForElevation(elevation, accent) ?: return this
    return this.dropShadow(shape, shadow)
}
