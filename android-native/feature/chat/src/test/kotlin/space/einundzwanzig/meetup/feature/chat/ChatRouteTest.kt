package space.einundzwanzig.meetup.feature.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.chat.VEREIN_URL
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Effect handling of [ChatRoute] (spec 11 §2.1): snackbars, Verein link, npub clipboard. */
@RunWith(AndroidJUnit4::class)
class ChatRouteTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val chat = FakeChatService()
    private val eventChat = FakeEventChatService()
    private val people = FakePeople(names = mapOf(BOB to "Bob"))
    private val navigator = RecordingNavigator()
    private val room = ChatTarget.Room("wien", "Einundzwanzig Wien")

    private fun show() {
        val vm = ChatViewModel(room, SavedStateHandle(), chat, eventChat, people, FakeTime(1_792_584_000_000L))
        rule.setContent {
            CompositionLocalProvider(LocalNavigator provides navigator) {
                EinundzwanzigTheme { ChatRoute(room, vm) }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `send failure shows chatSendFailed with the reason`() {
        chat.member = true
        chat.sendResult = "rate-limited"
        show()
        rule.onNodeWithTag(ChatTestTags.INPUT).performTextInput("Servus")
        rule.onNodeWithTag(ChatTestTags.SEND).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(L10nR.string.chatSendFailed, "rate-limited")).assertExists()
    }

    @Test
    fun `join failure shows chatJoinFailed whose action opens the Verein page`() {
        chat.joinResult = "blocked"
        show()
        rule.onNodeWithTag(ChatTestTags.JOIN).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(L10nR.string.chatJoinFailed, "blocked")).assertExists()
        // The footer also shows chatMemberLink; the snackbar action is the other clickable node.
        rule.onNode(hasText(str(L10nR.string.chatMemberLink)) and hasClickAction() and !hasTestTag(ChatTestTags.MEMBER_LINK))
            .performClick()
        rule.waitForIdle()
        assertEquals(listOf(VEREIN_URL), navigator.openedUrls)
    }

    @Test
    fun `the member link box opens the Verein page`() {
        show()
        rule.onNodeWithTag(ChatTestTags.MEMBER_LINK).performClick()
        rule.waitForIdle()
        assertEquals(listOf(VEREIN_URL), navigator.openedUrls)
    }

    @Test
    fun `npub chip copies the full npub and shows chatNpubCopied`() {
        chat.member = true
        chat.messages = listOf(msg("1", BOB, "Wer kommt heute?", 1_792_583_000L))
        show()
        rule.onNodeWithText("Bob").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ChatTestTags.NPUB_CHIP).performClick()
        rule.waitForIdle()
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals(people.npubOf(BOB), clip?.getItemAt(0)?.text?.toString())
        rule.onNodeWithText(str(L10nR.string.chatNpubCopied)).assertExists()
    }
}

private class RecordingNavigator : Navigator {
    val openedUrls = mutableListOf<String>()
    override fun navigate(key: NavKey) = Unit
    override fun replaceTop(key: NavKey) = Unit
    override fun back() = Unit
    override fun backTo(key: NavKey, inclusive: Boolean) = Unit
    override fun resetTo(key: NavKey) = Unit
    override fun navigateFromRoot(key: NavKey) = Unit
    override fun openUrl(url: String) {
        openedUrls += url
    }
}
