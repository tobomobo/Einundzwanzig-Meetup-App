package space.einundzwanzig.meetup.feature.profile.common

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/** Spec 07 §2.1: the relay test result replaces "Verbindung wird geprüft …" immediately. */
@RunWith(AndroidJUnit4::class)
class SnackbarEffectTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `replaceCurrent drops a message that was sent right before and not shown yet`() {
        val channel = Channel<SnackbarMessage>(Channel.BUFFERED)
        lateinit var host: SnackbarHostState
        // Both messages are in the channel before the effect collects (INVALID_URL returns without suspending).
        channel.trySend(SnackbarMessage(UiText.res(L10nR.string.rsTesting), Tone.Neutral, AppSnackbarDurations.Long))
        channel.trySend(SnackbarMessage(UiText.res(L10nR.string.rsInvalidUrl), Tone.Error, replaceCurrent = true))
        rule.setContent {
            EinundzwanzigTheme {
                host = remember { SnackbarHostState() }
                SnackbarEffect(channel.receiveAsFlow(), host)
                AppSnackbarHost(host)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(L10nR.string.rsInvalidUrl)).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.rsTesting)).assertDoesNotExist()
        assertEquals(context.getString(L10nR.string.rsInvalidUrl), host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `replaceCurrent dismisses the visible snackbar`() {
        val channel = Channel<SnackbarMessage>(Channel.BUFFERED)
        lateinit var host: SnackbarHostState
        rule.setContent {
            EinundzwanzigTheme {
                host = remember { SnackbarHostState() }
                SnackbarEffect(channel.receiveAsFlow(), host)
                AppSnackbarHost(host)
            }
        }
        channel.trySend(SnackbarMessage(UiText.res(L10nR.string.rsTesting), Tone.Neutral, AppSnackbarDurations.Long))
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(L10nR.string.rsTesting)).assertExists()
        channel.trySend(SnackbarMessage(UiText.res(L10nR.string.rsRelayAdded), Tone.Success, replaceCurrent = true))
        rule.waitForIdle()
        assertEquals(context.getString(L10nR.string.rsRelayAdded), host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `messages without replaceCurrent queue`() {
        val channel = Channel<SnackbarMessage>(Channel.BUFFERED)
        lateinit var host: SnackbarHostState
        channel.trySend(SnackbarMessage(UiText.Raw("eins")))
        channel.trySend(SnackbarMessage(UiText.Raw("zwei")))
        rule.setContent {
            EinundzwanzigTheme {
                host = remember { SnackbarHostState() }
                SnackbarEffect(channel.receiveAsFlow(), host)
                AppSnackbarHost(host)
            }
        }
        rule.waitForIdle()
        assertEquals("eins", host.currentSnackbarData?.visuals?.message)
    }
}
