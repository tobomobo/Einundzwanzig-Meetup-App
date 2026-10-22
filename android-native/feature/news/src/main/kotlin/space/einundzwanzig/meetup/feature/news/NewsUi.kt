package space.einundzwanzig.meetup.feature.news

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import space.einundzwanzig.meetup.core.ui.format.Formats
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.news.NewsArticle
import space.einundzwanzig.meetup.data.news.NewsArticles

/** Literal subtitle of both News action tiles (not translated in Flutter). */
internal const val MEDIA_HOST_LABEL = "media.einundzwanzig.space"

/** Dart `"${d.day}.${d.month}.${d.year}"` of `publishedAt` in the local zone. */
internal fun NewsArticle.dateLabel(zone: ZoneId = ZoneId.systemDefault()): String =
    Formats.dateShort(Instant.ofEpochMilli(publishedAtMillis).atZone(zone).toLocalDate())

/** Person icon + author label, spacer, date — the meta line of cards and the detail header. */
@Composable
internal fun ArticleMetaRow(
    article: NewsArticle,
    style: TextStyle,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    spaceBetween: Boolean = true,
) {
    val color = MaterialTheme.extendedColors.textTertiary
    val date = remember(article.publishedAt) { article.dateLabel() }
    val dateDescription = stringResource(R.string.native_news_date_description, date)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.Person,
            contentDescription = stringResource(R.string.native_news_author_description),
            tint = color,
            modifier = Modifier.size(iconSize),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            NewsArticles.authorLabel(article),
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (spaceBetween) Modifier.weight(1f, fill = true) else Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            date,
            style = style,
            color = color,
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = dateDescription },
        )
    }
}
