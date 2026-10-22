package space.einundzwanzig.meetup.feature.home.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.guide.guideAnchor
import space.einundzwanzig.meetup.core.ui.motion.Pressable
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.nostr.AvatarImage
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.home.HomeTour
import space.einundzwanzig.meetup.feature.home.R
import space.einundzwanzig.meetup.feature.home.SessionUi
import java.io.File
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Logo bar (spec 12 §2.5.2): logo left; refresh, glossary "?" (left of the gear), settings. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LogoBar(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onGlossary: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tertiary = MaterialTheme.extendedColors.textTertiary
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.semantics { contentDescription = "Einundzwanzig" }) {
            Image(painterResource(R.drawable.home_logo_mark), null, Modifier.height(16.dp))
            Image(
                painterResource(R.drawable.home_logo_text),
                null,
                Modifier.height(16.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
            )
        }
        Spacer(Modifier.weight(1f))
        val iconColors = IconButtonDefaults.iconButtonColors(contentColor = tertiary)
        if (refreshing) {
            Box(Modifier.size(48.dp).testTag(HomeTags.REFRESHING), contentAlignment = Alignment.Center) {
                val label = stringResource(R.string.native_home_refreshing)
                LoadingIndicator(Modifier.size(28.dp).semantics { contentDescription = label })
            }
        } else {
            IconButton(
                onClick = onRefresh,
                colors = iconColors,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.testTag(HomeTags.REFRESH),
            ) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.native_home_refresh), Modifier.size(20.dp))
            }
        }
        IconButton(
            onClick = onGlossary,
            colors = iconColors,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.guideAnchor(HomeTour.GLOSSARY).testTag(HomeTags.GLOSSARY),
        ) {
            Icon(Icons.AutoMirrored.Rounded.HelpOutline, stringResource(R.string.native_home_glossary), Modifier.size(20.dp))
        }
        IconButton(
            onClick = onSettings,
            colors = iconColors,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.guideAnchor(HomeTour.SETTINGS).testTag(HomeTags.SETTINGS),
        ) {
            Icon(Icons.Rounded.Settings, stringResource(R.string.native_home_settings), Modifier.size(20.dp))
        }
    }
}

/**
 * Profile header (spec 12 §2.5.3): avatar (local picture → Nostr picture → initial), nickname and
 * the trust-score pill. A tap on the avatar picks a gallery picture only without a Nostr picture.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProfileHeader(
    nickname: String,
    avatarLocal: String?,
    avatarUrl: String?,
    scorePill: String,
    onAvatarClick: (() -> Unit)?,
    onScoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val model: Any? = avatarLocal?.let { File(it) } ?: avatarUrl
        val initial = nickname.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val avatarShape = MaterialShapes.Cookie9Sided.toShape()
        val pickLabel = stringResource(R.string.native_home_avatar_pick)
        Pressable(
            onClick = onAvatarClick,
            onClickLabel = pickLabel,
            modifier = Modifier.size(48.dp).testTag(HomeTags.AVATAR),
        ) {
            AvatarImage(
                model = model,
                fallbackText = initial,
                backgroundColor = MaterialTheme.colorScheme.surfaceContainer,
                size = 44.dp,
                shape = avatarShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, avatarShape),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            nickname,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        ScorePill(scorePill, onScoreClick)
    }
}

@Composable
private fun ScorePill(score: String, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val cd = stringResource(R.string.native_home_score_cd, score)
    Pressable(
        onClick = onClick,
        modifier = Modifier
            .height(48.dp)
            .semantics(mergeDescendants = true) { contentDescription = cd }
            .testTag(HomeTags.SCORE_PILL),
    ) {
        Row(
            Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(primary, primary.copy(alpha = 0.78f))))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(score, style = MonoStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.Black), color = onPrimary)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = onPrimary.copy(alpha = 0.54f))
        }
    }
}

/** Root/jailbreak warning (spec 12 §2.5.4), dismissible for the lifetime of the screen. */
@Composable
internal fun IntegrityWarningCard(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val amber = MaterialTheme.extendedColors.amber
    Row(
        modifier
            .fillMaxWidth()
            .clip(Dimens.TileShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(Dimens.Hairline, amber.copy(alpha = 0.3f), Dimens.TileShape)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
            .testTag(HomeTags.INTEGRITY),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WarningAmber, null, Modifier.size(18.dp), tint = amber)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.native_home_device_warning),
            style = MaterialTheme.typography.bodySmall,
            color = amber.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, stringResource(R.string.native_home_dismiss_warning), Modifier.size(16.dp), tint = amber)
        }
    }
}

/** Live session card with pulsing dot and countdown (spec 12 §2.5.4). */
@Composable
internal fun LiveSessionCard(session: SessionUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val green = MaterialTheme.extendedColors.success
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "live")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reduced) 0f else 1f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label = "pulse",
    )
    val openLabel = stringResource(R.string.native_home_live_open)
    Pressable(
        onClick = onClick,
        onClickLabel = openLabel,
        modifier = modifier.fillMaxWidth().testTag(HomeTags.LIVE_SESSION),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Dimens.TileShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(Dimens.Hairline, green.copy(alpha = 0.25f), Dimens.TileShape)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .drawBehind {
                        drawCircle(green.copy(alpha = 0.3f * pulse), radius = size.minDimension / 2 + 4.dp.toPx())
                        drawCircle(green.copy(alpha = 0.5f + 0.5f * pulse))
                    },
            )
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(L10nR.string.statusLive),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.ExtraBold),
                color = green,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(green.copy(alpha = 0.15f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(10.dp))
            if (session.isEvent) {
                Icon(
                    Icons.Rounded.MilitaryTech,
                    stringResource(R.string.native_home_event_session),
                    Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                session.meetupName.ifEmpty { stringResource(L10nR.string.statusMeetupActive) },
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                session.timeLeft,
                style = MonoStyle.copy(fontSize = 11.sp),
                color = MaterialTheme.extendedColors.textTertiary,
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, Modifier.size(14.dp), tint = green.copy(alpha = 0.4f))
        }
    }
}
