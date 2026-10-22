package space.einundzwanzig.meetup.feature.news.detail

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.navigation.NewsArticleDetails
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.news.ArticleLikes
import space.einundzwanzig.meetup.data.news.NewsArticle
import space.einundzwanzig.meetup.data.news.NewsArticles
import space.einundzwanzig.meetup.data.news.NewsReactionsService
import space.einundzwanzig.meetup.data.news.NewsService
import space.einundzwanzig.meetup.data.news.NewsZapService
import space.einundzwanzig.meetup.data.news.ZapError
import space.einundzwanzig.meetup.data.news.ZapResult
import space.einundzwanzig.meetup.feature.news.NewsArticleStore
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * State of the article detail (Dart `_ArticleDetailState`, spec 11 §2.4).
 *
 * @property resolving the article is looked up (only visible after process death).
 * @property article `null` when not found (after [resolving]).
 * @property contentLoading the NIP-23 full text is loading (summary + indicator).
 * @property fullContent the Markdown body; `null`/blank → summary + "Auf der Webseite lesen".
 * @property zapSheetVisible the amount sheet (`newsZapTitle`) is open.
 * @property fallbackInvoice no wallet took the invoice: show the `newsZapNoWallet` sheet.
 */
@Immutable
data class ArticleDetailUiState(
    val resolving: Boolean = true,
    val article: NewsArticle? = null,
    val contentLoading: Boolean = true,
    val fullContent: String? = null,
    val likes: ArticleLikes = ArticleLikes.EMPTY,
    val likesLoaded: Boolean = false,
    val liking: Boolean = false,
    val zapping: Boolean = false,
    val zapSheetVisible: Boolean = false,
    val fallbackInvoice: String? = null,
) {
    val notFound: Boolean get() = !resolving && article == null

    /** `true` when [fullContent] can be rendered with the Markdown view. */
    val hasFullContent: Boolean get() = !fullContent.isNullOrBlank()

    /**
     * The Zap button is enabled only for a NIP-23 article with a 64-hex author key (spec 11 §14
     * risk 12, `:data:news` README): a link-only id has no author to look up.
     */
    val canZap: Boolean get() = article?.let { NewsArticles.zapAuthorPubkey(it).isNotEmpty() } == true

    /** Like button label: the count once loaded and > 0, else `newsLike`. */
    val likeCountLabel: String? get() = if (likesLoaded && likes.count > 0) likes.count.toString() else null
}

/** User actions of the article detail. */
sealed interface ArticleDetailAction {
    data object Back : ArticleDetailAction
    data object Like : ArticleDetailAction
    data object Zap : ArticleDetailAction
    data object DismissZapSheet : ArticleDetailAction
    data class ZapAmount(val sats: Long) : ArticleDetailAction
    data object DismissInvoiceFallback : ArticleDetailAction
    data class CopyInvoice(val invoice: String) : ArticleDetailAction
    data object Share : ArticleDetailAction
    data class OpenUrl(val url: String) : ArticleDetailAction
}

/** One-off effects the screen performs with a `Context`. */
sealed interface ArticleDetailEvent {
    data class Message(@StringRes val text: Int, val tone: Tone) : ArticleDetailEvent

    /** Hand the invoice to a wallet (`lightning:` ACTION_VIEW); on failure call [ArticleDetailViewModel.onWalletMissing]. */
    data class OpenInvoice(val invoice: String) : ArticleDetailEvent
}

/**
 * Article detail logic: resolves the article from [NewsArticleStore] (index, then id, then the
 * cached feed), then loads full text, local like and relay likes in parallel; like, zap.
 */
@HiltViewModel(assistedFactory = ArticleDetailViewModel.Factory::class)
class ArticleDetailViewModel @AssistedInject constructor(
    @Assisted private val key: NewsArticleDetails,
    private val store: NewsArticleStore,
    private val newsService: NewsService,
    private val reactions: NewsReactionsService,
    private val zaps: NewsZapService,
    /** Passed to `ZapInvoiceLauncher.open` by the route (wallet start failures). */
    val log: DiagnosticLog,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: NewsArticleDetails): ArticleDetailViewModel
    }

    private val _state = MutableStateFlow(ArticleDetailUiState())
    val state: StateFlow<ArticleDetailUiState> = _state.asStateFlow()

    private val _events = Channel<ArticleDetailEvent>(Channel.BUFFERED)
    val events: Flow<ArticleDetailEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val article = NewsArticleStore.resolve(store.articles, key.articleId, key.listIndex)
                ?: NewsArticleStore.resolve(newsService.cachedArticles(), key.articleId, key.listIndex)
            _state.update { it.copy(resolving = false, article = article, contentLoading = article != null) }
            if (article != null) loadAll(article)
        }
    }

    private fun loadAll(article: NewsArticle) {
        viewModelScope.launch {
            val content = newsService.fetchArticleContent(article.id)
            _state.update { it.copy(fullContent = content, contentLoading = false) }
        }
        viewModelScope.launch {
            if (reactions.hasLikedLocally(article.id)) {
                _state.update { it.copy(likes = ArticleLikes(it.likes.count, true)) }
            }
        }
        viewModelScope.launch {
            val likes = reactions.fetchLikes(article.id)
            _state.update { it.copy(likes = likes, likesLoaded = true) }
        }
    }

    /** Actions without a `Context`; share/URL/copy are handled by the route. */
    fun onAction(action: ArticleDetailAction) {
        when (action) {
            ArticleDetailAction.Like -> like()
            ArticleDetailAction.Zap -> openZapSheet()
            ArticleDetailAction.DismissZapSheet -> _state.update { it.copy(zapSheetVisible = false) }
            is ArticleDetailAction.ZapAmount -> zap(action.sats)
            ArticleDetailAction.DismissInvoiceFallback,
            is ArticleDetailAction.CopyInvoice,
            -> _state.update { it.copy(fallbackInvoice = null) }
            ArticleDetailAction.Back,
            ArticleDetailAction.Share,
            is ArticleDetailAction.OpenUrl,
            -> Unit
        }
    }

    /** No app took `lightning:<invoice>`: show the copy sheet. */
    fun onWalletMissing(invoice: String) {
        _state.update { it.copy(fallbackInvoice = invoice) }
    }

    /** Dart `_toggleLike`: no un-like (NIP-25 has no retraction), one like at a time. */
    private fun like() {
        val current = _state.value
        val article = current.article ?: return
        if (current.likes.mine || current.liking) return
        _state.update { it.copy(liking = true) }
        viewModelScope.launch {
            val ok = reactions.like(article.id, NewsArticles.authorPubkey(article))
            _state.update { it.copy(liking = false, likes = if (ok) it.likes.withMine() else it.likes) }
            if (!ok) _events.send(ArticleDetailEvent.Message(L10nR.string.newsLikeFailed, Tone.Error))
        }
    }

    private fun openZapSheet() {
        val current = _state.value
        val article = current.article ?: return
        // The button is disabled without a NIP-23 author key ([ArticleDetailUiState.canZap]).
        if (current.zapping || !current.canZap) return
        _state.update { it.copy(zapSheetVisible = true) }
    }

    /** Dart `_zap` after the amount sheet returned [sats]. */
    private fun zap(sats: Long) {
        val current = _state.value
        val article = current.article ?: return
        _state.update { it.copy(zapSheetVisible = false) }
        if (current.zapping) return
        _state.update { it.copy(zapping = true) }
        viewModelScope.launch {
            val result = zaps.createZapInvoice(
                authorPubkey = NewsArticles.zapAuthorPubkey(article),
                articleAddress = article.id,
                amountSats = sats,
                comment = article.title,
            )
            _state.update { it.copy(zapping = false) }
            when (result) {
                is ZapResult.Success -> _events.send(ArticleDetailEvent.OpenInvoice(result.invoice))
                is ZapResult.Failure -> _events.send(ArticleDetailEvent.Message(zapErrorText(result.error), Tone.Error))
            }
        }
    }

    companion object {
        /** Dart `_zapErrorText`. */
        @StringRes
        fun zapErrorText(error: ZapError): Int = when (error) {
            ZapError.NoLightningAddress -> L10nR.string.newsZapNoAddress
            ZapError.UnsupportedAddress -> L10nR.string.newsZapUnsupportedAddress
            ZapError.AmountOutOfRange -> L10nR.string.newsZapAmountRange
            ZapError.LnurlUnreachable,
            ZapError.SigningFailed,
            ZapError.InvoiceFailed,
            -> L10nR.string.newsZapFailed
        }
    }
}
