package space.einundzwanzig.meetup.feature.community.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Portal image ([url] from `pImg`) with the tinted icon box as placeholder and error fallback.
 */
@Composable
internal fun PortalThumb(
    url: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    radius: Dp = 10.dp,
) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.size(size).clip(shape)) {
        IconBox(icon, color, size = size, iconSize = size * 0.5f, shape = shape)
        if (url.startsWith("http")) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
