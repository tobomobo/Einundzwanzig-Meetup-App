package space.einundzwanzig.meetup.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import space.einundzwanzig.meetup.core.ui.component.ConfirmDialog
import space.einundzwanzig.meetup.feature.home.HomeAction
import space.einundzwanzig.meetup.feature.home.R
import space.einundzwanzig.meetup.feature.home.ResetStep
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * The three-step reset confirmation (spec 12 §2.5.12): confirm → mandatory backup offer →
 * (after a successful backup) confirm again; plus a blocking indicator while the wipe runs.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ResetDialogs(step: ResetStep, onAction: (HomeAction) -> Unit) {
    when (step) {
        ResetStep.NONE -> Unit
        ResetStep.CONFIRM -> ConfirmDialog(
            title = stringResource(L10nR.string.resetTitle),
            text = stringResource(L10nR.string.resetBody),
            confirmLabel = stringResource(L10nR.string.resetConfirm),
            dismissLabel = stringResource(L10nR.string.resetCancel),
            onConfirm = { onAction(HomeAction.ResetConfirm) },
            onDismiss = { onAction(HomeAction.ResetCancel) },
            destructive = true,
            icon = Icons.Rounded.DeleteForever,
            modifier = Modifier.testTag(HomeTags.RESET_CONFIRM),
        )
        ResetStep.BACKUP_OFFER -> AlertDialog(
            // Back cancels (Flutter pops with null); a tap outside does nothing.
            onDismissRequest = { onAction(HomeAction.ResetCancel) },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.testTag(HomeTags.RESET_BACKUP),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(L10nR.string.resetBackupTitle), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    stringResource(L10nR.string.resetBackupBody),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                    Button(onClick = { onAction(HomeAction.ResetCreateBackup) }, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.CloudUpload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(L10nR.string.resetBackupCreate), fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { onAction(HomeAction.ResetSkipBackup) }) {
                        Text(stringResource(L10nR.string.resetBackupSkip), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { onAction(HomeAction.ResetCancel) }) {
                        Text(stringResource(L10nR.string.resetCancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        )
        ResetStep.BACKUP_DONE -> ConfirmDialog(
            title = stringResource(L10nR.string.resetBackupTitle),
            text = stringResource(L10nR.string.resetBackupDone),
            confirmLabel = stringResource(L10nR.string.resetNowConfirm),
            dismissLabel = stringResource(L10nR.string.resetCancel),
            onConfirm = { onAction(HomeAction.ResetNow) },
            onDismiss = { onAction(HomeAction.ResetCancel) },
            destructive = true,
            modifier = Modifier.testTag(HomeTags.RESET_DONE),
        )
        ResetStep.RUNNING -> Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Column(
                Modifier.padding(24.dp).testTag(HomeTags.RESET_RUNNING),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LoadingIndicator(Modifier.size(64.dp))
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.native_home_reset_running), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
