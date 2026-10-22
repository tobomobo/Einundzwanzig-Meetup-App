package space.einundzwanzig.meetup.feature.chat

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val actions = mutableListOf<ChatAction>()

    private val today = LocalDate.of(2026, 10, 21)
    private val clock = ChatClock(today, ZoneOffset.UTC)
    private fun at(day: LocalDate, hour: Int, minute: Int): Instant = day.atTime(hour, minute).toInstant(ZoneOffset.UTC)

    private val messages = listOf(
        ChatMessageItem("1", BOB, "Wer kommt heute?", false, "Bob", "B", at(today, 18, 2)),
        ChatMessageItem("2", ME, "Ich bin dabei", true, "", "A", at(today, 18, 5)),
    )

    private fun show(state: ChatUiState) {
        rule.setContent {
            EinundzwanzigTheme {
                ChatScreen(state = state, onAction = { actions += it }, onBack = {}, clock = clock)
            }
        }
    }

    @Test
    fun `loading shows the indicator and no footer actions`() {
        show(ChatUiState(isEvent = false, title = "Wien"))
        rule.onNodeWithTag(ChatTestTags.LOADING).assertIsDisplayed()
        rule.onNodeWithContentDescription(str(L10nR.string.loading)).assertIsDisplayed()
        rule.onNodeWithTag(ChatTestTags.JOIN).assertDoesNotExist()
        rule.onNodeWithTag(ChatTestTags.INPUT).assertDoesNotExist()
        rule.onNodeWithText("Wien").assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.chatRelayHint)).assertIsDisplayed()
    }

    @Test
    fun `empty room for non-members shows join footer`() {
        show(ChatUiState(isEvent = false, title = "Wien", loading = false, member = false))
        rule.onNodeWithText(str(L10nR.string.chatEmpty)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.chatJoinHint)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.chatMemberHint)).assertIsDisplayed()
        rule.onNodeWithTag(ChatTestTags.INPUT).assertDoesNotExist()
        rule.onNodeWithTag(ChatTestTags.MEMBER_LINK).performClick()
        rule.onNodeWithText(str(L10nR.string.chatJoin)).performClick()
        assertEquals(listOf(ChatAction.OpenVerein, ChatAction.Join), actions)
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun `small screen at font scale 2 keeps messages and the join button visible`() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                EinundzwanzigTheme {
                    ChatScreen(
                        state = ChatUiState(isEvent = false, title = "Wien", loading = false, member = false, messages = messages),
                        onAction = { actions += it },
                        onBack = {},
                        clock = clock,
                    )
                }
            }
        }
        rule.onNodeWithText("Ich bin dabei").assertIsDisplayed()
        // The capped footer scrolls; the join button stays reachable.
        rule.onNodeWithTag(ChatTestTags.JOIN).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(ChatAction.Join, actions.last())
    }

    @Test
    fun `joining disables the join button`() {
        show(ChatUiState(isEvent = false, title = "Wien", loading = false, member = false, joining = true))
        rule.onNodeWithTag(ChatTestTags.JOIN).assertIsNotEnabled()
        rule.onNodeWithText(str(L10nR.string.chatJoin)).assertDoesNotExist()
        // The spinner replaces the text, the button stays labelled for TalkBack.
        rule.onNodeWithContentDescription(str(L10nR.string.chatJoin)).assertIsNotEnabled()
    }

    @Test
    fun `empty event thread shows event texts and composer`() {
        show(ChatUiState(isEvent = true, title = "Workshop", loading = false, member = true))
        rule.onNodeWithText(str(L10nR.string.chatEventEmpty)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.chatEventHint)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.chatPlaceholder)).assertIsDisplayed()
        rule.onNodeWithTag(ChatTestTags.JOIN).assertDoesNotExist()
    }

    @Test
    fun `messages render sender, own label, time and content`() {
        show(ChatUiState(isEvent = false, title = "Wien", loading = false, member = true, messages = messages))
        rule.onNodeWithText("Wer kommt heute?").assertIsDisplayed()
        rule.onNodeWithText("Bob").assertIsDisplayed()
        rule.onNodeWithText("18:02").assertIsDisplayed()
        rule.onNodeWithText("Ich bin dabei").assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.chatYou)).assertIsDisplayed()
        rule.onNodeWithText("Bob").performClick()
        assertEquals(ChatAction.ShowAuthor(BOB), actions.last())
    }

    @Test
    fun `composer forwards input, ime send and send button`() {
        show(ChatUiState(isEvent = false, title = "Wien", loading = false, member = true, messages = messages))
        rule.onNodeWithTag(ChatTestTags.INPUT).performTextInput("Servus")
        assertTrue(actions.any { it is ChatAction.InputChanged && it.text == "Servus" })
        rule.onNodeWithTag(ChatTestTags.INPUT).performImeAction()
        rule.onNodeWithContentDescription(str(R.string.native_chat_send)).performClick()
        assertEquals(2, actions.count { it == ChatAction.Send })
    }

    @Test
    fun `sending disables the send button`() {
        show(ChatUiState(isEvent = true, title = "Workshop", loading = false, member = true, sending = true, input = "x"))
        rule.onNodeWithTag(ChatTestTags.SEND).assertIsNotEnabled()
        rule.onNodeWithContentDescription(str(R.string.native_chat_send)).assertIsNotEnabled()
    }

    @Test
    fun `author sheet shows name and copies the npub`() {
        val npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        show(
            ChatUiState(
                isEvent = false,
                title = "Wien",
                loading = false,
                member = true,
                messages = messages,
                author = ChatAuthor(BOB, "Bob Builder", npub, "npub1qqqqqqq…qqqqqq", "B"),
            ),
        )
        rule.onNodeWithText("Bob Builder").assertIsDisplayed()
        rule.onNodeWithText("npub1qqqqqqq…qqqqqq").assertIsDisplayed()
        rule.onNodeWithTag(ChatTestTags.NPUB_CHIP).performClick()
        // The sheet animates out first, then the copy action is sent (which removes the sheet).
        rule.waitUntil(5_000) { actions.lastOrNull() == ChatAction.CopyNpub }
        assertEquals(listOf(ChatAction.CopyNpub), actions)
    }

    @Test
    fun `time labels show the date once the message is not from today`() {
        val older = ChatMessageItem("0", BOB, "Gestern", false, "Bob", "B", at(today.minusDays(2), 12, 0))
        var current by mutableStateOf(clock)
        rule.setContent {
            EinundzwanzigTheme {
                ChatScreen(
                    state = ChatUiState(isEvent = false, title = "Wien", loading = false, member = true, messages = listOf(older) + messages),
                    onAction = {},
                    onBack = {},
                    clock = current,
                )
            }
        }
        rule.onNodeWithText("19.10. 12:00").assertIsDisplayed()
        rule.onNodeWithText("18:02").assertIsDisplayed()
        // Past midnight the same messages switch to the dated form without new data.
        current = ChatClock(today.plusDays(1), ZoneOffset.UTC)
        rule.onNodeWithText("21.10. 18:02").assertIsDisplayed()
        rule.onNodeWithText("18:02").assertDoesNotExist()
    }

    @Test
    fun `restoring the screen keeps the scroll position`() {
        val many = (1..40).map { i ->
            ChatMessageItem("$i", BOB, "Nachricht $i", false, "Bob", "B", at(today, 10, i))
        }
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            EinundzwanzigTheme {
                ChatScreen(
                    state = ChatUiState(isEvent = false, title = "Wien", loading = false, member = true, messages = many, scrollRequest = 1),
                    onAction = {},
                    onBack = {},
                    clock = clock,
                )
            }
        }
        rule.onNodeWithText("Nachricht 40").assertIsDisplayed()
        // Reversed layout: index 39 is the oldest message.
        rule.onNodeWithTag(ChatTestTags.LIST).performScrollToIndex(39)
        rule.onNodeWithText("Nachricht 1").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithText("Nachricht 1").assertIsDisplayed()
        rule.onNodeWithText("Nachricht 40").assertDoesNotExist()
    }
}
