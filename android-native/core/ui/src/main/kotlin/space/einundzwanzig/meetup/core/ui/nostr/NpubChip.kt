package space.einundzwanzig.meetup.core.ui.nostr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.ui.R
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.haptics.rememberHaptics
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.Inconsolata
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.core.ui.util.SafeUrlLauncher
import space.einundzwanzig.meetup.core.ui.util.UiLog
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** npub shortening rules (spec 07 §8.7: both variants are used, keep both). */
object NpubFormat {
    /**
     * `NpubChip.shorten`: keeps start **and** end, `…` (U+2026) in between:
     * unchanged if `length <= head + tail + 1`.
     */
    fun shorten(npub: String, head: Int = 10, tail: Int = 8): String {
        val v = npub.trim()
        if (v.length <= head + tail + 1) return v
        return v.take(head) + "…" + v.takeLast(tail)
    }

    /**
     * `NostrService.shortenNpub`: unchanged if shorter than `chars * 2 + 5`, else the first
     * `4 + chars` characters, three ASCII dots and the last [chars].
     */
    fun shortenNpub(npub: String, chars: Int = 8): String {
        if (npub.length < chars * 2 + 5) return npub
        return npub.substring(0, 4 + chars) + "..." + npub.takeLast(chars)
    }

    /** Public profile link that works with or without a Nostr app (njump.me). */
    fun profileUrl(npub: String): String = "https://njump.me/${npub.trim()}"
}

/**
 * Shows an npub shortened but always with start **and** end, and makes it usable
 * (port of `lib/widgets/npub_chip.dart`, spec 07 §2.4):
 *
 * - without a known display name: shortened npub (Inconsolata) + open icon; tap opens the
 *   profile on njump.me, long press copies the full npub;
 * - with a display name ([showName], loaded in the background): name (tap = open) followed
 *   by a small "npub" chip with a copy icon (tap = copy).
 *
 * Opening uses the light haptic, copying the medium haptic and shows `npubCopied` for 2 s on
 * [LocalAppSnackbarHostState]. If no app can open the link, the npub is copied instead.
 *
 * @param displayNameLoader overrides [NostrProfileSource.displayNameForNpub].
 * @param onOpenProfile overrides opening njump.me (receives the trimmed npub).
 */
@Composable
fun NpubChip(
    npub: String,
    modifier: Modifier = Modifier,
    head: Int = 10,
    tail: Int = 8,
    style: TextStyle? = null,
    showIcon: Boolean = true,
    showName: Boolean = true,
    displayNameLoader: (suspend (String) -> String?)? = null,
    onOpenProfile: ((String) -> Unit)? = null,
) {
    val value = npub.trim()
    if (value.isEmpty()) return

    val source = LocalNostrProfileSource.current
    var name by remember(value) { mutableStateOf<String?>(null) }
    if (showName) {
        LaunchedEffect(value, displayNameLoader, source) {
            val loaded = try {
                (displayNameLoader ?: source::displayNameForNpub).invoke(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiLog.warn("Npub", "Anzeigename nicht ladbar", e)
                null
            }
            loaded?.trim()?.takeIf { it.isNotEmpty() }?.let { name = it }
        }
    }

    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snackbarHost = LocalAppSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(L10nR.string.npubCopied)

    val copy: () -> Unit = {
        haptics.medium()
        SecretClipboard.copyPlain(context, value, label = "npub")
        snackbarHost?.let { host ->
            scope.launch { host.showAppSnackbar(copiedMessage, durationMillis = AppSnackbarDurations.Short) }
        }
    }
    val open: () -> Unit = {
        haptics.light()
        if (onOpenProfile != null) {
            onOpenProfile(value)
        } else if (!SafeUrlLauncher.open(context, NpubFormat.profileUrl(value))) {
            UiLog.warn("Npub", "Profil konnte nicht geöffnet werden")
            copy()
        }
    }

    val base = style ?: TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    val baseColor = base.color.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val openLabel = stringResource(R.string.native_npub_open_profile)
    val copyLabel = stringResource(R.string.native_npub_copy)
    val currentName = name

    if (currentName == null) {
        Row(
            modifier = modifier.combinedClickable(
                role = Role.Button,
                onClickLabel = openLabel,
                onLongClickLabel = copyLabel,
                onLongClick = copy,
                onClick = open,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = NpubFormat.shorten(value, head, tail),
                style = base.copy(fontFamily = Inconsolata),
                color = baseColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (showIcon) {
                Spacer(Modifier.width(5.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = baseColor.copy(alpha = baseColor.alpha * 0.8f),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    } else {
        val fontSize: TextUnit = if (base.fontSize.isSpecified) base.fontSize else 12.sp
        val small = fontSize * 0.78f
        val iconSize = with(LocalDensity.current) { (small.toDp() + 1.dp) }
        val chipShape = RoundedCornerShape(5.dp)
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = currentName,
                style = base.copy(fontWeight = FontWeight.Bold),
                color = baseColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clickable(role = Role.Button, onClickLabel = openLabel, onClick = open),
            )
            Spacer(Modifier.width(7.dp))
            Row(
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = copyLabel, onClick = copy)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, chipShape)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, chipShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "npub",
                    color = MaterialTheme.extendedColors.textTertiary,
                    fontSize = small,
                    fontFamily = Inconsolata,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = copyLabel,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Preview(name = "NpubChip", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun NpubChipPreview() {
    EinundzwanzigTheme {
        CompositionLocalProvider(LocalNostrProfileSource provides NostrProfileSource.None) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NpubChip("npub1dzu6ceugmm7xqs2drhwl5jdyhwrhc3ezgr0w6tmmvqzjmn5ac0ysl4qd9z")
                NpubChip(
                    "npub1dzu6ceugmm7xqs2drhwl5jdyhwrhc3ezgr0w6tmmvqzjmn5ac0ysl4qd9z",
                    displayNameLoader = { "Satoshi" },
                    style = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                )
            }
        }
    }
}
