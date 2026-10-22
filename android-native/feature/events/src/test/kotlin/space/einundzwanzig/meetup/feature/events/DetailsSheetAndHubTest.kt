package space.einundzwanzig.meetup.feature.events

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.feature.events.common.DETAILS_SHEET_TAG
import space.einundzwanzig.meetup.feature.events.common.DetailsSheetLayout
import space.einundzwanzig.meetup.feature.events.common.EventDetailsSheet
import space.einundzwanzig.meetup.feature.events.hub.EventsHubAction
import space.einundzwanzig.meetup.feature.events.hub.EventsHubScreen

/** Port of `event_details_sheet_test.dart` (bounded sheet, spec 10 §2.7) and the hub (§2.1). */
@RunWith(AndroidJUnit4::class)
class DetailsSheetAndHubTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `short content keeps the sheet compact`() {
        rule.setContent {
            EinundzwanzigTheme {
                Box(Modifier.size(402.dp, 874.dp)) {
                    DetailsSheetLayout(onClose = {}) { Text("Eine Zeile") }
                }
            }
        }
        val bounds = rule.onNodeWithTag(DETAILS_SHEET_TAG).getUnclippedBoundsInRoot()
        assertTrue((bounds.bottom - bounds.top).value < 200f)
    }

    @Test
    fun `long content is bounded to 90 percent of the height`() {
        rule.setContent {
            EinundzwanzigTheme {
                Box(Modifier.size(320.dp, 568.dp)) {
                    DetailsSheetLayout(onClose = {}) { repeat(80) { Text("Zeile $it") } }
                }
            }
        }
        val bounds = rule.onNodeWithTag(DETAILS_SHEET_TAG).getUnclippedBoundsInRoot()
        assertTrue((bounds.bottom - bounds.top).value <= 568f * 0.9f + 0.5f)
    }

    @Test
    fun `close button has the actionClose description and closes`() {
        var closed = 0
        rule.setContent {
            EinundzwanzigTheme { DetailsSheetLayout(onClose = { closed++ }) { Text("x") } }
        }
        rule.onNodeWithContentDescription(context.getString(L10nR.string.actionClose))
            .assertHasClickAction()
            .performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `modal details sheet shows its content and close button`() {
        var closed = 0
        rule.setContent {
            EinundzwanzigTheme { EventDetailsSheet(onDismiss = { closed++ }) { Text("Inhalt") } }
        }
        rule.onNodeWithText("Inhalt").assertExists()
        rule.onNodeWithContentDescription(context.getString(L10nR.string.actionClose)).performClick()
        // The close button runs the hide animation before dismissing.
        rule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun `hub shows both cards and opens them`() {
        val actions = mutableListOf<EventsHubAction>()
        rule.setContent { EinundzwanzigTheme { EventsHubScreen { actions += it } } }
        rule.onNodeWithText(context.getString(L10nR.string.hubMeetupsSub)).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.hubMeetups)).performClick()
        rule.onNodeWithText(context.getString(L10nR.string.hubCalendar)).performClick()
        assertEquals(listOf(EventsHubAction.OPEN_MEETUPS, EventsHubAction.OPEN_CALENDAR), actions)
    }
}
