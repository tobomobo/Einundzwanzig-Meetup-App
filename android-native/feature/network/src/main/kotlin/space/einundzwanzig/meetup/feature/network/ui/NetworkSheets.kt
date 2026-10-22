package space.einundzwanzig.meetup.feature.network.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.ui.haptics.rememberHaptics
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.socialgraph.coattendance.AttendanceKeyLabel
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.feature.network.NetworkPresentation
import space.einundzwanzig.meetup.feature.network.R
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Info sheet "So entsteht dein Netzwerk" (spec 05 §2.1). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoSheet(sheetState: SheetState, onDismiss: () -> Unit) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .testTag("infoSheet"),
        ) {
            Text(
                stringResource(L10nR.string.mnHowItWorks),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(L10nR.string.mnIntro),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            NetworkInfoBox(stringResource(L10nR.string.mnEventNote), Icons.Outlined.EventBusy)
            Spacer(Modifier.height(10.dp))
            NetworkInfoBox(
                stringResource(L10nR.string.mnTrustHint),
                Icons.Rounded.Lightbulb,
                iconTint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            NetworkInfoBox(stringResource(L10nR.string.mnPrivacyNote), Icons.Outlined.PrivacyTip)
        }
    }
}

/** Person detail sheet (Dart `_openDetail`). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun PersonDetailSheet(
    net: MyNetwork,
    npub: String,
    names: Map<String, String>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onOpenInNostr: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val you = stringResource(L10nR.string.mnYou)
    fun label(n: String) = NetworkPresentation.label(n, net.myNpub, names, you)
    val contact = net.contactsByNpub[npub]
    val degree = contact?.degree ?: 0
    val name = names[npub]
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.75f
    fun hideThen(after: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { after() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
    ) {
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                .testTag("detailSheet"),
        ) {
            // Header: avatar, name / short npub, degree pill, copy row.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ContactAvatar(name, degree, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (name != null) {
                            Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        } else {
                            Text(
                                NetworkPresentation.short(npub),
                                style = MonoStyle.copy(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
                            )
                        }
                        if (degree in 1..3) DegreePill(degree)
                    }
                    CopyNpubRow(npub)
                }
            }

            // Path "Dein Weg zu …".
            val path = NetworkPresentation.detailPath(net, npub)
            if (path.size >= 2) {
                Spacer(Modifier.height(20.dp))
                SectionCaption(
                    stringResource(L10nR.string.mnPathTo, label(npub)).uppercase(),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                path.forEachIndexed { i, node ->
                    PathNode(node, isMe = node == net.myNpub, names, you, net)
                    if (i < path.lastIndex) {
                        val next = path[i + 1]
                        val shared = net.sharedBetween(node, next).firstOrNull()
                        PathLink(
                            degree = net.contactsByNpub[next]?.degree ?: 1,
                            text = shared?.let {
                                stringResource(L10nR.string.mnTogetherAt, AttendanceKeyLabel.label(it))
                            },
                        )
                    }
                }
            }

            // Shared meetups (direct contacts only).
            if (degree == 1 && contact != null) {
                Spacer(Modifier.height(20.dp))
                SectionCaption(
                    stringResource(L10nR.string.mnSharedMeetupsList).uppercase(),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                AttendanceKeyLabel.newestFirst(contact.sharedMeetupsWithMe).forEach { key ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Groups,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = degreeColor(1),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(AttendanceKeyLabel.label(key), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // Also reachable via.
            val others = NetworkPresentation.alsoVia(net, npub, path, names, you)
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionCaption(
                    stringResource(L10nR.string.mnAlsoVia).uppercase(),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    others.forEach { b ->
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(
                                Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ContactAvatar(names[b], net.contactsByNpub[b]?.degree ?: 1, 22.dp)
                                Spacer(Modifier.width(6.dp))
                                val n = names[b]
                                Text(
                                    n ?: NetworkPresentation.short(b),
                                    style = if (n == null) MonoStyle.copy(fontSize = 14.sp) else MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { hideThen { onDismiss(); onOpenInNostr(npub) } },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(L10nR.string.mnOpenInNostr),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = { hideThen(onDismiss) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(stringResource(L10nR.string.mnClose), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DegreePill(degree: Int) {
    val color = degreeColor(degree)
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(degreeLabel(degree), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/** Short npub with a copy button that turns into a green check for 2 s (medium haptic). */
@Composable
private fun CopyNpubRow(npub: String) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            NetworkPresentation.short(npub),
            style = MonoStyle.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        IconButton(
            onClick = {
                SecretClipboard.copyPlain(context, npub, "npub")
                haptics.medium()
                copied = true
            },
            modifier = Modifier.testTag("copyNpub"),
        ) {
            AnimatedContent(targetState = copied, label = "copy") { done ->
                if (done) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.native_network_copied),
                        tint = MaterialTheme.extendedColors.success,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(L10nR.string.mnCopyNpub),
                        tint = MaterialTheme.extendedColors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PathNode(npub: String, isMe: Boolean, names: Map<String, String>, you: String, net: MyNetwork) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isMe) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(MaterialShapes.Cookie6Sided.toShape())
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(you, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Text(you, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        } else {
            val name = names[npub]
            ContactAvatar(name, net.contactsByNpub[npub]?.degree ?: 1, 28.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                name ?: NetworkPresentation.short(npub),
                style = if (name == null) MonoStyle.copy(fontSize = 15.sp) else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PathLink(degree: Int, text: String?) {
    Row(
        Modifier
            .height(34.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(degreeColor(degree)),
            )
        }
        Spacer(Modifier.width(12.dp))
        if (text != null) {
            Text(
                text,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
