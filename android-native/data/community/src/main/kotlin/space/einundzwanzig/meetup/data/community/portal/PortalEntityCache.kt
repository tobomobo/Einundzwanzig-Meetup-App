package space.einundzwanzig.meetup.data.community.portal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory replacement of Flutter's `fallback` constructor argument of `CourseDetailScreen` /
 * `LecturerDetailScreen` (spec 11 §2.8, ROUTES.md "Arguments instead of models"): the routes only
 * carry an id, so this keeps the last seen JSON object per course / lecturer id. It is filled from
 * the `getCourses` / `getLecturers` lists and from the nested `lecturer` object and `courses`
 * array of detail responses. The detail screens render `detail ?: cached[id]`.
 *
 * Process-lifetime only (nothing is persisted), thread-safe.
 */
@Singleton
class PortalEntityCache @Inject constructor() {

    private val courses = ConcurrentHashMap<Int, JsonObject>()
    private val lecturers = ConcurrentHashMap<Int, JsonObject>()

    /** Last seen course JSON for [id], or `null`. */
    fun course(id: Int): JsonObject? = courses[id]

    /** Last seen lecturer JSON for [id], or `null`. */
    fun lecturer(id: Int): JsonObject? = lecturers[id]

    /** Remembers every course of a `getCourses` list (entries without an int id are skipped). */
    fun rememberCourses(list: List<JsonObject>) {
        list.forEach { rememberCourse(it) }
    }

    /** Remembers every lecturer of a `getLecturers` list. */
    fun rememberLecturers(list: List<JsonObject>) {
        list.forEach { rememberLecturer(it) }
    }

    /** Remembers a course and its nested `lecturer` object. */
    fun rememberCourse(course: JsonObject) {
        PortalFields.intId(course)?.let { courses[it] = course }
        (course["lecturer"] as? JsonObject)?.let { lec ->
            PortalFields.intId(lec)?.let { id -> lecturers.putIfAbsent(id, lec) }
        }
    }

    /** Remembers a lecturer and the objects of its nested `courses` array. */
    fun rememberLecturer(lecturer: JsonObject) {
        PortalFields.intId(lecturer)?.let { lecturers[it] = lecturer }
        (lecturer["courses"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { c ->
            PortalFields.intId(c)?.let { id -> courses.putIfAbsent(id, c) }
        }
    }
}
