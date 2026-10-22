package space.einundzwanzig.meetup.feature.wallet

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import space.einundzwanzig.meetup.core.navigation.BadgeDetails
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.InMemoryTourProgressStore
import space.einundzwanzig.meetup.core.ui.guide.LocalGuideController
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.feature.wallet.details.BadgeDetailsRouteContent
import space.einundzwanzig.meetup.feature.wallet.details.BadgeDetailsTestTags
import space.einundzwanzig.meetup.feature.wallet.details.BadgeDetailsViewModel
import space.einundzwanzig.meetup.feature.wallet.details.NO_SHARE_TARGET
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Route-level behavior: tour lifecycle, share fallbacks, clipboard, error snackbars. */
@RunWith(AndroidJUnit4::class)
class WalletRouteTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val tourStore = InMemoryTourProgressStore()
    private val guide = GuideController(tourStore, CoroutineScope(Dispatchers.Unconfined))
    private val navigator = RecordingNavigator()
    private val vmStore = ViewModelStore()

    private fun walletVm(repo: FakeBadgeRepository): WalletViewModel =
        ViewModelProvider(
            vmStore,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    WalletViewModel(repo, FakePublisher(), FakeProfiles(), FakeTime()) as T
            },
        )[WalletViewModel::class.java]

    /** Shows the wallet route; [shown] simulates Navigation 3 dropping a covered entry's content. */
    private var shown by mutableStateOf(true)

    private fun showWallet(vm: WalletViewModel) {
        rule.setContent {
            CompositionLocalProvider(LocalNavigator provides navigator, LocalGuideController provides guide) {
                EinundzwanzigTheme { if (shown) WalletRouteContent(showBack = false, viewModel = vm) }
            }
        }
    }

    private fun clipboardText(): String? =
        context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    // region Tour

    @Test
    fun `tour pauses while covered, resumes on return and ends only when the entry is popped`() {
        showWallet(walletVm(FakeBadgeRepository(listOf(badge("1", name = "Ulm")))))
        rule.waitUntil(3_000) { guide.isActive }
        assertEquals(GuideTour.Wallet, guide.activeTour)

        // Step 1 target (collection card) pushes the collection screen.
        rule.onNodeWithText("ULM", substring = true).performClick()
        assertEquals("navigate:BadgeCollection", navigator.calls.last())
        rule.runOnIdle { shown = false }
        rule.runOnIdle {
            assertTrue(guide.isActive)
            assertTrue(guide.current.isPaused)
        }
        assertFalse(runBlocking { tourStore.isCompleted(GuideTour.Wallet) })

        rule.runOnIdle { shown = true }
        rule.runOnIdle {
            assertEquals(GuideTour.Wallet, guide.activeTour)
            assertFalse(guide.current.isPaused)
            assertEquals(0, guide.current.currentStepIndex)
        }

        // Popped: content leaves, then the entry's ViewModel is cleared.
        rule.runOnIdle { shown = false }
        rule.runOnIdle { vmStore.clear() }
        assertNull(guide.activeTour)
        assertTrue(runBlocking { tourStore.isCompleted(GuideTour.Wallet) })
    }

    @Test
    fun `tour starts once after the delay and not again on recomposition`() {
        showWallet(walletVm(FakeBadgeRepository(listOf(badge("1")))))
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(WalletTour.START_DELAY_MS / 2)
        assertFalse(guide.isActive)
        rule.mainClock.autoAdvance = true
        rule.waitUntil(3_000) { guide.isActive }
        rule.runOnIdle { guide.nextStep() }
        rule.mainClock.advanceTimeBy(2_000)
        rule.runOnIdle { assertEquals(1, guide.current.currentStepIndex) }
    }

    @Test
    fun `tour does not start for an empty wallet`() {
        showWallet(walletVm(FakeBadgeRepository()))
        rule.mainClock.advanceTimeBy(2_000)
        rule.runOnIdle { assertFalse(guide.isActive) }
        assertFalse(runBlocking { tourStore.isCompleted(GuideTour.Wallet) })
    }

    @Test
    fun `completed tour is not shown again`() {
        runBlocking { tourStore.setCompleted(GuideTour.Wallet, true) }
        showWallet(walletVm(FakeBadgeRepository(listOf(badge("1")))))
        rule.mainClock.advanceTimeBy(2_000)
        rule.runOnIdle { assertFalse(guide.isActive) }
    }

    // endregion

    @Test
    fun `share text without a share target copies to the clipboard with the orange snackbar`() {
        runBlocking { tourStore.setCompleted(GuideTour.Wallet, true) }
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)
        val all = listOf(badge("1", name = "Ulm"))
        val vm = walletVm(FakeBadgeRepository(all))
        showWallet(vm)
        rule.runOnIdle { vm.onAction(WalletAction.ShareText) }
        rule.waitUntil(3_000) { clipboardText() != null }
        assertTrue(clipboardText()!!.contains("Ulm"))
        rule.onNodeWithText(str(L10nR.string.walletReputationCopied)).assertExists()

        rule.runOnIdle { vm.onAction(WalletAction.ExportJson) }
        rule.waitUntil(3_000) { clipboardText()?.startsWith("{") == true }
        rule.onNodeWithText(str(L10nR.string.walletJsonCopied)).assertExists()
    }

    // region Details

    private fun showDetails(b: MeetupBadge, share: Boolean) {
        val repo = FakeBadgeRepository(listOf(b))
        val vm = BadgeDetailsViewModel(BadgeDetails(BadgeIdentity.unionKey(b), 0), repo)
        rule.setContent {
            CompositionLocalProvider(LocalNavigator provides navigator) {
                EinundzwanzigTheme { BadgeDetailsRouteContent(vm, shareImage = { _, _, _ -> share }) }
            }
        }
    }

    @Test
    fun `details share without a target shows repShareError`() {
        showDetails(badge("a"), share = false)
        rule.onNodeWithContentDescription(str(L10nR.string.badgeShare)).performClick()
        rule.onNodeWithText(str(L10nR.string.repShareError, NO_SHARE_TARGET)).assertExists()
    }

    @Test
    fun `details hash card copies the verification hash`() {
        val b = badge("a")
        showDetails(b, share = true)
        rule.onNodeWithTag(BadgeDetailsTestTags.HASH).performScrollTo().performClick()
        assertEquals(b.verificationHash(), clipboardText())
        rule.onNodeWithText(str(L10nR.string.badgeHashCopied)).assertExists()
    }

    // endregion
}

private class RecordingNavigator : Navigator {
    val calls = mutableListOf<String>()
    private fun name(key: NavKey) = key::class.simpleName.orEmpty()
    override fun navigate(key: NavKey) { calls += "navigate:${name(key)}" }
    override fun replaceTop(key: NavKey) { calls += "replaceTop:${name(key)}" }
    override fun back() { calls += "back" }
    override fun backTo(key: NavKey, inclusive: Boolean) { calls += "backTo:${name(key)}" }
    override fun resetTo(key: NavKey) { calls += "resetTo:${name(key)}" }
    override fun navigateFromRoot(key: NavKey) { calls += "navigateFromRoot:${name(key)}" }
    override fun openUrl(url: String) { calls += "openUrl:$url" }
}
