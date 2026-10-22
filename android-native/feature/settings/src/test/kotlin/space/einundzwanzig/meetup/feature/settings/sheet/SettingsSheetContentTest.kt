package space.einundzwanzig.meetup.feature.settings.sheet

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.ThemeMode
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class SettingsSheetContentTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<SettingsSheetAction>()

    private fun show(state: SettingsSheetUiState = SettingsSheetUiState(appVersion = "1.7.2"), backupBusy: Boolean = false) {
        rule.setContent {
            EinundzwanzigTheme { SettingsSheetContent(state, backupBusy = backupBusy) { actions += it } }
        }
    }

    private fun string(id: Int) = context.getString(id)

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
    }

    @Test
    fun `renders header, every group and the version footer`() {
        show()
        rule.onNodeWithText(string(L10nR.string.settingsHeaderTitle)).assertExists()
        rule.onNodeWithText(string(L10nR.string.settingsHeaderSub)).assertExists()
        listOf(
            L10nR.string.settingsSecAccount,
            L10nR.string.settingsSecData,
            L10nR.string.settingsSecNetwork,
            L10nR.string.settingsSecApp,
            L10nR.string.v4vSectionTitle,
            L10nR.string.settingsSecDanger,
        ).forEach { rule.onNodeWithText(string(it)).performScrollTo().assertExists() }
        rule.onNodeWithText("V4V").performScrollTo().assertExists()
        rule.onNodeWithTag(SettingsSheetTags.VERSION).performScrollTo().assertTextEquals("21Meetup · Version 1.7.2")
    }

    @Test
    fun `empty version shows no footer text`() {
        show(SettingsSheetUiState(appVersion = ""))
        rule.onNodeWithTag(SettingsSheetTags.VERSION).performScrollTo().assertTextEquals("")
    }

    @Test
    fun `navigation rows emit their actions`() {
        show()
        click(SettingsSheetTags.PROFILE)
        click(SettingsSheetTags.BACKUP)
        click(SettingsSheetTags.RESTORE)
        click(SettingsSheetTags.RELAYS)
        click(SettingsSheetTags.MEMPOOL)
        click(SettingsSheetTags.LOG)
        click(SettingsSheetTags.RESTART)
        click(SettingsSheetTags.V4V)
        click(SettingsSheetTags.RESET)
        assertEquals(
            listOf(
                SettingsSheetAction.OpenProfile,
                SettingsSheetAction.CreateBackup,
                SettingsSheetAction.RestoreBackup,
                SettingsSheetAction.OpenRelays,
                SettingsSheetAction.OpenMempool,
                SettingsSheetAction.OpenLog,
                SettingsSheetAction.RestartTours,
                SettingsSheetAction.OpenV4V,
                SettingsSheetAction.ResetApp,
            ),
            actions,
        )
    }

    @Test
    fun `backup rows are disabled while a backup runs`() {
        show(backupBusy = true)
        rule.onNodeWithTag(SettingsSheetTags.BACKUP).assertIsNotEnabled()
        rule.onNodeWithTag(SettingsSheetTags.RESTORE).assertIsNotEnabled()
        rule.onNodeWithTag(SettingsSheetTags.PROFILE).assertIsEnabled()
    }

    @Test
    fun `language row shows flag and name, dialog applies the choice`() {
        show(SettingsSheetUiState(locale = "de"))
        rule.onNodeWithText("🇩🇪  Deutsch").performScrollTo().assertExists()
        click(SettingsSheetTags.LANGUAGE)
        rule.onNodeWithText(string(L10nR.string.settingsLanguageChoose)).assertExists()
        rule.onNodeWithTag(SettingsSheetTags.language("de")).assertIsSelected()
        mapOf(null to "System", "de" to "Deutsch", "en" to "English", "es" to "Español").forEach { (code, name) ->
            rule.onNodeWithTag(SettingsSheetTags.language(code)).assert(hasText(name, substring = true))
        }
        rule.onNodeWithTag(SettingsSheetTags.language("en")).performClick()
        assertEquals(listOf<SettingsSheetAction>(SettingsSheetAction.SelectLanguage("en")), actions)
        rule.onAllNodesWithTag(SettingsSheetTags.LANGUAGE_DIALOG).assertCountEquals(0)
    }

    @Test
    fun `system language is the null choice`() {
        show(SettingsSheetUiState(locale = "en"))
        rule.onNodeWithText("🇬🇧  English").performScrollTo().assertExists()
        click(SettingsSheetTags.LANGUAGE)
        rule.onNodeWithTag(SettingsSheetTags.language(null)).performClick()
        assertEquals(listOf<SettingsSheetAction>(SettingsSheetAction.SelectLanguage(null)), actions)
    }

    @Test
    fun `currency row and dialog with all seven currencies`() {
        show(SettingsSheetUiState(currency = "EUR"))
        rule.onNodeWithText("EUR · ${string(L10nR.string.curEUR)}").performScrollTo().assertExists()
        click(SettingsSheetTags.CURRENCY)
        rule.onNodeWithText(string(L10nR.string.convSelectCurrency)).assertExists()
        rule.onNodeWithText(string(L10nR.string.settingsCurrencyHint)).assertExists()
        listOf("EUR", "USD", "GBP", "CHF", "CAD", "AUD", "JPY").forEach {
            rule.onNodeWithTag(SettingsSheetTags.currency(it)).performScrollTo().assertExists()
        }
        rule.onNodeWithTag(SettingsSheetTags.currency("EUR")).performScrollTo().assertIsSelected()
        rule.onNodeWithText("CA$").performScrollTo().assertExists()
        rule.onNodeWithTag(SettingsSheetTags.currency("USD")).performScrollTo().performClick()
        assertEquals(listOf<SettingsSheetAction>(SettingsSheetAction.SelectCurrency("USD")), actions)
        rule.onAllNodesWithTag(SettingsSheetTags.CURRENCY_DIALOG).assertCountEquals(0)
    }

    @Test
    fun `haptics row toggles and shows its state`() {
        show(SettingsSheetUiState(hapticEnabled = true))
        rule.onNodeWithTag(SettingsSheetTags.HAPTIC).performScrollTo().assertIsOn()
        rule.onNodeWithText(string(L10nR.string.settingsHapticOn)).assertExists()
        click(SettingsSheetTags.HAPTIC)
        assertEquals(listOf<SettingsSheetAction>(SettingsSheetAction.SetHaptic(false)), actions)
    }

    @Test
    fun `haptics off shows the off text`() {
        show(SettingsSheetUiState(hapticEnabled = false))
        rule.onNodeWithTag(SettingsSheetTags.HAPTIC).performScrollTo().assertIsOff()
        rule.onNodeWithText(string(L10nR.string.settingsHapticOff)).assertExists()
    }

    @Test
    fun `theme buttons select a mode, the active one is a no-op`() {
        show(SettingsSheetUiState(themeMode = ThemeMode.DARK))
        rule.onNodeWithTag(SettingsSheetTags.theme(ThemeMode.DARK)).performScrollTo().assertIsOn()
        click(SettingsSheetTags.theme(ThemeMode.DARK))
        click(SettingsSheetTags.theme(ThemeMode.LIGHT))
        click(SettingsSheetTags.theme(ThemeMode.SYSTEM))
        assertEquals(
            listOf<SettingsSheetAction>(
                SettingsSheetAction.SetThemeMode(ThemeMode.LIGHT),
                SettingsSheetAction.SetThemeMode(ThemeMode.SYSTEM),
            ),
            actions,
        )
    }
}
