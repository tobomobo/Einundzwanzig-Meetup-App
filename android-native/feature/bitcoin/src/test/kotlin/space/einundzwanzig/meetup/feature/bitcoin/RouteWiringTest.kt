package space.einundzwanzig.meetup.feature.bitcoin

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.MempoolSettings
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.feature.bitcoin.converter.ConverterRoute
import space.einundzwanzig.meetup.feature.bitcoin.converter.ConverterViewModel
import space.einundzwanzig.meetup.feature.bitcoin.dashboard.BitcoinDashboardRoute
import space.einundzwanzig.meetup.feature.bitcoin.dashboard.BitcoinDashboardViewModel
import space.einundzwanzig.meetup.feature.bitcoin.settings.MempoolSettingsRoute
import space.einundzwanzig.meetup.feature.bitcoin.settings.MempoolSettingsViewModel
import java.time.Duration
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Route-level wiring: navigation calls and the lifecycle-bound dashboard auto-refresh. */
@RunWith(AndroidJUnit4::class)
class RouteWiringTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val navigator = FakeNavigator()
    private val service = FakeMempoolService()
    private val config = FakeMempoolConfig()
    private val lifecycle = ManualLifecycleOwner()

    private class ManualLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(
                    LocalNavigator provides navigator,
                    LocalLifecycleOwner provides lifecycle,
                ) { content() }
            }
        }
        rule.waitForIdle()
    }

    private fun dashboardVm() =
        BitcoinDashboardViewModel(service, config, FakeCurrencyRepository(), FixedTime())

    private fun back() = context.getString(L10nR.string.actionBack)

    /** Advances virtual time: the compose test clock and the main looper (Dispatchers.Main). */
    private fun advance(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, 1_000L)
            rule.mainClock.advanceTimeBy(step)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
        rule.waitForIdle()
    }

    @Test
    fun `dashboard change server navigates to the mempool settings`() {
        service.nextDashboard = dashboard(sourcesOk = 0, blocked = true, lastError = "HTTP 403")
        show { BitcoinDashboardRoute(dashboardVm()) }
        rule.onNodeWithText(context.getString(L10nR.string.dashChangeServer)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(MempoolSettings), navigator.navigated)
    }

    @Test
    fun `dashboard back pops`() {
        show { BitcoinDashboardRoute(dashboardVm()) }
        rule.onNodeWithContentDescription(back()).performClick()
        assertEquals(1, navigator.backCalls)
    }

    @Test
    fun `converter back pops`() {
        val vm = ConverterViewModel(service, FakeCurrencyRepository(), FixedTime(), CoroutineScope(Dispatchers.Unconfined))
        show { ConverterRoute(vm) }
        rule.onNodeWithContentDescription(back()).performClick()
        assertEquals(1, navigator.backCalls)
    }

    @Test
    fun `mempool settings back pops`() {
        val vm = MempoolSettingsViewModel(config, service, CoroutineScope(Dispatchers.Unconfined), FakeLog())
        show { MempoolSettingsRoute(vm) }
        rule.onNodeWithContentDescription(back()).performClick()
        assertEquals(1, navigator.backCalls)
    }

    @Test
    fun `auto refresh runs while started and stops below STARTED`() {
        show { BitcoinDashboardRoute(dashboardVm()) }
        assertEquals(1, service.dashboardCalls)
        advance(BitcoinDashboardViewModel.REFRESH_MILLIS + 1)
        assertEquals(2, service.dashboardCalls)

        rule.runOnUiThread { lifecycle.registry.currentState = Lifecycle.State.CREATED }
        rule.waitForIdle()
        advance(3 * BitcoinDashboardViewModel.REFRESH_MILLIS)
        assertEquals(2, service.dashboardCalls)
    }
}
