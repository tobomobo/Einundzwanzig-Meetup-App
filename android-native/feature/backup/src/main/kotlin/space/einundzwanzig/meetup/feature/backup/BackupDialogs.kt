package space.einundzwanzig.meetup.feature.backup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import space.einundzwanzig.meetup.core.ui.component.AppContainedLoadingIndicator
import space.einundzwanzig.meetup.core.ui.motion.ExpressiveSprings
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.security.SecureScreenEffect
import space.einundzwanzig.meetup.core.ui.security.SecureWindows
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.backup.BackupPasswordRules
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the backup dialogs. */
object BackupTestTags {
    const val PASSWORD_FIELD = "backup_password"
    const val CONFIRM_FIELD = "backup_password_confirm"
    const val SUBMIT = "backup_password_submit"
    const val CANCEL = "backup_password_cancel"
    const val OVERLAY = "backup_overlay"
}

/**
 * Stateless rendering of the coordinator state: the active dialog plus the loading overlay
 * (spec 08 §2.2, §2.3, §2.5.1, §2.5.2).
 */
@Composable
fun BackupDialogs(state: BackupUiState, onAction: (BackupAction) -> Unit) {
    when (val dialog = state.dialog) {
        is BackupDialog.Password -> BackupPasswordDialog(
            isExport = dialog.isExport,
            onSubmit = { onAction(BackupAction.SubmitPassword(it)) },
            onCancel = { onAction(BackupAction.CancelPassword) },
        )
        is BackupDialog.ConfirmReplace -> IdentityConfirmDialog(
            title = stringResource(L10nR.string.backupReplaceTitle),
            text = stringResource(L10nR.string.backupReplaceBody, dialog.existingShort, dialog.backupShort),
            confirmLabel = stringResource(L10nR.string.backupReplaceConfirm),
            dismissLabel = stringResource(L10nR.string.dialogCancelMixed),
            onConfirm = { onAction(BackupAction.Confirm) },
            onDismiss = { onAction(BackupAction.Decline) },
            destructive = true,
            icon = Icons.Rounded.SwapHoriz,
        )
        is BackupDialog.ConfirmAddUnknown -> IdentityConfirmDialog(
            title = stringResource(L10nR.string.backupAddUnknownTitle),
            text = stringResource(L10nR.string.backupAddUnknownBody, dialog.existingShort),
            confirmLabel = stringResource(L10nR.string.backupAddUnknownConfirm),
            dismissLabel = stringResource(L10nR.string.dialogCancelMixed),
            onConfirm = { onAction(BackupAction.Confirm) },
            onDismiss = { onAction(BackupAction.Decline) },
            icon = Icons.Rounded.PersonAdd,
        )
        null -> Unit
    }
    if (state.loading) BackupLoadingOverlay()
}

/**
 * The identity dialogs (spec 08 §2.5.1, §2.5.2) in the brand style of core:ui `ConfirmDialog`.
 * Flutter's `barrierDismissible: false` blocks only a tap outside; system/predictive back still
 * closes the dialog and counts as cancel. `ConfirmDialog(dismissible = false)` would block back
 * too, so this dialog sets the two flags separately.
 */
@Composable
private fun IdentityConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector,
    destructive: Boolean = false,
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null, tint = accent) },
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
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
    )
}

/**
 * Password dialog (Dart `_promptForPassword`, spec 08 §2.2). Export: password + confirmation with
 * the live rule checklist, button enabled only when both rules pass. Import: one field, no rules,
 * empty input shows `backupPasswordEmpty`. Not dismissible by tapping outside; back cancels.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackupPasswordDialog(
    isExport: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // Plain remember: a password must never end up in the saved-state bundle.
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var obscure by remember { mutableStateOf(true) }
    var obscureConfirm by remember { mutableStateOf(true) }
    var showEmptyError by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val longEnough = BackupPasswordRules.longEnough(password)
    val matches = BackupPasswordRules.matches(password, confirm)
    val allOk = BackupPasswordRules.canSubmit(isExport, password, confirm)
    val submit = {
        if (password.isEmpty()) showEmptyError = true else onSubmit(password)
    }

    AlertDialog(
        onDismissRequest = onCancel,
        properties = SecureWindows.dialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        icon = { ShapedIcon(if (isExport) Icons.Rounded.Lock else Icons.Rounded.LockOpen) },
        title = {
            Text(
                stringResource(if (isExport) L10nR.string.backupEncryptTitle else L10nR.string.backupDecryptTitle),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            SecureScreenEffect()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(if (isExport) L10nR.string.backupExportDesc else L10nR.string.backupImportDesc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                PasswordField(
                    value = password,
                    onValueChange = {
                        password = it
                        showEmptyError = false
                    },
                    label = stringResource(L10nR.string.backupPassword),
                    obscured = obscure,
                    onToggle = { obscure = !obscure },
                    accent = if (!isExport || password.isEmpty()) null else longEnough,
                    error = if (showEmptyError) stringResource(L10nR.string.backupPasswordEmpty) else null,
                    imeAction = if (isExport) ImeAction.Next else ImeAction.Done,
                    onDone = { if (allOk) submit() },
                    modifier = Modifier
                        .focusRequester(focus)
                        .testTag(BackupTestTags.PASSWORD_FIELD),
                )
                if (isExport) {
                    Spacer(Modifier.height(12.dp))
                    PasswordField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = stringResource(L10nR.string.backupPasswordConfirm),
                        obscured = obscureConfirm,
                        onToggle = { obscureConfirm = !obscureConfirm },
                        accent = if (confirm.isEmpty()) null else matches,
                        error = null,
                        imeAction = ImeAction.Done,
                        onDone = { if (allOk) submit() },
                        modifier = Modifier.testTag(BackupTestTags.CONFIRM_FIELD),
                    )
                    Spacer(Modifier.height(14.dp))
                    RuleRow(
                        ok = longEnough,
                        text = stringResource(L10nR.string.backupPwRuleLength, BackupPasswordRules.MIN_PASSWORD_LENGTH),
                    )
                    Spacer(Modifier.height(6.dp))
                    RuleRow(ok = matches, text = stringResource(L10nR.string.backupPwRuleMatch))
                }
            }
            // Autofocus (spec 08 §2.2 item 4). Inside the dialog window's composition, so the
            // requester is attached by the time the effect runs.
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) {
                focus.requestFocus()
                keyboard?.show()
            }
        },
        confirmButton = {
            Button(
                onClick = submit,
                enabled = allOk,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.testTag(BackupTestTags.SUBMIT),
            ) {
                Text(
                    stringResource(if (isExport) L10nR.string.backupEncryptSave else L10nR.string.backupDecryptLoad),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(BackupTestTags.CANCEL)) {
                Text(
                    stringResource(L10nR.string.dialogCancelMixed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    obscured: Boolean,
    onToggle: () -> Unit,
    /** `null` = neutral, `true` = rule met (green), `false` = not met (red). */
    accent: Boolean?,
    error: String?,
    imeAction: ImeAction,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neutral = MaterialTheme.colorScheme.outlineVariant
    val target = when (accent) {
        null -> neutral
        true -> MaterialTheme.extendedColors.success
        false -> MaterialTheme.colorScheme.error
    }
    val border by animateColorAsState(target, label = "pwBorder")
    val focused = if (accent == null) MaterialTheme.colorScheme.primary else border
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (obscured) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (obscured) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = stringResource(if (obscured) L10nR.string.backupPwShow else L10nR.string.backupPwHide),
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = border,
            focusedBorderColor = focused,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    )
}

@Composable
private fun RuleRow(ok: Boolean, text: String) {
    val reduced = rememberReducedMotion()
    val color by animateColorAsState(
        if (ok) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "ruleColor",
    )
    val scale by animateFloatAsState(
        targetValue = if (ok || reduced) 1f else 0.85f,
        animationSpec = ExpressiveSprings.fastSpatial(),
        label = "ruleScale",
    )
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(
            imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
                .scale(scale),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShapedIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(MaterialShapes.Cookie9Sided.toShape())
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
    }
}

/**
 * Blocking overlay during key derivation (spec 08 §2.3): modal, not dismissible, an Expressive
 * contained loading indicator on the default scrim.
 */
@Composable
fun BackupLoadingOverlay() {
    val description = stringResource(R.string.native_backupWorking)
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .testTag(BackupTestTags.OVERLAY)
                .semantics { contentDescription = description },
        ) {
            AppContainedLoadingIndicator(size = 72.dp)
        }
    }
}

@Preview(name = "Export password", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun ExportPasswordPreview() {
    EinundzwanzigTheme {
        BackupDialogs(BackupUiState(dialog = BackupDialog.Password(isExport = true))) {}
    }
}

@Preview(name = "Import password", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun ImportPasswordPreview() {
    EinundzwanzigTheme {
        BackupDialogs(BackupUiState(dialog = BackupDialog.Password(isExport = false))) {}
    }
}

@Preview(name = "Replace identity", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun ReplacePreview() {
    EinundzwanzigTheme {
        BackupDialogs(
            BackupUiState(dialog = BackupDialog.ConfirmReplace("npub1abcdefgh...12345678", "npub1zyxwvuts...87654321")),
        ) {}
    }
}

@Preview(name = "Overlay", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun OverlayPreview() {
    EinundzwanzigTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(verticalArrangement = Arrangement.Center) { BackupDialogs(BackupUiState(loading = true)) {} }
        }
    }
}
