package space.einundzwanzig.meetup

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.navigation.EventsTab
import space.einundzwanzig.meetup.core.navigation.HomeTab
import space.einundzwanzig.meetup.core.navigation.Intro
import space.einundzwanzig.meetup.core.navigation.MeetupDetails
import space.einundzwanzig.meetup.core.navigation.NearbyMeetups
import space.einundzwanzig.meetup.core.navigation.TopLevelDestination
import space.einundzwanzig.meetup.core.navigation.WalletTab
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.navigation.AppBackStacks
import space.einundzwanzig.meetup.navigation.AppNavHost
import space.einundzwanzig.meetup.navigation.AppNavigator
import space.einundzwanzig.meetup.ui.AppBottomBar
import space.einundzwanzig.meetup.ui.AppShellScaffold
import space.einundzwanzig.meetup.ui.AppShellTags
import space.einundzwanzig.meetup.ui.ShellAction

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "de")
class AppShellUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `bottom bar shows the tabs, scan and nearby and reports actions`() {
        val actions = mutableListOf<ShellAction>()
        rule.setContent { EinundzwanzigTheme { AppBottomBar(currentTab = HomeTab, onAction = { actions += it }) } }
        rule.onNodeWithText("Home").assertIsDisplayed()
        rule.onNodeWithText("Badges").assertIsDisplayed()
        rule.onNodeWithText("Events").assertIsDisplayed()
        rule.onNodeWithText("In der Nähe").assertIsDisplayed()

        rule.onNodeWithTag(AppShellTags.tab(WalletTab)).performClick()
        rule.onNodeWithTag(AppShellTags.SCAN).performClick()
        rule.onNodeWithTag(AppShellTags.NEARBY).performClick()
        rule.onNodeWithTag(AppShellTags.tab(HomeTab)).performClick()
        assertEquals(
            listOf(ShellAction.SelectTab(WalletTab), ShellAction.Scan, ShellAction.Nearby, ShellAction.SelectTab(HomeTab)),
            actions,
        )
    }

    private fun newStacks(vararg root: NavKey) = AppBackStacks(
        root = mutableStateListOf(*root),
        tabs = TopLevelDestination.entries.associateWith { mutableStateListOf<NavKey>() },
    )

    /** Fake feature entries: each screen shows its key and a saveable counter. */
    private val fakeProvider: (NavKey) -> NavEntry<NavKey> = { key ->
        NavEntry(key) {
            var count by rememberSaveable { mutableIntStateOf(0) }
            Column {
                Text("screen:$key")
                Button(onClick = { count++ }) { Text("count:$key:$count") }
            }
        }
    }

    private fun setNavHost(stacks: AppBackStacks): AppNavigator {
        val navigator = AppNavigator(stacks) { true }
        rule.setContent {
            EinundzwanzigTheme {
                AppNavHost(
                    stacks = stacks,
                    onBack = navigator::back,
                    featureProvider = fakeProvider,
                    shellFrame = { tab, content ->
                        AppShellScaffold(tab, onAction = { action ->
                            when (action) {
                                is ShellAction.SelectTab -> navigator.selectTab(action.tab)
                                ShellAction.Nearby -> navigator.navigate(NearbyMeetups)
                                ShellAction.Scan -> Unit
                            }
                        }, content = content)
                    },
                )
            }
        }
        return navigator
    }

    @Test
    fun `pre-shell flow has no bottom bar, the shell has one`() {
        val stacks = newStacks(Intro)
        val navigator = setNavHost(stacks)
        rule.onNodeWithText("screen:Intro").assertIsDisplayed()
        rule.onNodeWithTag(AppShellTags.BAR).assertDoesNotExist()

        rule.runOnIdle { navigator.resetTo(HomeTab) }
        rule.waitForIdle()
        rule.onNodeWithText("screen:HomeTab").assertIsDisplayed()
        rule.onNodeWithTag(AppShellTags.BAR).assertIsDisplayed()
    }

    @Test
    fun `tabs keep their saved state while another tab is shown`() {
        val stacks = newStacks(Intro)
        val navigator = setNavHost(stacks)
        rule.runOnIdle { navigator.resetTo(HomeTab) }
        rule.waitForIdle()
        rule.onNodeWithText("count:HomeTab:0").performClick()
        rule.onNodeWithText("count:HomeTab:1").assertIsDisplayed()

        rule.onNodeWithTag(AppShellTags.tab(EventsTab)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("screen:EventsTab").assertIsDisplayed()

        rule.onNodeWithTag(AppShellTags.tab(HomeTab)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("count:HomeTab:1").assertIsDisplayed()
    }

    @Test
    fun `pushed screens cover the bar and back returns to the tab`() {
        val stacks = newStacks(Intro)
        val navigator = setNavHost(stacks)
        rule.runOnIdle { navigator.resetTo(WalletTab) }
        rule.waitForIdle()
        rule.onNodeWithTag(AppShellTags.NEARBY).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("screen:NearbyMeetups").assertIsDisplayed()
        rule.onNodeWithTag(AppShellTags.BAR).assertDoesNotExist()

        rule.runOnIdle { navigator.navigate(MeetupDetails("7")) }
        rule.waitForIdle()
        rule.onNodeWithText("screen:MeetupDetails(meetupId=7)").assertIsDisplayed()

        rule.runOnIdle { navigator.back(); navigator.back() }
        rule.waitForIdle()
        rule.onNodeWithText("screen:WalletTab").assertIsDisplayed()
        rule.onNodeWithTag(AppShellTags.BAR).assertIsDisplayed()
    }
}
