package space.einundzwanzig.meetup.feature.community

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.data.community.portal.PortalEntityCache
import space.einundzwanzig.meetup.feature.community.courses.CourseDetailsViewModel
import space.einundzwanzig.meetup.feature.community.courses.CoursesAction
import space.einundzwanzig.meetup.feature.community.courses.CoursesTab
import space.einundzwanzig.meetup.feature.community.courses.CoursesViewModel
import space.einundzwanzig.meetup.feature.community.courses.LecturerDetailsViewModel

class CoursesViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val course1 = portalObject("id" to 1, "name" to "Bitcoin 101", "description" to "Grundlagen https://a.example")
    private val courseNoId = portalObject("id" to "x", "title" to "Ohne Id", "intro" to "Siehe https://b.example, https://b.example")
    private val lecturer7 = portalObject("id" to 7, "name" to "Satoshi", "bio" to "Pleb")

    @Test
    fun `loads courses then lecturers sequentially`() {
        val src = FakePortalCourses(courses = listOf(course1, courseNoId), lecturers = listOf(lecturer7))
        val vm = CoursesViewModel(src, PortalEntityCache())
        assertEquals(listOf("courses", "lecturers"), src.log)
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf("Bitcoin 101", "Ohne Id"), s.courses.map { it.name })
        assertEquals(listOf(1, null), s.courses.map { it.id })
        assertEquals("Pleb", s.lecturers.single().description)
        assertEquals(listOf("https://b.example"), s.courses[1].links)
    }

    @Test
    fun `starts loading until the portal answers`() {
        val gate = CompletableDeferred<Unit>()
        val src = object : space.einundzwanzig.meetup.feature.community.di.PortalCoursesSource by FakePortalCourses() {
            override suspend fun getCourses() = gate.await().let { listOf(course1) }
        }
        val vm = CoursesViewModel(src, PortalEntityCache())
        assertTrue(vm.state.value.loading)
        gate.complete(Unit)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `failure gives empty lists`() {
        val vm = CoursesViewModel(FakePortalCourses(failLists = true), PortalEntityCache())
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.courses.isEmpty())
        assertTrue(vm.state.value.lecturers.isEmpty())
    }

    @Test
    fun `pull to refresh reloads both lists`() {
        val src = FakePortalCourses(courses = listOf(course1))
        val vm = CoursesViewModel(src, PortalEntityCache())
        src.courses = listOf(course1, courseNoId)
        vm.onAction(CoursesAction.Refresh)
        assertEquals(listOf("courses", "lecturers", "courses", "lecturers"), src.log)
        assertEquals(2, vm.state.value.courses.size)
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun `tab switch and sheet for items without int id`() {
        val vm = CoursesViewModel(FakePortalCourses(courses = listOf(course1, courseNoId)), PortalEntityCache())
        vm.onAction(CoursesAction.SelectTab(CoursesTab.Lecturers))
        assertEquals(CoursesTab.Lecturers, vm.state.value.tab)
        val withId = vm.state.value.courses[0]
        vm.onAction(CoursesAction.OpenItem(CoursesTab.Courses, withId))
        assertNull(vm.state.value.sheet)
        val noId = vm.state.value.courses[1]
        vm.onAction(CoursesAction.OpenItem(CoursesTab.Courses, noId))
        assertEquals(CoursesTab.Courses to noId, vm.state.value.sheet)
        vm.onAction(CoursesAction.DismissSheet)
        assertNull(vm.state.value.sheet)
    }

    @Test
    fun `list load fills the cache used as detail fallback`() {
        val cache = PortalEntityCache()
        CoursesViewModel(FakePortalCourses(courses = listOf(course1), lecturers = listOf(lecturer7)), cache)
        assertNotNull(cache.course(1))
        assertNotNull(cache.lecturer(7))
    }

    @Test
    fun `course detail shows the cached fallback, then the detail`() {
        val cache = PortalEntityCache().apply { rememberCourses(listOf(course1)) }
        val gate = CompletableDeferred<Unit>()
        val detail = portalObject(
            "id" to 1,
            "name" to "Bitcoin 101 (Detail)",
            "lecturer" to portalObject("id" to 7, "name" to "Satoshi"),
            "events" to JsonArray(listOf(portalObject("from" to "2026-11-02T18:00:00", "location" to "Wien"))),
        )
        val src = object : space.einundzwanzig.meetup.feature.community.di.PortalCoursesSource by FakePortalCourses() {
            override suspend fun getCourse(id: Int) = gate.await().let { detail }
        }
        val vm = CourseDetailsViewModel(1, src, cache)
        assertTrue(vm.state.value.loading)
        assertEquals("Bitcoin 101", vm.state.value.course?.name)
        gate.complete(Unit)
        val c = vm.state.value.course!!
        assertFalse(vm.state.value.loading)
        assertEquals("Bitcoin 101 (Detail)", c.name)
        assertEquals("Satoshi", c.lecturerName)
        assertEquals(7, c.lecturerId)
        assertEquals("02.11.2026 · 18:00", c.events.single().whenLabel)
        // Nested lecturer object fills the lecturer cache.
        assertNotNull(cache.lecturer(7))
    }

    @Test
    fun `course detail failure keeps the fallback, unknown id is not found`() {
        val cache = PortalEntityCache().apply { rememberCourses(listOf(course1)) }
        val src = FakePortalCourses(failDetails = true)
        assertEquals("Bitcoin 101", CourseDetailsViewModel(1, src, cache).state.value.course?.name)
        val missing = CourseDetailsViewModel(99, src, cache).state.value
        assertFalse(missing.loading)
        assertNull(missing.course)
    }

    @Test
    fun `lecturer detail resolves links and courses`() {
        val detail = portalObject(
            "id" to 7,
            "name" to "Satoshi",
            "intro" to "Pleb",
            "description" to "Bio",
            "twitter" to "satoshi",
            "nostr" to "npub1xyz",
            "courses" to JsonArray(listOf(course1)),
        )
        val vm = LecturerDetailsViewModel(7, FakePortalCourses(lecturerDetails = mapOf(7 to detail)), PortalEntityCache())
        val l = vm.state.value.lecturer!!
        assertEquals("Bio", l.bio)
        assertEquals(listOf("X (Twitter): satoshi", "Nostr: npub1xyz"), l.links.map { it.text })
        assertEquals("https://x.com/satoshi", l.links[0].url)
        assertEquals(1, l.courses.size)
    }

    @Test
    fun `lecturer detail without data is not found`() {
        val s = LecturerDetailsViewModel(3, FakePortalCourses(), PortalEntityCache()).state.value
        assertFalse(s.loading)
        assertNull(s.lecturer)
    }
}
