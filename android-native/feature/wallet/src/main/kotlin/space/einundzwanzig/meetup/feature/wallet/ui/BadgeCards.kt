package space.einundzwanzig.meetup.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.badge.BadgeArt
import space.einundzwanzig.meetup.core.ui.badge.BadgeArtSpec
import space.einundzwanzig.meetup.core.ui.crest.MeetupCrestWatermark
import space.einundzwanzig.meetup.core.ui.motion.Pressable
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.feature.wallet.domain.AgoLabel
import space.einundzwanzig.meetup.feature.wallet.domain.BadgeFormat
import space.einundzwanzig.meetup.feature.wallet.domain.CollectionSubtitle
import space.einundzwanzig.meetup.feature.wallet.domain.WalletLibrary
import space.einundzwanzig.meetup.feature.wallet.GroupUi
import space.einundzwanzig.meetup.feature.wallet.R
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Art seed shared by cards, details and share card (spec 03 §8.6). */
internal fun MeetupBadge.artSeed(): String = BadgeArtSpec.seedFor(meetupName, blockHeight)

/** Dart `toUpperCase()` for display (keeps `ß`). */
internal fun dartUpper(s: String): String = DartString.upper(s)

/**
 * The single TalkBack announcement of a badge card: name, date, number and the organizer /
 * unverified-presence marker (also on compact cards, which do not draw it). The card clears its
 * children's semantics, so nothing is read twice.
 */
@Composable
private fun badgeCardDescription(badge: MeetupBadge, number: Int): String {
    val base = "${badge.meetupName}, ${BadgeFormat.dateShort(badge.date)}, #$number"
    return when {
        badge.isOrganizer -> "$base, ${stringResource(L10nR.string.walletOrganizerSection)}"
        !badge.presenceVerified -> "$base, ${stringResource(L10nR.string.badgeUnverified)}"
        else -> base
    }
}

/**
 * Normal wallet badge card (spec 03 §2.1): art, crest watermark, gradient, verified mark,
 * `#n` pill (with organizer / presence markers), name, date and block.
 */
@Composable
internal fun NormalBadgeCard(
    badge: MeetupBadge,
    number: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val scrim = MaterialTheme.colorScheme.scrim
    val shape = RoundedCornerShape(16.dp)
    Pressable(
        onClick = onClick,
        modifier = modifier
            .aspectRatio(0.80f)
            .shadow(12.dp, shape, ambientColor = primary.copy(alpha = 0.12f), spotColor = primary.copy(alpha = 0.12f))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, primary.copy(alpha = 0.4f), shape),
    ) {
        val description = badgeCardDescription(badge, number)
        Box(Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = description }) {
            BadgeArt(badge.artSeed(), Modifier.fillMaxSize())
            MeetupCrestWatermark(
                meetupName = badge.meetupName,
                imageUrl = badge.coverUrl.ifEmpty { null },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.35f to scrim.copy(alpha = 0.15f),
                            1f to scrim.copy(alpha = 0.80f),
                        ),
                    ),
            )
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(scrim.copy(alpha = 0.5f))
                            .border(1.dp, primary.copy(alpha = 0.5f), CircleShape)
                            .padding(6.dp),
                    ) {
                        Icon(Icons.Rounded.Verified, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(scrim.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (badge.isOrganizer) {
                            Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.extendedColors.onScrim.copy(alpha = OnScrimSecondary), modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(4.dp))
                        } else if (!badge.presenceVerified) {
                            Icon(
                                Icons.Rounded.LocationOff,
                                contentDescription = null,
                                tint = MaterialTheme.extendedColors.onScrim.copy(alpha = OnScrimTertiary),
                                modifier = Modifier.size(11.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text("#$number", color = primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                // Flutter draws two shadows: (1,1) blur 6 black and (0,0) blur 12 black at 54 %.
                // Compose takes one per text, so the glow is a second, semantics-free layer.
                val density = LocalDensity.current
                val nameStyle = TextStyle(
                    fontSize = if (badge.meetupName.length > 18) 11.sp else 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.3.sp,
                    lineHeight = 1.2.em,
                )
                val name = dartUpper(badge.meetupName)
                Box {
                    Text(
                        text = name,
                        color = MaterialTheme.extendedColors.onScrim,
                        style = nameStyle.copy(shadow = Shadow(scrim.copy(alpha = 0.54f), Offset.Zero, with(density) { 12.dp.toPx() })),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = name,
                        color = MaterialTheme.extendedColors.onScrim,
                        style = nameStyle.copy(
                            shadow = with(density) { Shadow(scrim, Offset(1.dp.toPx(), 1.dp.toPx()), 6.dp.toPx()) },
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CalendarToday, contentDescription = null, tint = MaterialTheme.extendedColors.bitcoinOnScrim, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        BadgeFormat.dateShort(badge.date),
                        color = MaterialTheme.extendedColors.onScrim.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("₿", color = MaterialTheme.extendedColors.bitcoinOnScrim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Block ${BadgeFormat.blockWallet(badge.blockHeight)}",
                        color = MaterialTheme.extendedColors.onScrim.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Compact wallet card (3-column year grid, spec 03 §2.1): no organizer / presence markers. */
@Composable
internal fun CompactBadgeCard(
    badge: MeetupBadge,
    number: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val scrim = MaterialTheme.colorScheme.scrim
    val shape = RoundedCornerShape(12.dp)
    val shortName = dartUpper(badge.meetupName.split(',').first().trim())
    val description = badgeCardDescription(badge, number)
    Pressable(
        onClick = onClick,
        modifier = modifier
            .aspectRatio(0.75f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(0.8.dp, primary.copy(alpha = 0.3f), shape),
    ) {
        Box(Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = description }) {
            BadgeArt(badge.artSeed(), Modifier.fillMaxSize())
            MeetupCrestWatermark(
                meetupName = badge.meetupName,
                opacity = 0.17f,
                widthFactor = 0.95f,
                imageUrl = badge.coverUrl.ifEmpty { null },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to scrim.copy(alpha = 0.2f),
                            1f to scrim.copy(alpha = 0.8f),
                        ),
                    ),
            )
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Verified, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
                    Text("#$number", color = primary.copy(alpha = 0.8f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    shortName,
                    color = MaterialTheme.extendedColors.onScrim,
                    style = TextStyle(
                        fontSize = if (shortName.length > 12) 9.sp else 11.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 1.2.em,
                        shadow = with(LocalDensity.current) { Shadow(scrim, Offset(1.dp.toPx(), 1.dp.toPx()), 4.dp.toPx()) },
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(BadgeFormat.dateShort(badge.date), color = MaterialTheme.extendedColors.onScrim.copy(alpha = 0.54f), fontSize = 9.sp)
            }
        }
    }
}

/** Collection card of the "Nach Meetup" grouping (spec 03 §2.1). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CollectionCard(
    group: GroupUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isOrganizer = group.group.isOrganizer
    val count = group.group.badges.size
    val cover = group.group.cover
    val shape = RoundedCornerShape(10.dp)
    val title = if (isOrganizer) {
        dartUpper(stringResource(L10nR.string.walletOrganizerSection))
    } else {
        dartUpper(group.key)
    }
    Pressable(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(0.5.dp, if (isOrganizer) colors.outlineVariant else colors.primary.copy(alpha = 0.3f), shape),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))) {
                MeetupCrestWatermark(
                    meetupName = group.key,
                    opacity = 1f,
                    widthFactor = 1f,
                    imageUrl = cover.coverUrl.ifEmpty { null },
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = if (isOrganizer) colors.onSurfaceVariant else colors.onSurface,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.3.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("×$count", color = colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitleText(group.subtitle),
                    color = MaterialTheme.extendedColors.textTertiary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (WalletLibrary.showCountDots(count)) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(count) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.65f)))
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.extendedColors.textTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

/** `seit Mär 2025 · zuletzt vor 3 Tagen` (spec 03 §8.4). */
@Composable
internal fun subtitleText(subtitle: CollectionSubtitle): String {
    val since = stringResource(L10nR.string.walletSince, subtitle.sinceMonthYear)
    val last = subtitle.lastVisit ?: return since
    return "$since · ${stringResource(L10nR.string.walletLastVisit, agoText(last))}"
}

@Composable
internal fun agoText(label: AgoLabel): String = when (label) {
    AgoLabel.Today -> stringResource(L10nR.string.walletAgoToday)
    AgoLabel.Yesterday -> stringResource(L10nR.string.walletAgoYesterday)
    is AgoLabel.Days -> stringResource(L10nR.string.walletAgoDays, label.days.toInt())
    is AgoLabel.Months -> stringResource(L10nR.string.walletAgoMonths, label.months.toInt())
    is AgoLabel.Years -> stringResource(L10nR.string.walletAgoYears, label.years.toInt())
}

/** Collapsible year/organizer section header of the "Nach Jahr" grouping (spec 03 §2.1). */
@Composable
internal fun YearSectionHeader(
    group: GroupUi,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isOrganizer = group.group.isOrganizer
    val label = if (isOrganizer) dartUpper(stringResource(L10nR.string.walletOrganizerSection)) else group.key
    val accent = if (isOrganizer) colors.onSurfaceVariant else colors.primary
    val stateText = stringResource(if (collapsed) R.string.native_wallet_collapsed else R.string.native_wallet_expanded)
    Pressable(
        onClick = onToggle,
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = stateText },
        pressedScale = 0.99f,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(start = 2.dp, end = 2.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (isOrganizer) Icons.Outlined.Shield else Icons.Rounded.Event, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, color = accent, fontSize = 11.5.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.primary.copy(alpha = 0.14f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text("${group.group.badges.size}", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                contentDescription = null,
                tint = MaterialTheme.extendedColors.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Alpha of [space.einundzwanzig.meetup.core.ui.theme.ExtendedColors.onScrim] for Flutter `cTextSecondary` on a scrim. */
private const val OnScrimSecondary = 0.62f

/** Alpha of `onScrim` for Flutter `cTextTertiary` on a scrim. */
private const val OnScrimTertiary = 0.54f
