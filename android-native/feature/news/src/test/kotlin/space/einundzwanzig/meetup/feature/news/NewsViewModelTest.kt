package space.einundzwanzig.meetup.feature.news

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.feature.news.list.NewsViewModel

class NewsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val news = FakeNewsService(feed = listOf(article("a"), article("b")))
    private val widget = FakeWidgetStore()
    private val store = NewsArticleStore()

    private fun vm(markAsRead: Boolean = false) = NewsViewModel(markAsRead, news, widget, store, AppLogger)

    @Test
    fun `initial load fetches the uncached feed and fills the store`() {
        val vm = vm()
        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(listOf("Titel a", "Titel b"), state.articles.map { it.title })
        assertEquals(1, news.fetchCalls)
        assertEquals(0, news.cachedCalls)
        assertEquals(state.articles, store.articles)
    }

    @Test
    fun `starts in loading state until the feed answers`() {
        val gate = CompletableDeferred<Unit>()
        val slow = object : space.einundzwanzig.meetup.data.news.NewsService by news {
            override suspend fun fetchArticles(limit: Int) = gate.await().let { news.feed }
        }
        val vm = NewsViewModel(false, slow, widget, store, AppLogger)
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.isEmpty)
        gate.complete(Unit)
        assertFalse(vm.state.value.loading)
        assertEquals(2, vm.state.value.articles.size)
    }

    @Test
    fun `empty feed shows the empty state`() {
        news.feed = emptyList()
        val vm = vm()
        assertTrue(vm.state.value.isEmpty)
    }

    @Test
    fun `markAsRead clears read mark and widget marker once`() {
        vm(markAsRead = true)
        assertEquals(1, news.markReadCalls)
        assertEquals(1, widget.markNewsSeenCalls)
    }

    @Test
    fun `hub path does not mark anything read`() {
        vm(markAsRead = false)
        assertEquals(0, news.markReadCalls)
        assertEquals(0, widget.markNewsSeenCalls)
    }

    @Test
    fun `widget failure does not break the list`() {
        widget.fail = true
        val vm = vm(markAsRead = true)
        assertEquals(2, vm.state.value.articles.size)
        assertEquals(1, news.markReadCalls)
    }

    @Test
    fun `pull to refresh reloads and replaces the list`() {
        val vm = vm()
        news.feed = listOf(article("c"))
        vm.refresh()
        val state = vm.state.value
        assertFalse(state.refreshing)
        assertEquals(listOf("Titel c"), state.articles.map { it.title })
        assertEquals(2, news.fetchCalls)
        assertEquals(state.articles, store.articles)
    }
}
