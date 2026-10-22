package space.einundzwanzig.meetup.feature.news.list

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.news.NewsArticle
import space.einundzwanzig.meetup.data.news.NewsService
import space.einundzwanzig.meetup.feature.news.NewsArticleStore

/**
 * State of the News list (spec 11 §2.3).
 *
 * @property loading the first load (or a load without articles on screen) is running: show `newsLoading`.
 * @property refreshing a pull-to-refresh is running (the list stays visible).
 * @property articles the feed, newest first; empty + not loading = `newsEmpty`.
 */
@Immutable
data class NewsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val articles: List<NewsArticle> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && articles.isEmpty()
}

/** User actions of the News list. */
sealed interface NewsAction {
    data object Refresh : NewsAction
    data class OpenArticle(val index: Int, val article: NewsArticle) : NewsAction
    data object WriteArticle : NewsAction
    data object OpenWebsite : NewsAction
    data object Back : NewsAction
}

/**
 * Dart `_NewsScreenState`: `_load()` = `NewsService.fetchArticles()` (limit 50, not the cached
 * variant). With [markAsRead] (Home tile / widget news row, `_openNews`) the read mark and the
 * widget's "NEU" marker are cleared once on open (fire and forget, like Dart).
 */
@HiltViewModel(assistedFactory = NewsViewModel.Factory::class)
class NewsViewModel @AssistedInject constructor(
    @Assisted private val markAsRead: Boolean,
    private val newsService: NewsService,
    private val widgetStore: HomeWidgetStore,
    private val store: NewsArticleStore,
    private val log: DiagnosticLog,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(markAsRead: Boolean): NewsViewModel
    }

    private val _state = MutableStateFlow(NewsUiState())
    val state: StateFlow<NewsUiState> = _state.asStateFlow()

    init {
        if (markAsRead) {
            viewModelScope.launch {
                runSuspendCatching { widgetStore.markNewsSeen() }
                    .onFailure { log.debug(TAG, "markNewsSeen fehlgeschlagen: ${it.message}") }
            }
            viewModelScope.launch { newsService.markRead() }
        }
        viewModelScope.launch { load(pull = false) }
    }

    /** Pull-to-refresh. Ignored while a load is running. */
    fun refresh() {
        val current = _state.value
        if (current.refreshing || current.loading) return
        viewModelScope.launch { load(pull = true) }
    }

    private suspend fun load(pull: Boolean) {
        _state.update {
            if (pull) it.copy(refreshing = true) else it.copy(loading = true)
        }
        val articles = newsService.fetchArticles()
        store.update(articles)
        _state.update { it.copy(loading = false, refreshing = false, articles = articles) }
    }

    private companion object {
        const val TAG = "News"
    }
}
