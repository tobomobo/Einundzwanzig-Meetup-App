package space.einundzwanzig.meetup.data.chat

import space.einundzwanzig.meetup.data.chat.internal.dartIntTryParse
import java.time.Instant

/**
 * Local read marks of rooms and event threads (Dart `ChatService._readMarks` / `lastReadFor` /
 * `markRead`). Stored in the shared `KeyValueStore` under [KEY] as a string list of
 * `"<key>|<unixSeconds>"`; the key is a room `h` or `"evt|<kind>:<pubkey>:<d>"`
 * ([EventChatService.readKey]). Never published (privacy).
 */
interface ChatReadMarkStore {

    /** All marks, in stored order; unreadable entries are skipped. Errors read as empty. */
    suspend fun all(): Map<String, Long>

    /** The mark of [key] in unix seconds, `0` if none. */
    suspend fun lastReadFor(key: String): Long

    /**
     * Remembers that [key] was read up to [until] (`floor(millis / 1000)`). Never moves a mark
     * backwards; the whole list is rewritten (previous order, new keys appended, unreadable
     * entries dropped), as in Dart. Failures are logged, not thrown.
     */
    suspend fun markRead(key: String, until: Instant)

    companion object {
        /** Flutter pref key. */
        const val KEY: String = "chat_last_read"

        /**
         * Dart parsing of the stored list: split at the **last** `|`; entries with the separator
         * at index ≤ 0 or a suffix that is not a Dart int are skipped; a later duplicate key
         * overwrites the value but keeps the first position.
         */
        fun parse(entries: List<String>): LinkedHashMap<String, Long> {
            val out = LinkedHashMap<String, Long>()
            for (entry in entries) {
                val i = entry.lastIndexOf('|')
                if (i <= 0) continue
                val ts = dartIntTryParse(entry.substring(i + 1)) ?: continue
                out[entry.substring(0, i)] = ts
            }
            return out
        }

        /** Inverse of [parse]: `"<key>|<seconds>"` in map order. */
        fun format(marks: Map<String, Long>): List<String> = marks.map { (k, v) -> "$k|$v" }
    }
}
