package space.einundzwanzig.meetup.feature.community.glossary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import space.einundzwanzig.meetup.data.community.glossary.Glossary
import space.einundzwanzig.meetup.data.community.glossary.GlossaryCategory
import space.einundzwanzig.meetup.data.community.glossary.ResolvedGlossaryEntry
import javax.inject.Inject

/**
 * Glossary state (spec 11 §2.12). [toggled] holds the expansion state the user set per entry title
 * (Flutter `PageStorageKey(title)`); untouched entries are expanded iff the query is non-blank.
 */
data class GlossaryUiState(
    val query: String = "",
    val toggled: Map<String, Boolean> = emptyMap(),
) {
    /** All entries auto-expand while searching. */
    val searching: Boolean get() = Glossary.normalizeQuery(query).isNotEmpty()

    fun isExpanded(title: String): Boolean = toggled[title] ?: searching

    /** Hits grouped by category in enum order (empty categories left out). */
    fun sections(entries: List<ResolvedGlossaryEntry>): Map<GlossaryCategory, List<ResolvedGlossaryEntry>> =
        Glossary.groupByCategory(Glossary.search(entries, query))
}

sealed interface GlossaryAction {
    data class QueryChanged(val query: String) : GlossaryAction
    data object ClearQuery : GlossaryAction
    data class Toggle(val title: String, val expanded: Boolean) : GlossaryAction
}

@HiltViewModel
class GlossaryViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(GlossaryUiState(query = savedState[KEY_QUERY] ?: ""))
    val state: StateFlow<GlossaryUiState> = _state.asStateFlow()

    fun onAction(action: GlossaryAction) {
        when (action) {
            is GlossaryAction.QueryChanged -> setQuery(action.query)
            GlossaryAction.ClearQuery -> setQuery("")
            is GlossaryAction.Toggle -> _state.update { it.copy(toggled = it.toggled + (action.title to action.expanded)) }
        }
    }

    private fun setQuery(q: String) {
        savedState[KEY_QUERY] = q
        _state.update { it.copy(query = q) }
    }

    private companion object {
        const val KEY_QUERY = "query"
    }
}
