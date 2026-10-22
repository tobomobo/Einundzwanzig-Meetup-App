package space.einundzwanzig.meetup.feature.onboarding

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.feature.onboarding.common.SnackbarMessage
import space.einundzwanzig.meetup.feature.onboarding.common.UiText
import space.einundzwanzig.meetup.feature.onboarding.intro.IntroAction
import space.einundzwanzig.meetup.feature.onboarding.intro.IntroEffect
import space.einundzwanzig.meetup.feature.onboarding.intro.IntroViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class IntroViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val profiles = FakeUserProfileRepository()
    private val backup = FakeBackupLauncher()
    private val locale = FakeLocaleController()
    private val effects = mutableListOf<IntroEffect>()
    private val snacks = mutableListOf<SnackbarMessage>()
    private var vm: IntroViewModel? = null

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        vm?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun TestScope.create(): IntroViewModel {
        val model = IntroViewModel(profiles, backup, locale)
        backgroundScope.launch(dispatcher) { model.effects.collect { effects += it } }
        backgroundScope.launch(dispatcher) { model.snackbars.collect { snacks += it } }
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        model.onAction(IntroAction.Resumed) // first resume after composition: no-op
        vm = model
        return model
    }

    @Test
    fun `join without identity shows the snackbar and opens the setup`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(IntroAction.Join)
        assertEquals(listOf<IntroEffect>(IntroEffect.OpenIdentitySetup), effects)
        assertEquals(UiText.res(L10nR.string.introSetIdentity), snacks.single().text)
        assertTrue(vm.state.value.joining)
    }

    @Test
    fun `closing the setup without identity stays on intro`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(IntroAction.Join)
        vm.onAction(IntroAction.Resumed)
        assertFalse(vm.state.value.joining)
        assertEquals(1, effects.size)
    }

    @Test
    fun `returning from setup onboarded goes home`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(IntroAction.Join)
        profiles.current = UserProfile(nickname = "Hal")
        vm.onAction(IntroAction.Resumed)
        assertEquals(IntroEffect.GoHome, effects.last())
    }

    @Test
    fun `join when already onboarded goes straight home`() = runTest(dispatcher) {
        profiles.current = UserProfile(isNostrVerified = true)
        val vm = create()
        vm.onAction(IntroAction.Join)
        assertEquals(listOf<IntroEffect>(IntroEffect.GoHome), effects)
        assertTrue(snacks.isEmpty())
    }

    @Test
    fun `restore success goes home, failure stays`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(IntroAction.RestoreBackup)
        assertEquals(1, backup.restoreCalls)
        assertTrue(effects.isEmpty())
        backup.restoreResult = true
        vm.onAction(IntroAction.RestoreBackup)
        assertEquals(listOf<IntroEffect>(IntroEffect.GoHome), effects)
    }

    @Test
    fun `restore is ignored while a backup runs`() = runTest(dispatcher) {
        val vm = create()
        backup.busy.value = true
        assertTrue(vm.state.value.backupBusy)
        vm.onAction(IntroAction.RestoreBackup)
        assertEquals(0, backup.restoreCalls)
    }

    @Test
    fun `language dialog stores the selection, system removes it`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(IntroAction.OpenLanguage)
        assertTrue(vm.state.value.showLanguageDialog)
        vm.onAction(IntroAction.SelectLanguage("es"))
        assertFalse(vm.state.value.showLanguageDialog)
        assertEquals("es", vm.state.value.locale)
        vm.onAction(IntroAction.SelectLanguage(null))
        assertNull(locale.locale.value)
        assertNull(vm.state.value.locale)
    }

    @Test
    fun `unreadable profile shows an error and re-enables the button`() = runTest(dispatcher) {
        profiles.failure = IllegalStateException("kaputt")
        val vm = create()
        vm.onAction(IntroAction.Join)
        assertFalse(vm.state.value.joining)
        assertTrue(effects.isEmpty())
        assertEquals(UiText.res(L10nR.string.errorGeneric, "kaputt"), snacks.single().text)
    }
}
