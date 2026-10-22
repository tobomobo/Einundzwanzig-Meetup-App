package space.einundzwanzig.meetup.feature.network

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation3.runtime.NavKey
import org.robolectric.Shadows.shadowOf
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.navigation.VerifyPerson
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkBuildEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class MyNetworkScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val actions = mutableListOf<MyNetworkAction>()

    private fun show(state: MyNetworkUiState) {
        rule.setContent {
            EinundzwanzigTheme {
                MyNetworkScreen(state = state, onAction = { actions += it }, onBack = {})
            }
        }
    }

    private fun content() = MyNetworkUiState(
        loading = false,
        network = sampleNetwork(),
        standTime = "08:15",
        names = mapOf(np(1) to "Anna"),
    )

    @Test
    fun `first load shows building text, then the stage being updated`() {
        show(MyNetworkUiState(loading = true, updating = true))
        rule.onNodeWithTag("firstLoad").assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.mnLoading)).assertIsDisplayed()
    }

    @Test
    fun `first load during stage shows the next degree`() {
        show(MyNetworkUiState(loading = false, updating = true, doneDepth = 1))
        rule.onNodeWithText(str(L10nR.string.mnUpdating, 2)).assertIsDisplayed()
    }

    @Test
    fun `empty state shows texts, saved hint and failed banner with retry`() {
        show(MyNetworkUiState(loading = false, updateFailed = true, failedCount = 2))
        rule.onNodeWithTag("empty").assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.mnUpdateFailed)).assertIsDisplayed()
        rule.onNodeWithText(context.resources.getQuantityString(L10nR.plurals.mnFailedBanner, 2, 2)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.mnEmpty)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.mnRetry)).performClick()
        assertEquals(listOf<MyNetworkAction>(MyNetworkAction.Retry), actions)
    }

    @Test
    fun `retrying hides the retry button`() {
        show(MyNetworkUiState(loading = false, failedCount = 1, retrying = true))
        rule.onNodeWithText(str(L10nR.string.mnRetry)).assertDoesNotExist()
    }

    @Test
    fun `app bar actions open info and check person`() {
        show(MyNetworkUiState(loading = false))
        rule.onNodeWithText(str(L10nR.string.mnTitle)).assertIsDisplayed()
        rule.onNodeWithContentDescription(str(L10nR.string.mnHowItWorks)).performClick()
        rule.onNodeWithContentDescription(str(L10nR.string.mnCheckPerson)).performClick()
        assertEquals(listOf(MyNetworkAction.OpenInfo, MyNetworkAction.CheckPerson), actions)
    }

    @Test
    fun `content shows reach, stand time, split and graph`() {
        show(content())
        rule.onNodeWithTag("content").assertIsDisplayed()
        rule.onNodeWithTag("totalReach").assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.mnReachSub, 2)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.mnReachSplit, 2, 2, 1)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.mnStandAt, "08:15")).assertIsDisplayed()
        rule.onNodeWithTag("graph").assertExists()
    }

    @Test
    fun `updating shows the stage chip instead of the stand time`() {
        show(content().copy(updating = true, doneDepth = 2))
        rule.onNodeWithText(str(L10nR.string.mnUpdating, 3)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.mnStandAt, "08:15")).assertDoesNotExist()
    }

    @Test
    fun `update failed shows the saved state chip`() {
        show(content().copy(updateFailed = true))
        rule.onNodeWithText(str(L10nR.string.mnSavedState)).assertIsDisplayed()
    }

    @Test
    fun `filter chip and search send actions`() {
        show(content())
        rule.onNodeWithTag("content").performScrollToNode(hasTestTag("filter2"))
        rule.onNodeWithTag("filter2").performClick()
        rule.onNodeWithTag("content").performScrollToNode(hasTestTag("search"))
        rule.onNodeWithTag("search").performTextInput("ann")
        assertEquals(listOf(MyNetworkAction.SetFilter(2), MyNetworkAction.SetQuery("ann")), actions)
    }

    @Test
    fun `contact rows show name, detail line and open the detail`() {
        show(content())
        rule.onNodeWithTag("content").performScrollToNode(hasText("Anna"))
        rule.onNodeWithText("2 gemeinsame Meetups · zuletzt Wien, 12.09.").assertIsDisplayed()
        rule.onNodeWithText("Anna").performClick()
        assertEquals(listOf<MyNetworkAction>(MyNetworkAction.OpenDetail(np(1))), actions)
    }

    @Test
    fun `long sections offer show all`() {
        show(MyNetworkUiState(loading = false, network = bigNetwork(25)))
        val showAll = str(L10nR.string.mnShowAll, 25)
        rule.onNodeWithTag("content").performScrollToNode(hasText(showAll))
        rule.onNodeWithText(showAll).performClick()
        assertEquals(listOf<MyNetworkAction>(MyNetworkAction.Expand(1)), actions)
    }

    @Test
    fun `search without match shows no results`() {
        show(content().copy(query = "zzz"))
        rule.onNodeWithTag("content").performScrollToNode(hasText(str(L10nR.string.mnNoResults)))
        rule.onNodeWithText(str(L10nR.string.mnNoResults)).assertIsDisplayed()
    }

    @Test
    fun `how it works row opens the info sheet`() {
        show(content())
        rule.onNodeWithTag("content").performScrollToNode(hasText(str(L10nR.string.mnHowItWorksSub)))
        rule.onNodeWithText(str(L10nR.string.mnHowItWorksSub)).performClick()
        assertEquals(listOf<MyNetworkAction>(MyNetworkAction.OpenInfo), actions)
    }

    @Test
    fun `info sheet shows intro and notes`() {
        show(content().copy(infoSheetOpen = true))
        rule.onNodeWithTag("infoSheet").assertExists()
        rule.onNodeWithText(str(L10nR.string.mnIntro)).assertExists()
        rule.onNodeWithText(str(L10nR.string.mnPrivacyNote)).assertExists()
    }

    @Test
    fun `detail sheet shows path, shared meetups and opens nostr`() {
        show(content().copy(highlight = np(1)))
        rule.onNodeWithTag("detailSheet").assertExists()
        rule.onNodeWithText(str(L10nR.string.mnPathTo, "Anna").uppercase()).assertExists()
        rule.onNodeWithText(str(L10nR.string.mnSharedMeetupsList).uppercase()).assertExists()
        rule.onNodeWithText("Wien, 12.09.2026").assertExists()
        rule.onNodeWithText(str(L10nR.string.mnDegreeDirect)).assertExists()
        rule.onNodeWithTag("copyNpub").assertExists()
        rule.onNodeWithText(str(L10nR.string.mnOpenInNostr)).performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertTrue(actions.toString(), actions.contains(MyNetworkAction.OpenInNostr(np(1))))
        assertTrue(actions.contains(MyNetworkAction.CloseDetail))
    }

    @Test
    fun `detail sheet for third degree shows also via bridges`() {
        val names = mapOf(np(1) to "Anna", np(2) to "Ben", np(3) to "Carla")
        show(content().copy(highlight = np(3), names = names))
        rule.onNodeWithText(str(L10nR.string.mnAlsoVia).uppercase()).assertExists()
        rule.onNodeWithText("Ben").assertExists()
    }

    @Test
    fun `pull to refresh sends refresh`() {
        show(content())
        rule.onNodeWithTag("content").performTouchInput {
            swipeDown(startY = top + 10f, endY = bottom, durationMillis = 400)
        }
        rule.waitForIdle()
        assertTrue(actions.toString(), actions.contains(MyNetworkAction.Refresh))
    }

    @Test
    fun `tapping a graph node opens its detail`() {
        show(content())
        rule.onNodeWithTag("content").performScrollToNode(hasTestTag("graph"))
        val graph = rule.onNodeWithTag("graph")
        val size = graph.fetchSemanticsNode().size
        val density = rule.density.density
        val net = sampleNetwork()
        val node = NetworkPresentation.layoutTree(net, mapOf(np(1) to "Anna"), size.width.toFloat(), size.height.toFloat(), density)
            .first { it.degree == 1 }
        graph.performTouchInput { click(Offset(node.x, node.y)) }
        assertEquals(listOf<MyNetworkAction>(MyNetworkAction.OpenDetail(node.npub)), actions)
    }

    @Test
    fun `copy npub puts it on the clipboard and shows the check for 2 s`() {
        show(content().copy(highlight = np(1)))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("copyNpub").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(500)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals(np(1), clipboard.primaryClip!!.getItemAt(0).text.toString())
        rule.onNodeWithContentDescription(str(R.string.native_network_copied)).assertExists()
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithContentDescription(str(R.string.native_network_copied)).assertDoesNotExist()
        rule.onNodeWithContentDescription(str(L10nR.string.mnCopyNpub)).assertExists()
    }

    @Test
    fun `open in nostr falls back to njump without a nostr app`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)
        shadowApp.checkActivities(true)
        val web = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://njump.me/${np(1)}"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        shadowOf(app.packageManager).addResolveInfoForIntent(
            web,
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "browser"; name = "Browser" } },
        )
        try {
            openInNostr(app, np(1))
            assertEquals("https://njump.me/${np(1)}", shadowApp.nextStartedActivity.dataString)
        } finally {
            shadowApp.checkActivities(false)
        }
    }

    @Test
    fun `route shows the retry result snackbar`() {
        val source = FakeNetworkSource().apply {
            stages = listOf(NetworkBuildEvent.Done(MyNetwork.empty(ME)))
            failed = 2
            fixed = 1
        }
        val vm = MyNetworkViewModel(source, FakeTime(0))
        rule.setContent {
            CompositionLocalProvider(LocalNavigator provides NoopNavigator) {
                EinundzwanzigTheme { MyNetworkRoute(vm) }
            }
        }
        rule.onNodeWithText(str(L10nR.string.mnRetry)).performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText(str(L10nR.string.mnRetryResult, 1, 2))).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, source.retries)
    }

    @Test
    fun `route check person navigates to verify person`() {
        val source = FakeNetworkSource().apply { stages = listOf(NetworkBuildEvent.Done(MyNetwork.empty(ME))) }
        val vm = MyNetworkViewModel(source, FakeTime(0))
        val nav = RecordingNavigator()
        rule.setContent {
            CompositionLocalProvider(LocalNavigator provides nav) {
                EinundzwanzigTheme { MyNetworkRoute(vm) }
            }
        }
        rule.onNodeWithContentDescription(str(L10nR.string.mnCheckPerson)).performClick()
        rule.waitForIdle()
        assertEquals(listOf<NavKey>(VerifyPerson()), nav.pushed)
    }

    @Test
    fun `section captions keep sharp s like Dart toUpperCase`() {
        val names = mapOf(np(1) to "Straße")
        show(content().copy(highlight = np(1), names = names))
        rule.onNodeWithText(DartString.upper(str(L10nR.string.mnPathTo, "Straße"))).assertExists()
        assertTrue(DartString.upper(str(L10nR.string.mnPathTo, "Straße")).contains("STRAßE"))
    }

    private class RecordingNavigator : Navigator {
        val pushed = mutableListOf<NavKey>()
        override fun navigate(key: NavKey) {
            pushed += key
        }
        override fun replaceTop(key: NavKey) = Unit
        override fun back() = Unit
        override fun backTo(key: NavKey, inclusive: Boolean) = Unit
        override fun resetTo(key: NavKey) = Unit
        override fun navigateFromRoot(key: NavKey) = Unit
        override fun openUrl(url: String) = Unit
    }

    private object NoopNavigator : Navigator {
        override fun navigate(key: NavKey) = Unit
        override fun replaceTop(key: NavKey) = Unit
        override fun back() = Unit
        override fun backTo(key: NavKey, inclusive: Boolean) = Unit
        override fun resetTo(key: NavKey) = Unit
        override fun navigateFromRoot(key: NavKey) = Unit
        override fun openUrl(url: String) = Unit
    }
}
