package space.einundzwanzig.meetup.feature.chat

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.EventChat
import space.einundzwanzig.meetup.core.navigation.MeetupChat

/** Registers the chat destinations (spec 11 §2.1) in the app's `entryProvider`. */
fun EntryProviderScope<NavKey>.chatEntries() {
    entry<MeetupChat> { key -> MeetupChatRoute(roomId = key.roomId, roomName = key.roomName) }
    entry<EventChat> { key -> EventChatRoute(eventAddress = key.eventAddress, title = key.title) }
}
