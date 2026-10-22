package space.einundzwanzig.meetup.feature.badges

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import space.einundzwanzig.meetup.core.ui.nostr.NpubFormat
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.issuance.session.SessionRemaining
import space.einundzwanzig.meetup.feature.badges.rollingqr.RollingQrAction
import space.einundzwanzig.meetup.feature.badges.rollingqr.RollingQrScreen
import space.einundzwanzig.meetup.feature.badges.rollingqr.RollingQrTags
import space.einundzwanzig.meetup.feature.badges.rollingqr.RollingQrUiState
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class RollingQrScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<RollingQrAction>()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private val npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsx9zf3"

    private fun active(secondsLeft: Int = 7) = RollingQrUiState(
        loading = false,
        session = session(expiresAt = 4 * 3600),
        qr = "{\"v\":2}",
        secondsLeft = secondsLeft,
        remaining = SessionRemaining.HoursMinutes(3, 12),
        npub = npub,
    )

    private fun show(state: RollingQrUiState) {
        rule.setContent { EinundzwanzigTheme { RollingQrScreen(state, onAction = { actions += it }) } }
    }

    @Test
    fun `active session shows bar, QR, countdown and footer`() {
        show(active())
        rule.onNodeWithText(s(L10nR.string.rqTitle)).assertExists()
        rule.onNodeWithText("📍 Würzburg, DE").assertExists()
        rule.onNodeWithText(s(L10nR.string.rqSessionRemaining, s(R.string.native_remaining_hm, 3, 12))).assertExists()
        rule.onNodeWithText(s(L10nR.string.rqActive)).assertExists()
        rule.onNodeWithTag(RollingQrTags.QR).assertExists()
        rule.onNodeWithText("7").assertExists()
        rule.onNodeWithText(s(L10nR.string.rqNextCodeIn)).assertExists()
        rule.onNodeWithText(s(R.string.native_rq_block, "915000")).assertExists()
        rule.onNodeWithText(NpubFormat.shortenNpub(npub)).assertExists()
    }

    @Test
    fun `last three seconds announce the renewal`() {
        show(active(secondsLeft = 3))
        rule.onNodeWithText(s(L10nR.string.rqCodeRenewing)).assertExists()
    }

    @Test
    fun `stop icon asks for confirmation`() {
        show(active())
        rule.onNodeWithContentDescription(s(L10nR.string.rqEndSession)).performClick()
        assertEquals(listOf<RollingQrAction>(RollingQrAction.EndClicked), actions)
    }

    @Test
    fun `confirm dialog shows remaining time and ends the session`() {
        show(active().copy(confirmEnd = true))
        rule.onNodeWithText(s(L10nR.string.rqEndSessionQ)).assertExists()
        val body = s(L10nR.string.rqRemainingTime, s(R.string.native_remaining_hm, 3, 12)) + s(L10nR.string.rqEndSessionBody)
        rule.onNodeWithText(body).assertExists()
        rule.onNodeWithText(s(L10nR.string.rqEnd)).performClick()
        rule.onNodeWithText(s(L10nR.string.apCancel)).performClick()
        assertEquals(listOf(RollingQrAction.EndConfirmed, RollingQrAction.EndDismissed), actions)
    }

    @Test
    fun `no session shows the empty state with the way back`() {
        show(RollingQrUiState(loading = false))
        rule.onNodeWithTag(RollingQrTags.EMPTY).assertExists()
        rule.onNodeWithText(s(L10nR.string.rqNoActiveSession)).assertExists()
        rule.onNodeWithText(s(L10nR.string.rqNoSessionBody)).assertExists()
        rule.onNodeWithContentDescription(s(L10nR.string.rqEndSession)).assertDoesNotExist()
        rule.onNodeWithText(s(L10nR.string.rqBackToAdmin)).performScrollTo().performClick()
        assertEquals(listOf<RollingQrAction>(RollingQrAction.Back), actions)
    }

    @Test
    fun `loading shows neither session nor empty state`() {
        show(RollingQrUiState())
        rule.onNodeWithTag(RollingQrTags.EMPTY).assertDoesNotExist()
        rule.onNodeWithTag(RollingQrTags.QR).assertDoesNotExist()
    }
}
