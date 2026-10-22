package space.einundzwanzig.meetup.data.chat

import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.data.chat.internal.dartIntOrNullOf
import space.einundzwanzig.meetup.data.chat.internal.dartToStringOrNull
import space.einundzwanzig.meetup.data.chat.internal.tagLists
import space.einundzwanzig.meetup.data.chat.internal.tagName
import space.einundzwanzig.meetup.data.chat.internal.tagValue
import java.time.Instant

/** The Einundzwanzig NIP-29 group relay (Dart `kGroupRelay`). Room ids `h` are only valid here. */
const val GROUP_RELAY: String = "wss://group.einundzwanzig.space"

/** Association ("Verein") profile page; writing in rooms needs a membership there (Dart `kVereinUrl`). */
const val VEREIN_URL: String = "https://verein.einundzwanzig.space/association/profile"

/** Event kinds of the chat domain (spec 11 §6.4/§6.5). */
object ChatKinds {
    /** NIP-29 chat message, tags `[["h", h]]`. */
    const val MESSAGE: Int = 9

    /** NIP-29 join request, tags `[["h", h]]`, content `""`. */
    const val JOIN: Int = 9021

    /** NIP-29 leave request, tags `[["h", h]]`, content `""`. */
    const val LEAVE: Int = 9022

    /** NIP-29 room metadata (relay-signed, addressable). */
    const val ROOM_META: Int = 39000

    /** NIP-29 room members (relay-signed, `p` tags). */
    const val ROOM_MEMBERS: Int = 39002

    /** NIP-42 client authentication. */
    const val AUTH: Int = 22242

    /** NIP-22 comment (event chat). */
    const val COMMENT: Int = 1111
}

/**
 * A NIP-29 room as described by the relay's kind-39000 event (Dart `ChatRoom`).
 *
 * @property h room id (`d` tag), unique only together with [GROUP_RELAY].
 * @property meetupSlug portal slug of a meetup room (`meetup_slug` tag), `""` otherwise.
 * @property eventAddress `<kind>:<pubkey>:<d>` from the last `["i", "event:…"]` tag, `""` otherwise.
 */
data class ChatRoom(
    val h: String,
    val name: String = "",
    val about: String = "",
    val picture: String = "",
    val meetupSlug: String = "",
    val eventAddress: String = "",
) {
    /** The room belongs to a meetup. */
    val isMeetup: Boolean get() = meetupSlug.isNotEmpty()

    /** The room is bound to a calendar event (read but unused by the UI). */
    val isEvent: Boolean get() = eventAddress.isNotEmpty()

    /** Title for the chat header: [name], or [h] when the room has no name. */
    val displayName: String get() = name.ifEmpty { h }

    companion object {
        /**
         * Dart `ChatRoom.fromEvent`: values of the **first** `[key, value]` tag, the binding from
         * the **last** `["i", "event:…"]` tag; `null` if the `d` tag is missing or empty.
         */
        fun fromEvent(event: JsonObject): ChatRoom? {
            val tags = event.tagLists()
            fun value(key: String): String {
                for (t in tags) {
                    if (t.isNotEmpty() && t.tagName(0) == key && t.size > 1) return t.tagValue(1)
                }
                return ""
            }
            val h = value("d")
            if (h.isEmpty()) return null
            var eventAddress = ""
            for (t in tags) {
                if (t.isNotEmpty() && t.tagName(0) == "i" && t.size > 1) {
                    val v = t.tagValue(1)
                    if (v.startsWith("event:")) eventAddress = v.substring(6)
                }
            }
            return ChatRoom(
                h = h,
                name = value("name"),
                about = value("about"),
                picture = value("picture"),
                meetupSlug = value("meetup_slug"),
                eventAddress = eventAddress,
            )
        }
    }
}

/**
 * One chat message: a kind-9 room message or a kind-1111 event comment (Dart `ChatMessage`).
 *
 * @property createdAt `created_at` (whole seconds) for relay events; the local clock with
 *   microsecond precision for an optimistic local message ([local]).
 */
data class ChatMessage(
    val id: String,
    val pubkey: String,
    val content: String,
    val createdAt: Instant,
) {
    /** `true` for an optimistic message built by [local] (id `lokal-…`). */
    val isLocal: Boolean get() = id.startsWith(LOCAL_ID_PREFIX)

    companion object {
        /** Prefix of optimistic message ids (Dart `'lokal-${nowMicros}'`). */
        const val LOCAL_ID_PREFIX: String = "lokal-"

        /** Largest `|created_at|` (seconds) a Dart `DateTime` can hold (8.64e15 ms). */
        private const val MAX_DART_SECONDS: Long = 8_640_000_000_000L

        /**
         * Dart `ChatMessage.fromEvent`: `id` and `pubkey` must be non-empty, `created_at` must be
         * a Dart int within the Dart `DateTime` range; `content` defaults to `""`. Never throws.
         */
        fun fromEvent(event: JsonObject): ChatMessage? {
            val id = event["id"].dartToStringOrNull() ?: ""
            val pubkey = event["pubkey"].dartToStringOrNull() ?: ""
            val content = event["content"].dartToStringOrNull() ?: ""
            val ts = event["created_at"].dartIntOrNullOf() ?: return null
            if (id.isEmpty() || pubkey.isEmpty()) return null
            // Outside the Dart DateTime range (±8.64e15 ms) Dart throws; here: not a message.
            if (ts !in -MAX_DART_SECONDS..MAX_DART_SECONDS) return null
            return ChatMessage(id, pubkey, content, Instant.ofEpochSecond(ts))
        }

        /**
         * The optimistic message shown right after a successful send (spec 11 §2.1 `_send`):
         * id `lokal-<nowMicros>`, [pubkey] = own pubkey or `""`.
         */
        fun local(pubkey: String?, content: String, nowMicros: Long): ChatMessage = ChatMessage(
            id = "$LOCAL_ID_PREFIX$nowMicros",
            pubkey = pubkey ?: "",
            content = content,
            createdAt = Instant.ofEpochSecond(Math.floorDiv(nowMicros, 1_000_000L), Math.floorMod(nowMicros, 1_000_000L) * 1000L),
        )
    }
}

/**
 * The group relay gave **no** answer (no connection, AUTH failed, request refused, timeout).
 * Distinct from "no room" (`null`), so the UI never claims a meetup has no chat because of an
 * outage (Dart `ChatRelayUnavailable`).
 *
 * @property offline the device had no network at all (DNS failure, network unreachable): show
 *   `chatOffline` instead of `chatRelayUnavailable`.
 * @param cause why there was no answer, for the diagnostics log: the connect, AUTH or socket
 *   failure, `IOException("CLOSED: <reason>")` for a refused request, `null` for a plain
 *   timeout. [toString] stays the user-visible text.
 */
class ChatRelayUnavailable(val offline: Boolean = false, cause: Throwable? = null) :
    Exception(if (offline) "Keine Internetverbindung" else "Gruppen-Relay ohne Auskunft", cause) {
    override fun toString(): String = message!!
}
