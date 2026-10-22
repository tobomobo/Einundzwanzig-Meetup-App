package space.einundzwanzig.meetup.feature.settings.sheet

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideHost
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.InMemoryTourProgressStore
import space.einundzwanzig.meetup.core.ui.guide.TourProgressStore
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.feature.backup.BackupLauncher
import space.einundzwanzig.meetup.feature.backup.LocalBackupLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import space.einundzwanzig.meetup.feature.settings.FakeCurrencyRepository
import space.einundzwanzig.meetup.feature.settings.FakeLocaleController
import space.einundzwanzig.meetup.feature.settings.FakeSettingsRepository
import space.einundzwanzig.meetup.feature.settings.SettingsTour

/**
 * The settings tour must survive an activity recreation (language change, rotation): neither
 * finished/persisted nor restarted from step 0. The sheet's `visible` flag lives outside the
 * activity here, as the host keeps it in `rememberSaveable` / ViewModel state.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSheetRecreateTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val store = InMemoryTourProgressStore()
    private val guide = GuideController(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    private var visible by mutableStateOf(true)
    private val vm = SettingsSheetViewModel(FakeSettingsRepository(), FakeLocaleController("de"), FakeCurrencyRepository(), guide) { "1.7.2" }
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    private fun ComponentActivity.showSheet() = setContent {
        EinundzwanzigTheme {
            CompositionLocalProvider(LocalNavigator provides NoopNavigator, LocalBackupLauncher provides NoopBackup) {
                GuideHost(guide) {
                    SettingsSheet(
                        visible = visible,
                        onDismissRequest = { visible = false },
                        onResetApp = {},
                        onRestoreFinished = {},
                        snackbarHostState = SnackbarHostState(),
                        viewModel = vm,
                    )
                }
            }
        }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun `recreating the activity keeps the running settings tour at its step`() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { it.showSheet() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SettingsTour.START_DELAY_MS + 100)
        rule.waitUntil(3_000) { guide.activeTour == GuideTour.Settings }
        guide.nextStep()
        guide.nextStep()
        guide.nextStep() // the language step: the user now switches the language
        assertEquals(SettingsTour.LANGUAGE, guide.currentStep?.targetId)
        val run = guide.current.run

        scenario.recreate()
        scenario.onActivity { it.showSheet() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SettingsTour.START_DELAY_MS + 500)
        rule.waitForIdle()

        rule.onNodeWithTag(SettingsSheetTags.CONTENT).assertExists()
        assertEquals(GuideTour.Settings, guide.activeTour)
        assertEquals(SettingsTour.LANGUAGE, guide.currentStep?.targetId)
        assertEquals(run, guide.current.run)
        assertNotEquals(true, store.values[TourProgressStore.completedKey(GuideTour.Settings)])
    }

    @Test
    fun `closing the sheet for real still finishes the tour`() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { it.showSheet() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SettingsTour.START_DELAY_MS + 100)
        rule.waitUntil(3_000) { guide.activeTour == GuideTour.Settings }
        visible = false
        rule.waitForIdle()
        rule.waitUntil(3_000) { store.values[TourProgressStore.completedKey(GuideTour.Settings)] == true }
        assertNull(guide.activeTour)
    }
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

private object NoopBackup : BackupLauncher {
    override val busy: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun export(): Boolean = true
    override suspend fun restore(): Boolean = true
    override fun launchExport(onResult: (Boolean) -> Unit) = Unit
    override fun launchRestore(onResult: (Boolean) -> Unit) = Unit
}
