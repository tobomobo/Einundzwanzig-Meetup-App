package space.einundzwanzig.meetup.feature.news

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.News
import space.einundzwanzig.meetup.core.navigation.NewsArticleDetails
import space.einundzwanzig.meetup.feature.news.detail.ArticleDetailRoute
import space.einundzwanzig.meetup.feature.news.list.NewsRoute

/** Registers the News destinations (spec 11 §2.3–§2.4) in the app's `entryProvider`. */
fun EntryProviderScope<NavKey>.newsEntries() {
    entry<News> { key -> NewsRoute(markAsRead = key.markAsRead) }
    entry<NewsArticleDetails> { key -> ArticleDetailRoute(key) }
}
