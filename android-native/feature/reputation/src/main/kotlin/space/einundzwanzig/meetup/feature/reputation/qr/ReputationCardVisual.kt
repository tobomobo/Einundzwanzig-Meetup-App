package space.einundzwanzig.meetup.feature.reputation.qr

import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.ui.qr.QrCode
import space.einundzwanzig.meetup.core.ui.qr.QrErrorCorrection
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.reputation.R
import space.einundzwanzig.meetup.feature.reputation.common.cyanColor
import space.einundzwanzig.meetup.feature.reputation.common.greenColor
import space.einundzwanzig.meetup.feature.reputation.common.levelStyle
import space.einundzwanzig.meetup.feature.reputation.common.localizedLevel
import space.einundzwanzig.meetup.feature.reputation.common.orangeColor
import space.einundzwanzig.meetup.feature.reputation.common.tertiaryTextColor
import space.einundzwanzig.meetup.feature.reputation.common.toColor
import space.einundzwanzig.meetup.feature.reputation.common.toImageVector
import androidx.compose.ui.res.stringResource
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * The quiet zone and module background of the reputation QR. It stays white in every theme:
 * scanners need dark modules on a light background (spec 04 §2.6 step 5, R-15 MUST).
 */
internal val QrBackground = Color.White

/** Test tag of the QR inside the card. */
internal const val REPUTATION_QR_TAG = "reputation_qr_code"

/**
 * `ReputationCardVisual` (spec 04 §2.6): the styled card around the reputation QR, also the
 * content of the shared PNG. The level label is localized (spec allows it; the wire value is
 * never translated).
 */
@Composable
internal fun ReputationCardVisual(
    nickname: String,
    score: Double,
    level: String,
    badgeCount: Int,
    meetupCount: Int,
    signerCount: Int,
    qrData: String,
    isSigned: Boolean,
    modifier: Modifier = Modifier,
) {
    val style = levelStyle(level)
    val levelColor = style.color.toColor()
    val shape = RoundedCornerShape(24.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .dropShadow(shape, Shadow(radius = 30.dp, spread = (-5).dp, color = levelColor.copy(alpha = 0.15f), offset = DpOffset(0.dp, 10.dp)))
            .background(Brush.linearGradient(listOf(colors.surfaceContainerHigh, colors.surface)), shape)
            .border(1.5.dp, levelColor.copy(alpha = 0.4f), shape)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .background(levelColor.copy(alpha = 0.15f), CircleShape)
                    .border(1.5.dp, levelColor.copy(alpha = 0.4f), CircleShape),
            ) {
                Icon(style.icon.toImageVector(), contentDescription = null, tint = levelColor, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = nickname.uppercase(),
                    color = colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (level.isNotEmpty()) {
                    Text(
                        text = localizedLevel(level),
                        color = levelColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        val scoreShape = RoundedCornerShape(16.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .background(levelColor.copy(alpha = 0.08f), scoreShape)
                .border(1.dp, levelColor.copy(alpha = 0.2f), scoreShape)
                .padding(vertical = 14.dp),
        ) {
            Text(
                text = stringResource(L10nR.string.reputationScoreLabel).uppercase(),
                color = levelColor.copy(alpha = 0.75f),
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = score.toStringAsFixed(1),
                style = MonoStyle,
                color = levelColor,
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 40.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CardStat(badgeCount, stringResource(L10nR.string.reputationBadges), orangeColor)
            StatDivider()
            CardStat(meetupCount, stringResource(L10nR.string.reputationMeetups), cyanColor)
            StatDivider()
            CardStat(signerCount, stringResource(L10nR.string.reputationSigners), MaterialTheme.extendedColors.nostrOnTile)
        }
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outlineVariant),
        )
        Spacer(Modifier.height(16.dp))
        val qrDescription = stringResource(R.string.native_repQrDescription)
        Box(
            Modifier
                .background(QrBackground, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            QrCode(
                content = qrData,
                size = 220.dp,
                errorCorrection = QrErrorCorrection.L,
                backgroundColor = QrBackground,
                contentDescription = qrDescription,
                modifier = Modifier.testTag(REPUTATION_QR_TAG),
            )
        }
        Spacer(Modifier.height(12.dp))
        val signedColor = if (isSigned) greenColor else tertiaryTextColor
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isSigned) Icons.Rounded.VerifiedUser else Icons.Outlined.Lock,
                contentDescription = null,
                tint = signedColor,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(if (isSigned) L10nR.string.reputationSchnorrSigned else L10nR.string.reputationUnsigned).uppercase(),
                color = signedColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "EINUNDZWANZIG · PROOF OF ATTENDANCE",
            color = tertiaryTextColor.copy(alpha = 0.65f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
        )
    }
}

@Composable
private fun CardStat(value: Int, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(value.toString(), color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(2.dp))
        Text(
            text = label.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0C0E)
@Composable
private fun ReputationCardVisualPreview() {
    EinundzwanzigTheme {
        ReputationCardVisual(
            nickname = "Satoshi",
            score = 12.34,
            level = "AKTIV",
            badgeCount = 9,
            meetupCount = 4,
            signerCount = 3,
            qrData = "21v3:eyJ2Ijo2fQ==.sig.id.1700000000.pub",
            isSigned = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}
