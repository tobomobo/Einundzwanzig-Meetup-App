package space.einundzwanzig.meetup.feature.events.common

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.crest.CrestFaceImage
import space.einundzwanzig.meetup.core.ui.theme.extendedColors

/** Month names `calMonth1..12`. */
val MonthNames: List<Int> = listOf(
    L10nR.string.calMonth1, L10nR.string.calMonth2, L10nR.string.calMonth3, L10nR.string.calMonth4,
    L10nR.string.calMonth5, L10nR.string.calMonth6, L10nR.string.calMonth7, L10nR.string.calMonth8,
    L10nR.string.calMonth9, L10nR.string.calMonth10, L10nR.string.calMonth11, L10nR.string.calMonth12,
)

/** Weekday abbreviations `calWd0..6`, Monday first. */
val WeekdayNames: List<Int> = listOf(
    L10nR.string.calWd0, L10nR.string.calWd1, L10nR.string.calWd2, L10nR.string.calWd3,
    L10nR.string.calWd4, L10nR.string.calWd5, L10nR.string.calWd6,
)

/** Month name of [month] (1..12). */
@Composable
fun monthName(month: Int): String = stringResource(MonthNames[month - 1])

/** Meetup crest with the orange `groups` fallback box (spec 10 §2.2). */
@Composable
fun CrestBox(url: String, size: Dp, radius: Dp, modifier: Modifier = Modifier, showFallback: Boolean = true) {
    val shape = RoundedCornerShape(radius)
    CrestFaceImage(
        imageUrl = url,
        modifier = modifier.size(size).clip(shape),
        contentDescription = null,
    ) {
        if (showFallback) {
            Box(
                Modifier
                    .size(size)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Groups,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(size * 0.5f),
                )
            }
        }
    }
}

/** Small uppercase section label (10–11 sp, tertiary, letter-spaced). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.extendedColors.textTertiary,
    )
}

/** Colored 8–10 dp type dot. */
@Composable
fun TypeDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** Row with icon + label used for the sheet actions ("Zum Kalender", "Link öffnen", …). */
@Composable
fun SheetActionButton(
    icon: ImageVector,
    @StringRes label: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), color = color, fontWeight = FontWeight.Bold)
    }
}

/** A tappable link row (orange): link icon, URL on one line, open-in-new. */
@Composable
fun LinkRow(url: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        border = BorderStroke(0.5.dp, accent.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Link, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Text(
                url,
                modifier = Modifier.weight(1f),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
            )
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
    }
}

/** Small expressive loading indicator for buttons. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InlineLoading(size: Dp = 18.dp, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    LoadingIndicator(modifier = modifier.size(size), color = color)
}
