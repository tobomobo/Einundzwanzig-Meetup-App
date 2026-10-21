package space.einundzwanzig.meetup.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Article address parsing (§6.6, §10.4) and the pure screen helpers of `news_screen.dart`. */
class NewsArticlesTest {

    private fun article(id: String = "30023:pk:d", link: String = "", author: String = "", publishedAt: Long = 0) =
        NewsArticle(id, author, "Titel", "", "", "", publishedAt, link)

    @Test
    fun `article address keeps colons in d`() {
        assertEquals(ArticleAddress("pk", "a:b"), ArticleAddress.parse("30023:pk:a:b"))
        assertEquals(ArticleAddress("pk", "d"), ArticleAddress.parse("30023:pk:d"))
    }

    @Test
    fun `article address rejects short or empty parts`() {
        assertNull(ArticleAddress.parse("30023:pk"))
        assertNull(ArticleAddress.parse("30023::d"))
        assertNull(ArticleAddress.parse("30023:pk:"))
        assertNull(ArticleAddress.parse("https://media.example/a"))
    }

    @Test
    fun `author pubkey is the second id part`() {
        assertEquals("pk", NewsArticles.authorPubkey(article("30023:pk:d")))
        assertEquals("//media.example/a", NewsArticles.authorPubkey(article("https://media.example/a")))
        assertEquals("", NewsArticles.authorPubkey(article("noparts")))
        // Only a 64-hex key is usable for zaps and the like's p tag.
        assertEquals("", NewsArticles.zapAuthorPubkey(article("https://media.example/a")))
        val pk = "68240d4932950242924434c5b118035a92448a9570caf1fde7aeca77afbc80e4"
        assertEquals(pk, NewsArticles.zapAuthorPubkey(article("30023:$pk:d")))
    }

    @Test
    fun `article url falls back to the website for non-urls`() {
        assertEquals("https://x.example/a", NewsArticles.articleUrl(article(link = "  https://x.example/a ")))
        assertEquals("http://x.example/a", NewsArticles.articleUrl(article(link = "http://x.example/a")))
        assertEquals(NewsLinks.WEBSITE_URL, NewsArticles.articleUrl(article(link = "30023:pk:d")))
        assertEquals("Titel\n\n${NewsLinks.WEBSITE_URL}", NewsArticles.shareText(article()))
    }

    @Test
    fun `author label and newest article`() {
        assertEquals("Einundzwanzig", NewsArticles.authorLabel(article()))
        assertEquals("Roger", NewsArticles.authorLabel(article(author = "Roger")))
        val a = article(id = "a", publishedAt = 5)
        val b = article(id = "b", publishedAt = 5)
        val c = article(id = "c", publishedAt = 3)
        assertSame(a, NewsArticles.newest(listOf(c, a, b)))
        assertNull(NewsArticles.newest(emptyList()))
    }

    @Test
    fun `article likes withMine`() {
        assertEquals(ArticleLikes(1, true), ArticleLikes.EMPTY.withMine())
        val mine = ArticleLikes(4, true)
        assertSame(mine, mine.withMine())
    }
}
