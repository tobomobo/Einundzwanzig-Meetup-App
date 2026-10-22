package space.einundzwanzig.meetup.feature.community.courses

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
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.data.community.portal.PortalCourse
import space.einundzwanzig.meetup.data.community.portal.PortalCourseEvent
import space.einundzwanzig.meetup.data.community.portal.PortalEntityCache
import space.einundzwanzig.meetup.data.community.portal.PortalFields
import space.einundzwanzig.meetup.data.community.portal.PortalLecturer
import space.einundzwanzig.meetup.feature.community.di.PortalCoursesSource
import java.time.ZoneId

/** Course detail state: [course] is `detail ?: cached` (spec 11 §2.8); null + !loading = not found. */
data class CourseDetailsUiState(val loading: Boolean = true, val course: PortalCourse? = null)

sealed interface CourseDetailsAction {
    data class OpenUrl(val url: String) : CourseDetailsAction
    data class AddToCalendar(val event: PortalCourseEvent) : CourseDetailsAction
    data class OpenLecturer(val lecturerId: Int) : CourseDetailsAction
}

@HiltViewModel(assistedFactory = CourseDetailsViewModel.Factory::class)
class CourseDetailsViewModel @AssistedInject constructor(
    @Assisted private val courseId: Int,
    private val source: PortalCoursesSource,
    private val cache: PortalEntityCache,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(courseId: Int): CourseDetailsViewModel
    }

    private val zone: ZoneId = ZoneId.systemDefault()
    private val _state = MutableStateFlow(
        CourseDetailsUiState(loading = true, course = cache.course(courseId)?.let { PortalFields.course(it, zone) }),
    )
    val state: StateFlow<CourseDetailsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val detail = runSuspendCatching { source.getCourse(courseId) }
                .onFailure { AppLogger.warn(TAG, "Kurs $courseId nicht ladbar", it) }
                .getOrNull()
            if (detail != null) cache.rememberCourse(detail)
            val shown = detail ?: cache.course(courseId)
            _state.update { CourseDetailsUiState(loading = false, course = shown?.let { PortalFields.course(it, zone) } ?: it.course) }
        }
    }

    private companion object {
        const val TAG = "CourseDetail"
    }
}

/** Lecturer detail state: [lecturer] is `detail ?: cached`; null + !loading = not found. */
data class LecturerDetailsUiState(val loading: Boolean = true, val lecturer: PortalLecturer? = null)

sealed interface LecturerDetailsAction {
    data class OpenUrl(val url: String) : LecturerDetailsAction
    data class OpenCourse(val courseId: Int) : LecturerDetailsAction
}

@HiltViewModel(assistedFactory = LecturerDetailsViewModel.Factory::class)
class LecturerDetailsViewModel @AssistedInject constructor(
    @Assisted private val lecturerId: Int,
    private val source: PortalCoursesSource,
    private val cache: PortalEntityCache,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(lecturerId: Int): LecturerDetailsViewModel
    }

    private val _state = MutableStateFlow(
        LecturerDetailsUiState(loading = true, lecturer = cache.lecturer(lecturerId)?.let(PortalFields::lecturer)),
    )
    val state: StateFlow<LecturerDetailsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val detail = runSuspendCatching { source.getLecturer(lecturerId) }
                .onFailure { AppLogger.warn(TAG, "Dozent $lecturerId nicht ladbar", it) }
                .getOrNull()
            if (detail != null) cache.rememberLecturer(detail)
            val shown = detail ?: cache.lecturer(lecturerId)
            _state.update { LecturerDetailsUiState(loading = false, lecturer = shown?.let(PortalFields::lecturer) ?: it.lecturer) }
        }
    }

    private companion object {
        const val TAG = "LecturerDetail"
    }
}
