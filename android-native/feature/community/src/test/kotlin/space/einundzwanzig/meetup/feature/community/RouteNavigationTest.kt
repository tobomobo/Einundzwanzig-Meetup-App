package space.einundzwanzig.meetup.feature.community

import android.content.Context
import android.content.ClipboardManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import space.einundzwanzig.meetup.core.navigation.News
import space.einundzwanzig.meetup.core.navigation.PlebrapLibrary
import space.einundzwanzig.meetup.core.navigation.PortalArea
import space.einundzwanzig.meetup.core.navigation.PortalMeetups
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideHost
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.InMemoryTourProgressStore
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.community.CommunityLinks
import space.einundzwanzig.meetup.data.community.portal.PortalEntityCache
import space.einundzwanzig.meetup.feature.community.courses.CourseDetailsRoute
import space.einundzwanzig.meetup.feature.community.courses.CourseDetailsViewModel
import space.einundzwanzig.meetup.feature.community.courses.CoursesRoute
import space.einundzwanzig.meetup.feature.community.courses.CoursesViewModel
import space.einundzwanzig.meetup.feature.community.courses.DetailTestTags
import space.einundzwanzig.meetup.feature.community.courses.LecturerDetailsRoute
import space.einundzwanzig.meetup.feature.community.courses.LecturerDetailsViewModel
import space.einundzwanzig.meetup.feature.community.di.NpubSource
import space.einundzwanzig.meetup.feature.community.hub.CommunityHubRoute
import space.einundzwanzig.meetup.feature.community.hub.CommunityHubViewModel
import space.einundzwanzig.meetup.feature.community.plebrap.PlebrapRoute
import space.einundzwanzig.meetup.feature.community.plebrap.PlebrapViewModel
import space.einundzwanzig.meetup.feature.community.portal.PortalAreaRoute
import space.einundzwanzig.meetup.feature.community.v4v.V4vTestTags
import space.einundzwanzig.meetup.feature.community.v4v.ValueForValueRoute
import space.einundzwanzig.meetup.feature.community.v4v.ValueForValueViewModel
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Route-level wiring (NavKeys, URLs, snackbars, clipboard, tours) with a recording [Navigator]. */
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

    private fun controller(store: InMemoryTourProgressStore) =
        GuideController(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    private fun completedStore(vararg tours: GuideTour) = InMemoryTourProgressStore().also { store ->
        runBlocking { tours.forEach { store.setCompleted(it, true) } }
    }

    @Test
    fun `community hub cards navigate to keys and open urls`() {
        val vm = CommunityHubViewModel(FakeDuellClient(), NpubSource { "npub1abc" })
        val guide = controller(completedStore(GuideTour.Portal))
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    GuideHost(guide) { CommunityHubRoute(vm) }
                }
            }
        }
        listOf(
            s(L10nR.string.chPortal), s(L10nR.string.chNews), s(L10nR.string.chNostr), s(L10nR.string.chShoutout),
            s(L10nR.string.chPodcast), "SatoshiDuell", "PlebRap",
        ).forEach { rule.onNodeWithText(it).performScrollTo().performClick() }
        rule.waitForIdle()
        assertEquals(listOf(PortalArea, News(), PlebrapLibrary), navigator.keys)
        assertEquals(
            listOf(
                CommunityLinks.NOSTR_PROFILE,
                CommunityLinks.SHOUTOUT,
                CommunityLinks.PODCAST,
                "https://satoshiduell.de/?npub=npub1abc",
            ),
            navigator.urls,
        )
    }

    @Test
    fun `community tour starts after 500 ms and is finished on dispose`() {
        val store = InMemoryTourProgressStore()
        val guide = controller(store)
        val vm = CommunityHubViewModel(FakeDuellClient(), NpubSource { null })
        var show by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    GuideHost(guide) { if (show) CommunityHubRoute(vm) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(300)
        assertNull(guide.activeTour)
        rule.mainClock.advanceTimeBy(400)
        assertEquals(GuideTour.Portal, guide.activeTour)
        assertFalse(runBlocking { store.isCompleted(GuideTour.Portal) })

        show = false
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        assertNull(guide.activeTour)
        assertTrue(runBlocking { store.isCompleted(GuideTour.Portal) })
    }

    @Test
    fun `completed community tour does not start again`() {
        val guide = controller(completedStore(GuideTour.Portal))
        val vm = CommunityHubViewModel(FakeDuellClient(), NpubSource { null })
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    GuideHost(guide) { CommunityHubRoute(vm) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        assertNull(guide.activeTour)
    }

    @Test
    fun `course details lecturer card opens lecturer details`() {
        val src = FakePortalCourses(
            courseDetails = mapOf(
                1 to portalObject(
                    "id" to 1,
                    "name" to "Bitcoin 101",
                    "lecturer" to portalObject("id" to 7, "name" to "Satoshi", "intro" to "Pleb"),
                ),
            ),
        )
        val vm = CourseDetailsViewModel(1, src, PortalEntityCache())
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) { CourseDetailsRoute(1, vm) }
            }
        }
        rule.onNodeWithTag(DetailTestTags.LIST).performScrollToNode(hasTestTag(DetailTestTags.LECTURER_CARD))
        rule.onNodeWithTag(DetailTestTags.LECTURER_CARD).performClick()
        assertEquals(listOf<NavKey>(LecturerDetails(7)), navigator.keys)
    }

    @Test
    fun `lecturer details course row opens course details`() {
        val src = FakePortalCourses(
            lecturerDetails = mapOf(
                7 to portalObject(
                    "id" to 7,
                    "name" to "Satoshi",
                    "courses" to kotlinx.serialization.json.JsonArray(listOf<JsonObject>(portalObject("id" to 3, "name" to "Kurs drei"))),
                ),
            ),
        )
        val vm = LecturerDetailsViewModel(7, src, PortalEntityCache())
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) { LecturerDetailsRoute(7, vm) }
            }
        }
        rule.onNodeWithText("Kurs drei").performClick()
        assertEquals(listOf<NavKey>(CourseDetails(3)), navigator.keys)
    }

    @Test
    fun `v4v without wallet shows the sheet and copies the invoice`() {
        val vm = ValueForValueViewModel(FakeV4vService())
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) { ValueForValueRoute(vm) }
            }
        }
        rule.onNodeWithTag(V4vTestTags.AMOUNT).performTextInput("2100")
        rule.onNodeWithTag(V4vTestTags.DONATE).performScrollTo().performClick()
        // No lightning: handler in Robolectric -> copy sheet.
        rule.onNodeWithTag(V4vTestTags.NO_WALLET_SHEET).assertExists()
        rule.onNodeWithText(s(L10nR.string.v4vCopyInvoice)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(V4vTestTags.NO_WALLET_SHEET).assertDoesNotExist()
        rule.onNodeWithText(s(L10nR.string.v4vCopied)).assertExists()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals("lnbc1", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `plebrap load error shows the error snackbar`() {
        val player = FakePlebrapPlayer()
        val vm = PlebrapViewModel(player)
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) { PlebrapRoute(vm) }
            }
        }
        rule.onNodeWithText(s(L10nR.string.prLoadError)).assertDoesNotExist()
        player.loadErrors.value = 1
        rule.waitForIdle()
        rule.onNodeWithText(s(L10nR.string.prLoadError)).assertExists()
    }
}

private class RecordingNavigator : Navigator {
    val keys = mutableListOf<NavKey>()
    val urls = mutableListOf<String>()
    override fun navigate(key: NavKey) {
        keys += key
    }
    override fun replaceTop(key: NavKey) = Unit
    override fun back() = Unit
    override fun backTo(key: NavKey, inclusive: Boolean) = Unit
    override fun resetTo(key: NavKey) = Unit
    override fun navigateFromRoot(key: NavKey) = Unit
    override fun openUrl(url: String) {
        urls += url
    }
}
