package space.einundzwanzig.meetup.feature.bitcoin.converter

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.mempool.converter.ConverterState
import space.einundzwanzig.meetup.feature.bitcoin.R
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class ConverterScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<ConverterAction>()

    private fun loaded(currency: String = "EUR") =
        ConverterState(currency = currency).onPricesLoaded(mapOf("EUR" to 95_000.0, "CHF" to 89_000.0), 1_760_000_000_000)

    private fun show(state: ConverterState) {
        rule.setContent { EinundzwanzigTheme { ConverterScreen(ConverterUiState(state)) { actions += it } } }
    }

    @Test
    fun `loading shows the loading text and disables refresh`() {
        show(ConverterState(currency = "EUR"))
        rule.onNodeWithText(context.getString(L10nR.string.convLoading)).performScrollTo().assertExists()
        rule.onNodeWithContentDescription(context.getString(L10nR.string.convRefresh)).assertIsNotEnabled()
    }

    @Test
    fun `rate info shows the rate in the currency code`() {
        show(loaded())
        rule.onNodeWithText(context.getString(L10nR.string.convRateInfo, "95.000", "EUR")).performScrollTo().assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.convSource)).assertExists()
    }

    @Test
    fun `offline banner retries`() {
        show(ConverterState(currency = "EUR").onPricesLoaded(emptyMap(), 0))
        rule.onNodeWithTag(ConverterTags.OFFLINE_BANNER).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.convOffline)).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.convRefresh)).performClick()
        assertEquals(listOf<ConverterAction>(ConverterAction.Refresh), actions)
    }

    @Test
    fun `typing in the fiat field keeps only digits and separators`() {
        show(loaded())
        rule.onNodeWithTag(ConverterTags.FIAT_FIELD).performTextInput("1a2,5")
        assertEquals(ConverterAction.FiatChanged("12,5"), actions.last())
    }

    @Test
    fun `typing in the crypto field reports a crypto change`() {
        show(loaded())
        rule.onNodeWithTag(ConverterTags.CRYPTO_FIELD).performTextInput("21000")
        assertEquals(ConverterAction.CryptoChanged("21000"), actions.last())
    }

    @Test
    fun `unit chip swaps the unit`() {
        show(loaded())
        rule.onNodeWithText(context.getString(L10nR.string.convUnitSats).uppercase()).assertExists()
        rule.onNodeWithTag(ConverterTags.UNIT_CHIP).performClick()
        assertEquals(listOf<ConverterAction>(ConverterAction.SwapUnit), actions)
    }

    @Test
    fun `currency chip opens the sheet and a pick is reported`() {
        show(loaded())
        rule.onNodeWithTag(ConverterTags.CURRENCY_CHIP).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(L10nR.string.convSelectCurrency)).assertExists()
        rule.onNodeWithText("CHF · ${context.getString(L10nR.string.curCHF)}").performClick()
        rule.waitForIdle()
        assertEquals(ConverterAction.PickCurrency("CHF"), actions.last())
    }

    @Test
    fun `currency sheet marks the current currency as the selected radio`() {
        show(loaded(currency = "CHF"))
        rule.onNodeWithTag(ConverterTags.CURRENCY_CHIP).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ConverterTags.currencyRow("CHF"))
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        rule.onNodeWithText("CHF · ${context.getString(L10nR.string.curCHF)}").assertExists()
        rule.onNodeWithTag(ConverterTags.currencyRow("EUR")).assertIsNotSelected()
    }

    @Test
    fun `premium sign button toggles the sign`() {
        show(loaded())
        rule.onNodeWithContentDescription(context.getString(R.string.native_bitcoin_toggle_sign)).performScrollTo().performClick()
        assertEquals(ConverterAction.TogglePremiumSign, actions.last())
    }

    @Test
    fun `premium slider is named`() {
        show(loaded())
        rule.onNodeWithTag(ConverterTags.SLIDER)
            .assert(hasContentDescription(context.getString(L10nR.string.convPremiumTitle)))
    }

    @Test
    fun `premium result appears once there is a base amount`() {
        show(loaded().onFiatChanged("100").onSlider(2.5))
        rule.onNodeWithTag(ConverterTags.PREMIUM_RESULT).performScrollTo().assertTextEquals("102.50 EUR")
        rule.onNodeWithText(context.getString(L10nR.string.convPremiumBase)).assertExists()
        rule.onNodeWithText("100.00 EUR").assertExists()
    }

    @Test
    fun `premium result is hidden without an amount`() {
        show(loaded())
        rule.onNodeWithTag(ConverterTags.PREMIUM_RESULT).assertDoesNotExist()
        rule.onNodeWithText(context.getString(L10nR.string.convPremiumTitle)).assertExists()
    }

    @Test
    fun `premium field reports typed text`() {
        show(loaded())
        rule.onNodeWithTag(ConverterTags.PREMIUM_FIELD).performScrollTo().performTextInput("-2")
        assertTrue(actions.last() is ConverterAction.PremiumChanged)
        assertEquals("-2", (actions.last() as ConverterAction.PremiumChanged).text)
    }
}
