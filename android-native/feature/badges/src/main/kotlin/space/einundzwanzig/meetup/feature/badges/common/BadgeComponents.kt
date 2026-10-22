package space.einundzwanzig.meetup.feature.badges.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * The pulsing scanner/writer circle (spec 02 §2.1 item 1, §2.4): accent ring with a soft glow,
 * scale [minScale]↔[maxScale] over 2 s (still with reduced motion).
 */
@Composable
internal fun PulsingCircle(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 200.dp,
    minScale: Float = 0.9f,
    maxScale: Float = 1.1f,
) {
    val reduced = rememberReducedMotion()
    val scale = if (reduced) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = minScale,
            targetValue = maxScale,
            animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
            label = "pulseScale",
        ).value
    }
    Box(
        modifier
            .size(size)
            .scale(scale)
            .dropShadow(CircleShape, Shadow(radius = 30.dp, color = accent.copy(alpha = 0.35f)))
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.08f))
            .border(4.dp, accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(size * 0.4f))
    }
}

/** Tinted Material shape icon container (confirmation and success headers). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShapedIcon(icon: ImageVector, accent: Color, modifier: Modifier = Modifier, size: Dp = 80.dp) {
    Box(
        modifier
            .size(size)
            .clip(MaterialShapes.Cookie9Sided.toShape())
            .background(accent.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(size * 0.5f))
    }
}

/** Full-width 52–56 dp filled action button with a leading icon. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PrimaryActionButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        shapes = ButtonDefaults.shapes(),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * NFC "not supported" / "disabled" dialog of the scanner and the writer (spec 02 §2.1, §2.4):
 * NFC icon (error colour when unsupported), the body text, an optional extra action and "OK".
 */
@Composable
internal fun NfcStateDialog(
    unsupported: Boolean,
    body: String,
    onDismiss: () -> Unit,
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
) {
    val accent = if (unsupported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Nfc, contentDescription = null, tint = accent, modifier = Modifier.size(48.dp)) },
        title = {
            Text(stringResource(if (unsupported) L10nR.string.nfcUnavailable else L10nR.string.nfcDisabled))
        },
        text = { Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(L10nR.string.actionOk)) }
        },
        dismissButton = if (extraLabel != null && onExtra != null) {
            {
                TextButton(onClick = onExtra) {
                    Text(extraLabel, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            null
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

/** Vertical gap helper. */
@Composable
internal fun Gap(height: Dp) = Spacer(Modifier.height(height))
