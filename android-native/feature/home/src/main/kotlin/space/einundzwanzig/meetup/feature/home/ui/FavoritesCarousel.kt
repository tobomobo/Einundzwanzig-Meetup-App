package space.einundzwanzig.meetup.feature.home.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocation
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import space.einundzwanzig.meetup.core.ui.motion.Pressable
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.home.FavCardUi
import space.einundzwanzig.meetup.feature.home.HomeFormat
import space.einundzwanzig.meetup.feature.home.R
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Pinned-tile look shared by the favorites card and the pinned tiles (orange gradient). */
@Composable
internal fun pinnedBrush(): Brush {
    val primary = MaterialTheme.colorScheme.primary
    return Brush.linearGradient(
        0f to primary.copy(alpha = 0.13f),
        0.45f to primary.copy(alpha = 0.04f),
        1f to MaterialTheme.colorScheme.surfaceContainerLow,
    )
}

/** "Wähle deinen Stammtisch" call to action when no favorite is set (spec 12 §2.5.5). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeMeetupCta(onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Pressable(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth().testTag(HomeTags.HOME_MEETUP_CTA)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Dimens.TileShape)
                .background(
                    Brush.linearGradient(listOf(primary.copy(alpha = 0.10f), MaterialTheme.colorScheme.surfaceContainerLow)),
                )
                .border(1.5.dp, primary.copy(alpha = 0.35f), Dimens.TileShape)
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(MaterialShapes.Cookie7Sided.toShape())
                    .background(primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.AddLocation, null, Modifier.size(28.dp), tint = primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(L10nR.string.homeMeetupLabel),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp),
                    color = primary,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    stringResource(L10nR.string.homeMeetupChoose),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(L10nR.string.homeMeetupChooseSub),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(24.dp), tint = primary)
        }
    }
}

/** Swipeable favorites (one card per favorite meetup) with page dots (spec 12 §2.5.5). */
@Composable
internal fun FavoritesCarousel(
    cards: List<FavCardUi>,
    epoch: Int,
    chatUnread: Map<String, Int>,
    countdownLoading: Boolean,
    onEvents: (FavCardUi) -> Unit,
    onChat: (FavCardUi) -> Unit,
    onInfo: (FavCardUi) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState { cards.size }
    LaunchedEffect(epoch) { if (pager.currentPage != 0) pager.scrollToPage(0) }
    Column(modifier.fillMaxWidth().testTag(HomeTags.CAROUSEL)) {
        HorizontalPager(
            state = pager,
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxWidth().height(152.dp),
            key = { cards.getOrNull(it)?.key ?: it },
        ) { page ->
            val card = cards[page]
            Pressable(onClick = null, onLongClick = onLongPress, role = null, modifier = Modifier.fillMaxSize()) {
                FavCard(
                    card = card,
                    unread = chatUnread[card.key] ?: 0,
                    countdownLoading = countdownLoading,
                    onEvents = { onEvents(card) },
                    onChat = { onChat(card) },
                    onInfo = { onInfo(card) },
                )
            }
        }
        if (cards.size > 1) {
            Spacer(Modifier.height(8.dp))
            val label = stringResource(R.string.native_home_page, pager.currentPage + 1, cards.size)
            Row(
                Modifier.fillMaxWidth().semantics { stateDescription = label },
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(cards.size) { i ->
                    val active = i == pager.currentPage
                    val width by animateDpAsState(if (active) 18.dp else 6.dp, label = "dot")
                    val color by animateColorAsState(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.extendedColors.textTertiary.copy(alpha = 0.4f),
                        label = "dotColor",
                    )
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = width, height = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FavCard(
    card: FavCardUi,
    unread: Int,
    countdownLoading: Boolean,
    onEvents: () -> Unit,
    onChat: () -> Unit,
    onInfo: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxSize()
            .clip(Dimens.TileShape)
            .background(pinnedBrush())
            .border(1.dp, primary.copy(alpha = 0.30f), Dimens.TileShape)
            .testTag(HomeTags.favCard(card.key)),
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (card.crestUrl.isNotEmpty()) {
                CrestCoverFlow(card.crestUrl, 60.dp.value)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(L10nR.string.homeMeetupLabel),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, letterSpacing = 1.1.sp),
                        color = primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(primary.copy(alpha = 0.20f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        card.country,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(5.dp))
                val label = card.label.uppercase()
                Text(
                    label,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = HomeFormat.favLabelFontSize(label.length).sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp,
                        lineHeight = (HomeFormat.favLabelFontSize(label.length) * 1.05f).sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                NextEventLine(card, countdownLoading)
            }
        }
        FavActions(card, unread, onEvents, onChat, onInfo)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NextEventLine(card: FavCardUi, loading: Boolean) {
    val tertiary = MaterialTheme.extendedColors.textTertiary
    when {
        loading -> LinearWavyProgressIndicator(Modifier.width(90.dp).height(14.dp).testTag(HomeTags.COUNTDOWN_LOADING))
        card.daysUntil == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.EventBusy, null, Modifier.size(13.dp), tint = tertiary)
            Spacer(Modifier.width(5.dp))
            Text(
                stringResource(L10nR.string.homeMeetupNoDate),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = tertiary,
                maxLines = 1,
            )
        }
        else -> {
            val days = card.daysUntil
            val text = when {
                days <= 0 -> stringResource(L10nR.string.homeMeetupToday)
                days == 1L -> stringResource(L10nR.string.homeMeetupTomorrow)
                else -> stringResource(L10nR.string.homeMeetupInDays, days.toInt())
            }
            val color = when (HomeFormat.proximity(days)) {
                0 -> MaterialTheme.colorScheme.primary
                1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold),
                    color = color,
                    maxLines = 1,
                )
                Text(
                    " · ${card.dateText}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FavActions(
    card: FavCardUi,
    unread: Int,
    onEvents: () -> Unit,
    onChat: () -> Unit,
    onInfo: () -> Unit,
) {
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(Dimens.Hairline).background(border))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            ActionField(
                Icons.Rounded.Event,
                stringResource(L10nR.string.btnEvents),
                MaterialTheme.colorScheme.primary,
                onClick = onEvents,
                modifier = Modifier.weight(1f).testTag(HomeTags.favAction(card.key, "events")),
            )
            Divider(border)
            ActionField(
                Icons.Rounded.Forum,
                stringResource(L10nR.string.btnChat),
                MaterialTheme.extendedColors.nostr,
                badge = unread,
                onClick = onChat,
                modifier = Modifier.weight(1f).testTag(HomeTags.favAction(card.key, "chat")),
            )
            Divider(border)
            ActionField(
                Icons.Rounded.Info,
                stringResource(L10nR.string.btnInfo),
                MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = if (card.meetupId == null) null else onInfo,
                modifier = Modifier.weight(1f).testTag(HomeTags.favAction(card.key, "info")),
            )
        }
    }
}

@Composable
private fun Divider(color: Color) {
    Box(Modifier.width(Dimens.Hairline).height(26.dp).background(color))
}

@Composable
private fun ActionField(
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    badge: Int = 0,
) {
    val disabled = onClick == null
    val tint = if (disabled) MaterialTheme.extendedColors.textTertiary.copy(alpha = 0.5f) else accent
    val textColor = if (disabled) tint else MaterialTheme.colorScheme.onSurface
    val unreadLabel = if (badge > 0) stringResource(R.string.native_home_unread_cd, HomeFormat.badgeText(badge)) else null
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !disabled, role = Role.Button) { onClick?.invoke() }
            .semantics { if (unreadLabel != null) stateDescription = unreadLabel }
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(icon, null, Modifier.size(15.dp), tint = tint)
            if (badge > 0) {
                Text(
                    HomeFormat.badgeText(badge),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Black, lineHeight = 11.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .offset(x = 9.dp, y = (-5).dp)
                        .widthIn(min = 13.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.2.sp),
            color = textColor,
            maxLines = 1,
        )
    }
}

/**
 * Crest in perspective, fading to the right (Flutter `_crestCoverFlow`): rotated −0.55 rad around
 * the Y axis at the left edge, masked with a horizontal gradient (`DstIn`).
 */
@Composable
private fun CrestCoverFlow(url: String, sizeDp: Float) {
    Box(
        Modifier
            .size(width = (sizeDp * 1.12f).dp, height = sizeDp.dp)
            .graphicsLayer {
                rotationY = -31.5f
                cameraDistance = 6f * density
                transformOrigin = TransformOrigin(0f, 0.5f)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.White,
                        0.35f to Color.White.copy(alpha = 0.7f),
                        0.7f to Color.White.copy(alpha = 0.24f),
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(sizeDp.dp).clip(Dimens.TileShape),
        )
    }
}
