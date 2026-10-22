package space.einundzwanzig.meetup.feature.bitcoin.settings

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.feature.bitcoin.FakeLog
import space.einundzwanzig.meetup.feature.bitcoin.FakeMempoolConfig
import space.einundzwanzig.meetup.feature.bitcoin.FakeMempoolService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.mempool.HostTestFailure
import space.einundzwanzig.meetup.data.mempool.MempoolHosts
import space.einundzwanzig.meetup.data.mempool.MempoolMode
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class MempoolSettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<MempoolSettingsAction>()

    private fun show(state: MempoolSettingsUiState) {
        rule.setContent {
            EinundzwanzigTheme { MempoolSettingsScreen(state, SnackbarHostState()) { actions += it } }
        }
    }

    @Test
    fun `clearnet shows three options, no tor hint and no custom field`() {
        show(MempoolSettingsUiState())
        rule.onNodeWithText(context.getString(L10nR.string.mempoolIntro)).assertExists()
        rule.onNodeWithTag(MempoolTags.option(MempoolMode.CLEARNET)).assertIsSelected()
        rule.onNodeWithText("mempool.space").assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.mempoolTorTitle)).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.mempoolCustomTitle)).assertExists()
        rule.onNodeWithTag(MempoolTags.TOR_HINT).assertDoesNotExist()
        rule.onNodeWithTag(MempoolTags.CUSTOM_FIELD).assertDoesNotExist()
    }

    @Test
    fun `tor shows the tor hint`() {
        show(MempoolSettingsUiState(mode = MempoolMode.TOR))
        rule.onNodeWithTag(MempoolTags.TOR_HINT).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.mempoolTorHint)).assertExists()
    }

    @Test
    fun `tapping an option selects it`() {
        show(MempoolSettingsUiState())
        rule.onNodeWithTag(MempoolTags.option(MempoolMode.TOR)).performClick()
        assertEquals(listOf<MempoolSettingsAction>(MempoolSettingsAction.Select(MempoolMode.TOR)), actions)
    }

    @Test
    fun `custom shows the url field and save button`() {
        show(MempoolSettingsUiState(mode = MempoolMode.CUSTOM, customText = "https://node.local"))
        rule.onNodeWithTag(MempoolTags.CUSTOM_FIELD).assertExists()
        rule.onNodeWithTag(MempoolTags.CUSTOM_FIELD).performTextInput("x")
        assertTrue(actions.last() is MempoolSettingsAction.CustomChanged)
        rule.onNodeWithText(context.getString(L10nR.string.mempoolSave)).performScrollTo().performClick()
        assertEquals(MempoolSettingsAction.SaveCustom, actions.last())
    }

    @Test
    fun `ime done on the custom url saves`() {
        show(MempoolSettingsUiState(mode = MempoolMode.CUSTOM, customText = "https://node.local"))
        rule.onNodeWithTag(MempoolTags.CUSTOM_FIELD).performImeAction()
        assertEquals(MempoolSettingsAction.SaveCustom, actions.last())
    }

    @Test
    fun `route shows the saved snackbar after saving`() {
        val config = FakeMempoolConfig()
        val vm = MempoolSettingsViewModel(config, FakeMempoolService(), CoroutineScope(Dispatchers.Unconfined), FakeLog())
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides NoopNavigator) { MempoolSettingsRoute(vm) }
            }
        }
        rule.onNodeWithTag(MempoolTags.option(MempoolMode.CUSTOM)).performClick()
        rule.onNodeWithTag(MempoolTags.CUSTOM_FIELD).performTextInput("node.local")
        rule.onNodeWithText(context.getString(L10nR.string.mempoolSave)).performScrollTo().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(context.getString(L10nR.string.mempoolSaved)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(context.getString(L10nR.string.mempoolSaved)).assertExists()
        assertEquals(MempoolHosts.normalize("node.local"), config.persistedHost)
    }

    private object NoopNavigator : Navigator {
        override fun navigate(key: NavKey) = Unit
        override fun replaceTop(key: NavKey) = Unit
        override fun back() = Unit
        override fun backTo(key: NavKey, inclusive: Boolean) = Unit
        override fun resetTo(key: NavKey) = Unit
        override fun navigateFromRoot(key: NavKey) = Unit
        override fun openUrl(url: String) = Unit
    }

    @Test
    fun `test button starts a connection test`() {
        show(MempoolSettingsUiState())
        rule.onNodeWithText(context.getString(L10nR.string.mempoolTest)).performScrollTo().performClick()
        assertEquals(listOf<MempoolSettingsAction>(MempoolSettingsAction.Test), actions)
    }

    @Test
    fun `testing disables the button and shows the testing label`() {
        show(MempoolSettingsUiState(testing = true))
        rule.onNodeWithText(context.getString(L10nR.string.mempoolTesting)).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.mempoolTest)).assertDoesNotExist()
    }

    @Test
    fun `ok result shows the unformatted height`() {
        show(MempoolSettingsUiState(testResult = MempoolTestResult.Ok(912_345)))
        rule.onNodeWithText("${context.getString(L10nR.string.mempoolTestOk)} · Block 912345").performScrollTo().assertExists()
    }

    @Test
    fun `failure texts follow the classification`() {
        val fail = context.getString(L10nR.string.mempoolTestFail)
        val cases = listOf(
            MempoolTestResult.Failed(HostTestFailure.Blocked(403)) to "${context.getString(L10nR.string.mempoolTestBlocked)} (HTTP 403)",
            MempoolTestResult.Failed(HostTestFailure.Http(500)) to "$fail — HTTP 500",
            MempoolTestResult.Failed(HostTestFailure.OnionUnreachable) to context.getString(L10nR.string.mempoolTestOnionFail),
            MempoolTestResult.Failed(HostTestFailure.Other("UnknownHostException")) to "$fail — UnknownHostException",
            MempoolTestResult.InvalidUrl to context.getString(L10nR.string.mempoolInvalidUrl),
        )
        var result by mutableStateOf<MempoolTestResult>(cases.first().first)
        rule.setContent {
            EinundzwanzigTheme { MempoolSettingsScreen(MempoolSettingsUiState(testResult = result), SnackbarHostState()) {} }
        }
        for ((r, text) in cases) {
            result = r
            rule.waitForIdle()
            rule.onNodeWithText(text).assertExists()
        }
    }

    @Test
    fun `footer shows the active host`() {
        show(MempoolSettingsUiState(activeHost = MempoolHosts.TOR))
        rule.onNodeWithTag(MempoolTags.ACTIVE).performScrollTo()
            .assertTextEquals("${context.getString(L10nR.string.mempoolActive)}:\n${MempoolHosts.TOR}")
    }
}
