package space.einundzwanzig.meetup.data.community.portal

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson

class PortalEntityCacheTest {

    private fun obj(json: String) = DartJson.decode(json) as JsonObject

    @Test
    fun listsAreRememberedByIntId() {
        val cache = PortalEntityCache()
        cache.rememberCourses(listOf(obj("""{"id":1,"name":"A"}"""), obj("""{"id":"2","name":"B"}""")))
        cache.rememberLecturers(listOf(obj("""{"id":7,"name":"L"}""")))
        assertEquals("A", PortalFields.pv(cache.course(1)!!, "name"))
        assertNull(cache.course(2))
        assertEquals("L", PortalFields.pv(cache.lecturer(7)!!, "name"))
    }

    @Test
    fun nestedObjectsFillTheOtherMapWithoutOverwritingListEntries() {
        val cache = PortalEntityCache()
        cache.rememberLecturers(listOf(obj("""{"id":7,"name":"Full"}""")))
        cache.rememberCourse(obj("""{"id":1,"lecturer":{"id":7,"name":"Nested"},"name":"C"}"""))
        assertEquals("Full", PortalFields.pv(cache.lecturer(7)!!, "name"))

        cache.rememberLecturer(obj("""{"id":8,"courses":[{"id":5,"name":"Nested course"},3]}"""))
        assertEquals("Nested course", PortalFields.pv(cache.course(5)!!, "name"))
        assertEquals("C", PortalFields.pv(cache.course(1)!!, "name"))
        assertEquals(8, PortalFields.intId(cache.lecturer(8)!!))
    }

    @Test
    fun detailOverwritesEarlierEntry() {
        val cache = PortalEntityCache()
        cache.rememberCourse(obj("""{"id":1,"name":"Old"}"""))
        cache.rememberCourse(obj("""{"id":1,"name":"New"}"""))
        assertEquals("New", PortalFields.pv(cache.course(1)!!, "name"))
    }
}
