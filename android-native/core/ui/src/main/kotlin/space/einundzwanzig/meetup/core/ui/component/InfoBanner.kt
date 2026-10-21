package space.einundzwanzig.meetup.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors

/** Semantic tone shared by [InfoBanner] and the app snackbars. */
enum class Tone { Neutral, Info, Success, Warning, Error }

/** Accent colour of a [Tone] in the current theme. */
@Composable
@ReadOnlyComposable
fun Tone.accentColor(): Color = when (this) {
    Tone.Neutral -> MaterialTheme.colorScheme.onSurface
    Tone.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Success -> MaterialTheme.extendedColors.success
    Tone.Warning -> MaterialTheme.colorScheme.primary
    Tone.Error -> MaterialTheme.colorScheme.error
}

/** Default icon of a [Tone]. */
fun Tone.defaultIcon(): ImageVector = when (this) {
    Tone.Neutral, Tone.Info -> Icons.Rounded.Info
    Tone.Success -> Icons.Rounded.CheckCircle
    Tone.Warning -> Icons.Rounded.WarningAmber
    Tone.Error -> Icons.Rounded.ErrorOutline
}

/**
 * Inline banner for warnings and hints inside a screen (e.g. the "no identity" warning on the
 * reputation screen, the device-integrity warning on home): tinted container, 50 % border,
 * radius 12, padding 14, 24 dp icon and 12 sp text with line height 1.4.
 *
 * @param actionLabel optional text button at the end; requires [onAction].
 */
@Composable
fun InfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Info,
    title: String? = null,
    icon: ImageVector = tone.defaultIcon(),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val accent = tone.accentColor()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), shape)
            .border(1.dp, accent.copy(alpha = 0.5f), shape)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    text = title,
                    color = accent,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = text,
                color = if (tone == Tone.Neutral || tone == Tone.Info) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    accent
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Preview(name = "InfoBanner", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun InfoBannerPreview() {
    EinundzwanzigTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoBanner("Ohne Nostr-Identität kann niemand deine Badges prüfen.", tone = Tone.Error)
            InfoBanner("Root erkannt — deine Schlüssel sind weniger geschützt.", tone = Tone.Warning, title = "Gerät")
            InfoBanner("Backup erstellt.", tone = Tone.Success, actionLabel = "Anzeigen", onAction = {})
            InfoBanner("Tippe auf ein Badge, um Details zu sehen.")
        }
    }
}
