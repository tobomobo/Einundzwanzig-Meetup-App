package space.einundzwanzig.meetup.feature.settings.sheet

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.navigation.DiagnosticLog
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.navigation.ProfileEdit
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideHost
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.InMemoryTourProgressStore
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.feature.backup.BackupLauncher
import space.einundzwanzig.meetup.feature.backup.LocalBackupLauncher
import space.einundzwanzig.meetup.feature.settings.FakeCurrencyRepository
import space.einundzwanzig.meetup.feature.settings.FakeLocaleController
import space.einundzwanzig.meetup.feature.settings.FakeSettingsRepository
import space.einundzwanzig.meetup.feature.settings.SettingsTour
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class SettingsSheetFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val navigator = RecordingNavigator()
    private val backup = RecordingBackupLauncher()
    private val store = InMemoryTourProgressStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val guide = GuideController(store, scope)
    private val snackbar = SnackbarHostState()
    private var visible by mutableStateOf(true)
    private var dismissed = 0
    private var resets = 0
    private var reloads = 0

    private fun show() {
        val vm = SettingsSheetViewModel(FakeSettingsRepository(), FakeLocaleController(), FakeCurrencyRepository(), guide) { "1.7.2" }
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator, LocalBackupLauncher provides backup) {
                    GuideHost(guide) {
                        SettingsSheet(
                            visible = visible,
                            onDismissRequest = {
                                dismissed++
                                visible = false
                            },
                            onResetApp = { resets++ },
                            onRestoreFinished = { reloads++ },
                            snackbarHostState = snackbar,
                            viewModel = vm,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun clickRow(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun `profile row closes the sheet, then opens the profile editor`() {
        show()
        clickRow(SettingsSheetTags.PROFILE)
        assertEquals(1, dismissed)
        assertEquals(listOf<NavKey>(ProfileEdit), navigator.pushed)
    }

    @Test
    fun `diagnose log row opens the DiagnosticLog destination`() {
        show()
        clickRow(SettingsSheetTags.LOG)
        assertEquals(listOf<NavKey>(DiagnosticLog), navigator.pushed)
    }

    @Test
    fun `backup row closes the sheet and starts the export`() {
        show()
        clickRow(SettingsSheetTags.BACKUP)
        assertEquals(1, dismissed)
        assertEquals(1, backup.exports)
    }

    @Test
    fun `restore always reloads the dashboard afterwards`() {
        show()
        clickRow(SettingsSheetTags.RESTORE)
        assertEquals(1, backup.restores)
        assertEquals(1, reloads)
    }

    @Test
    fun `reset row hands over to the caller's reset flow`() {
        show()
        clickRow(SettingsSheetTags.RESET)
        assertEquals(1, dismissed)
        assertEquals(1, resets)
    }

    @Test
    fun `restart tours resets them and shows the orange snackbar after closing`() {
        runBlocking { GuideTour.entries.forEach { store.setCompleted(it, true) } }
        show()
        clickRow(SettingsSheetTags.RESTART)
        rule.waitUntil(3_000) { snackbar.currentSnackbarData != null }
        assertEquals(context.getString(L10nR.string.settingsGuideReset), snackbar.currentSnackbarData?.visuals?.message)
        assertTrue(store.values.isEmpty())
        assertFalse(visible)
    }

    @Test
    fun `settings tour starts after opening and is completed when the sheet closes`() {
        show()
        rule.mainClock.advanceTimeBy(SettingsTour.START_DELAY_MS + 100)
        rule.waitUntil(3_000) { guide.activeTour == GuideTour.Settings }
        assertEquals(7, guide.current.totalSteps)
        visible = false
        rule.waitForIdle()
        rule.waitUntil(3_000) { store.values[space.einundzwanzig.meetup.core.ui.guide.TourProgressStore.completedKey(GuideTour.Settings)] == true }
        assertEquals(null, guide.activeTour)
    }

    @Test
    fun `completed settings tour does not start again`() {
        runBlocking { store.setCompleted(GuideTour.Settings, true) }
        show()
        rule.mainClock.advanceTimeBy(SettingsTour.START_DELAY_MS + 500)
        rule.waitForIdle()
        assertEquals(null, guide.activeTour)
    }
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

private class RecordingBackupLauncher : BackupLauncher {
    var exports = 0
    var restores = 0
    override val busy: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun export(): Boolean = true
    override suspend fun restore(): Boolean = true
    override fun launchExport(onResult: (Boolean) -> Unit) {
        exports++
        onResult(true)
    }
    override fun launchRestore(onResult: (Boolean) -> Unit) {
        restores++
        onResult(false)
    }
}
