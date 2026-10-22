package space.einundzwanzig.meetup.feature.community

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.community.portal.PortalCourse
import space.einundzwanzig.meetup.data.community.portal.PortalCourseEvent
import space.einundzwanzig.meetup.data.community.portal.PortalLecturer
import space.einundzwanzig.meetup.data.community.portal.PortalLink
import space.einundzwanzig.meetup.feature.community.courses.CourseDetailsAction
import space.einundzwanzig.meetup.feature.community.courses.CourseDetailsScreen
import space.einundzwanzig.meetup.feature.community.courses.CourseDetailsUiState
import space.einundzwanzig.meetup.feature.community.courses.CoursesAction
import space.einundzwanzig.meetup.feature.community.courses.CoursesScreen
import space.einundzwanzig.meetup.feature.community.courses.CoursesTab
import space.einundzwanzig.meetup.feature.community.courses.CoursesUiState
import space.einundzwanzig.meetup.feature.community.courses.DetailTestTags
import space.einundzwanzig.meetup.feature.community.courses.LecturerDetailsAction
import space.einundzwanzig.meetup.feature.community.courses.LecturerDetailsScreen
import space.einundzwanzig.meetup.feature.community.courses.LecturerDetailsUiState
import space.einundzwanzig.meetup.feature.community.courses.PortalListItem
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class CoursesScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)

    private val item = PortalListItem(1, "Bitcoin 101", "Grundlagen", "", emptyList())
    private val noId = PortalListItem(null, "Ohne Id", "Siehe https://b.example", "", listOf("https://b.example"))

    @Test
    fun `courses loading state`() {
        rule.setContent { EinundzwanzigTheme { CoursesScreen(CoursesUiState(loading = true), {}, {}) } }
        rule.onNodeWithText(s(L10nR.string.crsLoading)).assertExists()
    }

    @Test
    fun `courses empty state for both tabs`() {
        rule.setContent { EinundzwanzigTheme { CoursesScreen(CoursesUiState(loading = false, tab = CoursesTab.Lecturers), {}, {}) } }
        rule.onNodeWithText(s(L10nR.string.crsNone)).assertExists()
    }

    @Test
    fun `courses list items, tab toggle and item click`() {
        val actions = mutableListOf<CoursesAction>()
        rule.setContent {
            EinundzwanzigTheme { CoursesScreen(CoursesUiState(loading = false, courses = listOf(item, noId)), { actions += it }, {}) }
        }
        rule.onNodeWithText("Grundlagen").assertIsDisplayed()
        rule.onNodeWithText("Bitcoin 101").performClick()
        rule.onNodeWithText(s(L10nR.string.crsLecturers)).performClick()
        assertEquals(
            listOf(CoursesAction.OpenItem(CoursesTab.Courses, item), CoursesAction.SelectTab(CoursesTab.Lecturers)),
            actions,
        )
    }

    @Test
    fun `info sheet for items without id shows links`() {
        val actions = mutableListOf<CoursesAction>()
        rule.setContent {
            EinundzwanzigTheme {
                CoursesScreen(CoursesUiState(loading = false, courses = listOf(noId), sheet = CoursesTab.Courses to noId), { actions += it }, {})
            }
        }
        rule.onNodeWithTag("portalItemSheet").assertExists()
        rule.onNodeWithText("https://b.example").performClick()
        assertEquals(CoursesAction.OpenUrl("https://b.example"), actions.first())
    }

    private val course = PortalCourse(
        name = "Bitcoin 101",
        description = "Über Geld https://a.example",
        image = "",
        lecturerName = "Satoshi",
        lecturerIntro = "Pleb",
        lecturerImage = "",
        lecturerId = 7,
        lecturer = null,
        events = listOf(
            PortalCourseEvent("2026-11-02T18:00:00", "Wien", "https://e.example", "Teil 1", "", DartDateTime.tryParse("2026-11-02T18:00:00"), "02.11.2026 · 18:00"),
            PortalCourseEvent("bald", "", "", "", "", null, "bald"),
        ),
        portalLink = "https://portal.example/course/1",
        links = listOf("https://a.example"),
    )

    @Test
    fun `course detail renders sections and actions`() {
        val actions = mutableListOf<CourseDetailsAction>()
        rule.setContent { EinundzwanzigTheme { CourseDetailsScreen(CourseDetailsUiState(false, course), { actions += it }, {}) } }
        rule.onNodeWithText("Bitcoin 101").assertExists()
        rule.onNodeWithText(s(L10nR.string.crsUpcoming)).assertExists()
        rule.onNodeWithText("02.11.2026 · 18:00").assertExists()
        // Only the parsable date gets a calendar action and only http links a link action.
        rule.onAllNodesWithTag(DetailTestTags.ADD_TO_CALENDAR).assertCountEquals(1)
        rule.onAllNodesWithTag(DetailTestTags.EVENT_LINK).assertCountEquals(1)
        rule.onNodeWithTag(DetailTestTags.ADD_TO_CALENDAR).performClick()
        rule.onNodeWithText(s(L10nR.string.crsOpenPortal)).performClick()
        rule.onNodeWithTag(DetailTestTags.LIST).performScrollToNode(hasText("https://a.example"))
        rule.onNodeWithText("https://a.example").performClick()
        rule.onNodeWithTag(DetailTestTags.LIST).performScrollToNode(hasText("Pleb"))
        rule.onNodeWithTag(DetailTestTags.LECTURER_CARD).performClick()
        rule.onNodeWithTag(DetailTestTags.LIST).performScrollToNode(hasText("bald"))
        rule.onNodeWithText("bald").assertExists()
        assertEquals(
            listOf(
                CourseDetailsAction.AddToCalendar(course.events[0]),
                CourseDetailsAction.OpenUrl("https://portal.example/course/1"),
                CourseDetailsAction.OpenUrl("https://a.example"),
                CourseDetailsAction.OpenLecturer(7),
            ),
            actions,
        )
    }

    @Test
    fun `course detail loading and not found states`() {
        var state by mutableStateOf(CourseDetailsUiState(loading = true))
        rule.setContent { EinundzwanzigTheme { CourseDetailsScreen(state, {}, {}) } }
        rule.onNodeWithTag(DetailTestTags.LOADING).assertExists()
        state = CourseDetailsUiState(loading = false)
        rule.onNodeWithTag(DetailTestTags.NOT_FOUND).assertExists()
    }

    @Test
    fun `lecturer detail shows bio, courses and links`() {
        val actions = mutableListOf<LecturerDetailsAction>()
        val lecturer = PortalLecturer(
            name = "Satoshi",
            intro = "Pleb",
            bio = "Schreibt Whitepaper.",
            image = "",
            courses = listOf(portalObject("id" to 3, "name" to "Kurs drei")),
            links = listOf(PortalLink("X (Twitter)", "satoshi", "https://x.com/satoshi")),
        )
        rule.setContent { EinundzwanzigTheme { LecturerDetailsScreen(LecturerDetailsUiState(false, lecturer), { actions += it }, {}) } }
        rule.onNodeWithText(s(L10nR.string.lecAbout)).assertExists()
        rule.onNodeWithText("Kurs drei").performClick()
        rule.onNodeWithTag(DetailTestTags.LIST).performScrollToNode(hasText("X (Twitter): satoshi"))
        rule.onNodeWithText("X (Twitter): satoshi").performClick()
        assertEquals(
            listOf(LecturerDetailsAction.OpenCourse(3), LecturerDetailsAction.OpenUrl("https://x.com/satoshi")),
            actions,
        )
    }
}
