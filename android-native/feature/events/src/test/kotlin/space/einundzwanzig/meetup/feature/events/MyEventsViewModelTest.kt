package space.einundzwanzig.meetup.feature.events

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.data.calendar.RsvpError
import space.einundzwanzig.meetup.data.calendar.model.MyMeetupDate
import space.einundzwanzig.meetup.data.chat.ChatRelayUnavailable
import space.einundzwanzig.meetup.data.chat.ChatRoom
import space.einundzwanzig.meetup.feature.events.common.UiMessage
import space.einundzwanzig.meetup.feature.events.common.UiText
import space.einundzwanzig.meetup.feature.events.myevents.MyEventsAction
import space.einundzwanzig.meetup.feature.events.myevents.MyEventsEffect
import space.einundzwanzig.meetup.feature.events.myevents.MyEventsViewModel

class MyEventsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val e1 = nostrEvent("e1")
    private val e2 = nostrEvent("e2")
    private val date = MyMeetupDate("107", "München", meetupDate(), 5)
    private val service = FakeMyEventsService(listOf(e1, e2), listOf(date))
    private val chat = FakeEventsChat().apply { unread = mapOf(e1.address to 3) }

    private fun vm() = MyEventsViewModel(service, chat, FixedTime(), berlinZone)

    @Test
    fun `loads both lists and the unread counts`() {
        val s = vm().state.value
        assertFalse(s.loading)
        assertEquals(listOf(e1, e2), s.events)
        assertEquals(listOf(date), s.meetupDates)
        assertEquals(mapOf(e1.address to 3), s.unread)
        assertFalse(s.isEmpty)
    }

    @Test
    fun `empty state when nothing is planned`() {
        service.events = emptyList()
        service.dates = emptyList()
        assertTrue(vm().state.value.isEmpty)
    }

    @Test
    fun `pull to refresh reloads unread counts only`() {
        val vm = vm()
        chat.unread = mapOf(e2.address to 120)
        vm.onAction(MyEventsAction.Refresh)
        assertEquals(mapOf(e2.address to 120), vm.state.value.unread)
        assertEquals(2, chat.unreadCalls)
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun `withdraw confirm declines and removes the row`() {
        val vm = vm()
        vm.onAction(MyEventsAction.RequestWithdraw(e1))
        assertEquals(e1, vm.state.value.withdrawTarget)
        vm.onAction(MyEventsAction.ConfirmWithdraw)
        assertEquals(listOf(e1), service.withdrawn)
        assertEquals(listOf(e2), vm.state.value.events)
        assertNull(vm.state.value.withdrawTarget)
    }

    @Test
    fun `withdraw dismiss keeps the row`() {
        val vm = vm()
        vm.onAction(MyEventsAction.RequestWithdraw(e1))
        vm.onAction(MyEventsAction.DismissWithdraw)
        assertTrue(service.withdrawn.isEmpty())
        assertEquals(2, vm.state.value.events.size)
    }

    @Test
    fun `withdraw failure keeps the row and shows rsvpFailed`() = runTest {
        service.withdrawError = RsvpError.SigningFailed("abgelehnt")
        val vm = vm()
        vm.onAction(MyEventsAction.RequestWithdraw(e1))
        vm.onAction(MyEventsAction.ConfirmWithdraw)
        assertEquals(2, vm.state.value.events.size)
        assertEquals(UiText.res(L10nR.string.rsvpFailed, "abgelehnt"), vm.messages.first().text)
    }

    @Test
    fun `meetup chat opens the found room`() = runTest {
        chat.room = ChatRoom(h = "room1", name = "München")
        val vm = vm()
        vm.onAction(MyEventsAction.OpenMeetupChat(date))
        val msgs = vm.messages.take(2).toList()
        assertEquals(UiText.res(L10nR.string.chatSearching), msgs[0].text)
        assertEquals(UiMessage.Dismiss, msgs[1])
        assertEquals(MyEventsEffect.OpenMeetupRoom("room1", "München"), vm.effects.first())
    }

    @Test
    fun `meetup chat without room shows chatNoRoom`() = runTest {
        val vm = vm()
        vm.onAction(MyEventsAction.OpenMeetupChat(date))
        val msgs = vm.messages.take(2).toList()
        assertEquals(UiText.res(L10nR.string.chatNoRoom, "München"), msgs[1].text)
    }

    @Test
    fun `relay failure shows chatOffline or chatRelayUnavailable`() = runTest {
        chat.failure = ChatRelayUnavailable(offline = true)
        val vm = vm()
        vm.onAction(MyEventsAction.OpenMeetupChat(date))
        assertEquals(UiText.res(L10nR.string.chatOffline), vm.messages.take(2).toList()[1].text)
        chat.failure = ChatRelayUnavailable(offline = false)
        vm.onAction(MyEventsAction.OpenMeetupChat(date))
        assertEquals(UiText.res(L10nR.string.chatRelayUnavailable), vm.messages.take(2).toList()[1].text)
    }
}
