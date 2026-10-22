package space.einundzwanzig.meetup.feature.onboarding.bunker

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import space.einundzwanzig.meetup.core.camera.QrScanner
import space.einundzwanzig.meetup.core.ui.component.AppTopBar
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.url.SafeUrl
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.qr.QrCode
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.core.ui.util.SafeUrlLauncher
import space.einundzwanzig.meetup.feature.onboarding.R
import space.einundzwanzig.meetup.feature.onboarding.common.resolve

/**
 * BunkerConnectSheet (spec 01 §2.5): choose → signer app (QR) / paste a `bunker://` URI.
 * Back/swipe are blocked while a pairing runs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BunkerConnectSheet(state: BunkerSheetState, onAction: (BunkerAction) -> Unit) {
    val busy by rememberUpdatedState(state.busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !busy },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!state.busy) onAction(BunkerAction.Cancel) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        BackHandler(enabled = state.busy) {}
        BunkerSheetContent(state, onAction)
    }
}

/** Sheet body, separate for tests and previews. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BunkerSheetContent(state: BunkerSheetState, onAction: (BunkerAction) -> Unit) {
    val gold = MaterialTheme.extendedColors.community
    val context = LocalContext.current
    var scanning by rememberSaveable { mutableStateOf(false) }
    if (scanning) {
        BunkerQrScanDialog(
            onResult = {
                scanning = false
                onAction(BunkerAction.Scanned(it))
            },
            onDismiss = { scanning = false },
        )
    }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp)
            .imePadding()
            .navigationBarsPadding()
            .testTag(BunkerTags.SHEET),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = gold, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(L10nR.string.bunkerTitle), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(L10nR.string.bunkerIntro), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        AnimatedContent(targetState = state.step, label = "bunkerStep") { step ->
            Column {
                when (step) {
                    BunkerStep.CHOOSE -> {
                        OptionTile(
                            icon = Icons.Rounded.QrCode2,
                            color = gold,
                            title = stringResource(L10nR.string.bunkerModeSigner),
                            subtitle = stringResource(L10nR.string.bunkerModeSignerDesc),
                            enabled = !state.busy,
                            onClick = { onAction(BunkerAction.StartSignerApp) },
                            tag = BunkerTags.MODE_SIGNER,
                        )
                        Spacer(Modifier.height(10.dp))
                        OptionTile(
                            icon = Icons.Rounded.ContentPaste,
                            color = MaterialTheme.extendedColors.success,
                            title = stringResource(L10nR.string.bunkerModePaste),
                            subtitle = stringResource(L10nR.string.bunkerModePasteDesc),
                            enabled = !state.busy,
                            onClick = { onAction(BunkerAction.ShowPaste) },
                            tag = BunkerTags.MODE_PASTE,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(gold.copy(alpha = 0.08f))
                                .padding(10.dp),
                        ) {
                            Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = gold, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(L10nR.string.bunkerRecommendAndroid), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            onClick = { onAction(BunkerAction.Cancel) },
                            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(L10nR.string.dialogCancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    BunkerStep.SIGNER_APP -> {
                        val uri = state.pairingUri
                        if (uri == null) {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                AppLoadingIndicator(color = gold)
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.extendedColors.qrBackground,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            ) {
                                QrCode(
                                    content = uri,
                                    size = 200.dp,
                                    backgroundColor = MaterialTheme.extendedColors.qrBackground,
                                    moduleColor = MaterialTheme.extendedColors.qrModule,
                                    contentDescription = stringResource(R.string.native_onboarding_bunker_qr),
                                    modifier = Modifier.testTag(BunkerTags.QR),
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(L10nR.string.bunkerScanHint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        SecretClipboard.copyPlain(context, uri)
                                        onAction(BunkerAction.UriCopied)
                                    },
                                    shapes = ButtonDefaults.shapes(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(L10nR.string.bunkerCopy), fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val opened = try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                            true
                                        } catch (e: ActivityNotFoundException) {
                                            false
                                        } catch (e: SecurityException) {
                                            false
                                        }
                                        if (!opened) onAction(BunkerAction.NoSignerApp)
                                    },
                                    shapes = ButtonDefaults.shapes(),
                                    border = BorderStroke(1.dp, gold.copy(alpha = 0.7f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = gold),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(L10nR.string.bunkerOpenSigner), fontSize = 12.sp)
                                }
                            }
                        }
                        if (state.busy) {
                            Spacer(Modifier.height(16.dp))
                            WaitingRow()
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { onAction(BunkerAction.Cancel) },
                            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(L10nR.string.dialogCancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    BunkerStep.PASTE -> {
                        val green = MaterialTheme.extendedColors.success
                        OutlinedTextField(
                            value = state.pasteInput,
                            onValueChange = { onAction(BunkerAction.PasteChanged(it)) },
                            enabled = !state.busy,
                            minLines = 1,
                            maxLines = 3,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            label = { Text(stringResource(L10nR.string.bunkerPasteLabel)) },
                            placeholder = { Text(stringResource(L10nR.string.bunkerPasteHint), fontSize = 11.sp) },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = green, focusedLabelColor = green),
                            modifier = Modifier.fillMaxWidth().testTag(BunkerTags.PASTE_INPUT),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { scanning = true },
                            enabled = !state.busy,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(BunkerTags.SCAN),
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.native_onboarding_bunker_scan))
                        }
                        if (state.busy) {
                            Spacer(Modifier.height(12.dp))
                            WaitingRow()
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { onAction(BunkerAction.BackToChoose) },
                                enabled = !state.busy,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(L10nR.string.bunkerBack))
                            }
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { onAction(BunkerAction.ConnectPasted) },
                                enabled = !state.busy,
                                shapes = ButtonDefaults.shapes(),
                                colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = MaterialTheme.extendedColors.onSuccess),
                                modifier = Modifier.heightIn(min = 48.dp).testTag(BunkerTags.CONNECT),
                            ) {
                                Text(stringResource(L10nR.string.bunkerConnect), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        state.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(
                error.resolve(),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                    .padding(10.dp)
                    .testTag(BunkerTags.ERROR),
            )
        }
        state.authUrl?.let { url ->
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    if (!SafeUrlLauncher.open(context, url)) {
                        AppLogger.warn(TAG, "auth_url nicht geöffnet (Host: ${SafeUrl.hostForLog(url)})")
                    }
                },
                shapes = ButtonDefaults.shapes(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(L10nR.string.bunkerAuthOpen))
            }
        }
    }
}

@Composable
private fun WaitingRow() {
    val gold = MaterialTheme.extendedColors.community
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(BunkerTags.WAITING)) {
        AppLoadingIndicator(size = 24.dp, color = gold)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(L10nR.string.bunkerWaiting), color = gold, fontSize = 12.sp)
            Text(stringResource(L10nR.string.bunkerWaitingHint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun OptionTile(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(Modifier.padding(12.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

/** Test tags of the bunker sheet. */
object BunkerTags {
    const val SHEET = "bunker_sheet"
    const val MODE_SIGNER = "bunker_mode_signer"
    const val MODE_PASTE = "bunker_mode_paste"
    const val PASTE_INPUT = "bunker_paste_input"
    const val CONNECT = "bunker_connect"
    const val QR = "bunker_qr"
    const val WAITING = "bunker_waiting"
    const val ERROR = "bunker_error"
    const val SCAN = "bunker_scan"
}

/** Full-screen QR scanner for a `bunker://` code (native addition to spec 01 §2.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BunkerQrScanDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.native_onboarding_bunker_scan_title),
                    onBack = onDismiss,
                )
            },
        ) { padding ->
            QrScanner(
                onResult = { raw ->
                    onResult(raw)
                    true
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

private const val TAG = "BunkerConnect"
