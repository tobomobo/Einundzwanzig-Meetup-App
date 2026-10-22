package space.einundzwanzig.meetup.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/**
 * Empty state with a gently breathing icon, an explanation and, where it makes sense, a way
 * forward (port of `lib/widgets/empty_state.dart`). Always answers three questions: what is
 * missing, why, and what the user can do. The button only shows when both [actionLabel] and
 * [onAction] are given.
 *
 * @param accentColor icon circle and button colour; defaults to the brand primary.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PulsingIcon(icon, accentColor)
            Spacer(Modifier.height(24.dp))
            Text(
                text = title,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.3.sp,
                ),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = subtitle,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(26.dp))
                Button(
                    onClick = onAction,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = if (accentColor.luminance() > 0.4f) Color.Black else Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 28.dp),
                ) {
                    Text(actionLabel, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                }
            }
        }
    }
}

/** 96 dp circle breathing between scale 1.0 and 1.06 over 2.4 s; still with reduced motion. */
@Composable
private fun PulsingIcon(icon: ImageVector, color: Color) {
    val reduced = rememberReducedMotion()
    val pulse: State<Float>? = if (reduced) {
        null
    } else {
        rememberInfiniteTransition(label = "emptyStatePulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
            label = "emptyStatePulseValue",
        )
    }
    Box(
        modifier = Modifier
            .size(96.dp)
            .graphicsLayer {
                val scale = 1f + 0.06f * (pulse?.value ?: 0f)
                scaleX = scale
                scaleY = scale
            }
            .background(color.copy(alpha = 0.10f), CircleShape)
            .border(1.5.dp, color.copy(alpha = 0.30f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(40.dp))
    }
}

@Preview(name = "EmptyState", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 480)
@Composable
private fun EmptyStatePreview() {
    EinundzwanzigTheme {
        EmptyState(
            icon = Icons.Rounded.WorkspacePremium,
            title = "Noch keine Badges",
            subtitle = "Scanne beim nächsten Meetup den QR-Code des Organisators.",
            actionLabel = "QR-CODE SCANNEN",
            onAction = {},
        )
    }
}
