package space.einundzwanzig.meetup.feature.events.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.component.StatusChip
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.calendar.format.CalendarFormat
import space.einundzwanzig.meetup.data.calendar.model.CalendarItem
import space.einundzwanzig.meetup.data.calendar.model.CalendarItemType
import space.einundzwanzig.meetup.feature.events.R
import space.einundzwanzig.meetup.feature.events.common.TypeDot
import space.einundzwanzig.meetup.feature.events.common.monthName
import java.time.ZoneId

/** Item color: meetup orange, Nostr event purple, course grey. */
@Composable
fun CalendarItem.color(): Color = when (type) {
    CalendarItemType.MEETUP -> MaterialTheme.colorScheme.primary
    CalendarItemType.NOSTR_EVENT -> MaterialTheme.extendedColors.nostr
    CalendarItemType.COURSE -> MaterialTheme.extendedColors.course
}

/** Dart `_fmtWhen(item, showDate)` (spec 10 §8.7). */
@Composable
fun fmtWhen(item: CalendarItem, showDate: Boolean, zone: ZoneId): String {
    val day = item.day(zone)
    val datePart = "${day.dayOfMonth}. ${monthName(day.monthValue)} ${day.year}"
    return if (item.allDay) {
        val allDay = stringResource(L10nR.string.calAllDay)
        if (showDate) "$datePart · $allDay" else allDay
    } else {
        val time = CalendarFormat.hhmm(item.start, zone)
        if (showDate) "$datePart · $time" else time
    }
}

/** Network image that disappears when it fails to load (Flutter `errorBuilder: SizedBox.shrink`). */
@Composable
fun HidingImage(url: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed || url.isBlank()) return
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        onError = { failed = true },
    )
}

/** Event card of the calendar (spec 10 §2.3 "Event card"). */
@Composable
fun CalendarEventCard(
    item: CalendarItem,
    zone: ZoneId,
    showDate: Boolean,
    crestUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = item.color()
    Tile(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(12.dp),
        borderColor = color.copy(alpha = 0.25f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeDot(color)
                Spacer(Modifier.width(8.dp))
                if (item.isMeetup && crestUrl.isNotEmpty()) {
                    HidingImage(crestUrl, Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    item.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.hasBadge) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.MilitaryTech,
                        contentDescription = stringResource(R.string.native_events_badge),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                StatusChip(
                    label = stringResource(if (item.isMeetup) L10nR.string.calTypeMeetup else L10nR.string.calTypeEvent),
                    color = color,
                )
            }
            if (item.badgeImage.isNotEmpty()) {
                HidingImage(
                    item.badgeImage,
                    Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(8.dp)),
                )
            }
            IconLine(Icons.Rounded.Schedule, fmtWhen(item, showDate, zone))
            if (item.location.isNotEmpty()) IconLine(Icons.Rounded.Place, item.location)
            if (item.description.isNotEmpty()) {
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Icon + one line of secondary text. */
@Composable
fun IconLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 14.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}
