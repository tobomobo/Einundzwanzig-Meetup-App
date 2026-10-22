package space.einundzwanzig.meetup.feature.home.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.home.HomeAction
import space.einundzwanzig.meetup.feature.home.HomeUiState
import space.einundzwanzig.meetup.feature.home.R
import space.einundzwanzig.meetup.feature.home.tiles.TileId
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Accent colour and watermark of a tile (spec 12 §2.5.8). */
internal data class TileLook(val accent: Color, val watermark: ImageVector? = null, @param:DrawableRes val watermarkRes: Int? = null)

@Composable
internal fun tileLook(tile: TileId, state: HomeUiState): TileLook {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extendedColors
    return when (tile) {
        TileId.EventChats -> TileLook(x.nostr, Icons.Rounded.EventAvailable)
        TileId.Reputation -> TileLook(x.amber, Icons.Rounded.WorkspacePremium)
        TileId.TrustNetwork -> TileLook(c.primary, Icons.Rounded.AccountTree)
        TileId.Community -> TileLook(x.community, Icons.Rounded.Hub)
        TileId.Events -> TileLook(if (state.eventsToday > 0) c.primary else x.textTertiary, Icons.Rounded.Event)
        TileId.BtcDashboard -> TileLook(c.primary)
        TileId.Converter -> TileLook(x.community, Icons.Rounded.SwapVert)
        TileId.News -> TileLook(c.primary, Icons.AutoMirrored.Rounded.Article)
        TileId.Portal -> TileLook(c.primary, Icons.Rounded.Groups)
        TileId.Shoutout -> TileLook(c.primary, Icons.Rounded.Campaign)
        TileId.Podcast -> TileLook(x.purple, Icons.Rounded.Podcasts)
        TileId.PlebRap -> TileLook(c.primary, Icons.Rounded.GraphicEq)
        TileId.SatoshiDuell -> TileLook(x.duellGold, watermarkRes = R.drawable.home_satoshiduell)
        TileId.PortalArea -> TileLook(c.primary, Icons.Rounded.Public)
        TileId.Nostr -> TileLook(x.nostr)
        TileId.Organisator -> TileLook(c.primary, Icons.Rounded.AdminPanelSettings)
        TileId.TrustScore, TileId.PortalConnect, TileId.HomeMeetup -> TileLook(c.primary)
    }
}

/** Content of a dashboard tile (spec 12 §2.5.8 builders). */
@Composable
internal fun BoxScope.TileBody(tile: TileId, state: HomeUiState, onAction: (HomeAction) -> Unit) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extendedColors
    when (tile) {
        TileId.EventChats -> {
            val sub = when {
                state.myEventsCount == 0 -> stringResource(L10nR.string.tileEventChatsNone)
                state.myEventsUnread > 0 -> stringResource(L10nR.string.tileEventChatsUnread, state.myEventsUnread)
                else -> stringResource(L10nR.string.tileEventChatsSub)
            }
            HeroContent(
                Icons.Rounded.EventAvailable,
                x.nostr,
                stringResource(L10nR.string.tileEventChats),
                "${state.myEventsCount}",
                sub,
                valueColor = if (state.myEventsUnread > 0) c.primary else null,
            )
        }
        TileId.Reputation -> HeroContent(
            Icons.Rounded.WorkspacePremium,
            x.amber,
            stringResource(L10nR.string.tileReputation),
            state.reputationValue,
            if (state.countedBadges > 0) {
                stringResource(L10nR.string.tileReputationBadges, state.countedBadges)
            } else {
                stringResource(L10nR.string.tileReputationCheck)
            },
            valueSize = 26.sp,
        )
        TileId.TrustNetwork -> HeroContent(
            Icons.Rounded.AccountTree,
            x.success,
            stringResource(L10nR.string.tileActEncounters),
            stringResource(L10nR.string.tileTrustNetwork),
            stringResource(L10nR.string.tileTrustNetworkSub),
            valueSize = 17.sp,
        )
        TileId.Community -> HeroContent(
            Icons.Rounded.Hub,
            x.success,
            stringResource(L10nR.string.tileActExchange),
            stringResource(L10nR.string.tileCommunity),
            stringResource(L10nR.string.tileCommunityPortal),
            valueSize = 17.sp,
        )
        TileId.Events -> {
            val hasToday = state.eventsToday > 0
            HeroContent(
                Icons.Rounded.Event,
                if (hasToday) c.primary else c.onSurfaceVariant,
                stringResource(L10nR.string.tileEvents),
                if (hasToday) "${state.eventsToday}" else stringResource(L10nR.string.tileEventsCalendar),
                if (hasToday) stringResource(L10nR.string.tileEventsToday) else null,
                valueSize = if (hasToday) 26.sp else 16.sp,
                valueColor = if (hasToday) c.onSurface else c.onSurfaceVariant,
            )
        }
        TileId.BtcDashboard -> BtcTileContent(state)
        TileId.Converter -> HeroContent(
            Icons.Rounded.SwapVert,
            x.community,
            stringResource(L10nR.string.tileActConvert),
            state.converterText ?: stringResource(L10nR.string.tileConverter),
            stringResource(L10nR.string.tileConverterSub),
            valueSize = 17.sp,
        )
        TileId.News -> HeroContent(
            Icons.AutoMirrored.Rounded.Article,
            c.primary,
            stringResource(L10nR.string.tileNews),
            state.latestNewsTitle?.takeIf { it.isNotEmpty() } ?: stringResource(L10nR.string.tileNewsSub),
            null,
            valueSize = 14.sp,
            valueColor = if (state.latestNewsTitle.isNullOrEmpty()) c.onSurfaceVariant else c.onSurface,
            trailing = if (state.unreadNews > 0) {
                {
                    Text(
                        "${state.unreadNews}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold),
                        color = c.onError,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.error)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            } else {
                null
            },
        )
        TileId.Portal -> HeroContent(
            Icons.Rounded.Groups,
            x.community,
            stringResource(L10nR.string.tileActLookup),
            stringResource(L10nR.string.tilePortal),
            stringResource(L10nR.string.tilePortalSub),
            valueSize = 17.sp,
            trailing = { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = x.textTertiary) },
        )
        TileId.Shoutout -> HeroContent(
            Icons.Rounded.Campaign,
            c.primary,
            stringResource(L10nR.string.tileActSend),
            stringResource(L10nR.string.tileShoutout),
            stringResource(L10nR.string.tileShoutoutSend),
            valueSize = 17.sp,
        )
        TileId.Podcast -> HeroContent(
            Icons.Rounded.Podcasts,
            x.purple,
            stringResource(L10nR.string.tileActListen),
            stringResource(L10nR.string.tilePodcast),
            stringResource(L10nR.string.tilePodcastListen),
            valueSize = 17.sp,
        )
        TileId.PlebRap -> PlebrapContent(state, onAction)
        TileId.SatoshiDuell -> DuellContent(state)
        TileId.PortalArea -> HeroContent(
            Icons.Rounded.Public,
            c.primary,
            "Portal",
            stringResource(L10nR.string.chPortalSub),
            null,
            valueSize = 17.sp,
        )
        TileId.Nostr -> NostrContent(state.nostrHasNew)
        TileId.Organisator -> HeroContent(
            Icons.Rounded.AdminPanelSettings,
            c.primary,
            stringResource(L10nR.string.tileActManage),
            stringResource(L10nR.string.tileOrganizer),
            stringResource(L10nR.string.tileOrganizerPanel),
            valueSize = 17.sp,
            trailing = { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = x.textTertiary) },
        )
        TileId.TrustScore, TileId.PortalConnect, TileId.HomeMeetup -> Unit // never drawn (dead / rendered separately)
    }
}

/** `_heroContent`: label row, big value, optional sub line. */
@Composable
internal fun HeroContent(
    icon: ImageVector,
    accent: Color,
    label: String,
    value: String,
    sub: String?,
    valueSize: TextUnit = 24.sp,
    valueColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = accent)
            Spacer(Modifier.width(6.dp))
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp),
                color = accent,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(7.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = valueSize,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = valueSize * 1.12f,
            ),
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!sub.isNullOrEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                sub,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BoxScope.BtcTileContent(state: HomeUiState) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extendedColors
    Image(
        painterResource(R.drawable.home_bitcoin),
        null,
        Modifier
            .align(Alignment.CenterEnd)
            .offset(x = 18.dp)
            .size(104.dp)
            .alpha(0.10f),
    )
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.home_bitcoin), null, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Bitcoin", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.native_home_btc_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
                maxLines = 1,
            )
            state.btc.priceText?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (state.btc.live) x.success.copy(alpha = 0.7f) else x.textTertiary),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "BLOCK",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 2.sp),
                    color = x.textTertiary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                state.btc.blockText,
                style = MonoStyle.copy(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp),
                color = c.primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlebrapContent(state: HomeUiState, onAction: (HomeAction) -> Unit) {
    val c = MaterialTheme.colorScheme
    val p = state.plebrap
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            HeroContent(
                Icons.Rounded.GraphicEq,
                c.primary,
                "PlebRap",
                p.title ?: stringResource(L10nR.string.chPlebrapSub),
                p.artist,
                valueSize = if (p.title != null) 17.sp else 14.sp,
                valueColor = if (p.title != null) c.onSurface else c.onSurfaceVariant,
            )
        }
        val playLabel = stringResource(if (p.playing) R.string.native_home_plebrap_pause else R.string.native_home_plebrap_play)
        FilledIconButton(
            onClick = { onAction(HomeAction.PlebrapToggle) },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(48.dp),
        ) {
            if (p.loading) {
                LoadingIndicator(Modifier.size(28.dp), color = c.onPrimary)
            } else {
                Icon(if (p.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, playLabel, Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { onAction(HomeAction.PlebrapNext) }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.SkipNext, stringResource(R.string.native_home_plebrap_next), Modifier.size(26.dp))
        }
        IconButton(onClick = { onAction(HomeAction.PlebrapLibrary) }, shapes = IconButtonDefaults.shapes()) {
            Icon(
                Icons.Rounded.QueueMusic,
                stringResource(R.string.native_home_plebrap_library),
                Modifier.size(24.dp),
                tint = c.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DuellContent(state: HomeUiState) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extendedColors
    val gold = x.duellGold
    val d = state.duell
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp), tint = gold)
            Spacer(Modifier.width(6.dp))
            Text(
                "SATOSHIDUELL",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp),
                color = gold,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(7.dp))
        if (d.count > 0) {
            Text(
                "${d.count}",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold),
                maxLines = 1,
            )
        } else {
            Text(
                stringResource(L10nR.string.chDuellSub),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 17.sp),
                color = c.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val turn = stringResource(L10nR.string.sdShortTurn)
        val lobby = stringResource(L10nR.string.sdShortLobby)
        val wait = stringResource(L10nR.string.sdShortWait)
        if (d.myTurn > 0 || d.lobby > 0 || d.waiting > 0) {
            Spacer(Modifier.height(3.dp))
            val line = buildAnnotatedString {
                var first = true
                fun sep() {
                    if (!first) withStyle(SpanStyle(color = x.textTertiary)) { append("  ·  ") }
                    first = false
                }
                if (d.myTurn > 0) {
                    sep()
                    withStyle(SpanStyle(color = gold, fontWeight = FontWeight.Bold)) { append("⚡ ${d.myTurn} $turn") }
                }
                if (d.lobby > 0) {
                    sep()
                    withStyle(SpanStyle(color = c.primary, fontWeight = FontWeight.SemiBold)) { append("${d.lobby} $lobby") }
                }
                if (d.waiting > 0) {
                    sep()
                    withStyle(SpanStyle(color = x.success)) { append("${d.waiting} $wait") }
                }
            }
            Text(line, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BoxScope.NostrContent(hasNew: Boolean) {
    Column {
        Image(
            painterResource(R.drawable.home_nostr_icon),
            null,
            Modifier.size(30.dp).alpha(if (hasNew) 1f else 0.55f),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(L10nR.string.tileNostr),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            stringResource(L10nR.string.tileNostrCommunity),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
    if (hasNew) {
        val label = stringResource(R.string.native_home_nostr_new)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .semantics { contentDescription = label },
        )
    }
}

/** Dp helper for the watermark offsets. */
internal val WatermarkIconSize: Dp = 84.dp
internal val WatermarkAssetSize: Dp = 100.dp
