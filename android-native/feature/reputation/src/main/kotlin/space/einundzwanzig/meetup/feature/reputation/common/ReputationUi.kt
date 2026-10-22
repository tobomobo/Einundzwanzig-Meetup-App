package space.einundzwanzig.meetup.feature.reputation.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.FiberNew
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.ui.nostr.NpubFormat
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.trust.layers.TrustLayersEvaluator
import space.einundzwanzig.meetup.data.trust.layers.TrustSignalColor
import space.einundzwanzig.meetup.data.trust.level.TrustLevel
import space.einundzwanzig.meetup.data.trust.level.TrustLevelColor
import space.einundzwanzig.meetup.data.trust.level.TrustLevelIcon
import space.einundzwanzig.meetup.data.trust.level.TrustLevelStyle
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/*
 * Shared presentation helpers of the reputation screens: level icon/colour/label (spec 04 §2.0),
 * semantic colours of the Flutter palette mapped to theme tokens (:data:trust README), platform
 * icons (§2.5), npub shortening and the "open in Nostr app" fallback (§2.7).
 */

/** Flutter `Colors.amber` (0xFFFFC107), distinct from the `cCyan` gold. */
internal val amberColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.extendedColors.amber

/** Flutter `cCyan` (BrandColors.Gold). */
internal val cyanColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.extendedColors.community

/** Flutter `cPurple` / `Colors.purple`. */
internal val purpleColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.extendedColors.nostr

/** Flutter `Colors.green` / `cGreen`. */
internal val greenColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.extendedColors.success

/** Flutter `cOrange`. */
internal val orangeColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

/** Flutter `Colors.red`. */
internal val redColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error

/** Flutter `Colors.grey`. */
internal val greyColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline

/** Flutter `cTextTertiary`. */
internal val tertiaryTextColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.extendedColors.textTertiary

/** Theme colour of a level (QR card, scanner result; spec 04 §2.0). */
@Composable
@ReadOnlyComposable
internal fun TrustLevelColor.toColor(): Color = when (this) {
    TrustLevelColor.AMBER -> amberColor
    TrustLevelColor.SUCCESS_GREEN -> greenColor
    TrustLevelColor.COMMUNITY_GOLD -> cyanColor
    TrustLevelColor.BITCOIN_ORANGE -> MaterialTheme.extendedColors.bitcoin
    TrustLevelColor.NEUTRAL_GREY -> greyColor
}

/** Theme colour of a trust signal (layers widget, spec 04 §8.9). */
@Composable
@ReadOnlyComposable
internal fun TrustSignalColor.toColor(): Color = when (this) {
    TrustSignalColor.ERROR -> redColor
    TrustSignalColor.WARNING -> MaterialTheme.extendedColors.bitcoin
    TrustSignalColor.CAUTION -> amberColor
    TrustSignalColor.SUCCESS -> greenColor
    TrustSignalColor.SUCCESS_SOFT -> greenColor.copy(alpha = 0.7f)
}

/** Material icon of a level icon name. */
internal fun TrustLevelIcon.toImageVector(): ImageVector = when (this) {
    TrustLevelIcon.BOLT -> Icons.Rounded.Bolt
    TrustLevelIcon.SHIELD -> Icons.Rounded.Shield
    TrustLevelIcon.LOCAL_FIRE_DEPARTMENT -> Icons.Rounded.LocalFireDepartment
    TrustLevelIcon.ECO -> Icons.Rounded.Eco
    TrustLevelIcon.FIBER_NEW -> Icons.Rounded.FiberNew
}

/** Level style for a wire value (`VETERAN`, …; unknown and `""` → grey `fiber_new`). */
internal fun levelStyle(wire: String): TrustLevelStyle = TrustLevelStyle.forWire(wire)

/** Dart `localizedLevel()`: NEU→`levelNew` … VETERAN→`levelVeteran`, anything else the raw value. */
@Composable
internal fun localizedLevel(wire: String): String = when (TrustLevel.fromWire(wire)) {
    TrustLevel.NEU -> stringResource(L10nR.string.levelNew)
    TrustLevel.STARTER -> stringResource(L10nR.string.levelStarter)
    TrustLevel.AKTIV -> stringResource(L10nR.string.levelActive)
    TrustLevel.ETABLIERT -> stringResource(L10nR.string.levelEstablished)
    TrustLevel.VETERAN -> stringResource(L10nR.string.levelVeteran)
    null -> wire
}

/** Scanner platform icon (spec 04 §2.5 table). */
internal fun platformIcon(platform: String): ImageVector = when (platform) {
    "telegram" -> Icons.AutoMirrored.Rounded.Send
    "satoshikleinanzeigen" -> Icons.Rounded.ShoppingCart
    "robosats" -> Icons.Rounded.SmartToy
    "nostr" -> Icons.Rounded.Hub
    else -> Icons.Rounded.Language
}

/** `shortenNpub(npub, 8)` of the scanner/VerifyPerson: `npub[0..12] + "..." + last 8` from 21 chars. */
internal fun shortenNpub(npub: String): String = NpubFormat.shortenNpub(npub)

/**
 * `shortenNpubVerify` of the verify screen (spec 04 §2.4): unchanged below 20 chars, else
 * `npub[0..12] + "..." + last 8`.
 */
internal fun shortenNpubVerify(npub: String): String =
    if (npub.length < 20) npub else npub.take(12) + "..." + npub.takeLast(8)

/**
 * Dart `openNostr(npub)` (spec 04 §2.7): `nostr:<npub>` in an installed app, else
 * `https://njump.me/<npub>`; errors swallowed.
 */
internal fun openNostr(context: Context, npub: String) {
    val nostr = Intent(Intent.ACTION_VIEW, "nostr:$npub".toUri())
    try {
        context.startActivity(nostr)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, NpubFormat.profileUrl(npub).toUri()))
        } catch (e: RuntimeException) {
            AppLogger.warn("Reputation", "njump.me konnte nicht geöffnet werden", e)
        }
    } catch (e: SecurityException) {
        AppLogger.warn("Reputation", "nostr:-Link konnte nicht geöffnet werden", e)
    }
}

/**
 * Localized platform name: `other` uses `qrPlatformOther` ([TrustLayersEvaluator.platformLabel]
 * returns the German literal "Andere"); brand names stay as they are.
 */
@Composable
@ReadOnlyComposable
internal fun platformDisplayName(platform: String): String =
    if (platform == "other") stringResource(L10nR.string.qrPlatformOther) else TrustLayersEvaluator.platformLabel(platform)
