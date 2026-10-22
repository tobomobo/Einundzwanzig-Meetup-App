package space.einundzwanzig.meetup.feature.settings.sheet

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.InMemoryTourProgressStore
import space.einundzwanzig.meetup.core.ui.guide.TourProgressStore
import space.einundzwanzig.meetup.core.ui.theme.ThemeMode
import space.einundzwanzig.meetup.feature.settings.FakeCurrencyRepository
import space.einundzwanzig.meetup.feature.settings.FakeLocaleController
import space.einundzwanzig.meetup.feature.settings.FakeSettingsRepository
import space.einundzwanzig.meetup.feature.settings.MainDispatcherRule

class SettingsSheetViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val locale = FakeLocaleController("en")
    private val currency = FakeCurrencyRepository("CHF")
    private val store = InMemoryTourProgressStore()

    private fun vm(version: String = "1.7.2"): SettingsSheetViewModel {
        val guide = GuideController(store, CoroutineScope(main.dispatcher))
        val vm = SettingsSheetViewModel(settings, locale, currency, guide) { version }
        CoroutineScope(main.dispatcher).launch { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun `state combines locale, currency, haptics, theme and version`() {
        settings.hapticEnabled.value = false
        settings.themeMode.value = ThemeMode.SYSTEM
        val s = vm().uiState.value
        assertEquals("en", s.locale)
        assertEquals("CHF", s.currency)
        assertFalse(s.hapticEnabled)
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals("1.7.2", s.appVersion)
    }

    @Test
    fun `stored language is in the state before the first collection`() {
        val guide = GuideController(store, CoroutineScope(main.dispatcher))
        val vm = SettingsSheetViewModel(settings, locale, currency, guide) { "1.7.2" }
        assertEquals("en", vm.uiState.value.locale)
    }

    @Test
    fun `empty version stays empty`() {
        assertEquals("", vm(version = "").uiState.value.appVersion)
    }

    @Test
    fun `language choice is stored and applied, null means system`() {
        val vm = vm()
        vm.setLocale("es")
        assertEquals("es", vm.uiState.value.locale)
        vm.setLocale(null)
        assertEquals(listOf("es", null), locale.applied)
        assertEquals(null, vm.uiState.value.locale)
    }

    @Test
    fun `currency choice goes through the currency repository`() {
        val vm = vm()
        vm.setCurrency("JPY")
        assertEquals(listOf("JPY"), currency.sets)
        assertEquals("JPY", vm.uiState.value.currency)
    }

    @Test
    fun `haptics switch writes haptic_enabled without a stale cache`() {
        val vm = vm()
        vm.setHapticEnabled(false)
        assertFalse(settings.hapticEnabled.value)
        assertFalse(vm.uiState.value.hapticEnabled)
        vm.setHapticEnabled(true)
        assertTrue(vm.uiState.value.hapticEnabled)
    }

    @Test
    fun `failed haptics write keeps the old value`() {
        settings.writesSucceed = false
        val vm = vm()
        vm.setHapticEnabled(false)
        assertTrue(vm.uiState.value.hapticEnabled)
    }

    @Test
    fun `theme toggle stores the native theme mode`() {
        val vm = vm()
        vm.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, settings.themeMode.value)
        assertEquals(ThemeMode.LIGHT, vm.uiState.value.themeMode)
    }

    @Test
    fun `restart tours resets every tour, then reports it`() = runTest {
        GuideTour.entries.forEach { store.setCompleted(it, true) }
        val vm = vm()
        vm.uiEvents.test {
            vm.restartTours()
            assertEquals(SettingsSheetEvent.ToursReset, awaitItem())
        }
        GuideTour.entries.forEach { assertFalse(store.values.containsKey(TourProgressStore.completedKey(it))) }
    }
}
