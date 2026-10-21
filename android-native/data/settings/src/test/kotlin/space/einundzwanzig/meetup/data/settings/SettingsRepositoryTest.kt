package space.einundzwanzig.meetup.data.settings

import app.cash.turbine.test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.ui.theme.ThemeMode
import space.einundzwanzig.meetup.data.settings.internal.KeyValueSettingsRepository

class SettingsRepositoryTest {

    private val store = InMemoryKeyValueStore()

    private fun TestScope.repo() = KeyValueSettingsRepository(store, backgroundScope)

    // ---- app_locale (Dart LocaleController) ----

    @Test
    fun `locale is system when absent or empty`() = runTest {
        val repo = repo()
        assertNull(repo.getAppLocale())
        store.setString("app_locale", "")
        assertNull(repo.getAppLocale())
    }

    @Test
    fun `setAppLocale writes the language code and null removes the key`() = runTest {
        val repo = repo()
        assertTrue(repo.setAppLocale("en"))
        assertEquals(PrefValue.StringValue("en"), store.entries.value["app_locale"])
        assertEquals("en", repo.getAppLocale())

        assertTrue(repo.setAppLocale(null))
        assertFalse("app_locale" in store.entries.value)

        repo.setAppLocale("es")
        repo.setAppLocale("")
        assertFalse("app_locale" in store.entries.value)
    }

    @Test
    fun `appLocale flow follows the store`() = runTest {
        val repo = repo()
        repo.appLocale.test {
            assertNull(awaitItem())
            store.setString("app_locale", "de")
            assertEquals("de", awaitItem())
            store.remove("app_locale")
            assertNull(awaitItem())
        }
    }

    @Test
    fun `a failed write is reported`() = runTest {
        val repo = repo()
        store.failAllWrites = true
        assertFalse(repo.setAppLocale("en"))
        assertFalse(repo.setHapticEnabled(false))
        assertFalse(repo.setThemeMode(ThemeMode.LIGHT))
    }

    // ---- haptic_enabled ----

    @Test
    fun `haptics default to on`() = runTest {
        val repo = repo()
        runCurrent()
        assertTrue(repo.isHapticEnabled())
        assertTrue(repo.hapticEnabled.value)
    }

    @Test
    fun `haptics state follows every write without a stale cache`() = runTest {
        val repo = repo()
        // Settings sheet writes the pref directly in Dart; the hot flow must see it.
        store.setBool("haptic_enabled", false)
        runCurrent()
        assertFalse(repo.hapticEnabled.value)
        assertFalse(repo.isHapticEnabled())

        repo.setHapticEnabled(true)
        runCurrent()
        assertTrue(repo.hapticEnabled.value)
        assertEquals(PrefValue.BoolValue(true), store.entries.value["haptic_enabled"])
    }

    @Test
    fun `migrated haptics value is read`() = runTest {
        val migrated = InMemoryKeyValueStore(mapOf("haptic_enabled" to PrefValue.BoolValue(false)))
        val repo = KeyValueSettingsRepository(migrated, backgroundScope)
        runCurrent()
        assertFalse(repo.hapticEnabled.value)
    }

    // ---- native_theme_mode ----

    @Test
    fun `theme defaults to dark`() = runTest {
        val repo = repo()
        runCurrent()
        assertEquals(ThemeMode.DARK, repo.getThemeMode())
        assertEquals(ThemeMode.DARK, repo.themeMode.value)
    }

    @Test
    fun `theme mode round trip`() = runTest {
        val repo = repo()
        for (mode in ThemeMode.entries) {
            repo.setThemeMode(mode)
            runCurrent()
            assertEquals(mode, repo.getThemeMode())
            assertEquals(mode, repo.themeMode.value)
        }
        assertEquals(PrefValue.StringValue("system"), store.entries.value["native_theme_mode"])
    }

    @Test
    fun `unknown theme value falls back to dark`() = runTest {
        store.setString("native_theme_mode", "sepia")
        assertEquals(ThemeMode.DARK, repo().getThemeMode())
        assertEquals("light", SettingsRepository.themeModeToPref(ThemeMode.LIGHT))
        assertEquals(ThemeMode.DARK, SettingsRepository.themeModeFromPref(null))
    }

    // ---- nostr_last_seen ----

    @Test
    fun `nostr last seen is unix seconds stored as int`() = runTest {
        val repo = repo()
        assertEquals(0L, repo.getNostrLastSeen())
        repo.setNostrLastSeen(1_790_000_000L)
        assertEquals(PrefValue.IntValue(1_790_000_000L), store.entries.value["nostr_last_seen"])
        assertEquals(1_790_000_000L, repo.getNostrLastSeen())
    }

    // ---- reset ----

    @Test
    fun `clearing the store resets every hot setting to its default`() = runTest {
        val repo = repo()
        repo.setHapticEnabled(false)
        repo.setThemeMode(ThemeMode.LIGHT)
        repo.setAppLocale("es")
        runCurrent()
        assertFalse(repo.hapticEnabled.value)

        store.clear()
        runCurrent()

        assertTrue(repo.hapticEnabled.value)
        assertEquals(ThemeMode.DARK, repo.themeMode.value)
        assertNull(repo.getAppLocale())
    }

    @Test
    fun `feature flags match features dart`() {
        assertFalse(FeatureFlags.NFC_ENABLED)
        assertFalse(FeatureFlags.WEB_LOCAL_NSEC_LOGIN_ENABLED)
    }
}
