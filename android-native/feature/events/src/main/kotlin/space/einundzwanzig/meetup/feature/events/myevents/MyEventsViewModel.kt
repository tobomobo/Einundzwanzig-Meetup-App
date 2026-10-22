package space.einundzwanzig.meetup.feature.events.myevents

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.calendar.MyEventsService
import space.einundzwanzig.meetup.data.calendar.model.MyMeetupDate
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.chat.ChatRelayUnavailable
import space.einundzwanzig.meetup.feature.events.calendar.EventCalendarViewModel
import space.einundzwanzig.meetup.feature.events.common.EventsChat
import space.einundzwanzig.meetup.feature.events.common.UiMessage
import space.einundzwanzig.meetup.feature.events.common.UiText
import space.einundzwanzig.meetup.feature.events.common.ZoneProvider
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

@Immutable
data class MyEventsUiState(
    val zone: ZoneId,
    val now: Instant,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val events: List<NostrCalendarEvent> = emptyList(),
    val meetupDates: List<MyMeetupDate> = emptyList(),
    val unread: Map<String, Int> = emptyMap(),
    /** Event of the open "Zusage zurücknehmen?" dialog. */
    val withdrawTarget: NostrCalendarEvent? = null,
    val withdrawing: Boolean = false,
) {
    val isEmpty: Boolean get() = !loading && events.isEmpty() && meetupDates.isEmpty()
}

sealed interface MyEventsAction {
    data object Refresh : MyEventsAction
    data class RequestWithdraw(val event: NostrCalendarEvent) : MyEventsAction
    data object DismissWithdraw : MyEventsAction
    data object ConfirmWithdraw : MyEventsAction
    data class OpenMeetupChat(val date: MyMeetupDate) : MyEventsAction

    // Route-handled.
    data object Back : MyEventsAction
    data class OpenEventChat(val event: NostrCalendarEvent) : MyEventsAction
}

sealed interface MyEventsEffect {
    data class OpenMeetupRoom(val roomId: String, val roomName: String) : MyEventsEffect
}

/** "Meine Termine" (Dart `MyEventsScreen` + the Home loaders, spec 10 §2.5, spec 12 §8.6). */
@HiltViewModel
class MyEventsViewModel @Inject constructor(
    private val myEvents: MyEventsService,
    private val chat: EventsChat,
    private val time: TimeSource,
    zones: ZoneProvider,
) : ViewModel() {

    private val mutableState = MutableStateFlow(MyEventsUiState(zone = zones.zone(), now = now()))
    val state: StateFlow<MyEventsUiState> = mutableState.asStateFlow()

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    private val effectChannel = Channel<MyEventsEffect>(Channel.BUFFERED)
    val effects: Flow<MyEventsEffect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val events = async { myEvents.loadAcceptedEvents() }
            val dates = async { myEvents.loadMeetupRsvps() }
            mutableState.update {
                it.copy(loading = false, events = events.await(), meetupDates = dates.await(), now = now())
            }
            loadUnread()
        }
    }

    /** Reloads the unread counts (pull-to-refresh, return from an event chat). */
    fun refreshUnread() {
        if (state.value.loading) return
        viewModelScope.launch { loadUnread() }
    }

    private suspend fun loadUnread() {
        val addresses = state.value.events.map { it.address }
        val counts = try {
            chat.eventUnreadCounts(addresses)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state.value.unread
        }
        mutableState.update { it.copy(unread = counts, now = now()) }
    }

    fun onAction(action: MyEventsAction) {
        when (action) {
            MyEventsAction.Refresh -> {
                if (state.value.loading || state.value.refreshing) return
                mutableState.update { it.copy(refreshing = true) }
                viewModelScope.launch {
                    try {
                        loadUnread()
                    } finally {
                        mutableState.update { it.copy(refreshing = false) }
                    }
                }
            }
            is MyEventsAction.RequestWithdraw -> mutableState.update { it.copy(withdrawTarget = action.event) }
            MyEventsAction.DismissWithdraw -> mutableState.update { it.copy(withdrawTarget = null) }
            MyEventsAction.ConfirmWithdraw -> withdraw()
            is MyEventsAction.OpenMeetupChat -> openMeetupChat(action.date)
            MyEventsAction.Back, is MyEventsAction.OpenEventChat -> Unit
        }
    }

    private fun withdraw() {
        val event = state.value.withdrawTarget ?: return
        if (state.value.withdrawing) return
        mutableState.update { it.copy(withdrawTarget = null, withdrawing = true) }
        viewModelScope.launch {
            val error = try {
                myEvents.withdraw(event)
            } finally {
                mutableState.update { it.copy(withdrawing = false) }
            }
            if (error == null) {
                mutableState.update { s -> s.copy(events = s.events.filterNot { it.address == event.address }) }
            } else {
                messageChannel.send(
                    UiMessage(UiText.res(L10nR.string.rsvpFailed, EventCalendarViewModel.rsvpErrorText(error)), Tone.Error),
                )
            }
        }
    }

    /** Home `_openMeetupChat(favKey, label)`. */
    private fun openMeetupChat(date: MyMeetupDate) {
        viewModelScope.launch {
            messageChannel.send(UiMessage(UiText.res(L10nR.string.chatSearching), Tone.Neutral, 4_000))
            val room = try {
                chat.findMeetupRoom(date.favKey)
            } catch (e: ChatRelayUnavailable) {
                messageChannel.send(
                    UiMessage(
                        UiText.res(if (e.offline) L10nR.string.chatOffline else L10nR.string.chatRelayUnavailable),
                        Tone.Neutral,
                        5_000,
                    ),
                )
                return@launch
            }
            if (room == null) {
                messageChannel.send(UiMessage(UiText.res(L10nR.string.chatNoRoom, date.label), Tone.Neutral, 4_000))
                return@launch
            }
            // Replaces the "searching" snackbar.
            messageChannel.send(UiMessage.Dismiss)
            effectChannel.send(MyEventsEffect.OpenMeetupRoom(room.h, room.name))
        }
    }

    private fun now(): Instant = Instant.ofEpochMilli(time.nowMillis())
}
