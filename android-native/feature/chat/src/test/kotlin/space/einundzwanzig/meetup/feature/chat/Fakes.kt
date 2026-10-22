package space.einundzwanzig.meetup.feature.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.data.chat.ChatMessage
import space.einundzwanzig.meetup.data.chat.ChatRoom
import space.einundzwanzig.meetup.data.chat.ChatService
import space.einundzwanzig.meetup.data.chat.EventChatService
import java.time.Instant

internal const val ME = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val BOB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

internal fun msg(id: String, pubkey: String, content: String, seconds: Long) =
    ChatMessage(id, pubkey, content, Instant.ofEpochSecond(seconds))

internal class FakeTime(var millis: Long) : TimeSource {
    override fun nowMillis(): Long = millis
}

internal class FakePeople(
    var me: String? = ME,
    val names: Map<String, String> = emptyMap(),
) : ChatPeople {
    val nameCalls = mutableListOf<String>()
    override suspend fun myPubkeyHex(): String? = me
    override suspend fun displayName(pubkeyHex: String): String? {
        nameCalls += pubkeyHex
        return names[pubkeyHex]
    }
}

internal class FakeChatService : ChatService {
    var messages: List<ChatMessage> = emptyList()
    var member = false
    var memberAfterJoin = true
    var joinResult: String? = null
    var sendResult: String? = null
    var sendGate: CompletableDeferred<Unit>? = null
    val live = Channel<ChatMessage>(Channel.UNLIMITED)
    val sent = mutableListOf<Pair<String, String>>()
    val joined = mutableListOf<String>()
    val marks = mutableListOf<Pair<String, Instant>>()
    var subscribedSince: Instant? = null
    var subscribeError: Throwable? = null
    private var joinedOnce = false

    override suspend fun loadMeetupRooms(force: Boolean): List<ChatRoom> = emptyList()
    override suspend fun findMeetupRoom(slug: String): ChatRoom? = null
    override suspend fun findRoomForMeetupId(portalId: String): ChatRoom? = null
    override suspend fun findRoomForCity(city: String): ChatRoom? = null
    override suspend fun isMember(h: String): Boolean = if (joinedOnce) memberAfterJoin else member
    override suspend fun join(h: String): String? {
        joined += h
        joinedOnce = true
        return joinResult
    }
    override suspend fun leave(h: String): String? = null
    override suspend fun loadMessages(h: String, limit: Int): List<ChatMessage> = messages
    override fun subscribe(h: String, since: Instant?): Flow<ChatMessage> {
        subscribedSince = since
        subscribeError?.let { e -> return kotlinx.coroutines.flow.flow { throw e } }
        return live.receiveAsFlow()
    }
    override suspend fun send(h: String, text: String): String? {
        sendGate?.await()
        sent += h to text
        return sendResult
    }
    override suspend fun unreadCounts(hs: List<String>): Map<String, Int> = emptyMap()
    override suspend fun lastReadFor(key: String): Long = 0
    override suspend fun markRead(key: String, until: Instant) {
        marks += key to until
    }
}

internal class FakeEventChatService : EventChatService {
    var messages: List<ChatMessage> = emptyList()
    var sendResult: String? = null
    val live = Channel<ChatMessage>(Channel.UNLIMITED)
    val sent = mutableListOf<Triple<String, String, String>>()
    val marks = mutableListOf<Pair<String, Instant>>()

    override suspend fun loadMessages(eventAddress: String): List<ChatMessage> = messages
    override suspend fun send(eventAddress: String, eventAuthorPubkey: String, text: String): String? {
        sent += Triple(eventAddress, eventAuthorPubkey, text)
        return sendResult
    }
    override fun subscribe(eventAddress: String, since: Instant?): Flow<ChatMessage> = live.receiveAsFlow()
    override suspend fun unreadCounts(addresses: List<String>): Map<String, Int> = emptyMap()
    override suspend fun markRead(eventAddress: String, until: Instant) {
        marks += eventAddress to until
    }
    override suspend fun lastReadFor(eventAddress: String): Long = 0
}
