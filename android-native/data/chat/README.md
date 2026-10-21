# :data:chat

Meetup group chat (NIP-29 on `wss://group.einundzwanzig.space` with NIP-42 AUTH) and event
comment threads (NIP-22, kind 1111) without UI. Spec: `spec/11-chat-news-community.md` §1 (chat
items 1–19), §2.1/§2.2 (data side), §3.1–§3.3, §4 (`chat_last_read`), §6.1–§6.5, §8.2, §8.8, §10.1, §10.4.
Ports `lib/services/chat_service.dart`, `lib/services/event_chat_service.dart` and
`test/chat_auth_handshake_test.dart` (incl. the late-challenge / late-signer fixes e9ead8d, b45b4df).
Package root: `space.einundzwanzig.meetup.data.chat`.

```kotlin
implementation(project(":data:chat"))   // exposes :core:nostr (+ :core:crypto) as api
```

Depends on `:data:badges` only for `DartString.lower` (canonical Dart `toLowerCase`, binding
resolution #12); switch to `:core:common` once the helper moves there. The module manifest adds
`ACCESS_NETWORK_STATE` (offline classification).

## Public API (inject the interfaces; all `@Singleton`)

| API | Purpose (Dart origin) |
|---|---|
| `ChatService` | `loadMeetupRooms(force)`, `findMeetupRoom(slug)`, `findRoomForMeetupId(portalId)`, `findRoomForCity(city)` (throw `ChatRelayUnavailable` when the relay gave no answer; `null` = no room), `isMember(h)`, `join(h)` (kind 9021), `leave(h)` (kind 9022), `loadMessages(h, limit = 100)`, `subscribe(h, since): Flow<ChatMessage>`, `send(h, text)` (kind 9), `unreadCounts(hs)`, `lastReadFor(key)`, `markRead(key, until)`. `send`/`join`/`leave` return `null` on success, else the raw reason for `chatSendFailed(msg)` / `chatJoinFailed(msg)` |
| `EventChatService` | `loadMessages(address)`, `send(address, eventAuthorPubkey, text)`, `subscribe(address, since): Flow<ChatMessage>`, `unreadCounts(addresses)`. Companion: `readKey(address)` = `"evt|$address"`, `commentTags(address, author)` |
| `ChatReadMarkStore` | `all()`, `lastReadFor(key)`, `markRead(key, until: Instant)`; `KEY = "chat_last_read"`, `parse(list)`, `format(map)` |
| `ChatRoom(h, name, about, picture, meetupSlug, eventAddress)` | `isMeetup`, `isEvent`, `displayName` (= name or h), `fromEvent(JsonObject)` |
| `ChatMessage(id, pubkey, content, createdAt: Instant)` | `fromEvent(JsonObject)`, `local(myPubkey, text, nowMicros)` (optimistic `lokal-<micros>`), `isLocal` |
| `ChatRelayUnavailable(offline)` | `offline` → `chatOffline`, else `chatRelayUnavailable` |
| `ChatRules` | `slugify`, `matchCity(rooms, city)`, `isDuplicate(existing, m, myPubkey)` (2 min window, own only), `merge(messages, m, myPubkey)` (live-update rule: skip known id / duplicate, stable sort), `shortKey`, `shortNpub`, `timeLabel(time, now, zone)` |
| `GROUP_RELAY`, `VEREIN_URL`, `ChatKinds` (`MESSAGE` 9, `JOIN` 9021, `LEAVE` 9022, `ROOM_META` 39000, `ROOM_MEMBERS` 39002, `AUTH` 22242, `COMMENT` 1111) | constants |

## Wiring for `:feature:chat` / home / events

ChatScreen ViewModel (spec 11 §2.1 `_load`):

```kotlin
val myPubkey = signingService.pubkeyHex()
val msgs = if (event) eventChat.loadMessages(address) else chat.loadMessages(room.h)
val member = event || chat.isMember(room.h)
val readKey = if (event) EventChatService.readKey(address) else room.h
msgs.lastOrNull()?.let { chat.markRead(readKey, it.createdAt) }
val since = msgs.lastOrNull()?.createdAt ?: Instant.ofEpochMilli(time.nowMillis())
viewModelScope.launch {
    (if (event) eventChat.subscribe(address, since) else chat.subscribe(room.h, since)).collect { m ->
        ChatRules.merge(state.messages, m, myPubkey)?.let { /* update, scroll, load names */ chat.markRead(readKey, m.createdAt) }
    }
}   // cancelling the collector (ViewModel cleared) closes the socket
```

After a successful send add `ChatMessage.local(myPubkey, text, time.nowMicros())` unless
`ChatRules.isDuplicate(...)`. After `join(h) == null` re-check `isMember(h)`. Display names come
from `:data:profile` `NostrProfileService.fetchDisplayName`. Open `VEREIN_URL` via `SafeUrl`.

Dashboard: `findRoomForMeetupId(meetup.id)` → if `null` `findRoomForCity(cityFor(favKey))`; a
thrown `ChatRelayUnavailable` must **not** fall back to the city. `loadMeetupRooms` is
single-flight: parallel `findMeetupRoom` / `findRoomForCity` / `loadMeetupRooms` calls share one
authenticated fetch (and its failure), so they open one connection and cause at most one Amber
prompt. `findRoomForMeetupId`, `unreadCounts`, `isMember` are separate queries with their own
connection each; run dashboard lookups sequentially (Dart order) to avoid parallel prompts. Unread: `chat.unreadCounts(rooms.map { it.h })`,
"Meine Termine" `eventChat.unreadCounts(addresses).values.sum()`.

## Behaviour notes

- Every group-relay query/publish/subscription opens its own connection and runs the NIP-42
  state machine (`internal Nip42Authenticator`, a `RelayAuthenticator` for `RelayClient`) with the
  signer-dependent deadline: local 8 s, NIP-46 60 s + 20 s, Amber/NIP-07 25 s. Connect 5 s, EOSE
  8 s (unread 6 s), OK 8 s. Sub ids `q` / `live` (event chat `q` / `evtchat`), filters in Dart key order.
- No answer vs. empty: CONNECT/AUTH failure, CLOSED (one retry for `auth-required…`), or a timeout
  with neither EOSE nor events → no answer. Room list cache 5 min, only non-empty lists; stale
  cache served when the relay is silent.
- Offline (`ChatRelayUnavailable.offline`) = the connect failure looks like "no network"
  (Dart message texts, `UnknownHostException`, `NoRouteToHostException`, `ENETUNREACH`) **or**
  ConnectivityManager reports no active network with `INTERNET` + `VALIDATED` (captive portal,
  airplane mode just toggled; internal `NetworkStatus`).
- Live flows (`subscribe`) end quietly on upstream `IOException`s only (`Flow.catch`); exceptions
  thrown by the collector propagate unchanged.
- `ChatMessage.fromEvent` never throws: `created_at` outside the Dart `DateTime` range
  (|s| > 8.64e12) gives `null`.
- NIP-42: a `CancellationException` leaked by the signer (e.g. its own `withTimeout`) and any
  `Throwable` it throws fail the handshake at once with one WARN (Dart `catch (e)`); only real
  cancellation (socket closed) ends silently.
- Every incoming event passes `EventGuard.verifiedEvent` + `answersFilter`; newest-wins uses
  `replaceableCreatedAt` (≤ now + 600 s). 39000/39002 are not checked against the relay key (Dart parity).
- Event chat uses `LightningAddressLookup.readTargets()` (`:data:profile`) for reads **and** writes
  (incl. `relay.nostr.band`, parity), connect 4 s, EOSE/OK 7 s; live = first reachable relay.
- Read marks are local only; parse via the last `|`, never decrease, whole list rewritten.

## Tests

`tools/gradlew-locked :data:chat:testDebugUnitTest` — 51 JVM tests.

- `ChatAuthHandshakeTest` (17): all 10 Dart cases of `chat_auth_handshake_test.dart` (13 test
  methods incl. the 3 abort variants and both missing/rejected OK variants) in virtual time, plus
  frame forwarding, the exact AUTH frame, a leaked signer `CancellationException` and a signer `Error`.
- `ChatServiceTest` (19): REQ shapes, newest-wins, cache, single-flight, null vs. empty, offline
  flag (exception and ConnectivityManager), `auth-required` retry, timeout, unread vector of §10.4,
  membership, send/join/leave outcomes, live subscription incl. renewal on the open socket and
  collector exception transparency, AUTH deadlines.
- `EventChatServiceTest` (5): comment tags on the wire, one-OK rule, merge/sort, unread de-dupe and
  `evt|` marks, first reachable live relay, collector exception transparency.
- `ChatRulesTest` (10): slugify/city vectors, read marks, `ChatRoom`/`ChatMessage` parsing (incl.
  out-of-range `created_at`), duplicates, labels, tag layouts.

## Deviations from the Dart code

- The offline flag travels with each query result instead of the static `_lastOffline`; offline is
  detected from the exception type/cause chain (`UnknownHostException`, `NoRouteToHostException`,
  `ENETUNREACH`) in addition to the Dart message texts, plus the ConnectivityManager check
  (spec 11 §8.2/§11 native complement).
- `loadMeetupRooms` is single-flight (Dart relied on the sequential home-screen loop).
- `dartIntTryParse` (read marks) is a module-local copy of Dart `int.tryParse`; the copies in
  `:core:nostr` / `:data:meetups` are `internal` and `:core:common` has none yet.
- Signing coroutines of a handshake are cancelled when its socket closes (Dart let a late signer
  result run and dropped it); observable behaviour (nothing sent, no WARN) is identical.
- A connection that drops before EOSE/OK ends the wait immediately (Dart waited for the timeout);
  the outcome (partial events / `"Zeitüberschreitung"`) is the same.
- `RelayClient` ignores frames for other subscription ids (Dart did not check them).
- `subscribe` returns a cold `Flow` instead of `subscribe(onMessage) → unsubscribe`.
