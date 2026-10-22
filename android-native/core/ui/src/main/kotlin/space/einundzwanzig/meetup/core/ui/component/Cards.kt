package space.einundzwanzig.meetup.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.motion.pressScale
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/** Defaults for [GlassCard] and [Tile]. */
object CardDefaults {
    /** Default inner padding of [GlassCard] (Flutter: 20). */
    val GlassPadding = PaddingValues(20.dp)

    /** Default glass gradient: top-left to bottom-right over two raised surfaces. */
    @Composable
    fun glassGradient(): Brush = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )

    /** Hairline border of glass cards (Flutter `cGlassBorder`, white @ 8 %). */
    @Composable
    fun glassBorderColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
}

/**
 * Port of Flutter's `GlassCard`: a raised card with a subtle diagonal gradient, hairline
 * border and a soft shadow. With [glowing] the shadow is tinted with [borderColor] (or the
 * brand orange) instead of black.
 *
 * @param onClick makes the whole card tappable (with press scale and ripple).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = CardDefaults.GlassPadding,
    borderColor: Color? = null,
    cornerRadius: Dp = Dimens.GlassRadius,
    gradient: Brush = CardDefaults.glassGradient(),
    glowing: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val shadow = if (glowing) {
        Shadow(
            radius = 24.dp,
            color = (borderColor ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.08f),
            spread = (-4).dp,
            offset = DpOffset(0.dp, 8.dp),
        )
    } else {
        Shadow(
            radius = 12.dp,
            color = Color.Black.copy(alpha = 0.2f),
            offset = DpOffset(0.dp, 4.dp),
        )
    }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interactionSource, enabled = onClick != null)
            .dropShadow(shape, shadow)
            .clip(shape)
            .tapTarget(interactionSource, onClick, onLongClick = null, onClickLabel = null)
            .background(gradient)
            .border(Dimens.Hairline, borderColor ?: CardDefaults.glassBorderColor(), shape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * The dashboard / list tile of the brand: flat card surface, 10 dp corners and a hairline
 * border (Flutter `CardTheme`: `cCard`, radius 10, side 0.5 `cTileBorder`).
 *
 * Tap and long press are optional; with either the tile sinks while pressed. A long press is
 * only claimed when [onLongClick] is given.
 */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = Dimens.TileShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    onClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interactionSource, enabled = onClick != null || onLongClick != null)
            .clip(shape)
            .tapTarget(interactionSource, onClick, onLongClick, onClickLabel)
            .background(containerColor)
            .border(Dimens.Hairline, borderColor, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** Clickable with ripple, claiming only the gestures that are given. */
internal fun Modifier.tapTarget(
    interactionSource: MutableInteractionSource,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onClickLabel: String?,
): Modifier = if (onClick == null && onLongClick == null) {
    this
} else {
    this.combinedClickable(
        interactionSource = interactionSource,
        indication = ripple(),
        onClickLabel = onClickLabel,
        role = Role.Button,
        onLongClick = onLongClick,
        hapticFeedbackEnabled = false,
        onClick = onClick ?: {},
    )
}

@Preview(name = "GlassCard / Tile", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun CardsPreview() {
    EinundzwanzigTheme {
        Column(Modifier.padding(16.dp)) {
            GlassCard(onClick = {}, glowing = true) {
                Column {
                    Text("Glass card", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Gradient, hairline border, tinted glow",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Dimens.TileGap))
            Row {
                Tile(Modifier.weight(1f), onClick = {}) {
                    Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(Dimens.TileGap))
                Tile(Modifier.weight(1f), onClick = {}) {
                    Text("Tile", Modifier.align(Alignment.Center))
                }
            }
        }
    }
}
