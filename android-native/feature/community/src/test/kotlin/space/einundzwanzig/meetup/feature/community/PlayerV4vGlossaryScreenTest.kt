package space.einundzwanzig.meetup.feature.community

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import space.einundzwanzig.meetup.data.community.glossary.Glossary
import space.einundzwanzig.meetup.data.community.plebrap.PlebSongs
import space.einundzwanzig.meetup.data.community.v4v.V4vErrorKind
import space.einundzwanzig.meetup.feature.community.glossary.GlossaryAction
import space.einundzwanzig.meetup.feature.community.glossary.GlossaryScreen
import space.einundzwanzig.meetup.feature.community.glossary.GlossaryTestTags
import space.einundzwanzig.meetup.feature.community.glossary.GlossaryUiState
import space.einundzwanzig.meetup.feature.community.plebrap.PlebrapAction
import space.einundzwanzig.meetup.feature.community.plebrap.PlebrapScreen
import space.einundzwanzig.meetup.feature.community.plebrap.PlebrapTestTags
import space.einundzwanzig.meetup.feature.community.plebrap.PlebrapUiState
import space.einundzwanzig.meetup.feature.community.v4v.V4vTestTags
import space.einundzwanzig.meetup.feature.community.v4v.ValueForValueAction
import space.einundzwanzig.meetup.feature.community.v4v.ValueForValueScreen
import space.einundzwanzig.meetup.feature.community.v4v.ValueForValueUiState
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class PlayerV4vGlossaryScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)

    // --- PlebRap --------------------------------------------------------------------------------

    @Test
    fun `player without song shows pick hint and disabled skip buttons`() {
        rule.setContent { EinundzwanzigTheme { PlebrapScreen(PlebrapUiState(), {}, {}) } }
        rule.onNodeWithText(s(L10nR.string.prPickSong)).assertExists()
        rule.onNodeWithText(PlebSongs.FALLBACK_ARTIST).assertExists()
        rule.onNodeWithTag(PlebrapTestTags.PREV).assertIsNotEnabled()
        rule.onNodeWithTag(PlebrapTestTags.NEXT).assertIsNotEnabled()
        rule.onNodeWithTag(PlebrapTestTags.SLIDER).assertIsNotEnabled()
    }

    @Test
    fun `player controls and track list dispatch actions`() {
        val actions = mutableListOf<PlebrapAction>()
        rule.setContent {
            EinundzwanzigTheme {
                PlebrapScreen(PlebrapUiState(index = 1, durationMs = 100_000, seekable = true), { actions += it }, {})
            }
        }
        rule.onNodeWithTag(PlebrapTestTags.SLIDER).assertIsEnabled()
        rule.onNodeWithTag(PlebrapTestTags.PREV).performClick()
        rule.onNodeWithTag(PlebrapTestTags.PLAY).performClick()
        rule.onNodeWithTag(PlebrapTestTags.NEXT).performClick()
        rule.onNodeWithText(s(L10nR.string.prV4V)).performClick()
        rule.onNodeWithTag(PlebrapTestTags.LIST).performScrollToNode(androidx.compose.ui.test.hasTestTag(PlebrapTestTags.track(3)))
        rule.onNodeWithTag(PlebrapTestTags.track(3)).performClick()
        assertEquals(
            listOf(PlebrapAction.Prev, PlebrapAction.Toggle, PlebrapAction.Next, PlebrapAction.OpenV4v, PlebrapAction.PlayIndex(3)),
            actions,
        )
    }

    @Test
    fun `player shows caching hint while not seekable and spinner while loading`() {
        rule.setContent {
            EinundzwanzigTheme { PlebrapScreen(PlebrapUiState(index = 0, loading = true, cacheProgress = 0.42f), {}, {}) }
        }
        rule.onNodeWithTag(PlebrapTestTags.CACHING).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.prCaching, 42)).assertExists()
        rule.onNodeWithTag(PlebrapTestTags.PLAY_LOADING, useUnmergedTree = true).assertExists()
    }

    // --- V4V ------------------------------------------------------------------------------------

    @Test
    fun `v4v shows texts, recipient and dispatches typing and donate`() {
        val actions = mutableListOf<ValueForValueAction>()
        rule.setContent { EinundzwanzigTheme { ValueForValueScreen(ValueForValueUiState(), { actions += it }, {}) } }
        rule.onAllNodesWithText(s(L10nR.string.v4vHeadline)).onFirst().assertExists()
        rule.onNodeWithText(s(L10nR.string.v4vAmountLabel).uppercase()).assertExists()
        rule.onNodeWithText("${s(L10nR.string.v4vRecipient)}: wolpertinger1@timecatcher.lnbits.de").assertExists()
        rule.onNodeWithTag(V4vTestTags.AMOUNT).performTextInput("21")
        rule.onNodeWithTag(V4vTestTags.DONATE).performScrollTo().performClick()
        assertEquals(listOf(ValueForValueAction.AmountChanged("21"), ValueForValueAction.Donate), actions)
    }

    @Test
    fun `v4v shows the inline error`() {
        rule.setContent {
            EinundzwanzigTheme { ValueForValueScreen(ValueForValueUiState(amount = "1", error = V4vErrorKind.BelowMin), {}, {}) }
        }
        rule.onNodeWithText(s(L10nR.string.v4vErrBelowMin)).assertExists()
    }

    @Test
    fun `v4v loading hides the label and disables the button`() {
        rule.setContent { EinundzwanzigTheme { ValueForValueScreen(ValueForValueUiState(amount = "21", loading = true), {}, {}) } }
        rule.onNodeWithTag(V4vTestTags.DONATE).assertIsNotEnabled()
        rule.onNodeWithText(s(L10nR.string.v4vDonateButton)).assertDoesNotExist()
        rule.onNodeWithTag(V4vTestTags.LOADING, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `v4v no wallet sheet copies the invoice`() {
        val actions = mutableListOf<ValueForValueAction>()
        rule.setContent {
            EinundzwanzigTheme { ValueForValueScreen(ValueForValueUiState(noWalletInvoice = "lnbc21n1xyz"), { actions += it }, {}) }
        }
        rule.onNodeWithText(s(L10nR.string.v4vNoWalletTitle)).assertExists()
        rule.onNodeWithText("lnbc21n1xyz").assertExists()
        rule.onNodeWithText(s(L10nR.string.v4vCopyInvoice)).performClick()
        // The sheet animates out first, then the copy action is dispatched.
        rule.waitForIdle()
        assertEquals(listOf<ValueForValueAction>(ValueForValueAction.CopyInvoice), actions)
    }

    // --- Glossary -------------------------------------------------------------------------------

    private val entries by lazy { Glossary.resolve(context::getString) }

    @Test
    fun `glossary lists categories and toggles entries`() {
        val actions = mutableListOf<GlossaryAction>()
        rule.setContent { EinundzwanzigTheme { GlossaryScreen(GlossaryUiState(), entries, { actions += it }, {}) } }
        rule.onNodeWithText(s(L10nR.string.glCatStart).uppercase()).assertExists()
        val first = entries.first()
        rule.onNodeWithText(first.body).assertDoesNotExist()
        rule.onNodeWithText(first.title).performClick()
        assertEquals(listOf<GlossaryAction>(GlossaryAction.Toggle(first.title, true)), actions)
    }

    @Test
    fun `glossary search expands hits and clear button clears`() {
        val actions = mutableListOf<GlossaryAction>()
        val first = entries.first()
        rule.setContent { EinundzwanzigTheme { GlossaryScreen(GlossaryUiState(query = first.title), entries, { actions += it }, {}) } }
        rule.onNodeWithText(first.body).assertExists()
        rule.onNodeWithContentDescription(context.getString(R.string.native_community_clear_search)).performClick()
        assertTrue(actions.contains(GlossaryAction.ClearQuery))
    }

    @Test
    fun `glossary without hits shows no results`() {
        rule.setContent { EinundzwanzigTheme { GlossaryScreen(GlossaryUiState(query = "zzzqqqxx"), entries, {}, {}) } }
        rule.onNodeWithTag(GlossaryTestTags.NO_RESULTS).assertExists()
        rule.onNodeWithText(s(L10nR.string.glNoResults)).assertExists()
    }

    @Test
    fun `glossary typing dispatches query changes`() {
        val actions = mutableListOf<GlossaryAction>()
        rule.setContent { EinundzwanzigTheme { GlossaryScreen(GlossaryUiState(), entries, { actions += it }, {}) } }
        rule.onNodeWithTag(GlossaryTestTags.SEARCH).performTextInput("relay")
        assertEquals(listOf<GlossaryAction>(GlossaryAction.QueryChanged("relay")), actions)
    }
}
