package space.einundzwanzig.meetup.feature.community.courses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.data.community.portal.PortalEntityCache
import space.einundzwanzig.meetup.data.community.portal.PortalFields
import space.einundzwanzig.meetup.feature.community.di.PortalCoursesSource
import javax.inject.Inject

/** Tabs of the courses screen. */
enum class CoursesTab { Courses, Lecturers }

/**
 * One list row (spec 11 §2.7): `name ?? title`, `description ?? intro ?? bio`, the int id (null →
 * bottom sheet instead of a detail screen) and the URLs extracted from the description (sheet).
 */
data class PortalListItem(
    val id: Int?,
    val name: String,
    val description: String,
    val links: List<String>,
) {
    companion object {
        fun from(e: JsonObject): PortalListItem {
            val desc = PortalFields.listDescription(e)
            return PortalListItem(
                id = PortalFields.intId(e),
                name = PortalFields.listName(e),
                description = desc,
                links = PortalFields.extractLinks(desc),
            )
        }
    }
}

data class CoursesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val tab: CoursesTab = CoursesTab.Courses,
    val courses: List<PortalListItem> = emptyList(),
    val lecturers: List<PortalListItem> = emptyList(),
    /** Item without an int id shown in the info sheet. */
    val sheet: Pair<CoursesTab, PortalListItem>? = null,
)

sealed interface CoursesAction {
    data class SelectTab(val tab: CoursesTab) : CoursesAction
    data object Refresh : CoursesAction
    data class OpenItem(val tab: CoursesTab, val item: PortalListItem) : CoursesAction
    data object DismissSheet : CoursesAction
    data class OpenUrl(val url: String) : CoursesAction
}

@HiltViewModel
class CoursesViewModel @Inject constructor(
    private val source: PortalCoursesSource,
    private val cache: PortalEntityCache,
) : ViewModel() {

    private val _state = MutableStateFlow(CoursesUiState())
    val state: StateFlow<CoursesUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(pullToRefresh = false)
    }

    fun onAction(action: CoursesAction) {
        when (action) {
            is CoursesAction.SelectTab -> _state.update { it.copy(tab = action.tab) }
            CoursesAction.Refresh -> load(pullToRefresh = true)
            is CoursesAction.OpenItem -> if (action.item.id == null) _state.update { it.copy(sheet = action.tab to action.item) }
            CoursesAction.DismissSheet -> _state.update { it.copy(sheet = null) }
            is CoursesAction.OpenUrl -> Unit // handled by the route
        }
    }

    /** `_load()`: courses THEN lecturers (sequential, as in Flutter); pull-to-refresh reloads both. */
    private fun load(pullToRefresh: Boolean) {
        if (loadJob?.isActive == true) return
        _state.update { if (pullToRefresh) it.copy(refreshing = true) else it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            val courses = runSuspendCatching { source.getCourses() }
                .onFailure { AppLogger.warn(TAG, "Kurse nicht ladbar", it) }
                .getOrDefault(emptyList())
            cache.rememberCourses(courses)
            val lecturers = runSuspendCatching { source.getLecturers() }
                .onFailure { AppLogger.warn(TAG, "Dozenten nicht ladbar", it) }
                .getOrDefault(emptyList())
            cache.rememberLecturers(lecturers)
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    courses = courses.map(PortalListItem::from),
                    lecturers = lecturers.map(PortalListItem::from),
                )
            }
        }
    }

    private companion object {
        const val TAG = "Courses"
    }
}
