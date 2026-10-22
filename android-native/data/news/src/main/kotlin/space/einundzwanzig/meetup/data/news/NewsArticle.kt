package space.einundzwanzig.meetup.data.news

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.crypto.Hex

/** Fixed URLs and relays of the news domain (Dart `news_service.dart`, spec 11 §2.3, §5 N1, §6.1). */
object NewsLinks {
    /** RSS feed (N1). */
    const val FEED_URL: String = "https://media.einundzwanzig.space/s/einundzwanzig-news/feed.xml"

    /** "Auf der Webseite öffnen" and the fallback of [NewsArticles.articleUrl] (Dart `kNewsWebsiteUrl`). */
    const val WEBSITE_URL: String = "https://media.einundzwanzig.space/s/einundzwanzig-news"

    /** "Artikel schreiben". */
    const val WRITE_URL: String = "https://media.einundzwanzig.space"

    /** Byte-exact `User-Agent` of the feed request (same string on all platforms). */
    const val FEED_USER_AGENT: String = "Einundzwanzig-Meetup-App/1.0 (Android)"

    /** `Accept` header of the feed request. */
    const val FEED_ACCEPT: String = "application/rss+xml, application/xml, text/xml"

    /** Relays queried for the NIP-23 full text (Dart `NewsService._relays`; user relays are not added). */
    val ARTICLE_RELAYS: List<String> = listOf(
        "wss://nostr.einundzwanzig.space",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
    )
}

/**
 * One RSS `<item>` of the news feed (Dart `NewsArticle`, spec 11 §3.4).
 *
 * @property id the RSS `guid` if non-empty, else [link]; normally the NIP-23 address
 *   `30023:<hexpubkey>:<d>` (see [ArticleAddress]).
 * @property author the author NAME from `<dc:creator>`/`<author>` (Dart field `pubkey`; it is not a key).
 * @property title cleaned title, `"(ohne Titel)"` if empty.
 * @property summary the description without HTML tags, whitespace collapsed.
 * @property image enclosure URL or first `<img src>` of the content; `""` if none.
 * @property content cleaned `content:encoded` (or description). Parsed but not displayed by the Flutter app.
 * @property publishedAt unix seconds (pubDate; timezone suffix ignored, see [RssParser.parseDate]).
 * @property link cleaned `<link>`, else the guid. May be a non-URL; use [NewsArticles.articleUrl].
 */
data class NewsArticle(
    val id: String,
    val author: String,
    val title: String,
    val summary: String,
    val image: String,
    val content: String,
    val publishedAt: Long,
    val link: String,
) {
    /** [publishedAt] in epoch milliseconds (Dart `date`; format it in the local zone as `d.M.yyyy`). */
    val publishedAtMillis: Long get() = publishedAt * 1000
}

/** Pure helpers of the news screens (Dart `news_screen.dart`). */
object NewsArticles {
    /** Fallback author label when the feed has none. */
    const val DEFAULT_AUTHOR_LABEL: String = "Einundzwanzig"

    /** Dart `authorLabel`: the author name or [DEFAULT_AUTHOR_LABEL]. */
    fun authorLabel(article: NewsArticle): String = article.author.ifEmpty { DEFAULT_AUTHOR_LABEL }

    /**
     * Dart `_ArticleDetail.authorPubkey`: the second `:`-part of the id (`30023:<pubkey>:<d>`), or
     * `""` when the id has no `:`. Not validated; see [zapAuthorPubkey].
     */
    fun authorPubkey(article: NewsArticle): String {
        val parts = article.id.split(':')
        return if (parts.size >= 2) parts[1] else ""
    }

    /**
     * [authorPubkey] if it is a 64-char hex key, else `""`. Spec 11 §14 risk 12: an article
     * without guid has an id like `https://…`, whose "pubkey" is `//host/…`; the UI should
     * disable zapping for `""` and the like gets no `p` tag.
     */
    fun zapAuthorPubkey(article: NewsArticle): String =
        authorPubkey(article).takeIf { Hex.isHexOfLength(it, 64) } ?: ""

    /** Dart `articleUrl(a)`: the trimmed link if it starts with `http://`/`https://`, else [NewsLinks.WEBSITE_URL]. */
    fun articleUrl(article: NewsArticle): String {
        val link = article.link.dartTrim()
        return if (link.startsWith("http://") || link.startsWith("https://")) link else NewsLinks.WEBSITE_URL
    }

    /** The share text `"<title>\n\n<articleUrl>"`. */
    fun shareText(article: NewsArticle): String = "${article.title}\n\n${articleUrl(article)}"

    /** The newest article by [NewsArticle.publishedAt] (Dart `reduce(a.publishedAt >= b.publishedAt ? a : b)`: ties keep the earlier one). */
    fun newest(articles: List<NewsArticle>): NewsArticle? =
        articles.reduceOrNull { a, b -> if (a.publishedAt >= b.publishedAt) a else b }
}

/**
 * A NIP-23 article address `30023:<pubkey>:<d>` split like Dart `fetchArticleContent`.
 *
 * @property pubkey the author key (second part).
 * @property d the identifier: every part after the second joined with `:` (d may contain `:`).
 */
data class ArticleAddress(val pubkey: String, val d: String) {
    companion object {
        /** Kind of long-form articles. */
        const val KIND_LONG_FORM: Int = 30023

        /** Parses [guid]; `null` with fewer than 3 parts or an empty pubkey/d. */
        fun parse(guid: String): ArticleAddress? {
            val parts = guid.split(':')
            if (parts.size < 3) return null
            val pubkey = parts[1]
            val d = parts.subList(2, parts.size).joinToString(":")
            if (pubkey.isEmpty() || d.isEmpty()) return null
            return ArticleAddress(pubkey, d)
        }
    }
}
