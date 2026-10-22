package space.einundzwanzig.meetup.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.data.chat.VEREIN_URL
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val chat = FakeChatService()
    private val eventChat = FakeEventChatService()
    private var people = FakePeople()

    // 2026-10-21 12:00:00 UTC
    private val nowSeconds = 1_792_584_000L
    private val time = FakeTime(nowSeconds * 1000)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val room = ChatTarget.Room("wien", "Einundzwanzig Wien")
    private val event = ChatTarget.Event("31923:$BOB:abc", "Workshop")

    private fun TestScope.vm(target: ChatTarget = room, saved: SavedStateHandle = SavedStateHandle()): ChatViewModel =
        ChatViewModel(target, saved, chat, eventChat, people, time).also { advanceUntilIdle() }

    @Test
    fun `room title falls back to h and subtitle kind is room`() = runTest(dispatcher) {
        val vm = vm(ChatTarget.Room("muc", ""))
        assertEquals("muc", vm.state.value.title)
        assertFalse(vm.state.value.isEvent)
    }

    @Test
    fun `load shows messages, membership, labels and marks read`() = runTest(dispatcher) {
        chat.messages = listOf(
            msg("1", BOB, "hi", nowSeconds - 86_400 * 2),
            msg("2", ME, "servus", nowSeconds - 60),
        )
        chat.member = true
        people = FakePeople(names = mapOf(BOB to "Bob"))
        val vm = vm()
        val s = vm.state.value
        assertFalse(s.loading)
        assertTrue(s.member)
        assertEquals(listOf("1", "2"), s.messages.map { it.id })
        assertEquals("Bob", s.messages[0].senderName)
        assertEquals("B", s.messages[0].avatarLetter)
        assertFalse(s.messages[0].mine)
        assertTrue(s.messages[1].mine)
        assertEquals(Instant.ofEpochSecond(nowSeconds - 86_400 * 2), s.messages[0].createdAt)
        assertEquals(Instant.ofEpochSecond(nowSeconds - 60), s.messages[1].createdAt)
        assertEquals(listOf("wien" to Instant.ofEpochSecond(nowSeconds - 60)), chat.marks)
        assertEquals(Instant.ofEpochSecond(nowSeconds - 60), chat.subscribedSince)
        assertTrue(s.scrollRequest > 0)
    }

    @Test
    fun `unknown names fall back to short key and are fetched once`() = runTest(dispatcher) {
        chat.messages = listOf(msg("1", BOB, "a", nowSeconds), msg("2", BOB, "b", nowSeconds))
        val vm = vm()
        assertEquals("bbbbbb…bbbb", vm.state.value.messages[0].senderName)
        chat.live.send(msg("3", BOB, "c", nowSeconds + 1))
        advanceUntilIdle()
        assertEquals(3, vm.state.value.messages.size)
        assertEquals(1, people.nameCalls.count { it == BOB })
    }

    @Test
    fun `empty room subscribes from now and does not mark read`() = runTest(dispatcher) {
        val vm = vm()
        assertTrue(vm.state.value.messages.isEmpty())
        assertFalse(vm.state.value.member)
        assertTrue(chat.marks.isEmpty())
        assertEquals(Instant.ofEpochMilli(time.millis), chat.subscribedSince)
    }

    @Test
    fun `live messages are merged, deduplicated and mark read`() = runTest(dispatcher) {
        chat.messages = listOf(msg("1", BOB, "hi", nowSeconds - 10))
        val vm = vm()
        chat.live.send(msg("1", BOB, "hi", nowSeconds - 10)) // known id
        chat.live.send(msg("0", BOB, "older", nowSeconds - 20))
        advanceUntilIdle()
        assertEquals(listOf("0", "1"), vm.state.value.messages.map { it.id })
        assertEquals(Instant.ofEpochSecond(nowSeconds - 20), chat.marks.last().second)
    }

    @Test
    fun `a message the relay sent twice is listed once`() = runTest(dispatcher) {
        val twice = msg("1", BOB, "hi", nowSeconds - 120)
        chat.messages = listOf(twice, twice, msg("2", BOB, "ho", nowSeconds - 60))
        val vm = vm()
        assertEquals(listOf("1", "2"), vm.state.value.messages.map { it.id })
    }

    @Test
    fun `the composer draft is kept in the saved state`() = runTest(dispatcher) {
        chat.member = true
        val saved = SavedStateHandle()
        vm(saved = saved).onAction(ChatAction.InputChanged("Ser"))
        // Process death: a new ViewModel on the same saved state restores the draft.
        val restored = vm(saved = saved)
        assertEquals("Ser", restored.state.value.input)
        restored.onAction(ChatAction.Send)
        advanceUntilIdle()
        assertEquals("", restored.state.value.input)
        assertEquals("", saved.get<String>("chat_draft"))
    }

    @Test
    fun `send success clears input and adds optimistic message once`() = runTest(dispatcher) {
        chat.member = true
        val vm = vm()
        vm.onAction(ChatAction.InputChanged("  Hallo  "))
        vm.onAction(ChatAction.Send)
        advanceUntilIdle()
        assertEquals(listOf("wien" to "Hallo"), chat.sent)
        val s = vm.state.value
        assertEquals("", s.input)
        assertFalse(s.sending)
        assertEquals(1, s.messages.size)
        assertTrue(s.messages[0].id.startsWith("lokal-"))
        assertTrue(s.messages[0].mine)
        // Relay echo within 2 minutes is suppressed.
        chat.live.send(msg("real", ME, "Hallo", nowSeconds + 5))
        advanceUntilIdle()
        assertEquals(1, vm.state.value.messages.size)
    }

    @Test
    fun `optimistic message is skipped when the echo came first`() = runTest(dispatcher) {
        chat.member = true
        val gate = CompletableDeferred<Unit>()
        chat.sendGate = gate
        val vm = vm()
        vm.onAction(ChatAction.InputChanged("Hallo"))
        vm.onAction(ChatAction.Send)
        advanceUntilIdle()
        assertTrue(vm.state.value.sending)
        chat.live.send(msg("real", ME, "Hallo", nowSeconds))
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("real"), vm.state.value.messages.map { it.id })
    }

    @Test
    fun `blank input is ignored`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(ChatAction.InputChanged("   "))
        vm.onAction(ChatAction.Send)
        advanceUntilIdle()
        assertTrue(chat.sent.isEmpty())
    }

    @Test
    fun `send failure keeps input and emits snackbar`() = runTest(dispatcher) {
        chat.sendResult = "Zeitüberschreitung"
        val vm = vm()
        vm.effects.test {
            vm.onAction(ChatAction.InputChanged("Hallo"))
            vm.onAction(ChatAction.Send)
            advanceUntilIdle()
            assertEquals(ChatEffect.SendFailed("Zeitüberschreitung"), awaitItem())
        }
        assertEquals("Hallo", vm.state.value.input)
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun `join success re-checks membership`() = runTest(dispatcher) {
        val vm = vm()
        assertFalse(vm.state.value.member)
        vm.onAction(ChatAction.Join)
        advanceUntilIdle()
        assertEquals(listOf("wien"), chat.joined)
        assertTrue(vm.state.value.member)
        assertFalse(vm.state.value.joining)
    }

    @Test
    fun `join accepted but list not updated stays non-member silently`() = runTest(dispatcher) {
        chat.memberAfterJoin = false
        val vm = vm()
        vm.effects.test {
            vm.onAction(ChatAction.Join)
            advanceUntilIdle()
            expectNoEvents()
        }
        assertFalse(vm.state.value.member)
    }

    @Test
    fun `join rejection emits snackbar with reason`() = runTest(dispatcher) {
        chat.joinResult = "blocked: not a member"
        val vm = vm()
        vm.effects.test {
            vm.onAction(ChatAction.Join)
            advanceUntilIdle()
            assertEquals(ChatEffect.JoinFailed("blocked: not a member"), awaitItem())
        }
        assertFalse(vm.state.value.member)
    }

    @Test
    fun `open verein emits the association url`() = runTest(dispatcher) {
        val vm = vm()
        vm.effects.test {
            vm.onAction(ChatAction.OpenVerein)
            assertEquals(ChatEffect.OpenUrl(VEREIN_URL), awaitItem())
        }
    }

    @Test
    fun `author sheet shows name and npub, copy closes it`() = runTest(dispatcher) {
        people = FakePeople(names = mapOf(BOB to "Bob"))
        chat.messages = listOf(msg("1", BOB, "hi", nowSeconds))
        val vm = vm()
        vm.onAction(ChatAction.ShowAuthor(BOB))
        val author = vm.state.value.author!!
        assertEquals("Bob", author.display)
        assertTrue(author.npub.startsWith("npub1"))
        assertEquals(author.npub.take(12) + "…" + author.npub.takeLast(6), author.shortNpub)
        vm.effects.test {
            vm.onAction(ChatAction.CopyNpub)
            assertEquals(ChatEffect.CopyNpub(author.npub), awaitItem())
        }
        assertNull(vm.state.value.author)
    }

    @Test
    fun `author sheet falls back to short key and raw pubkey for invalid hex`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(ChatAction.ShowAuthor("not-hex-pubkey"))
        val author = vm.state.value.author!!
        assertEquals("not-he…bkey", author.display)
        assertEquals("not-hex-pubkey", author.npub)
        vm.onAction(ChatAction.DismissAuthor)
        assertNull(vm.state.value.author)
    }

    @Test
    fun `event thread is writable, sends with author and marks read per address`() = runTest(dispatcher) {
        eventChat.messages = listOf(msg("e1", BOB, "Treffpunkt?", nowSeconds - 30))
        val vm = vm(event)
        val s = vm.state.value
        assertTrue(s.isEvent)
        assertTrue(s.member)
        assertEquals("Workshop", s.title)
        assertEquals(listOf(event.address to Instant.ofEpochSecond(nowSeconds - 30)), eventChat.marks)
        assertTrue(chat.marks.isEmpty())
        vm.onAction(ChatAction.InputChanged("Am Eingang"))
        vm.onAction(ChatAction.Send)
        advanceUntilIdle()
        assertEquals(listOf(Triple(event.address, BOB, "Am Eingang")), eventChat.sent)
        assertEquals(2, vm.state.value.messages.size)
        // Join is a no-op for event threads.
        vm.onAction(ChatAction.Join)
        advanceUntilIdle()
        assertTrue(chat.joined.isEmpty())
    }

    @Test
    fun `without identity no message is mine`() = runTest(dispatcher) {
        people = FakePeople(me = null)
        chat.messages = listOf(msg("1", ME, "x", nowSeconds))
        val vm = vm()
        assertFalse(vm.state.value.messages.single().mine)
    }

    @Test
    fun `failing live subscription keeps the chat readable and writable`() = runTest(dispatcher) {
        chat.member = true
        chat.subscribeError = IllegalStateException("socket closed")
        chat.messages = listOf(msg("1", BOB, "hi", nowSeconds - 10))
        val vm = vm()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf("hi"), s.messages.map { it.content })
        vm.onAction(ChatAction.InputChanged("noch da"))
        vm.onAction(ChatAction.Send)
        advanceUntilIdle()
        assertEquals(listOf("wien" to "noch da"), chat.sent)
    }
}
