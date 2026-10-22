package space.einundzwanzig.meetup.feature.community

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.navigation.CommunityPortal
import space.einundzwanzig.meetup.core.navigation.CourseDetails
import space.einundzwanzig.meetup.core.navigation.Courses
import space.einundzwanzig.meetup.core.navigation.LecturerDetails
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.MeetupCalendar
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.navigation.NearbyMeetups
import space.einundzwanzig.meetup.core.navigation.PortalMeetups
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideHost
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.InMemoryTourProgressStore
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.community.portal.PortalEntityCache
import space.einundzwanzig.meetup.feature.community.courses.CoursesRoute
import space.einundzwanzig.meetup.feature.community.courses.CoursesViewModel
import space.einundzwanzig.meetup.feature.community.portal.PortalAreaRoute
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Route-level NavKey mapping of `PortalAreaRoute` and `CoursesRoute` with a recording [Navigator]. */
@RunWith(AndroidJUnit4::class)
class RouteNavigationTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)

    private val navigator = RecordingNavigator()

    @Test
    fun `portal area rows navigate to their keys`() {
        val store = InMemoryTourProgressStore()
        runBlocking { store.setCompleted(GuideTour.PortalArea, true) }
        val controller = GuideController(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    GuideHost(controller) { PortalAreaRoute() }
                }
            }
        }
        listOf(
            L10nR.string.paMeetups, L10nR.string.paEvents, L10nR.string.paCourses,
            L10nR.string.paMap, L10nR.string.paMine, L10nR.string.paWeb,
        ).forEach { rule.onNodeWithText(s(it)).performScrollTo().performClick() }
        assertEquals(
            listOf(MeetupCalendar(), MeetupCalendar(), Courses, NearbyMeetups, PortalMeetups, CommunityPortal),
            navigator.keys,
        )
    }

    @Test
    fun `courses route opens course or lecturer details by tab`() {
        val src = FakePortalCourses(
            courses = listOf(portalObject("id" to 1, "name" to "Bitcoin 101")),
            lecturers = listOf(portalObject("id" to 7, "name" to "Satoshi")),
        )
        val vm = CoursesViewModel(src, PortalEntityCache())
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) { CoursesRoute(vm) }
            }
        }
        rule.onNodeWithText("Bitcoin 101").performClick()
        rule.onNodeWithText(s(L10nR.string.crsLecturers)).performClick()
        rule.onNodeWithText("Satoshi").performClick()
        assertEquals(listOf(CourseDetails(1), LecturerDetails(7)), navigator.keys)
    }
}

private class RecordingNavigator : Navigator {
    val keys = mutableListOf<NavKey>()
    override fun navigate(key: NavKey) {
        keys += key
    }
    override fun replaceTop(key: NavKey) = Unit
    override fun back() = Unit
    override fun backTo(key: NavKey, inclusive: Boolean) = Unit
    override fun resetTo(key: NavKey) = Unit
    override fun navigateFromRoot(key: NavKey) = Unit
    override fun openUrl(url: String) = Unit
}
