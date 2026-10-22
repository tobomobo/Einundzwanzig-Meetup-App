package space.einundzwanzig.meetup.feature.news

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import space.einundzwanzig.meetup.data.news.NewsArticle

/**
 * The list the News screen currently shows. Flutter pushes `ArticleDetail(article)` with the
 * model; the native key carries `articleId` + `listIndex` only (ROUTES.md "Arguments instead of
 * models"), so the detail screen resolves the article here.
 */
@Singleton
class NewsArticleStore @Inject constructor() {
    private val current = AtomicReference<List<NewsArticle>>(emptyList())

    /** The last list shown by the News screen. */
    val articles: List<NewsArticle> get() = current.get()

    /** Replaces the list after a (re)load. */
    fun update(articles: List<NewsArticle>) {
        current.set(articles)
    }

    companion object {
        /**
         * The article at [listIndex] of [articles] if its id equals [articleId], else the first
         * article with that id, else `null`.
         */
        fun resolve(articles: List<NewsArticle>, articleId: String, listIndex: Int): NewsArticle? {
            articles.getOrNull(listIndex)?.takeIf { it.id == articleId }?.let { return it }
            return articles.firstOrNull { it.id == articleId }
        }
    }
}
