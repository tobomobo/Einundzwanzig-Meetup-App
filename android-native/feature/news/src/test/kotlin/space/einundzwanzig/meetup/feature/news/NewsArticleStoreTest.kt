package space.einundzwanzig.meetup.feature.news

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NewsArticleStoreTest {

    private val first = article("x", id = "https://same", title = "erster")
    private val second = article("y", id = "https://same", title = "zweiter")
    private val other = article("z")
    private val list = listOf(other, first, second)

    @Test
    fun `index wins when its id matches`() {
        assertSame(second, NewsArticleStore.resolve(list, "https://same", 2))
    }

    @Test
    fun `mismatching index falls back to the first article with the id`() {
        assertSame(first, NewsArticleStore.resolve(list, "https://same", 0))
    }

    @Test
    fun `negative or out of range index uses the id`() {
        assertSame(first, NewsArticleStore.resolve(list, "https://same", -1))
        assertSame(other, NewsArticleStore.resolve(list, other.id, 99))
    }

    @Test
    fun `unknown id is not found`() {
        assertNull(NewsArticleStore.resolve(list, "30023:nope:nope", 0))
        assertNull(NewsArticleStore.resolve(emptyList(), "", -1))
    }

    @Test
    fun `empty id resolves only an article with empty id`() {
        val empty = article("e", id = "")
        assertSame(empty, NewsArticleStore.resolve(listOf(other, empty), "", 5))
    }
}
