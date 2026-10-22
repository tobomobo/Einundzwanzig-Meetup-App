package space.einundzwanzig.meetup.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.data.chat.ChatMessage
import space.einundzwanzig.meetup.data.chat.ChatRules
import space.einundzwanzig.meetup.data.chat.ChatService
import space.einundzwanzig.meetup.data.chat.EventChatService
import space.einundzwanzig.meetup.data.chat.VEREIN_URL
import java.time.Instant

/** Which conversation the screen shows (Dart `ChatScreen.room` / `ChatScreen.event`). */
sealed interface ChatTarget {
    /** NIP-29 meetup room [h] with its metadata [name] (may be empty → header shows [h]). */
    data class Room(val h: String, val name: String) : ChatTarget

    /** NIP-22 thread on the calendar event [address] (`<kind>:<pubkey>:<d>`). */
    data class Event(val address: String, val title: String) : ChatTarget {
        /** Event author = pubkey segment of the address (Flutter passes `event.pubkey`). */
        val author: String get() = address.split(':', limit = 3).getOrNull(1).orEmpty()
    }
}

/**
 * Chat screen logic (Dart `_ChatScreenState`, spec 11 §2.1): load, membership, live
 * subscription, optimistic send, join, lazy display names, local read marks.
 */
@HiltViewModel(assistedFactory = ChatViewModel.Factory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted private val target: ChatTarget,
    private val savedState: SavedStateHandle,
    private val chat: ChatService,
    private val eventChat: EventChatService,
    private val people: ChatPeople,
    private val time: TimeSource,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(target: ChatTarget): ChatViewModel
    }

    private val isEvent = target is ChatTarget.Event

    private val _state = MutableStateFlow(
        ChatUiState(
            isEvent = isEvent,
            title = when (target) {
                is ChatTarget.Room -> target.name.ifEmpty { target.h }
                is ChatTarget.Event -> target.title
            },
            member = isEvent,
            // The composer draft survives process death.
            input = savedState.get<String>(DRAFT_KEY).orEmpty(),
        ),
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects: Flow<ChatEffect> = _effects.receiveAsFlow()

    private var myPubkey: String? = null
    private var messages: List<ChatMessage> = emptyList()
    private val names = mutableMapOf<String, String>()
    private val nameAttempted = mutableSetOf<String>()

    init {
        viewModelScope.launch { load() }
    }

    fun onAction(action: ChatAction) {
        when (action) {
            is ChatAction.InputChanged -> setInput(action.text)
            ChatAction.Send -> viewModelScope.launch { send() }
            ChatAction.Join -> viewModelScope.launch { join() }
            ChatAction.OpenVerein -> _effects.trySend(ChatEffect.OpenUrl(VEREIN_URL))
            is ChatAction.ShowAuthor -> showAuthor(action.pubkey)
            ChatAction.DismissAuthor -> _state.update { it.copy(author = null) }
            ChatAction.CopyNpub -> {
                val author = _state.value.author ?: return
                _state.update { it.copy(author = null) }
                _effects.trySend(ChatEffect.CopyNpub(author.npub))
            }
        }
    }

    private suspend fun load() {
        myPubkey = runSuspendCatching { people.myPubkeyHex() }
            .onFailure { AppLogger.warn(TAG, "Eigener Schlüssel nicht lesbar", it) }
            .getOrNull()
        val loaded = runSuspendCatching {
            when (target) {
                is ChatTarget.Room -> chat.loadMessages(target.h)
                is ChatTarget.Event -> eventChat.loadMessages(target.address)
            }
        }.onFailure { AppLogger.warn(TAG, "Nachrichten nicht geladen", it) }.getOrDefault(emptyList())
        val member = when (target) {
            is ChatTarget.Event -> true
            is ChatTarget.Room -> runSuspendCatching { chat.isMember(target.h) }.getOrDefault(false)
        }
        // A relay may repeat an EVENT; the list is keyed by id.
        messages = loaded.distinctBy { it.id }
        _state.update { it.copy(loading = false, member = member) }
        publish(scroll = true)
        loadNames()

        messages.lastOrNull()?.let { markRead(it.createdAt) }

        val since = messages.lastOrNull()?.createdAt ?: Instant.ofEpochMilli(time.nowMillis())
        val live = when (target) {
            is ChatTarget.Room -> chat.subscribe(target.h, since)
            is ChatTarget.Event -> eventChat.subscribe(target.address, since)
        }
        // Without live updates the chat stays readable (Dart swallows subscribe errors).
        // Cancelling the collector (ViewModel cleared) closes the socket.
        live.catch { AppLogger.debug(TAG, "Live-Abo beendet: $it") }.collect { m -> onLive(m) }
    }

    private fun onLive(m: ChatMessage) {
        val merged = ChatRules.merge(messages, m, myPubkey) ?: return
        messages = merged
        publish(scroll = true)
        loadNames()
        viewModelScope.launch { markRead(m.createdAt) }
    }

    private suspend fun markRead(until: Instant) {
        runSuspendCatching {
            when (target) {
                is ChatTarget.Room -> chat.markRead(target.h, until)
                is ChatTarget.Event -> eventChat.markRead(target.address, until)
            }
        }.onFailure { AppLogger.warn(TAG, "Lesestand nicht gespeichert", it) }
    }

    private suspend fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.sending) return
        _state.update { it.copy(sending = true) }
        val err = runSuspendCatching {
            when (target) {
                is ChatTarget.Room -> chat.send(target.h, text)
                is ChatTarget.Event -> eventChat.send(target.address, target.author, text)
            }
        }.getOrElse { it.message ?: it.toString() }
        _state.update { it.copy(sending = false) }
        if (err == null) {
            setInput("")
            // Show the own message at once; many relays do not echo it to the sender.
            val mine = ChatMessage.local(myPubkey, text, time.nowMicros())
            if (!ChatRules.isDuplicate(messages, mine, myPubkey)) {
                messages = messages + mine
                publish(scroll = true)
            }
        } else {
            _effects.send(ChatEffect.SendFailed(err))
        }
    }

    private suspend fun join() {
        val room = target as? ChatTarget.Room ?: return
        if (_state.value.joining) return
        _state.update { it.copy(joining = true) }
        val err = runSuspendCatching { chat.join(room.h) }.getOrElse { it.message ?: it.toString() }
        // Ask the relay instead of assuming: an accepted 9021 does not mean the list is updated.
        val member = if (err == null) runSuspendCatching { chat.isMember(room.h) }.getOrDefault(false) else false
        _state.update { it.copy(joining = false, member = member) }
        if (err != null) _effects.send(ChatEffect.JoinFailed(err))
    }

    private fun setInput(text: String) {
        savedState[DRAFT_KEY] = text
        _state.update { it.copy(input = text) }
    }

    private fun showAuthor(pubkey: String) {
        val name = names[pubkey]
        val npub = people.npubOf(pubkey)
        val display = if (!name.isNullOrEmpty()) name else ChatRules.shortKey(pubkey)
        _state.update {
            it.copy(
                author = ChatAuthor(
                    pubkey = pubkey,
                    display = display,
                    npub = npub,
                    shortNpub = ChatRules.shortNpub(npub),
                    avatarLetter = letterOf(display),
                ),
            )
        }
    }

    /** Fetches each unknown sender's name once per screen, one after the other (Dart `_loadNames`). */
    private fun loadNames() {
        val unknown = messages.map { it.pubkey }.distinct().filter { it.isNotEmpty() && it !in nameAttempted }
        if (unknown.isEmpty()) return
        nameAttempted += unknown
        viewModelScope.launch {
            for (pubkey in unknown) {
                val name = runSuspendCatching { people.displayName(pubkey) }.getOrNull()
                if (!name.isNullOrEmpty()) {
                    names[pubkey] = name
                    publish(scroll = false)
                }
            }
        }
    }

    private fun publish(scroll: Boolean) {
        val me = myPubkey
        val items = messages.map { m ->
            val name = names[m.pubkey]
            ChatMessageItem(
                id = m.id,
                pubkey = m.pubkey,
                content = m.content,
                mine = m.pubkey == me,
                senderName = if (!name.isNullOrEmpty()) name else ChatRules.shortKey(m.pubkey),
                avatarLetter = letterOf(name ?: m.pubkey),
                createdAt = m.createdAt,
            )
        }
        _state.update {
            it.copy(messages = items, scrollRequest = if (scroll) it.scrollRequest + 1 else it.scrollRequest)
        }
    }

    private companion object {
        const val TAG = "Chat"
        const val DRAFT_KEY = "chat_draft"

        fun letterOf(s: String): String = s.take(1).uppercase().ifEmpty { "?" }
    }
}
