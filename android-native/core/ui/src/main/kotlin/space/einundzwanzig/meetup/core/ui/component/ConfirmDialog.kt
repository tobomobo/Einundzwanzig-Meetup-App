package space.einundzwanzig.meetup.core.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * Confirmation dialog in the brand style (Flutter `DialogTheme`: card colour, no tint,
 * radius 12). The confirm action is red and bold when [destructive].
 *
 * @param dismissLabel defaults to the localized `actionCancel`.
 * @param dismissible false blocks back/outside dismissal (Flutter `barrierDismissible: false`).
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = stringResource(L10nR.string.actionCancel),
    destructive: Boolean = false,
    icon: ImageVector? = null,
    dismissible: Boolean = true,
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        modifier = modifier,
        icon = icon?.let { { Icon(it, contentDescription = null, tint = accent) } },
        title = { Text(title, color = if (destructive) accent else MaterialTheme.colorScheme.onSurface) },
        text = { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        shape = RoundedCornerShape(12.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
    )
}

@Preview(name = "ConfirmDialog", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun ConfirmDialogPreview() {
    EinundzwanzigTheme {
        ConfirmDialog(
            title = "Session beenden?",
            text = "Der QR-Code wird ungültig. Teilnehmer können danach kein Badge mehr scannen.",
            confirmLabel = "BEENDEN",
            onConfirm = {},
            onDismiss = {},
            destructive = true,
            icon = Icons.Rounded.DeleteForever,
        )
    }
}
