package space.einundzwanzig.meetup.core.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.theme.BrandColors
import space.einundzwanzig.meetup.core.ui.theme.extendedColors

/** Shared marker styling. */
object MapMarkerDefaults {
    /**
     * Marker ring. Markers sit on light map tiles in every theme, so the ring stays white
     * (Flutter `Colors.white`) instead of following the colour scheme.
     */
    val RingColor: Color = BrandColors.TextPrimary

    val BadgeMarkerSize: Dp = 40.dp
    val MeetupPinSize: Dp = 40.dp
    val LocationDotSize: Dp = 24.dp
    val DropPinSize: Dp = 44.dp
}

/**
 * World map badge marker (spec 03 §2.4): 40 dp circle in `primary` with a 2 dp white ring, an
 * orange glow and a bolt icon. Use with [MarkerAnchor.Center].
 */
@Composable
fun BitcoinBadgeMarker(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(MapMarkerDefaults.BadgeMarkerSize)
            .shadow(8.dp, CircleShape, ambientColor = primary.copy(alpha = 0.5f), spotColor = primary.copy(alpha = 0.5f))
            .background(primary, CircleShape)
            .border(2.dp, MapMarkerDefaults.RingColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Nearby meetup pin (spec 09 §2.2): a 38 dp `location_on` in a 40 dp box, `primary` when the meetup
 * has a matching date, otherwise secondary grey. Flutter centres it on the point
 * ([MarkerAnchor.Center]).
 */
@Composable
fun MeetupPinMarker(active: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(MapMarkerDefaults.MeetupPinSize), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(38.dp),
        )
    }
}

/**
 * Location / search-centre dot (spec 09 §2.2): a 24 dp circle in the community gold with a 2 dp
 * white ring. With [pulse] a soft halo breathes around it (off with reduced motion).
 */
@Composable
fun LocationDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.extendedColors.community,
    pulse: Boolean = true,
) {
    val reducedMotion = rememberReducedMotion()
    val animate = pulse && !reducedMotion
    val halo = if (animate) {
        val transition = rememberInfiniteTransition(label = "locationDotHalo")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
            label = "halo",
        )
        value
    } else {
        null
    }
    val size = MapMarkerDefaults.LocationDotSize
    Box(
        modifier
            .size(size * 2)
            .drawBehind {
                if (halo != null) {
                    val base = size.toPx() / 2
                    drawCircle(
                        color = color.copy(alpha = 0.35f * (1f - halo)),
                        radius = base + base * halo,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .background(color, CircleShape)
                .border(2.dp, MapMarkerDefaults.RingColor, CircleShape),
        )
    }
}

/**
 * Location picker pin (spec 09 §2.6): 44 dp rounded `location_on` in `primary`. Use with
 * [MarkerAnchor.PinTip] so the tip marks the picked point.
 */
@Composable
fun DropPinMarker(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Icon(
        Icons.Rounded.LocationOn,
        contentDescription = null,
        tint = color,
        modifier = modifier.size(MapMarkerDefaults.DropPinSize),
    )
}
