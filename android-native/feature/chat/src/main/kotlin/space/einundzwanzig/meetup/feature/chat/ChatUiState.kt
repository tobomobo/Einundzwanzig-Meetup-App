package space.einundzwanzig.meetup.feature.chat

import androidx.compose.runtime.Immutable
import java.time.Instant

/**
 * What the chat screen shows (spec 11 §2.1). One state for both backends: a NIP-29 meetup room
 * ([isEvent] = false) and a NIP-22 event comment thread ([isEvent] = true).
 *
 * @property title room name (or `h`) / event title for the app bar.
 * @property loading the first load (messages + membership) is running: spinner, no footer.
 * @property member may write; always `true` for event threads.
 * @property scrollRequest increases whenever the list must jump to the newest message.
 * @property author the author sheet that is open, `null` when closed.
 */
@Immutable
data class ChatUiState(
    val isEvent: Boolean,
    val title: String,
    val loading: Boolean = true,
    val messages: List<ChatMessageItem> = emptyList(),
    val member: Boolean = false,
    val joining: Boolean = false,
    val sending: Boolean = false,
    val input: String = "",
    val scrollRequest: Int = 0,
    val author: ChatAuthor? = null,
)

/**
 * One rendered message.
 *
 * @property mine own message: right-aligned, orange tint, header "Du" (`chatYou`).
 * @property senderName display name or the short hex key (`abcdef…1234`) for others.
 * @property avatarLetter fallback letter of the avatar (first char of name or pubkey, upper-cased).
 * @property createdAt send time; the screen formats it (`HH:mm` today, else `d.M. HH:mm`) against
 *   the current local date so the labels follow midnight and time zone changes.
 */
@Immutable
data class ChatMessageItem(
    val id: String,
    val pubkey: String,
    val content: String,
    val mine: Boolean,
    val senderName: String,
    val avatarLetter: String,
    val createdAt: Instant,
)

/** Author sheet content: avatar, [display] name and the npub chip ([shortNpub], copies [npub]). */
@Immutable
data class ChatAuthor(
    val pubkey: String,
    val display: String,
    val npub: String,
    val shortNpub: String,
    val avatarLetter: String,
)

/** User intents of the chat screen. */
sealed interface ChatAction {
    data class InputChanged(val text: String) : ChatAction
    data object Send : ChatAction
    data object Join : ChatAction
    data object OpenVerein : ChatAction
    data class ShowAuthor(val pubkey: String) : ChatAction
    data object DismissAuthor : ChatAction
    data object CopyNpub : ChatAction
}

/** One-shot effects the route performs (snackbars, clipboard, external links). */
sealed interface ChatEffect {
    /** Red snackbar `chatSendFailed(reason)`. */
    data class SendFailed(val reason: String) : ChatEffect

    /** Red snackbar `chatJoinFailed(reason)` for 6 s with the action `chatMemberLink`. */
    data class JoinFailed(val reason: String) : ChatEffect

    /** Copy [npub] to the clipboard and show `chatNpubCopied`. */
    data class CopyNpub(val npub: String) : ChatEffect

    /** Open an http(s) [url] externally (Dart `launchHttpUrl`). */
    data class OpenUrl(val url: String) : ChatEffect
}
