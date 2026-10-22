package space.einundzwanzig.meetup.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.ui.haptics.rememberHaptics
import space.einundzwanzig.meetup.core.ui.motion.Pressable
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the scan sheet. */
object ScanSheetTags {
    const val SHEET = "scan_sheet"
    const val BADGE = "scan_sheet_badge"
    const val REPUTATION = "scan_sheet_reputation"
}

/**
 * Scan sheet of the app shell's central scan button (spec 12 §2.4 `_ScanSheet`): "Badge scannen"
 * and "Reputation prüfen". The shell plays the medium haptic when it opens the sheet, then pushes
 * `BadgeScanner` / `ReputationQr` from the callbacks (the sheet hides itself first) and calls
 * [HomeRefreshRequests.request] when the scanner returns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(onDismissRequest: () -> Unit, onScanBadge: () -> Unit, onCheckReputation: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun closeThen(action: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion {
            onDismissRequest()
            action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        ScanSheetContent(onScanBadge = { closeThen(onScanBadge) }, onCheckReputation = { closeThen(onCheckReputation) })
    }
}

/** The two options of the scan sheet (stateless, for previews and tests). */
@Composable
fun ScanSheetContent(onScanBadge: () -> Unit, onCheckReputation: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            .navigationBarsPadding()
            .testTag(ScanSheetTags.SHEET),
    ) {
        ScanOption(
            icon = Icons.Rounded.QrCode,
            iconColor = MaterialTheme.colorScheme.primary,
            title = stringResource(L10nR.string.scanBadge),
            subtitle = stringResource(L10nR.string.scanBadgeSub),
            onClick = onScanBadge,
            modifier = Modifier.testTag(ScanSheetTags.BADGE),
        )
        Spacer(Modifier.height(8.dp))
        ScanOption(
            icon = Icons.Rounded.WorkspacePremium,
            iconColor = MaterialTheme.extendedColors.amber,
            title = stringResource(L10nR.string.scanReputation),
            subtitle = stringResource(L10nR.string.scanReputationSub),
            onClick = onCheckReputation,
            modifier = Modifier.testTag(ScanSheetTags.REPUTATION),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScanOption(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Pressable(onClick = { haptics.light(); onClick() }, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Dimens.TileShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(Dimens.Hairline, MaterialTheme.colorScheme.outlineVariant, Dimens.TileShape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(MaterialShapes.Cookie6Sided.toShape())
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = iconColor)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textTertiary)
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.extendedColors.textTertiary)
        }
    }
}

@Preview(name = "Scan sheet", showBackground = true, backgroundColor = 0xFF161618)
@Composable
private fun ScanSheetPreview() {
    EinundzwanzigTheme { ScanSheetContent(onScanBadge = {}, onCheckReputation = {}) }
}
