package space.einundzwanzig.meetup.data.chat.internal

import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.data.chat.ChatReadMarkStore
import java.time.Instant
import javax.inject.Inject

/** [ChatReadMarkStore] on the shared [KeyValueStore]. */
internal class DefaultChatReadMarkStore @Inject constructor(
    private val store: KeyValueStore,
) : ChatReadMarkStore {

    override suspend fun all(): Map<String, Long> =
        runSuspendCatching { ChatReadMarkStore.parse(store.getStringList(ChatReadMarkStore.KEY) ?: emptyList()) }
            .getOrElse { emptyMap() }

    override suspend fun lastReadFor(key: String): Long = all()[key] ?: 0L

    override suspend fun markRead(key: String, until: Instant) {
        val ts = Math.floorDiv(until.toEpochMilli(), 1000L)
        runSuspendCatching {
            // One atomic read-modify-write, so two concurrent marks cannot lose each other.
            val ok = store.edit {
                val marks = ChatReadMarkStore.parse(getStringList(ChatReadMarkStore.KEY) ?: emptyList())
                // Never move backwards: an older call must not overwrite a newer mark.
                if ((marks[key] ?: 0L) >= ts) return@edit
                marks[key] = ts
                setStringList(ChatReadMarkStore.KEY, ChatReadMarkStore.format(marks))
            }
            if (!ok) AppLogger.debug(TAG, "Lesestand nicht gespeichert: Schreiben fehlgeschlagen")
        }.onFailure { AppLogger.debug(TAG, "Lesestand nicht gespeichert: $it") }
    }

    private companion object {
        const val TAG = "Chat"
    }
}
