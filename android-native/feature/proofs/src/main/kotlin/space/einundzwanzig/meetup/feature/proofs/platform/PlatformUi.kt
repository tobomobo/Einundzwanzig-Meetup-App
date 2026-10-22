package space.einundzwanzig.meetup.feature.proofs.platform

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import space.einundzwanzig.meetup.data.proofs.platform.PlatformCatalog
import space.einundzwanzig.meetup.data.proofs.platform.PlatformIcon
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateError
import space.einundzwanzig.meetup.feature.proofs.R
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Material icon of a catalogue icon key (spec 06 §2.1 proof card mapping). */
internal fun PlatformIcon.imageVector(): ImageVector = when (this) {
    PlatformIcon.SHOPPING_CART -> Icons.Rounded.ShoppingCart
    PlatformIcon.SEND -> Icons.AutoMirrored.Rounded.Send
    PlatformIcon.SMART_TOY -> Icons.Rounded.SmartToy
    PlatformIcon.HUB -> Icons.Rounded.Hub
    PlatformIcon.LANGUAGE -> Icons.Rounded.Language
    PlatformIcon.LINK -> Icons.Rounded.Link
}

/** Icon for a stored platform id (custom ids → `language`). */
internal fun platformIcon(platformId: String): ImageVector = PlatformCatalog.iconFor(platformId).imageVector()

/**
 * Localized display name: brand names stay as in the catalogue, `other` is translated and
 * custom platforms show their raw id (Flutter proof card).
 */
@Composable
@ReadOnlyComposable
internal fun platformDisplayName(platformId: String): String =
    if (platformId == PlatformCatalog.OTHER) {
        stringResource(R.string.native_ppNameOther)
    } else {
        PlatformCatalog.displayName(platformId)
    }

/** Localized username hint of a catalogue platform, fallback `ppYourUsername`. */
@Composable
@ReadOnlyComposable
internal fun platformHint(platformId: String): String = when (platformId) {
    "satoshikleinanzeigen" -> stringResource(R.string.native_ppHintSka)
    "telegram" -> stringResource(R.string.native_ppHintTelegram)
    "robosats" -> stringResource(R.string.native_ppHintRobosats)
    "nostr" -> stringResource(R.string.native_ppHintNostr)
    PlatformCatalog.OTHER -> stringResource(R.string.native_ppHintOther)
    else -> stringResource(L10nR.string.ppYourUsername)
}

/** Localized [ProofCreateError] text (German default = the Flutter service message). */
internal fun createErrorMessage(resources: Resources, error: ProofCreateError, detail: String?): String = when (error) {
    ProofCreateError.NO_KEY -> resources.getString(R.string.native_proofErrNoKey)
    ProofCreateError.EMPTY_USERNAME -> resources.getString(R.string.native_ppErrEmptyUsername)
    ProofCreateError.SIGNER_OR_UNKNOWN -> resources.getString(R.string.native_proofErrGeneric, detail.orEmpty())
}

/** Flutter `"${d.day}.${d.month}.${d.year}"` of [createdAtSeconds] in local time, no padding. */
internal fun proofDate(createdAtSeconds: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val d = java.time.Instant.ofEpochMilli(createdAtSeconds * 1000).atZone(zone)
    return "${d.dayOfMonth}.${d.monthValue}.${d.year}"
}
