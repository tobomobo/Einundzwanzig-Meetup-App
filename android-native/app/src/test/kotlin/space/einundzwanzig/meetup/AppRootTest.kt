package space.einundzwanzig.meetup

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.navigation.BadgeScanner
import space.einundzwanzig.meetup.core.navigation.Destination
import space.einundzwanzig.meetup.core.navigation.EventsTab
import space.einundzwanzig.meetup.core.navigation.HomeTab
import space.einundzwanzig.meetup.core.navigation.Intro
import space.einundzwanzig.meetup.core.navigation.MeetupCalendar
import space.einundzwanzig.meetup.core.navigation.NearbyMeetups
import space.einundzwanzig.meetup.core.navigation.TopLevelDestination
import space.einundzwanzig.meetup.core.navigation.WalletTab
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.navigation.AppBackStacks
import space.einundzwanzig.meetup.navigation.AppNavigator
import space.einundzwanzig.meetup.navigation.ExternalUrlOpener
import space.einundzwanzig.meetup.ui.AuthUrlSnackbarEffect
import space.einundzwanzig.meetup.ui.RefreshOnReturnEffect
import space.einundzwanzig.meetup.ui.ShellAction
import space.einundzwanzig.meetup.ui.WidgetTargetEffect
import space.einundzwanzig.meetup.ui.handleShellAction
import space.einundzwanzig.meetup.ui.keepSystemSplash

/** Key flows of the app root (spec 12 §2.2–§2.4, spec 14 §3.1.5). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "de")
class AppRootTest {

    @get:Rule
    val rule = createComposeRule()

    private fun newStacks(vararg root: NavKey) = AppBackStacks(
        root = mutableStateListOf(*root),
        tabs = TopLevelDestination.entries.associateWith { mutableStateListOf<NavKey>() },
    )

    // --- NIP-46 auth_url snackbar ---------------------------------------------------------------

    private class AuthHarness {
        val urls = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val acknowledged = mutableListOf<String>()
        val opened = mutableListOf<String>()
    }

    private fun setAuthSnackbar(h: AuthHarness, open: (String) -> Boolean) {
        rule.setContent {
            EinundzwanzigTheme {
                val host = androidx.compose.runtime.remember { SnackbarHostState() }
                Box(Modifier.fillMaxSize()) {
                    AppSnackbarHost(host, Modifier.align(Alignment.BottomCenter))
                }
                AuthUrlSnackbarEffect(h.urls, { h.acknowledged += it }, host) { url -> h.opened += url; open(url) }
            }
        }
    }

    @Test
    fun `auth url snackbar shows text, action and close icon and opens the url on tap`() {
        val h = AuthHarness()
        setAuthSnackbar(h) { true }
        val url = "https://bunker.example/approve?token=geheim"
        rule.runOnIdle { assertTrue(h.urls.tryEmit(url)) }

        rule.onNodeWithText("Der Signer verlangt eine Freigabe im Browser.").assertIsDisplayed()
        rule.onNodeWithText("ÖFFNEN").assertIsDisplayed()
        rule.onNodeWithContentDescription("Schließen").assertIsDisplayed()
        assertTrue("not acknowledged before the user dealt with it", h.acknowledged.isEmpty())

        rule.onNodeWithText("ÖFFNEN").performClick()
        rule.waitForIdle()
        assertEquals(listOf(url), h.opened)
        assertEquals(listOf(url), h.acknowledged)
    }

    @Test
    fun `closing the auth snackbar acknowledges without opening`() {
        val h = AuthHarness()
        setAuthSnackbar(h) { true }
        rule.runOnIdle { h.urls.tryEmit("https://bunker.example/a") }
        rule.onNodeWithContentDescription("Schließen").performClick()
        rule.waitForIdle()
        assertTrue(h.opened.isEmpty())
        assertEquals(listOf("https://bunker.example/a"), h.acknowledged)
    }

    @Test
    fun `a javascript auth url is refused and logged with the host only`() {
        val h = AuthHarness()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        setAuthSnackbar(h) { ExternalUrlOpener.open(context, it) }
        val evil = "javascript:alert('token-123')"
        rule.runOnIdle { h.urls.tryEmit(evil) }
        rule.onNodeWithText("ÖFFNEN").performClick()
        rule.waitForIdle()

        assertEquals(listOf(evil), h.opened)
        val nip46 = AppLogger.entries.value.last { it.tag == "Nip46" }
        assertTrue(nip46.message, nip46.message.contains("Host: unbekannt"))
        assertTrue("never the full url", AppLogger.entries.value.none { it.message.contains("token-123") })
    }

    // --- widget targets ---------------------------------------------------------------------------

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `widget target is consumed once the shell is active and never pushed twice`() {
        val stacks = newStacks(Intro)
        val navigator = AppNavigator(stacks) { true }
        val target = MeetupCalendar(preselectNextFavorite = true)
        val queue = ArrayDeque<Destination?>(listOf(target, target))
        var consumed = 0
        val owner = FakeLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                WidgetTargetEffect(stacks, navigator) { consumed++; queue.removeFirstOrNull() }
            }
        }
        rule.waitForIdle()
        assertEquals("nothing is read before the shell exists", 0, consumed)

        rule.runOnIdle { navigator.resetTo(HomeTab) }
        rule.waitForIdle()
        assertEquals(1, consumed)
        assertEquals(listOf<NavKey>(HomeTab, target), stacks.root.toList())

        // A second tap on the same area while it is already shown: consumed, not pushed again.
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        assertEquals(2, consumed)
        assertEquals(listOf<NavKey>(HomeTab, target), stacks.root.toList())
    }

    // --- Home refresh -----------------------------------------------------------------------------

    @Test
    fun `home tab tap and re-tap refresh home, other actions do not`() {
        val stacks = newStacks(Intro)
        val navigator = AppNavigator(stacks) { true }
        var refreshes = 0
        var scanOpened = 0
        fun act(a: ShellAction) = handleShellAction(a, navigator, { refreshes++ }) { scanOpened++ }

        act(ShellAction.SelectTab(HomeTab))
        act(ShellAction.SelectTab(HomeTab))
        assertEquals(2, refreshes)
        act(ShellAction.SelectTab(EventsTab))
        act(ShellAction.Scan)
        act(ShellAction.Nearby)
        assertEquals(2, refreshes)
        assertEquals(1, scanOpened)
        assertEquals(listOf<NavKey>(EventsTab, NearbyMeetups), stacks.root.toList())
    }

    @Test
    fun `scanner opened from the scan sheet refreshes home once when the tab is back`() {
        val stacks = newStacks(WalletTab)
        val navigator = AppNavigator(stacks) { true }
        var pending = false
        var refreshes = 0
        rule.setContent {
            RefreshOnReturnEffect(stacks, pending = { pending }, onReturned = { pending = false; refreshes++ })
        }
        rule.runOnIdle {
            pending = true
            navigator.navigate(BadgeScanner)
        }
        rule.waitForIdle()
        assertEquals("not while the scanner is shown", 0, refreshes)

        rule.runOnIdle { navigator.back() }
        rule.waitForIdle()
        assertEquals(1, refreshes)
        assertFalse(pending)

        rule.runOnIdle { navigator.navigate(NearbyMeetups) }
        rule.runOnIdle { navigator.back() }
        rule.waitForIdle()
        assertEquals("only after a scan", 1, refreshes)
    }

    // --- system splash ----------------------------------------------------------------------------

    @Test
    fun `system splash waits for gate, theme and first route, but not past the timeout`() {
        assertTrue(keepSystemSplash(gateDone = false, themeLoaded = true, firstRouteShown = true, splashTimedOut = false))
        assertTrue(keepSystemSplash(gateDone = true, themeLoaded = false, firstRouteShown = true, splashTimedOut = false))
        assertTrue(keepSystemSplash(gateDone = true, themeLoaded = true, firstRouteShown = false, splashTimedOut = false))
        assertFalse(keepSystemSplash(gateDone = true, themeLoaded = true, firstRouteShown = true, splashTimedOut = false))
        assertFalse(
            "after 20 s the in-app wait screen takes over",
            keepSystemSplash(gateDone = false, themeLoaded = false, firstRouteShown = false, splashTimedOut = true),
        )
    }

    @Test
    fun `migration wait screen shows its text`() {
        rule.setContent { EinundzwanzigTheme { space.einundzwanzig.meetup.ui.MigrationWaitScreen() } }
        rule.onNodeWithText("Daten werden übernommen…").assertIsDisplayed()
    }
}
