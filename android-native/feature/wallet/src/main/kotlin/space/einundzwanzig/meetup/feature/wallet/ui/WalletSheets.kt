package space.einundzwanzig.meetup.feature.wallet.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.nostr.NpubFormat
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.badges.model.FormerCollection
import space.einundzwanzig.meetup.feature.wallet.CleanupDialogState
import space.einundzwanzig.meetup.feature.wallet.domain.BadgeFormat
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the wallet sheets and dialogs. */
internal object WalletSheetTags {
    const val SHARE_SHEET = "wallet.shareSheet"
    const val FORMER_SHEET = "wallet.formerSheet"
    const val MERGE_DIALOG = "wallet.mergeDialog"
    const val CLEANUP_DIALOG = "wallet.cleanupDialog"
}

/** "REPUTATION TEILEN" sheet (spec 03 §2.1.2): text, QR, JSON, cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareReputationSheet(
    onShareText: () -> Unit,
    onShowQr: () -> Unit,
    onExportJson: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag(WalletSheetTags.SHARE_SHEET),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(
                stringResource(L10nR.string.walletShareReputation),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.height(16.dp))
            ShareOption(
                icon = Icons.Rounded.Share,
                tint = MaterialTheme.extendedColors.community,
                title = stringResource(L10nR.string.walletShareText),
                subtitle = stringResource(L10nR.string.walletShareTextSub),
                onClick = { scope.hideThen(sheetState, onShareText) },
            )
            ShareOption(
                icon = Icons.Rounded.QrCode2,
                tint = MaterialTheme.colorScheme.primary,
                title = stringResource(L10nR.string.walletShowQr),
                subtitle = stringResource(L10nR.string.walletShowQrSub),
                onClick = { scope.hideThen(sheetState, onShowQr) },
            )
            ShareOption(
                icon = Icons.Rounded.Code,
                tint = MaterialTheme.extendedColors.purple,
                title = stringResource(L10nR.string.walletExportJson),
                subtitle = stringResource(L10nR.string.walletExportJsonSub),
                onClick = { scope.hideThen(sheetState, onExportJson) },
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { scope.hideThen(sheetState, onDismiss) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(L10nR.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ShareOption(icon: ImageVector, tint: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Plays the sheet's hide motion first and only then reports the choice, whose state change
 * removes the sheet from the composition (otherwise it would snap away).
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun CoroutineScope.hideThen(sheetState: SheetState, action: () -> Unit) {
    launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) action() }
}

/** "Frühere Sammlungen" archive sheet (spec 03 §2.1.3), entries newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FormerCollectionsSheet(
    entries: List<FormerCollection>,
    onMerge: (FormerCollection) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceContainer,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.testTag(WalletSheetTags.FORMER_SHEET),
    ) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(L10nR.string.walletFormerTitle), color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(L10nR.string.walletFormerHint),
                    color = MaterialTheme.extendedColors.textTertiary,
                    fontSize = 11.5.sp,
                    lineHeight = 1.35.em,
                )
                Spacer(Modifier.height(8.dp))
            }
            items(entries, key = { it.index }) { entry ->
                FormerEntryRow(entry, onMerge = { scope.hideThen(sheetState) { onMerge(it) } })
            }
        }
    }
}

@Composable
private fun FormerEntryRow(entry: FormerCollection, onMerge: (FormerCollection) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val badges = entry.badges
    val readable = badges != null
    val reason = when (entry.reason) {
        "replaced" -> stringResource(L10nR.string.walletFormerReasonReplaced)
        "rotated" -> stringResource(L10nR.string.walletFormerReasonRotated)
        "cleared" -> stringResource(L10nR.string.walletFormerReasonCleared)
        else -> entry.reason
    }
    val subtitle = buildList {
        add(BadgeFormat.formerTime(entry.at))
        add(reason)
        if (entry.npub.isNotEmpty()) add(NpubFormat.shortenNpub(entry.npub))
    }.joinToString(" · ")
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                if (readable) stringResource(L10nR.string.walletFormerEntry, badges.size) else stringResource(L10nR.string.walletFormerUnreadable),
                color = if (readable) colors.onSurface else MaterialTheme.extendedColors.textTertiary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        supportingContent = { Text(subtitle, color = colors.onSurfaceVariant, fontSize = 11.5.sp) },
        trailingContent = {
            TextButton(onClick = { onMerge(entry) }, enabled = readable) {
                Text(stringResource(L10nR.string.walletFormerMerge), fontWeight = FontWeight.Bold)
            }
        },
    )
}

/** Merge confirmation (spec 03 §2.1.3 step 4). */
@Composable
internal fun MergeFormerDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(WalletSheetTags.MERGE_DIALOG),
        containerColor = colors.surfaceContainer,
        title = { Text(stringResource(L10nR.string.walletFormerMergeTitle), color = colors.onSurface, fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(L10nR.string.walletFormerMergeBody), color = colors.onSurfaceVariant, lineHeight = 1.35.em) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(L10nR.string.dialogCancelMixed), color = MaterialTheme.extendedColors.textTertiary)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(L10nR.string.walletFormerMerge), color = colors.primary, fontWeight = FontWeight.Bold)
            }
        },
    )
}

/** Duplicate cleanup dialog listing "Name (Nx)" (spec 03 §2.1.4). */
@Composable
internal fun CleanupDialog(state: CleanupDialogState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(WalletSheetTags.CLEANUP_DIALOG),
        containerColor = colors.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CleaningServices, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(L10nR.string.walletCleanupTitle), color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            // Scrolls when many meetups have duplicates, so every removal can be reviewed.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(L10nR.string.walletCleanupBody, state.duplicates.size),
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 1.4.em,
                )
                Spacer(Modifier.height(12.dp))
                state.summary.forEach { (name, count) ->
                    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = null, tint = colors.error, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("$name (${count}x)", color = colors.onSurface, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(L10nR.string.walletCleanupHint), color = MaterialTheme.extendedColors.textTertiary, fontSize = 11.5.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(L10nR.string.dialogCancel), color = colors.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(L10nR.string.walletCleanupConfirm), color = colors.primary, fontWeight = FontWeight.Bold)
            }
        },
    )
}
