package space.einundzwanzig.meetup.feature.profile.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.ConfirmDialog
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.security.SecureScreenEffect
import space.einundzwanzig.meetup.core.ui.security.SecureWindows
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.profile.R
import space.einundzwanzig.meetup.feature.profile.bunker.BunkerConnectSheet
import space.einundzwanzig.meetup.feature.profile.bunker.BunkerSheetState

/** Renders the open [ProfileEditUiState.dialog] (at most one). */
@Composable
internal fun ProfileDialogs(
    state: ProfileEditUiState,
    bunkerState: BunkerSheetState,
    onAction: (ProfileEditAction) -> Unit,
) {
    val dismiss = { onAction(ProfileEditAction.DismissDialog) }
    when (val dialog = state.dialog) {
        null -> Unit
        ProfileDialog.UnlockWarning -> ConfirmDialog(
            title = stringResource(L10nR.string.profileWarning),
            text = stringResource(L10nR.string.profileEditWarnDesc),
            confirmLabel = stringResource(L10nR.string.profileEdit),
            dismissLabel = stringResource(L10nR.string.dialogCancelMixed),
            destructive = true,
            icon = Icons.Rounded.Edit,
            onConfirm = { onAction(ProfileEditAction.ConfirmEdit) },
            onDismiss = dismiss,
        )
        ProfileDialog.KeyEducation -> KeyEducationDialog(
            onResult = { onAction(ProfileEditAction.KeyEducationResult(it)) },
        )
        is ProfileDialog.NsecBackup -> NsecBackupDialog(
            nsec = dialog.nsec,
            onCopied = { onAction(ProfileEditAction.NsecCopied) },
            onClose = dismiss,
        )
        ProfileDialog.ShowNsecConfirm -> ConfirmDialog(
            title = stringResource(L10nR.string.profileShowNsecQ),
            text = stringResource(L10nR.string.profileShowNsecWarn),
            confirmLabel = stringResource(L10nR.string.profileShow),
            dismissLabel = stringResource(L10nR.string.dialogCancel),
            destructive = true,
            icon = Icons.Rounded.Key,
            onConfirm = { onAction(ProfileEditAction.ShowNsecConfirmed) },
            onDismiss = dismiss,
        )
        ProfileDialog.ImportNsec -> ImportNsecDialog(
            importing = state.isImportingNsec,
            onImport = { onAction(ProfileEditAction.ImportNsec(it)) },
            onDismiss = dismiss,
        )
        ProfileDialog.ExportPassword -> ExportPasswordDialog(
            onConfirm = { onAction(ProfileEditAction.ExportPassword(it)) },
            onCancel = dismiss,
        )
        is ProfileDialog.NcryptsecSheet -> NcryptsecSheet(
            ncryptsec = dialog.ncryptsec,
            fromVault = dialog.fromVault,
            onCopied = { onAction(ProfileEditAction.NcryptsecCopied) },
            onOtherPassword = { onAction(ProfileEditAction.ExportOtherPassword) },
            onDismiss = dismiss,
        )
        is ProfileDialog.SwitchSignerConfirm -> ConfirmDialog(
            title = stringResource(L10nR.string.profileSwitchSignerTitle),
            text = stringResource(L10nR.string.profileSwitchSignerBody),
            confirmLabel = stringResource(L10nR.string.profileSwitchSignerContinue),
            dismissLabel = stringResource(L10nR.string.dialogCancel),
            icon = Icons.Rounded.SwapHoriz,
            onConfirm = { onAction(ProfileEditAction.SwitchSignerConfirmed(dialog.target)) },
            onDismiss = dismiss,
        )
        ProfileDialog.AmberNotFound -> AlertDialog(
            onDismissRequest = dismiss,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            icon = { Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.extendedColors.community) },
            title = { Text(stringResource(L10nR.string.profileAmberNotFound), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(L10nR.string.profileAmberDesc) +
                        stringResource(L10nR.string.profileAmberInstall) +
                        stringResource(L10nR.string.profileAmberRetry),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(L10nR.string.actionOk), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
        )
        ProfileDialog.DisconnectConfirm -> ConfirmDialog(
            title = stringResource(L10nR.string.profileDisconnectTitle),
            text = stringResource(L10nR.string.profileDisconnectBody),
            confirmLabel = stringResource(L10nR.string.profileDisconnectSigner),
            dismissLabel = stringResource(L10nR.string.dialogCancel),
            icon = Icons.Rounded.LinkOff,
            onConfirm = { onAction(ProfileEditAction.DisconnectConfirmed) },
            onDismiss = dismiss,
        )
        ProfileDialog.BunkerConnect -> BunkerConnectSheet(
            state = bunkerState,
            onAction = { onAction(ProfileEditAction.Bunker(it)) },
        )
        ProfileDialog.MeetupPicker -> MeetupPickerSheet(
            meetups = state.meetups,
            initialSelected = state.favorites,
            onDone = { onAction(ProfileEditAction.MeetupPickerDone(it)) },
            onDismiss = dismiss,
        )
    }
}

// ------------------------------------------------------------------------------------ key education

private data class EduSection(val icon: ImageVector, val color: @Composable () -> Color, val title: Int, val body: Int, val highlight: Boolean = false)

private val eduSections = listOf(
    EduSection(Icons.Rounded.Public, { MaterialTheme.extendedColors.community }, L10nR.string.keyEduWhatNostrH, L10nR.string.keyEduWhatNostrB),
    EduSection(Icons.Rounded.VpnKey, { MaterialTheme.colorScheme.primary }, L10nR.string.keyEduPairH, L10nR.string.keyEduPairB),
    EduSection(Icons.Rounded.Badge, { MaterialTheme.extendedColors.success }, L10nR.string.keyEduNpubH, L10nR.string.keyEduNpubB),
    EduSection(Icons.Rounded.WarningAmber, { MaterialTheme.colorScheme.error }, L10nR.string.keyEduNsecH, L10nR.string.keyEduNsecB, highlight = true),
    EduSection(Icons.Rounded.Hub, { MaterialTheme.extendedColors.community }, L10nR.string.keyEduIdentityH, L10nR.string.keyEduIdentityB),
)

/** Key education dialog before creating a key (spec 01 §2.7.4). Back/outside = abort. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun KeyEducationDialog(onResult: (Boolean) -> Unit) {
    Dialog(onDismissRequest = { onResult(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 40.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(MaterialShapes.Cookie6Sided.toShape())
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(L10nR.string.keyEduTitle), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(stringResource(L10nR.string.keyEduIntro), fontSize = 13.5.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    eduSections.forEach { section ->
                        val color = section.color()
                        val content: @Composable () -> Unit = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(section.icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(section.title),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (section.highlight) color else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(section.body), fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (section.highlight) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(color.copy(alpha = 0.08f))
                                    .border(0.5.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                            ) { content() }
                        } else {
                            Column { content() }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(L10nR.string.keyEduProtectH), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    listOf(L10nR.string.keyEduProtect1, L10nR.string.keyEduProtect2, L10nR.string.keyEduProtect3, L10nR.string.keyEduProtect4).forEach {
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.extendedColors.success, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(it), fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onResult(true) },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(L10nR.string.keyEduUnderstood), fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { onResult(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(L10nR.string.keyEduCancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ nsec

/** Non-dismissible nsec backup dialog, FLAG_SECURE while open (spec 01 §2.7.4). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NsecBackupDialog(nsec: String, onCopied: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val orange = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = {},
        properties = SecureWindows.dialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            SecureScreenEffect()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = orange, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(L10nR.string.profileSecureKey), color = orange, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(L10nR.string.profileSaveKeyDesc) + stringResource(L10nR.string.profileWhoHasKey),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                SelectionContainer {
                    Text(
                        nsec,
                        style = MonoStyle.copy(fontSize = 11.sp),
                        color = orange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .border(1.dp, orange.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(L10nR.string.profileKeyNotShownAgain), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    SecretClipboard.copySecret(context, nsec)
                    onCopied()
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(L10nR.string.profileCopy), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(L10nR.string.profileKeySecured), color = MaterialTheme.extendedColors.success, fontWeight = FontWeight.Bold)
            }
        },
    )
}

/** nsec import dialog (spec 01 §2.7.4 `_importNsec`); the dialog stays open on errors. */
@Composable
private fun ImportNsecDialog(importing: Boolean, onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    val orange = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        // The typed nsec must not end up in screenshots either.
        properties = SecureWindows.dialogProperties(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            SecureScreenEffect()
            Text(stringResource(L10nR.string.profileImportNsecShort), fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(stringResource(L10nR.string.profileEnterNsec), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.native_nsec_hint), style = MonoStyle) },
                    textStyle = MonoStyle.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = orange,
                        unfocusedBorderColor = orange.copy(alpha = 0.6f),
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(ProfileEditTags.NSEC_INPUT),
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(L10nR.string.profileNsecNeverLeaves), color = orange, fontSize = 11.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(L10nR.string.dialogCancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(value) },
                enabled = !importing,
                modifier = Modifier.heightIn(min = 48.dp).testTag(ProfileEditTags.NSEC_IMPORT),
            ) {
                if (importing) {
                    AppLoadingIndicator(size = 18.dp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(L10nR.string.profileImport), fontWeight = FontWeight.Bold)
            }
        },
    )
}

// ------------------------------------------------------------------------------------ ncryptsec

/** Password + confirmation for the ncryptsec export (non-dismissible, spec 01 §2.7.4). */
@Composable
private fun ExportPasswordDialog(onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    val success = MaterialTheme.extendedColors.success
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null, tint = success) },
        title = { Text(stringResource(L10nR.string.keyExportTitle), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(L10nR.string.keyExportDesc), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(stringResource(L10nR.string.backupPassword)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(stringResource(it)) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(ProfileEditTags.PASSWORD),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = { Text(stringResource(L10nR.string.backupPasswordConfirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag(ProfileEditTags.PASSWORD_CONFIRM),
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(L10nR.string.keyExportDuration), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(L10nR.string.dialogCancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        password.isEmpty() -> error = L10nR.string.backupPasswordEmpty
                        password != confirm -> error = L10nR.string.keyExportMismatch
                        else -> onConfirm(password)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = success, contentColor = MaterialTheme.extendedColors.onSuccess),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(L10nR.string.keyExportAction), fontWeight = FontWeight.Bold)
            }
        },
    )
}

/** ncryptsec result sheet, FLAG_SECURE while open (spec 01 §2.7.4 `_showNcryptsecSheet`). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NcryptsecSheet(
    ncryptsec: String,
    fromVault: Boolean,
    onCopied: () -> Unit,
    onOtherPassword: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val success = MaterialTheme.extendedColors.success
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = SecureWindows.bottomSheetProperties(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        SecureScreenEffect()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = success)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(L10nR.string.keyExportReadyTitle), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(L10nR.string.keyExportReadyBody), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            if (fromVault) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(L10nR.string.keyExportFromVault), color = success, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    ncryptsec,
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    SecretClipboard.copySecret(context, ncryptsec)
                    onCopied()
                },
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = success, contentColor = MaterialTheme.extendedColors.onSuccess),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(ProfileEditTags.NCRYPTSEC_COPY),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(L10nR.string.keyExportCopy), fontWeight = FontWeight.Bold)
            }
            if (fromVault) {
                TextButton(
                    onClick = onOtherPassword,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ProfileEditTags.NCRYPTSEC_OTHER_PASSWORD),
                ) {
                    Text(stringResource(L10nR.string.keyExportOtherPassword), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}
