package space.einundzwanzig.meetup.feature.network.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.data.badges.text.DartString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.haptics.rememberHaptics
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkContact
import space.einundzwanzig.meetup.feature.network.NetworkPresentation
import space.einundzwanzig.meetup.feature.network.R
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Degree colours: 1 = success green, 2 = community gold, 3 = brand orange (spec 05 §2). */
@Composable
internal fun degreeColor(degree: Int): Color = when (degree) {
    1 -> MaterialTheme.extendedColors.success
    2 -> MaterialTheme.extendedColors.community
    else -> MaterialTheme.colorScheme.primary
}

@Composable
internal fun degreeLabel(degree: Int): String = stringResource(
    when (degree) {
        1 -> L10nR.string.mnDegreeDirect
        2 -> L10nR.string.mnDegreeSecond
        else -> L10nR.string.mnDegreeThird
    },
)

/** Section caption style: 11 sp, w600, letter spacing 1.2. */
@Composable
internal fun SectionCaption(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
    )
}

/** Hint box with an icon (Dart `_infoBox`). */
@Composable
internal fun NetworkInfoBox(text: String, icon: ImageVector, iconTint: Color? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Dimens.TileShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(Dimens.Hairline, MaterialTheme.colorScheme.outlineVariant, Dimens.TileShape)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Gespeicherter Stand" hint row of the empty state. */
@Composable
internal fun SavedStateHint(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.extendedColors.textTertiary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(L10nR.string.mnUpdateFailed),
            fontSize = 12.sp,
            color = MaterialTheme.extendedColors.textTertiary,
        )
    }
}

/** Failed-publication banner with "Erneut senden" (Dart `_failedBanner`). */
@Composable
internal fun FailedBanner(count: Int, retrying: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(Dimens.TileRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("failedBanner")
            .clip(shape)
            .background(accent.copy(alpha = 0.10f))
            .border(Dimens.Hairline, accent.copy(alpha = 0.45f), shape)
            .padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            pluralStringResource(L10nR.plurals.mnFailedBanner, count, count),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp),
            fontSize = 12.5.sp,
            lineHeight = 17.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            if (retrying) {
                val desc = stringResource(R.string.native_network_retrying)
                AppLoadingIndicator(
                    size = 32.dp,
                    contentDescription = desc,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else {
                TextButton(onClick = onRetry) {
                    Text(stringResource(L10nR.string.mnRetry), fontWeight = FontWeight.Bold, color = accent)
                }
            }
        }
    }
}

/** Reach card: total, meetup count, distribution bar, split line and status chip. */
@Composable
internal fun ReachCard(
    net: MyNetwork,
    updating: Boolean,
    doneDepth: Int,
    updateFailed: Boolean,
    standTime: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(Dimens.Hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionCaption(
                    stringResource(L10nR.string.mnReachTitle),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.weight(1f),
                )
                StatusChip(updating, doneDepth, updateFailed, standTime)
            }
            Spacer(Modifier.height(6.dp))
            Row {
                AnimatedContent(
                    targetState = net.totalReach,
                    modifier = Modifier.alignByBaseline(),
                    transitionSpec = {
                        (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                    },
                    label = "reach",
                ) { total ->
                    Text(
                        total.toString(),
                        modifier = Modifier.testTag("totalReach"),
                        fontSize = 44.sp,
                        lineHeight = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(L10nR.string.mnReachSub, net.myMeetupCount),
                    modifier = Modifier.alignByBaseline(),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            DistributionBar(net.degree1Count, net.degree2Count, net.degree3Count)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(L10nR.string.mnReachSplit, net.degree1Count, net.degree2Count, net.degree3Count),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DistributionBar(d1: Int, d2: Int, d3: Int) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (d1 + d2 + d3 == 0) {
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            )
            return@Row
        }
        listOf(
            d1 to degreeColor(1),
            d2 to degreeColor(2),
            d3 to degreeColor(3).copy(alpha = 0.6f),
        ).forEachIndexed { degreeIndex, (count, color) ->
            // Keyed per degree and always animated, so a degree that appears in a later build
            // stage grows from zero instead of inheriting another segment's animation state.
            key(degreeIndex) {
                val w by animateFloatAsState(count.toFloat(), spring(dampingRatio = 0.8f, stiffness = 380f), label = "bar")
                if (count > 0 || w > 0.01f) {
                    Box(
                        Modifier
                            .weight(w.coerceAtLeast(0.001f))
                            .height(8.dp)
                            .clip(shape)
                            .background(color),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusChip(updating: Boolean, doneDepth: Int, updateFailed: Boolean, standTime: String?) {
    val style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    when {
        updating -> Row(verticalAlignment = Alignment.CenterVertically) {
            AppLoadingIndicator(size = 18.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(L10nR.string.mnUpdating, NetworkPresentation.updatingDegree(doneDepth)),
                style = style,
            )
        }
        updateFailed -> {
            val tip = stringResource(L10nR.string.mnUpdateFailed)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(tip) } },
                state = rememberTooltipState(),
            ) {
                Row(
                    Modifier.semantics { contentDescription = tip },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.extendedColors.textTertiary,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(L10nR.string.mnSavedState), style = style)
                }
            }
        }
        standTime != null -> Text(stringResource(L10nR.string.mnStandAt, standTime), style = style)
    }
}

/** The four degree filter chips as a connected Expressive button group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FilterRow(net: MyNetwork, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val chips = listOf(
        Triple(0, net.totalReach, stringResource(L10nR.string.mnFilterAll)),
        Triple(1, net.degree1Count, stringResource(L10nR.string.mnDirectLabel)),
        Triple(2, net.degree2Count, stringResource(L10nR.string.mnDegreeSecond)),
        Triple(3, net.degree3Count, stringResource(L10nR.string.mnDegreeThird)),
    )
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        chips.forEachIndexed { index, (value, count, label) ->
            val isSelected = selected == value
            val countColor = if (value == 0) MaterialTheme.colorScheme.onSurface else degreeColor(value)
            ToggleButton(
                checked = isSelected,
                onCheckedChange = {
                    haptics.light()
                    onSelect(value)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("filter$value"),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    chips.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    checkedContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(count.toString(), fontSize = 17.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = countColor)
                    Text(label, fontSize = 12.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Search field with a clear button. */
@Composable
internal fun NetworkSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier
            .fillMaxWidth()
            .testTag("search"),
        placeholder = { Text(stringResource(L10nR.string.mnSearchHint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(L10nR.string.mnClose))
                }
            }
        } else {
            null
        },
        singleLine = true,
        // Spec 05 §2.1: filled, radius 10, orange border when focused (borderless otherwise).
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerLow,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/** Avatar with a ring in the degree colour, showing the initial or a person icon. */
@Composable
internal fun ContactAvatar(name: String?, degree: Int, size: Dp, modifier: Modifier = Modifier) {
    val initial = NetworkPresentation.initialOf(name)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(2.dp, degreeColor(degree), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (initial.isEmpty()) {
            Icon(
                Icons.Outlined.PersonOutline,
                contentDescription = null,
                modifier = Modifier.size(size * 0.45f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                initial,
                fontSize = (size.value * 0.4f).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Section header `TITLE · n` in the degree colour. */
@Composable
internal fun SectionHeader(degree: Int, total: Int, modifier: Modifier = Modifier) {
    val title = stringResource(
        when (degree) {
            1 -> L10nR.string.mnDegree1
            2 -> L10nR.string.mnDegree2
            else -> L10nR.string.mnDegree3
        },
    )
    SectionCaption(
        "${DartString.upper(title)} · $total",
        degreeColor(degree),
        modifier.padding(start = 2.dp, top = 14.dp, end = 2.dp, bottom = 8.dp),
    )
}

/** Shape of a row inside a section card (first/last rows carry the card corners). */
internal fun rowShape(first: Boolean, last: Boolean): Shape {
    val r = 12.dp
    return RoundedCornerShape(
        topStart = if (first) r else 0.dp,
        topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp,
        bottomEnd = if (last) r else 0.dp,
    )
}

/** One contact row (Dart `_contactRow`). */
@Composable
internal fun ContactRow(
    contact: NetworkContact,
    name: String?,
    detail: String,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openLabel = stringResource(R.string.native_network_open_details)
    Column(
        modifier
            .fillMaxWidth()
            .clip(rowShape(first, last))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = openLabel, onClick = onClick),
    ) {
        if (!first) HorizontalDivider(thickness = Dimens.Hairline, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContactAvatar(name, contact.degree, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (name != null) {
                    Text(
                        name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    NetworkPresentation.short(contact.npub),
                    style = MonoStyle.copy(
                        fontSize = if (name == null) 14.sp else 12.sp,
                        color = if (name == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.extendedColors.textTertiary,
                    ),
                    maxLines = 1,
                )
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.extendedColors.textTertiary,
            )
        }
    }
}

/** "Alle {n} anzeigen" footer of a section card. */
@Composable
internal fun ShowAllRow(degree: Int, total: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(rowShape(first = false, last = true))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        HorizontalDivider(thickness = Dimens.Hairline, color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(L10nR.string.mnShowAll, total),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = degreeColor(degree),
            )
        }
    }
}

/** Centered "Niemand gefunden" / "—" when no section matches (same Dart trim rule as `sections`). */
@Composable
internal fun NoResults(query: String, modifier: Modifier = Modifier) {
    Text(
        if (query.dartTrim().isNotEmpty()) stringResource(L10nR.string.mnNoResults) else "—",
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        textAlign = TextAlign.Center,
        fontSize = 14.sp,
        color = MaterialTheme.extendedColors.textTertiary,
    )
}

/** "So entsteht dein Netzwerk" row that opens the info sheet. */
@Composable
internal fun HowItWorksRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(Dimens.Hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(L10nR.string.mnHowItWorks),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(L10nR.string.mnHowItWorksSub),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Info icon used by the empty state's event note. */
internal val InfoOutline: ImageVector get() = Icons.Outlined.Info
