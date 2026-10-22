package space.einundzwanzig.meetup.feature.news

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.navigation.NewsArticleDetails
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.news.ArticleLikes
import space.einundzwanzig.meetup.data.news.ZapError
import space.einundzwanzig.meetup.data.news.ZapResult
import space.einundzwanzig.meetup.feature.news.detail.ArticleDetailAction
import space.einundzwanzig.meetup.feature.news.detail.ArticleDetailEvent
import space.einundzwanzig.meetup.feature.news.detail.ArticleDetailViewModel
import space.einundzwanzig.meetup.core.l10n.R as L10nR

class ArticleDetailViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val a = article("a", title = "Halving")
    private val b = article("b")
    private val store = NewsArticleStore().apply { update(listOf(a, b)) }
    private val news = FakeNewsService(content = "## Body")
    private val reactions = FakeReactions(relayLikes = ArticleLikes(3, false))
    private val zaps = FakeZaps()

    private fun vm(key: NewsArticleDetails = NewsArticleDetails(a.id, 0)) =
        ArticleDetailViewModel(key, store, news, reactions, zaps, AppLogger)

    @Test
    fun `resolves from the store and loads content and likes`() {
        val state = vm().state.value
        assertFalse(state.resolving)
        assertSame(a, state.article)
        assertFalse(state.contentLoading)
        assertEquals("## Body", state.fullContent)
        assertTrue(state.hasFullContent)
        assertEquals(ArticleLikes(3, false), state.likes)
        assertEquals("3", state.likeCountLabel)
        assertEquals(listOf(a.id), news.contentRequests)
        assertEquals(0, news.cachedCalls)
    }

    @Test
    fun `falls back to the cached feed when the store is empty`() {
        store.update(emptyList())
        news.cached = listOf(b)
        val state = vm(NewsArticleDetails(b.id, 3)).state.value
        assertSame(b, state.article)
        assertEquals(1, news.cachedCalls)
    }

    @Test
    fun `unknown article shows not found and loads nothing`() {
        val state = vm(NewsArticleDetails("30023:x:y", 0)).state.value
        assertTrue(state.notFound)
        assertNull(state.article)
        assertTrue(news.contentRequests.isEmpty())
    }

    @Test
    fun `while the full text loads the summary state is shown`() {
        news.contentGate = CompletableDeferred()
        val vm = vm()
        assertTrue(vm.state.value.contentLoading)
        news.contentGate!!.complete(Unit)
        assertFalse(vm.state.value.contentLoading)
    }

    @Test
    fun `blank or missing full text falls back to the website button`() {
        news.content = "   "
        assertFalse(vm().state.value.hasFullContent)
        news.content = null
        assertFalse(vm().state.value.hasFullContent)
    }

    @Test
    fun `local like fills the heart before relays answer`() {
        reactions.likedLocally = true
        reactions.likesGate = CompletableDeferred()
        val vm = vm()
        assertTrue(vm.state.value.likes.mine)
        assertFalse(vm.state.value.likesLoaded)
        assertNull(vm.state.value.likeCountLabel)
        reactions.relayLikes = ArticleLikes(5, true)
        reactions.likesGate!!.complete(Unit)
        assertEquals("5", vm.state.value.likeCountLabel)
    }

    @Test
    fun `zero likes shows the plain label`() {
        reactions.relayLikes = ArticleLikes.EMPTY
        assertNull(vm().state.value.likeCountLabel)
    }

    @Test
    fun `like success adds the own heart`() {
        val vm = vm()
        vm.onAction(ArticleDetailAction.Like)
        assertEquals(ArticleLikes(4, true), vm.state.value.likes)
        assertFalse(vm.state.value.liking)
        assertEquals(listOf(a.id to PK_A), reactions.likeCalls)
    }

    @Test
    fun `like is ignored while liking and once liked`() {
        reactions.likeGate = CompletableDeferred()
        val vm = vm()
        vm.onAction(ArticleDetailAction.Like)
        assertTrue(vm.state.value.liking)
        vm.onAction(ArticleDetailAction.Like)
        reactions.likeGate!!.complete(Unit)
        vm.onAction(ArticleDetailAction.Like)
        assertEquals(1, reactions.likeCalls.size)
    }

    @Test
    fun `like failure keeps the heart empty and reports newsLikeFailed`() = runTest {
        reactions.likeResult = false
        val vm = vm()
        vm.events.test {
            vm.onAction(ArticleDetailAction.Like)
            assertEquals(ArticleDetailEvent.Message(L10nR.string.newsLikeFailed, Tone.Error), awaitItem())
        }
        assertFalse(vm.state.value.likes.mine)
        assertFalse(vm.state.value.liking)
    }

    @Test
    fun `zap opens the amount sheet and hands the invoice to a wallet`() = runTest {
        val vm = vm()
        assertTrue(vm.state.value.canZap)
        vm.onAction(ArticleDetailAction.Zap)
        assertTrue(vm.state.value.zapSheetVisible)
        vm.events.test {
            vm.onAction(ArticleDetailAction.ZapAmount(2100))
            assertEquals(ArticleDetailEvent.OpenInvoice("lnbc1invoice"), awaitItem())
        }
        assertFalse(vm.state.value.zapSheetVisible)
        assertFalse(vm.state.value.zapping)
        assertEquals(listOf(ZapCall(PK_A, a.id, 2100, "Halving")), zaps.calls)
    }

    @Test
    fun `dismissing the amount sheet zaps nothing`() {
        val vm = vm()
        vm.onAction(ArticleDetailAction.Zap)
        vm.onAction(ArticleDetailAction.DismissZapSheet)
        assertFalse(vm.state.value.zapSheetVisible)
        assertTrue(zaps.calls.isEmpty())
    }

    @Test
    fun `zap errors map to their strings`() = runTest {
        val expected = mapOf(
            ZapError.NoLightningAddress to L10nR.string.newsZapNoAddress,
            ZapError.UnsupportedAddress to L10nR.string.newsZapUnsupportedAddress,
            ZapError.AmountOutOfRange to L10nR.string.newsZapAmountRange,
            ZapError.LnurlUnreachable to L10nR.string.newsZapFailed,
            ZapError.SigningFailed to L10nR.string.newsZapFailed,
            ZapError.InvoiceFailed to L10nR.string.newsZapFailed,
        )
        val vm = vm()
        vm.events.test {
            expected.forEach { (error, text) ->
                zaps.result = ZapResult.Failure(error)
                vm.onAction(ArticleDetailAction.ZapAmount(21))
                assertEquals(ArticleDetailEvent.Message(text, Tone.Error), awaitItem())
            }
        }
    }

    @Test
    fun `article without nip-23 address cannot be zapped`() = runTest {
        val linkOnly = article("l", id = "https://media.einundzwanzig.space/a/l")
        store.update(listOf(linkOnly))
        val vm = vm(NewsArticleDetails(linkOnly.id, 0))
        assertFalse(vm.state.value.canZap)
        vm.events.test {
            vm.onAction(ArticleDetailAction.Zap)
            expectNoEvents()
        }
        assertFalse(vm.state.value.zapSheetVisible)
        assertTrue(zaps.calls.isEmpty())
    }

    @Test
    fun `missing wallet shows the invoice sheet until copied or dismissed`() {
        val vm = vm()
        vm.onWalletMissing("lnbc1x")
        assertEquals("lnbc1x", vm.state.value.fallbackInvoice)
        vm.onAction(ArticleDetailAction.CopyInvoice("lnbc1x"))
        assertNull(vm.state.value.fallbackInvoice)
        vm.onWalletMissing("lnbc1y")
        vm.onAction(ArticleDetailAction.DismissInvoiceFallback)
        assertNull(vm.state.value.fallbackInvoice)
    }
}
