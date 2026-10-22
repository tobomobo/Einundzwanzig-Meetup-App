package space.einundzwanzig.meetup.feature.wallet.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.badge.BadgeArt
import space.einundzwanzig.meetup.core.ui.crest.MeetupCrestWatermark
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.feature.wallet.PreviewData
import space.einundzwanzig.meetup.feature.wallet.domain.BadgeFormat
import space.einundzwanzig.meetup.feature.wallet.ui.artSeed
import space.einundzwanzig.meetup.feature.wallet.ui.dartUpper
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Constants of the share card (spec 03 §2.3), verbatim. */
internal object ShareCard {
    /** Logical size `360 × 560` dp (Dart `kShareCardSize`). */
    const val WIDTH_DP = 360
    const val HEIGHT_DP = 560

    /** Rendered at 3× → 1080 × 1680 px. */
    const val PIXEL_RATIO = 3f

    const val APP_STORE_URL = "zapstore.dev/apps/space.einundzwanzig.meetup"
    const val SITE = "21meetup.space"
    const val BRAND = "EINUNDZWANZIG"
    const val FILE_NAME = "einundzwanzig_badge.png"

    /** Waits for crest images inside the capture (spec 03 §2.2 note: up to about 1 s). */
    const val SETTLE_MS = 900L

    fun chip(badge: MeetupBadge): String = if (badge.isEvent) "EVENT-BADGE" else "MEETUP-BADGE"

    /** The checksum row value: `hash.take(16) + "…"` above 16 characters. */
    fun checksum(hash: String): String = if (hash.length > 16) hash.take(16) + "…" else hash
}

/**
 * Fixed-size badge share image (Dart `BadgeShareCard`, spec 03 §2.3). Only rendered off-screen;
 * [collectorName] is kept for parity (no caller sets it, so the row never shows).
 */
@Composable
internal fun BadgeShareCard(badge: MeetupBadge, modifier: Modifier = Modifier, collectorName: String = "") {
    val colors = MaterialTheme.colorScheme
    val hash = badge.verificationHash()
    Column(modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            BadgeArt(badge.artSeed(), Modifier.fillMaxSize())
            MeetupCrestWatermark(meetupName = badge.meetupName, opacity = 0.26f, imageUrl = badge.coverUrl.ifEmpty { null })
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.35f to Color.Transparent,
                            0.78f to colors.background.copy(alpha = 0.65f),
                            1f to colors.background,
                        ),
                    ),
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shape = RoundedCornerShape(6.dp)
                Text(
                    ShareCard.chip(badge),
                    modifier = Modifier
                        .background(colors.primary.copy(alpha = 0.18f), shape)
                        .border(0.5.dp, colors.primary.copy(alpha = 0.5f), shape)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    color = colors.primary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                )
                Spacer(Modifier.weight(1f))
                if (badge.isNostrSigned) {
                    Icon(Icons.Rounded.Verified, contentDescription = null, tint = MaterialTheme.extendedColors.success, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, end = 22.dp, bottom = 18.dp)) {
                Text(
                    dartUpper(badge.meetupName),
                    color = colors.onSurface,
                    fontSize = 26.sp,
                    lineHeight = 1.1.em,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    BadgeFormat.datePadded(badge.date),
                    color = colors.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceContainer)
                .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 16.dp),
        ) {
            if (collectorName.isNotEmpty()) {
                FootRow(Icons.Rounded.Person, stringResource(L10nR.string.shareCardCollectedBy), collectorName)
                Spacer(Modifier.height(7.dp))
            }
            val block = BadgeFormat.blockShareCard(badge.blockHeight)
            if (block.isNotEmpty()) {
                FootRow(Icons.Rounded.Link, stringResource(L10nR.string.shareCardBlock), block, mono = true, accent = true)
                Spacer(Modifier.height(7.dp))
            }
            if (badge.claimTimestamp > 0) {
                FootRow(Icons.Rounded.Schedule, stringResource(L10nR.string.shareCardScanned), BadgeFormat.scanTime(badge.claimTimestamp), mono = true)
                Spacer(Modifier.height(7.dp))
            }
            if (hash.isNotEmpty()) {
                FootRow(Icons.Rounded.Fingerprint, stringResource(L10nR.string.shareCardChecksum), ShareCard.checksum(hash), mono = true)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.outlineVariant))
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(L10nR.string.shareCardPromo),
                color = colors.onSurface,
                fontSize = 11.5.sp,
                lineHeight = 1.4.em,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = colors.primary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    ShareCard.APP_STORE_URL,
                    color = colors.primary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = colors.primary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(ShareCard.BRAND, color = colors.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    ShareCard.SITE,
                    color = MaterialTheme.extendedColors.textTertiary.copy(alpha = 0.8f),
                    fontSize = 9.5.sp,
                    letterSpacing = 0.4.sp,
                )
            }
        }
    }
}

@Composable
private fun FootRow(icon: ImageVector, label: String, value: String, mono: Boolean = false, accent: Boolean = false) {
    val tertiary = MaterialTheme.extendedColors.textTertiary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tertiary, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(7.dp))
        Text("$label  ", color = tertiary, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = if (mono) FontFamily.Monospace else null,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(widthDp = ShareCard.WIDTH_DP, heightDp = ShareCard.HEIGHT_DP)
@Composable
private fun BadgeShareCardPreview() {
    EinundzwanzigTheme { BadgeShareCard(PreviewData.badges().first()) }
}
