package space.einundzwanzig.meetup.feature.community.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.motion.Pressable
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.elevationShadow
import space.einundzwanzig.meetup.core.ui.theme.extendedColors

/** Tinted icon container (Flutter: 44×44 box, color@0.12). */
@Composable
internal fun IconBox(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    shape: Shape = RoundedCornerShape(11.dp),
) {
    Box(
        modifier
            .size(size)
            .background(color.copy(alpha = 0.12f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
    }
}

/** Trailing chevron of navigable rows. */
@Composable
internal fun RowChevron(modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Rounded.ArrowForwardIos,
        contentDescription = null,
        tint = MaterialTheme.extendedColors.textTertiary,
        modifier = modifier.size(14.dp),
    )
}

/** Section header of the detail screens (15sp w800, padding top 18 / bottom 10). */
@Composable
internal fun DetailSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.padding(top = 18.dp, bottom = 10.dp),
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * Orange link row (spec 11 §2.8: orange@0.08 background, radius 10, orange@0.3 border,
 * padding 12/11, icon 15 + label 13sp w600, one line).
 */
@Composable
internal fun LinkRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.OpenInNew,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .defaultMinSize(minHeight = 48.dp),
        shape = shape,
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = accent,
            )
        }
    }
}

/** Card container of list rows (cCard, radius 10/12, hairline border). */
@Composable
internal fun CardRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            border = BorderStroke(0.5.dp, borderColor),
            content = content,
        )
    } else {
        Box(
            modifier
                .fillMaxWidth()
                .background(containerColor, shape)
                .border(0.5.dp, borderColor, shape),
        ) { content() }
    }
}

/**
 * Accent card of the community hub and the portal area: gradient `color@0.08 → card`, border
 * `color@0.22`, radius 10, tinted elevation-2 shadow, Expressive press scale with ripple.
 */
@Composable
internal fun AccentCard(
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    borderAlpha: Float = 0.22f,
    gradientAlpha: Float = 0.08f,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.TileRadius)
    val card = MaterialTheme.colorScheme.surfaceContainer
    Pressable(
        onClick = onClick,
        modifier = modifier
            .elevationShadow(2f, shape, accent)
            .clip(shape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = gradientAlpha), card.copy(alpha = 0.98f))))
            .border(0.8.dp, accent.copy(alpha = borderAlpha), shape),
        indication = ripple(color = accent),
        role = Role.Button,
        content = content,
    )
}
