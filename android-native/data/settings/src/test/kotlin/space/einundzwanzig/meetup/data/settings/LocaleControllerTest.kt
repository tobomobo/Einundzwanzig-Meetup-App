package space.einundzwanzig.meetup.data.settings

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
import space.einundzwanzig.meetup.data.settings.internal.KeyValueSettingsRepository
import space.einundzwanzig.meetup.data.settings.locale.AppLocales
import space.einundzwanzig.meetup.data.settings.locale.LocaleControllerImpl
import space.einundzwanzig.meetup.data.settings.locale.LocaleStartupTask
import space.einundzwanzig.meetup.data.settings.locale.PlatformLocales

class LocaleControllerTest {

    private class FakePlatform(var tag: String? = null, override val isAvailable: Boolean = true) : PlatformLocales {
        val writes = mutableListOf<String?>()
        override fun applicationLanguageTag(): String? = tag
        override fun setApplicationLanguageTag(tag: String?) {
            writes += tag
            this.tag = tag
        }
    }

    private val prefs = InMemoryKeyValueStore()

    private fun TestScope.controller(platform: PlatformLocales) =
        LocaleControllerImpl(KeyValueSettingsRepository(prefs, backgroundScope), prefs, platform)

    // ---- Dart LocaleController ----

    @Test
    fun `setLocale persists the language code and applies it`() = runTest {
        val platform = FakePlatform()
        val c = controller(platform)

        assertTrue(c.setLocale("en"))

        assertEquals(PrefValue.StringValue("en"), prefs.entries.value["app_locale"])
        assertEquals("en", c.current())
        assertEquals(listOf<String?>("en"), platform.writes)
        assertEquals(PrefValue.StringValue("en"), prefs.entries.value["native_applied_locale"])
    }

    @Test
    fun `setLocale null follows the system and removes the key`() = runTest {
        val platform = FakePlatform("en")
        val c = controller(platform)
        prefs.setString("app_locale", "en")

        c.setLocale(null)

        assertFalse("app_locale" in prefs.entries.value)
        assertNull(c.current())
        assertEquals(listOf<String?>(null), platform.writes)
        assertEquals(PrefValue.StringValue(""), prefs.entries.value["native_applied_locale"])
    }

    @Test
    fun `no platform write when it already shows that language`() = runTest {
        val platform = FakePlatform("de-AT")
        controller(platform).setLocale("de")
        assertTrue(platform.writes.isEmpty())
        assertEquals(PrefValue.StringValue("de-AT"), prefs.entries.value["native_applied_locale"])
    }

    @Test
    fun `below API 33 only the preference is written`() = runTest {
        val platform = FakePlatform(isAvailable = false)
        val c = controller(platform)

        c.setLocale("es")
        c.syncWithSystem()

        assertFalse(c.isManagedByPlatform)
        assertEquals("es", c.currentBlocking())
        assertTrue(platform.writes.isEmpty())
        assertFalse("native_applied_locale" in prefs.entries.value)
    }

    // ---- system settings sync (API 33+) ----

    @Test
    fun `a migrated preference wins over an untouched system setting`() = runTest {
        prefs.setString("app_locale", "es") // copied from Flutter, never applied natively
        val platform = FakePlatform(null)
        val c = controller(platform)

        c.syncWithSystem()
        assertEquals("es", c.current())

        c.applyToPlatform(c.current())
        assertEquals(listOf<String?>("es"), platform.writes)
    }

    @Test
    fun `a change in the system settings is adopted`() = runTest {
        val platform = FakePlatform()
        val c = controller(platform)
        c.setLocale("de")

        platform.tag = "en-GB" // user picks English in Android "App languages"
        c.syncWithSystem()

        assertEquals("en", c.current())
        assertEquals(PrefValue.StringValue("en-GB"), prefs.entries.value["native_applied_locale"])
    }

    @Test
    fun `resetting to system in the system settings is adopted`() = runTest {
        val platform = FakePlatform()
        val c = controller(platform)
        c.setLocale("es")

        platform.tag = null
        c.syncWithSystem()

        assertNull(c.current())
    }

    @Test
    fun `a system choice made before the first start is adopted`() = runTest {
        val c = controller(FakePlatform("es"))
        c.syncWithSystem()
        assertEquals("es", c.current())
    }

    @Test
    fun `an unsupported system language is adopted as system`() = runTest {
        val platform = FakePlatform("fr-FR")
        val c = controller(platform)
        c.setLocale("de")
        platform.tag = "fr-FR" // e.g. via `cmd locale` or an OEM settings screen
        platform.writes.clear()

        c.syncWithSystem()
        assertNull(c.current())
        assertFalse("app_locale" in prefs.entries.value)

        c.applyToPlatform(c.current())
        assertTrue(platform.writes.isEmpty())
        assertEquals("fr-FR", platform.tag)
    }

    @Test
    fun `nothing changes when the platform still shows what was applied`() = runTest {
        val platform = FakePlatform()
        val c = controller(platform)
        c.setLocale("en")
        platform.writes.clear()

        c.syncWithSystem()

        assertEquals("en", c.current())
        assertTrue(platform.writes.isEmpty())
    }

    @Test
    fun `startup task keeps the platform in step, also after an app reset`() = runTest {
        val platform = FakePlatform()
        val c = controller(platform)
        val task = LocaleStartupTask(c, backgroundScope)
        assertEquals(0, task.order)

        task.run()
        runCurrent()
        prefs.setString("app_locale", "en") // e.g. written by the Intro picker
        runCurrent()
        assertEquals("en", platform.tag)

        prefs.clear() // app reset
        runCurrent()
        assertNull(platform.tag)
    }

    // ---- AppLocales ----

    @Test
    fun `display names, flags and supported order`() {
        assertEquals(listOf("de", "en", "es"), AppLocales.SUPPORTED)
        assertEquals("Deutsch", AppLocales.displayName("de"))
        assertEquals("English", AppLocales.displayName("en"))
        assertEquals("Español", AppLocales.displayName("es"))
        assertEquals("System", AppLocales.displayName(null))
        assertEquals("System", AppLocales.displayName("fr"))
        assertEquals("🇩🇪", AppLocales.flagFor("de"))
        assertEquals("🇬🇧", AppLocales.flagFor("en"))
        assertEquals("🇪🇸", AppLocales.flagFor("es"))
        assertEquals("🌐", AppLocales.flagFor(null))
        assertEquals("🌐", AppLocales.flagFor("fr"))
    }

    @Test
    fun `language subtag of a tag`() {
        assertEquals("de", AppLocales.languageOf("de-AT"))
        assertEquals("en", AppLocales.languageOf("en"))
        assertNull(AppLocales.languageOf(null))
        assertNull(AppLocales.languageOf(""))
    }
}
