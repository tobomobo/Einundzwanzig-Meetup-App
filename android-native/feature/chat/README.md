# :feature:chat

Chat screen of spec 11 §2.1. One UI serves two backends:

| NavKey | Route | Backend |
|---|---|---|
| `MeetupChat(roomId, roomName)` | `MeetupChatRoute` | NIP-29 room on the group relay (`ChatService`) |
| `EventChat(eventAddress, title)` | `EventChatRoute` | NIP-22 comment thread on a NIP-52 event (`EventChatService`), event author = pubkey segment of the address |

Register with `chatEntries()` (`EntryProviderScope<NavKey>`).

**App wiring:** `ChatRoute` keys its ViewModel per conversation (`"chat:$target"`), so two chats
never share one. For the live subscription to close when the chat is popped (spec: "closed when
leaving the screen"), `:app` must install `rememberViewModelStoreNavEntryDecorator()` (plus the
saveable-state decorator) on its `NavDisplay`; otherwise the ViewModel lives in the Activity store.

## Contents

- `ChatViewModel` (assisted `ChatTarget`): load, membership, live subscription (closed when the
  ViewModel is cleared), optimistic send with duplicate suppression (`ChatRules`), join +
  membership re-check, lazy display names (one fetch per pubkey), local read marks, initial list
  deduplicated by id, composer draft kept in `SavedStateHandle` (survives process death).
- `ChatScreen(state, onAction, onBack)`: app bar title + relay/event hint, loading
  (`AppLoadingIndicator`), empty texts (`chatEmpty` / `chatEventEmpty`), bubbles with avatar
  (`NostrAvatar`, kind-0 picture or initial), "name · time" header, author sheet (npub chip →
  clipboard + `chatNpubCopied`), non-member footer (`chatJoinHint`, Verein link box, `chatJoin`),
  composer (`chatPlaceholder`, IME send, send button with spinner), snackbars `chatSendFailed`,
  `chatJoinFailed` (6 s, action `chatMemberLink`).
- `ChatClock` / `rememberChatClock()`: local date + zone for the time labels (`HH:mm` today, else
  `d.M. HH:mm`), refreshed on time tick / date / time zone broadcasts so labels follow midnight.
- `ChatPeople`: seam over `SigningService.pubkeyHex` and `NostrProfileService.fetchDisplayName`.

Room lookup, the `chatSearching` / `chatNoRoom` / `chatOffline` snackbars and the unread
badges belong to the callers (home / events, spec 11 §2.2).

## Dependencies

`:data:chat`, `:data:profile`, `:data:identity`.

## Tests

`ChatViewModelTest` (JVM, fakes), `ChatScreenTest` (Robolectric Compose, stateless screen) and
`ChatRouteTest` (Robolectric, route effects: snackbars, Verein link, npub clipboard).
