package space.einundzwanzig.meetup.feature.bitcoin.dashboard

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertTrue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.mempool.format.BtcFormat
import space.einundzwanzig.meetup.feature.bitcoin.dashboard
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class BitcoinDashboardScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<BitcoinDashboardAction>()

    private fun show(state: BitcoinDashboardUiState) {
        rule.setContent { EinundzwanzigTheme { BitcoinDashboardScreen(state) { actions += it } } }
    }

    @Test
    fun `no data while loading shows only the loading indicator`() {
        show(BitcoinDashboardUiState(data = null, loading = true))
        rule.onNodeWithTag(DashboardTags.BLOCK_HEIGHT).assertDoesNotExist()
        rule.onNodeWithText("Bitcoin").assertExists()
    }

    @Test
    fun `live data shows block height, rows, lightning and footer`() {
        show(BitcoinDashboardUiState(data = dashboard(), loading = false, currency = "CHF", hostLabel = "mempool.space"))
        rule.onNodeWithTag(DashboardTags.BLOCK_HEIGHT).assertTextEquals("912.345")
        rule.onNodeWithText("3·8·12").assertExists()
        rule.onNodeWithText("CHF/BTC").assertExists()
        rule.onNodeWithText("89.000").assertExists()
        rule.onNodeWithText("+3.7% / 1234 blk").assertExists()
        rule.onNodeWithText("812 EH/s").assertExists()
        rule.onNodeWithText("KAPAZITÄT").performScrollTo()
        rule.onNodeWithText("5.123 BTC").assertExists()
        rule.onNodeWithText("${context.getString(L10nR.string.dashSource)}: mempool.space").performScrollTo().assertExists()
        rule.onNodeWithTag(DashboardTags.ERROR_BANNER).assertDoesNotExist()
    }

    @Test
    fun `live data shows the update time instead of dashes`() {
        val data = dashboard()
        show(BitcoinDashboardUiState(data = data, loading = false))
        rule.onNodeWithTag(DashboardTags.TIME).assertTextEquals(BtcFormat.updatedLabel(data))
        val shown = BtcFormat.updatedLabel(data)
        assertTrue(shown, Regex("\\d{2}:\\d{2}").matches(shown))
    }

    @Test
    fun `pull to refresh reports a pull refresh`() {
        show(BitcoinDashboardUiState(data = dashboard(), loading = false))
        rule.onNodeWithTag(DashboardTags.CONTENT).performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 400) }
        rule.waitForIdle()
        assertEquals(listOf<BitcoinDashboardAction>(BitcoinDashboardAction.PullRefresh), actions)
    }

    @Test
    fun `partial data shows the partial note`() {
        show(BitcoinDashboardUiState(data = dashboard(sourcesOk = 4), loading = false))
        rule.onNodeWithText("${context.getString(L10nR.string.dashPartial)} (4/6)").assertExists()
    }

    @Test
    fun `blocked data shows the blocked banner, error code and the dashed time`() {
        show(BitcoinDashboardUiState(data = dashboard(sourcesOk = 0, blocked = true, lastError = "HTTP 403"), loading = false))
        rule.onNodeWithTag(DashboardTags.ERROR_BANNER).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.dashBlockedTitle)).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.dashBlockedBody)).assertExists()
        rule.onNodeWithText("HTTP 403").assertExists()
        rule.onNodeWithTag(DashboardTags.TIME).assertTextEquals("--:--")
    }

    @Test
    fun `offline data shows the offline banner and change server navigates`() {
        show(BitcoinDashboardUiState(data = dashboard(sourcesOk = 0), loading = false))
        rule.onNodeWithText(context.getString(L10nR.string.dashOfflineTitle)).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.dashChangeServer)).performClick()
        assertEquals(listOf<BitcoinDashboardAction>(BitcoinDashboardAction.ChangeServer), actions)
    }

    @Test
    fun `unknown height shows dashes`() {
        show(BitcoinDashboardUiState(data = dashboard(height = 0, sourcesOk = 3), loading = false))
        rule.onNodeWithTag(DashboardTags.BLOCK_HEIGHT).assertTextEquals("––")
    }

    @Test
    fun `refresh button triggers a refresh`() {
        show(BitcoinDashboardUiState(data = dashboard(), loading = false))
        rule.onNodeWithContentDescription(context.getString(L10nR.string.convRefresh)).performClick()
        assertEquals(listOf<BitcoinDashboardAction>(BitcoinDashboardAction.Refresh), actions)
    }
}
