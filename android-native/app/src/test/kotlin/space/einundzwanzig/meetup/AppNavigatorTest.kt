package space.einundzwanzig.meetup

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.navigation.BadgeScanner
import space.einundzwanzig.meetup.core.navigation.BadgeWallet
import space.einundzwanzig.meetup.core.navigation.EventsTab
import space.einundzwanzig.meetup.core.navigation.HomeTab
import space.einundzwanzig.meetup.core.navigation.IdentitySetup
import space.einundzwanzig.meetup.core.navigation.Intro
import space.einundzwanzig.meetup.core.navigation.MeetupDetails
import space.einundzwanzig.meetup.core.navigation.NfcTagWriter
import space.einundzwanzig.meetup.core.navigation.RollingQr
import space.einundzwanzig.meetup.core.navigation.Splash
import space.einundzwanzig.meetup.core.navigation.TopLevelDestination
import space.einundzwanzig.meetup.core.navigation.WalletTab
import space.einundzwanzig.meetup.navigation.AppBackStacks
import space.einundzwanzig.meetup.navigation.AppNavigator

class AppNavigatorTest {

    private val opened = mutableListOf<String>()

    private fun stacks(vararg root: NavKey) = AppBackStacks(
        root = root.toMutableList(),
        tabs = TopLevelDestination.entries.associateWith { mutableListOf<NavKey>() },
    )

    private fun navigatorFor(stacks: AppBackStacks) = AppNavigator(stacks) { url ->
        opened += url
        url.startsWith("https://")
    }

    @Test
    fun `splash resets to intro without a shell`() {
        val s = stacks(Splash)
        navigatorFor(s).resetTo(Intro)
        assertEquals(listOf<NavKey>(Intro), s.root)
        assertFalse(s.shellActive)
        assertTrue(s.tabs.values.all { it.isEmpty() })
        assertEquals(listOf<NavKey>(Intro), s.overlayKeys)
    }

    @Test
    fun `reset to the start tab opens the shell with that tab`() {
        val s = stacks(Intro, IdentitySetup)
        navigatorFor(s).resetTo(TopLevelDestination.start)
        assertEquals(listOf<NavKey>(HomeTab), s.root)
        assertTrue(s.shellActive)
        assertEquals(HomeTab, s.currentTab)
        assertEquals(listOf<NavKey>(HomeTab), s.tabs.getValue(HomeTab))
        assertTrue(s.overlayKeys.isEmpty())
    }

    @Test
    fun `selecting tabs keeps visited tab stacks alive`() {
        val s = stacks(HomeTab).also { it.tabs.getValue(HomeTab).add(HomeTab) }
        val nav = navigatorFor(s)
        nav.selectTab(WalletTab)
        nav.selectTab(EventsTab)
        assertEquals(EventsTab, s.currentTab)
        assertEquals(listOf<NavKey>(EventsTab), s.root)
        assertEquals(listOf<NavKey>(HomeTab), s.tabs.getValue(HomeTab))
        assertEquals(listOf<NavKey>(WalletTab), s.tabs.getValue(WalletTab))
        assertEquals(listOf<NavKey>(EventsTab), s.tabs.getValue(EventsTab))
    }

    @Test
    fun `navigate pushes above the shell and back pops`() {
        val s = stacks(HomeTab)
        val nav = navigatorFor(s)
        nav.navigate(MeetupDetails("42"))
        assertEquals(listOf<NavKey>(MeetupDetails("42")), s.overlayKeys)
        assertEquals(MeetupDetails("42"), s.top)
        nav.back()
        assertEquals(listOf<NavKey>(HomeTab), s.root)
        nav.back() // root stays
        assertEquals(listOf<NavKey>(HomeTab), s.root)
    }

    @Test
    fun `navigate to a tab closes the screens above the shell`() {
        val s = stacks(HomeTab, MeetupDetails("1"))
        navigatorFor(s).navigate(WalletTab)
        assertEquals(listOf<NavKey>(WalletTab), s.root)
        assertEquals(listOf<NavKey>(WalletTab), s.tabs.getValue(WalletTab))
    }

    @Test
    fun `replaceTop swaps the scanner for the wallet`() {
        val s = stacks(HomeTab, BadgeScanner)
        navigatorFor(s).replaceTop(BadgeWallet)
        assertEquals(listOf(HomeTab, BadgeWallet), s.root)
    }

    @Test
    fun `replaceTop on a single entry resets`() {
        val s = stacks(Splash)
        navigatorFor(s).replaceTop(HomeTab)
        assertEquals(listOf<NavKey>(HomeTab), s.root)
        assertEquals(listOf<NavKey>(HomeTab), s.tabs.getValue(HomeTab))
    }

    @Test
    fun `backTo pops to the key, inclusive pops it too, never the root`() {
        val s = stacks(HomeTab, BadgeScanner, MeetupDetails("1"), MeetupDetails("2"))
        val nav = navigatorFor(s)
        nav.backTo(BadgeScanner)
        assertEquals(listOf(HomeTab, BadgeScanner), s.root)
        nav.backTo(BadgeScanner, inclusive = true)
        assertEquals(listOf<NavKey>(HomeTab), s.root)
        nav.backTo(HomeTab, inclusive = true)
        assertEquals(listOf<NavKey>(HomeTab), s.root)
        nav.backTo(RollingQr) // unknown key: no-op
        assertEquals(listOf<NavKey>(HomeTab), s.root)
    }

    @Test
    fun `navigateFromRoot keeps only the root below the new key`() {
        val s = stacks(WalletTab, MeetupDetails("1"), NfcTagWriter)
        navigatorFor(s).navigateFromRoot(RollingQr)
        assertEquals(listOf(WalletTab, RollingQr), s.root)
    }

    @Test
    fun `reset to intro from the shell drops every tab`() {
        val s = stacks(HomeTab, MeetupDetails("1"))
        s.tabs.getValue(HomeTab).add(HomeTab)
        s.tabs.getValue(WalletTab).add(WalletTab)
        navigatorFor(s).resetTo(Intro)
        assertEquals(listOf<NavKey>(Intro), s.root)
        assertTrue(s.tabs.values.all { it.isEmpty() })
        assertNull(s.currentTab)
    }

    @Test
    fun `reset to home keeps the home tab stack`() {
        val s = stacks(WalletTab, MeetupDetails("1"))
        s.tabs.getValue(HomeTab).add(HomeTab)
        s.tabs.getValue(WalletTab).add(WalletTab)
        navigatorFor(s).resetTo(HomeTab)
        assertEquals(listOf<NavKey>(HomeTab), s.root)
        assertEquals(listOf<NavKey>(HomeTab), s.tabs.getValue(HomeTab))
        assertTrue(s.tabs.getValue(WalletTab).isEmpty())
    }

    @Test
    fun `openUrl delegates to the opener`() {
        val nav = navigatorFor(stacks(HomeTab))
        nav.openUrl("https://einundzwanzig.space")
        assertFalse(nav.openUrlChecked("javascript:alert(1)"))
        assertEquals(listOf("https://einundzwanzig.space", "javascript:alert(1)"), opened)
    }
}
