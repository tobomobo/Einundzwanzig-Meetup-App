package space.einundzwanzig.meetup.feature.home

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.feature.home.tiles.TileLayoutEngine
import space.einundzwanzig.meetup.feature.home.ui.CustomizeContent
import space.einundzwanzig.meetup.feature.home.ui.HomeTags
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de-w411dp-h1600dp")
class SheetsTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `scan sheet offers badge and reputation`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            EinundzwanzigTheme { ScanSheetContent(onScanBadge = { clicks += "badge" }, onCheckReputation = { clicks += "rep" }) }
        }
        rule.onNodeWithText(context.getString(L10nR.string.scanBadge)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(L10nR.string.scanReputationSub)).assertIsDisplayed()
        rule.onNodeWithTag(ScanSheetTags.BADGE).performClick()
        rule.onNodeWithTag(ScanSheetTags.REPUTATION).performClick()
        assertEquals(listOf("badge", "rep"), clicks)
    }

    @Test
    fun `customize sheet hides and adds tiles and saves only with done`() {
        var saved: Pair<List<String>, Set<String>>? = null
        rule.setContent {
            EinundzwanzigTheme {
                CustomizeContent(
                    initialOrder = TileLayoutEngine.DEFAULT_ORDER,
                    initialHidden = TileLayoutEngine.DEFAULT_HIDDEN,
                    isVisible = { true },
                    onDone = { o, h -> saved = o to h },
                )
            }
        }
        rule.onNodeWithText(context.getString(R.string.native_home_customize_title)).assertIsDisplayed()
        // Locked rows have no switch.
        rule.onAllNodes(hasAnyAncestor(hasTestTag(HomeTags.customizeRow("home_meetup"))) and isToggleable()).let {
            assertEquals(0, it.fetchSemanticsNodes().size)
        }
        // Hide "community", add "news".
        rule.onAllNodes(hasAnyAncestor(hasTestTag(HomeTags.customizeRow("community"))) and isToggleable()).onFirst()
            .performClick()
        rule.onAllNodes(hasAnyAncestor(hasTestTag(HomeTags.customizeRow("news"))) and isToggleable()).onFirst()
            .performClick()
        assertEquals(null, saved)
        rule.onNodeWithTag(HomeTags.CUSTOMIZE_DONE).performClick()
        val (order, hidden) = saved!!
        assertTrue("community" in hidden)
        assertFalse("news" in hidden)
        assertEquals(TileLayoutEngine.DEFAULT_ORDER, order)
    }

    @Test
    fun `customize sheet reorders through the accessibility actions`() {
        var saved: List<String>? = null
        rule.setContent {
            EinundzwanzigTheme {
                CustomizeContent(
                    initialOrder = TileLayoutEngine.DEFAULT_ORDER,
                    initialHidden = TileLayoutEngine.DEFAULT_HIDDEN,
                    isVisible = { true },
                    onDone = { o, _ -> saved = o },
                )
            }
        }
        val up = context.getString(R.string.native_home_move_up)
        val node = rule.onNodeWithTag(HomeTags.customizeRow("reputation"))
        val action = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .first { it.label == up }
        rule.runOnIdle { action.action() }
        rule.onNodeWithTag(HomeTags.CUSTOMIZE_DONE).performClick()
        assertEquals(listOf("home_meetup", "reputation", "event_chats"), saved!!.take(3))
    }
}
