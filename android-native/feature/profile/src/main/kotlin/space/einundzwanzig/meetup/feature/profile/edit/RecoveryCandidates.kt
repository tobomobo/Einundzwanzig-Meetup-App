package space.einundzwanzig.meetup.feature.profile.edit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.security.SecureScreenEffect
import space.einundzwanzig.meetup.core.ui.security.SecureWindows
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.feature.profile.R
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@Composable
internal fun RecoveryCandidates(state: ProfileEditUiState, onAction: (ProfileEditAction) -> Unit) {
    if (state.recoveryCandidates.isEmpty()) return
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.native_recovery_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.native_recovery_body), style = MaterialTheme.typography.bodyMedium)
            state.recoveryCandidates.forEach { candidate ->
                HorizontalDivider()
                Text(candidate.npub, style = MonoStyle)
                TextButton(enabled = !state.recoveryBusy, onClick = {
                    onAction(ProfileEditAction.RecoveryRequest(candidate, RecoveryOperation.RESTORE))
                }) { Text(stringResource(R.string.native_recovery_restore)) }
                TextButton(enabled = !state.recoveryBusy, onClick = {
                    onAction(ProfileEditAction.RecoveryRequest(candidate, RecoveryOperation.EXPORT))
                }) { Text(stringResource(R.string.native_recovery_export)) }
                TextButton(enabled = !state.recoveryBusy, onClick = {
                    onAction(ProfileEditAction.RecoveryRequest(candidate, RecoveryOperation.DISCARD))
                }) { Text(stringResource(R.string.native_recovery_discard), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
internal fun RecoveryConfirmation(
    dialog: ProfileDialog.Recovery,
    onAction: (ProfileEditAction) -> Unit,
    onDismiss: () -> Unit,
) {
    // Plain remember: never serialize a recovery password into saved instance state.
    var password by remember(dialog) { mutableStateOf("") }
    val needsPassword = dialog.operation == RecoveryOperation.RESTORE && dialog.candidate.needsPassword
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureWindows.dialogProperties(),
        title = {
            SecureScreenEffect()
            Text(stringResource(when (dialog.operation) {
                RecoveryOperation.RESTORE -> R.string.native_recovery_restore
                RecoveryOperation.EXPORT -> R.string.native_recovery_export
                RecoveryOperation.DISCARD -> R.string.native_recovery_discard
            }))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(dialog.candidate.npub, style = MonoStyle)
                Text(stringResource(when (dialog.operation) {
                    RecoveryOperation.RESTORE -> R.string.native_recovery_restore_confirm
                    RecoveryOperation.EXPORT -> R.string.native_recovery_export_confirm
                    RecoveryOperation.DISCARD -> R.string.native_recovery_discard_confirm
                }))
                if (needsPassword) OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(L10nR.string.backupPassword)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !needsPassword || password.isNotEmpty(),
                onClick = { onAction(ProfileEditAction.RecoveryConfirmed(password.takeIf { needsPassword })) },
            ) { Text(stringResource(L10nR.string.actionOk)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(L10nR.string.dialogCancel)) } },
    )
}
