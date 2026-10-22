package space.einundzwanzig.meetup.feature.profile.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyOff
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.profile.R

/** "SCHLÜSSEL AKTIV" card of the edit form (spec 01 §2.7.1, spec 07 §2.2.2 item 5). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun KeyActiveCard(state: ProfileEditUiState, onAction: (ProfileEditAction) -> Unit) {
    val success = MaterialTheme.extendedColors.success
    val gold = MaterialTheme.extendedColors.community
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, success.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = success, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(L10nR.string.profileKeyActiveCaps), color = success, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                state.npub,
                style = MonoStyle.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .padding(10.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AccentOutlinedButton(
                    label = stringResource(R.string.native_npub_button),
                    icon = Icons.Rounded.ContentCopy,
                    color = gold,
                    onClick = { onAction(ProfileEditAction.CopyNpub) },
                    modifier = Modifier.weight(1f).testTag(ProfileEditTags.NPUB_COPY),
                )
                if (state.isLocalMode) {
                    AccentOutlinedButton(
                        label = stringResource(R.string.native_nsec_button),
                        icon = Icons.Rounded.Key,
                        color = MaterialTheme.colorScheme.primary,
                        onClick = { onAction(ProfileEditAction.ShowNsec) },
                        modifier = Modifier.weight(1f).testTag(ProfileEditTags.NSEC_SHOW),
                    )
                }
            }
            if (state.isLocalMode) {
                Spacer(Modifier.height(8.dp))
                AccentOutlinedButton(
                    label = stringResource(L10nR.string.keyExportEncrypted),
                    icon = Icons.Rounded.Lock,
                    color = success,
                    busy = state.isExporting,
                    enabled = !state.isExporting,
                    onClick = { onAction(ProfileEditAction.ExportEncrypted) },
                    modifier = Modifier.fillMaxWidth().testTag(ProfileEditTags.EXPORT),
                )
            }
            SignerManagement(state, onAction)
        }
    }
}

/** No identity yet: create / Amber / bunker / import (spec 01 §2.7.1 "Else"). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NoKeyCard(state: ProfileEditUiState, onAction: (ProfileEditAction) -> Unit) {
    val gold = MaterialTheme.extendedColors.community
    val nostr = MaterialTheme.extendedColors.nostr
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.KeyOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(L10nR.string.profileNoKey), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onAction(ProfileEditAction.CreateKey) },
                enabled = !state.isGeneratingKey,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = nostr, contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(ProfileEditTags.CREATE_KEY),
            ) {
                if (state.isGeneratingKey) {
                    AppLoadingIndicator(size = 20.dp, color = MaterialTheme.colorScheme.onSurface)
                } else {
                    Icon(Icons.Rounded.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (state.isGeneratingKey) L10nR.string.profileCreating else L10nR.string.profileCreateNewKey),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            AccentOutlinedButton(
                label = stringResource(L10nR.string.profileConnectAmber),
                icon = Icons.Rounded.Shield,
                color = gold,
                enabled = !state.isGeneratingKey,
                onClick = { onAction(ProfileEditAction.ConnectAmber) },
                modifier = Modifier.fillMaxWidth().testTag(ProfileEditTags.CONNECT_AMBER),
            )
            Spacer(Modifier.height(8.dp))
            AccentOutlinedButton(
                label = stringResource(L10nR.string.profileConnectBunker),
                icon = Icons.Rounded.Lock,
                color = gold,
                enabled = !state.isGeneratingKey,
                onClick = { onAction(ProfileEditAction.ConnectBunker) },
                modifier = Modifier.fillMaxWidth().testTag(ProfileEditTags.CONNECT_BUNKER),
            )
            Spacer(Modifier.height(8.dp))
            // Import stays enabled while generating (Dart parity).
            AccentOutlinedButton(
                label = stringResource(L10nR.string.profileImportNsec),
                icon = Icons.Rounded.Download,
                color = MaterialTheme.colorScheme.primary,
                onClick = { onAction(ProfileEditAction.OpenImportNsec) },
                modifier = Modifier.fillMaxWidth().testTag(ProfileEditTags.IMPORT_NSEC),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(L10nR.string.profileNoNostrNeeded),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Signer management (spec 01 §2.7.3), both views, only with an identity. */
@Composable
internal fun SignerManagement(state: ProfileEditUiState, onAction: (ProfileEditAction) -> Unit) {
    val gold = MaterialTheme.extendedColors.community
    if (state.isNip46) {
        Spacer(Modifier.height(8.dp))
        AccentOutlinedButton(
            label = stringResource(L10nR.string.bunkerCheck),
            icon = Icons.Rounded.WifiTethering,
            color = gold,
            busy = state.isCheckingBunker,
            enabled = !state.isCheckingBunker,
            onClick = { onAction(ProfileEditAction.CheckBunker) },
            modifier = Modifier.fillMaxWidth().testTag(ProfileEditTags.CHECK_BUNKER),
        )
    }
    if (!state.showSignerSwitch) return
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(12.dp))
    if (state.offerBunker || state.offerAmber) {
        Text(
            stringResource(L10nR.string.profileSwitchSignerHeading),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(if (state.offerAgain) L10nR.string.profileSignerUnusable else L10nR.string.profileSwitchSignerHint),
            color = if (state.offerAgain) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        if (state.offerBunker) {
            AccentOutlinedButton(
                label = stringResource(L10nR.string.profileConnectBunker),
                icon = Icons.Rounded.Lock,
                color = gold,
                small = true,
                enabled = !state.isGeneratingKey,
                onClick = { onAction(ProfileEditAction.ConnectBunker) },
                modifier = Modifier.fillMaxWidth().testTag(ProfileEditTags.CONNECT_BUNKER),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (state.offerAmber) {
            AccentOutlinedButton(
                label = stringResource(L10nR.string.profileConnectAmber),
                icon = Icons.Rounded.Shield,
                color = gold,
                small = true,
                enabled = !state.isGeneratingKey,
                onClick = { onAction(ProfileEditAction.ConnectAmber) },
                modifier = Modifier.fillMaxWidth().testTag(ProfileEditTags.CONNECT_AMBER),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
    if (state.canDisconnect) {
        AccentOutlinedButton(
            label = stringResource(L10nR.string.profileDisconnectSigner),
            icon = Icons.Rounded.LinkOff,
            color = MaterialTheme.colorScheme.primary,
            small = true,
            onClick = { onAction(ProfileEditAction.Disconnect) },
            modifier = Modifier.fillMaxWidth().testTag(ProfileEditTags.DISCONNECT),
        )
    }
}

/** Outlined button in an accent colour with an optional inline loading indicator (48 dp target). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AccentOutlinedButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    small: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        border = BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.7f) else color.copy(alpha = 0.25f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        if (busy) {
            AppLoadingIndicator(size = 18.dp, color = color)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = if (small) 11.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
