# :core:nostr

Nostr event model bridge, the central event guard, and the whole relay stack (WebSocket, relay
configuration, query/publish/subscribe helpers). Spec: `spec/07-nostr-infra-profile.md` §3, §5,
§6, §7, §8, §10, plus the relay parts used by every other spec (11 for NIP-42).
Ports `lib/services/relay_socket.dart`, `relay_config.dart` and the `ProfileRelayClient` of
`nostr_profile_lookup.dart` / `profile_relay_connection_io.dart`.

```kotlin
implementation(project(":core:nostr"))   // exposes :core:common, :core:crypto, :core:network as api
```

Data modules **never open WebSockets themselves**: inject `RelayClient` (one-shot reads and
writes, live subscriptions) and `RelayConfig` (where to read and publish). Protocols with their
own socket state machine (NIP-46 RPC, NIP-42 chat) inject `RelayConnector`.

Packages: `space.einundzwanzig.meetup.core.nostr` (events, filters, guard) and `…core.nostr.relay`
(transport). Test doubles: `…core.nostr.testing`.

## Events — `core.nostr`

| API | Purpose |
|---|---|
| `typealias NostrEvent = SignedNostrEvent` | The `:core:crypto` event (`id, pubkey, createdAt, kind, tags, content, sig`) with `toJson(): String`, `toEventMessage()`, `isValid()` (= `Event.isValid()`), `toMap()`. Sign with `EventIds.sign(keyPair, …)` (crypto) or the identity domain's `SigningService` |
| `NostrEvent.toJsonObject(): JsonObject` | Key order `id, pubkey, created_at, kind, tags, content, sig` |
| `NostrEvents.fromJsonOrNull(JsonElement?)` / `fromJsonStringOrNull(String)` | Strict parse (all 7 fields, Dart types: `created_at`/`kind` ints, tags string arrays). **Does not verify** |
| `NostrFilter(ids, kinds, authors, tags = mapOf("d" to …), since, until, limit, extra)` | Typed REQ filter; `toJson()` gives `{"kinds":…,"#d":…}`. Tag names without `#`, case-sensitive |
| `EventGuard.verifiedEvent(raw, tag = "RelaySocket"): JsonObject?` | Dart `RelaySocket.verifiedEvent`: id + BIP-340 check with Dart defaults/types; returns the **raw** object; counts `rejectedEventCount` and logs at the 1st and every 10th rejection |
| `EventGuard.answersFilter(event, filter: JsonObject \| NostrFilter)`, `answersAnyFilter(event, filters)` | Dart `answersFilter` (kinds, authors, since/until with int `created_at`, `#X` tag filters; Dart `==`, so `1 == 1.0`) |
| `EventGuard.replaceableCreatedAt(event, nowSeconds \| timeSource): Long?` | `created_at` if int, ≥ 0 and ≤ now + 600 s (`FUTURE_TOLERANCE_SECONDS`) |
| `EventGuard.rejectedEventCount` | Process-wide counter |

Every incoming event must pass `verifiedEvent` → `answersFilter` (same filter as sent) →
`replaceableCreatedAt` for newest-wins decisions. `RelayClient` does the first two for you.
The profile lookup's stricter rules (author, kind ∈ {0, 10002}, `created_at ≤ now`, JSON-object
content, lowest id wins ties) stay in `:data:profile`.

## Relay configuration — `RelayConfig` (inject)

| API | Purpose |
|---|---|
| `getActiveRelays()` / `observeActiveRelays(): Flow` | enabled defaults (default order) + custom, de-duplicated |
| `getCustomRelays()`, `getDisabledDefaults()`, `getRelayStatus()` / `observeRelayStatus()` | settings screen data (ordered map: defaults → enabled, custom → true) |
| `addCustomRelay(url): RelayAddResult` | `INVALID_URL` / `ALREADY_PRESENT` (exact string) / `UNREACHABLE` (5 s probe) / `ADDED` (stored as typed, trimmed) |
| `removeCustomRelay(url)`, `setDefaultRelayEnabled(url, enabled)` | |
| `probeRelay(url, timeout = 5 s)` | handshake only, socket closed afterwards |
| `RelayConfig.DEFAULT_RELAYS`, `RELAY_TIMEOUT` (8 s), `PUBLISH_TIMEOUT` (5 s), `PROBE_TIMEOUT` (5 s) | constants used across domains |
| `RelayConfig.KEY_CUSTOM_RELAYS = "custom_relays"`, `KEY_DISABLED_DEFAULTS = "disabled_default_relays"` | Flutter pref keys in the shared `KeyValueStore` (string lists) |
| `RelayConfig.activeRelaysOf(disabled, custom)`, `relayStatusOf(…)`, `isWellFormedRelayUrl(url)` | pure helpers |
| `RelayConfig(store, connector)` | factory for tests |

`RelayUrls.isWellFormedRelayUrl(url)` (settings input) and `RelayUrls.normalize(value, discovered)`
(profile lookup `relayUrl`, rejects local/private/IPv6 hosts for NIP-65-discovered relays) follow
Dart `Uri.tryParse` (lower-cased host, percent-encoding, dot segments). Keep both.

## Relay operations — `RelayClient` (inject)

One socket per relay per operation, closed afterwards (exactly the Dart pattern). Never throws
for relay problems (only cancellation); the result says what happened. Exceptions thrown by your
own `onEvent` callbacks propagate (the socket is still closed).

```kotlin
// read until EOSE from all active relays in parallel (Dart Future.wait)
val results = relayClient.queryAll(relayConfig.getActiveRelays(), NostrFilter(kinds = listOf(30078),
    authors = listOf(hex), tags = mapOf("d" to listOf("einundzwanzig-reputation")), limit = 1))
val events = results.flatMap { it.events }        // verified + filter-matched raw JsonObjects
val newest = events.maxByOrNull { EventGuard.replaceableCreatedAt(it, time) ?: -1 }

// sequential (Dart `for (relay in relays) await …`)
relayClient.queryAll(relays, filter, RelayQueryOptions(connectTimeout = 6.seconds, responseTimeout = 6.seconds,
    subscriptionId = SubscriptionIds.secure("nam")), fanOut = RelayFanOut.SEQUENTIAL)

// publish and wait for ["OK", id, true]
val ok = relayClient.publishAll(relays, signedEvent).count { it.accepted }

// live
relayClient.subscribe(relay, filter).collect { when (it) { is RelaySubscriptionEvent.Event -> …; RelaySubscriptionEvent.EndOfStoredEvents -> … } }
```

| API | Purpose |
|---|---|
| `query(relay, filters: List<JsonObject>, options, onEvent?)` (+ `query(relay, NostrFilter, …)`) | `["REQ", sub, …filters]`, collect until EOSE / CLOSED / disconnect / `maxEvents` / response timeout → `RelayQueryResult(relay, events, status, closedReason, error, unparsedFrames)`; `isComplete` (EOSE/LIMIT), `hasAnswer` (complete or partial events) |
| `queryAll(relays, filters, options, fanOut = PARALLEL, onEvent: (relay, event)?)` | results in relay order; `onEvent` calls are serialized |
| `queryMulti(relay, requests: List<RelaySubscriptionRequest(subscriptionId, filters)>, options, onEvent: (subId, event)?)` | several REQs on **one** socket; each event matched against its own subscription's filters; ends when every subscription sent EOSE (or CLOSED / reached `maxEvents`), on disconnect or after `responseTimeout` (total) → `RelayMultiQueryResult(relay, events: List<RelayTaggedEvent(subscriptionId, event)> in arrival order, status, subscriptionStatus, closedReasons, error, unparsedFrames, framesReceived)`, `eventsFor(subId)`, `isComplete` |
| `publish(relay, event, options)`, `publishRaw(relay, rawEvent, options)`, `publishAll(relays, event, options, fanOut)` | `["EVENT", ev]`, wait for the matching OK → `RelayPublishResult(relay, status: ACCEPTED/REJECTED/TIMEOUT/DISCONNECTED/CONNECT_FAILED/AUTH_FAILED/SENT, message, error)`; `accepted`, `succeeded` (= accepted or SENT) |
| `subscribe(relay, filters, options): Flow<RelaySubscriptionEvent>` | `Event`, `EndOfStoredEvents`, `Closed(reason)`, `Notice`; collecting connects, cancelling closes the socket; throws `RelayConnectException` / `RelayAuthException` / `RelayConnectionLostException`. Never sends `["CLOSE", sub]` (the socket is closed instead) |
| `subscribeFirstReachable(relays, filters, options): Flow<Pair<relay, RelaySubscriptionEvent>>` | `subscribe` on the first relay that connects (in order, each with `connectTimeout`); throws the last `RelayConnectException` if none does |
| `RelayQueryOptions(connectTimeout = 8 s, responseTimeout = 8 s, subscriptionId = null → "q-<16 hex>", verifySignatures = true, matchFilters = true, stopOnClosed = true, maxEvents, logTag, authenticator, parseTally: RelayParseTally?)` | per call. `parseTally.message()` per received frame, `failed()` per frame that is not a non-empty JSON array; you add your own failures and call `report()` |
| `RelayQueryResult(…, unparsedFrames, framesReceived)` | the `X von Y` numbers of the Dart tally line |
| `RelayPublishOptions(connectTimeout = 5 s, ackTimeout = 5 s, ackInactivityTimeout = null, matchEventId = true, authenticator, awaitOk = true, linger = 2 s)` | `matchEventId = false` takes the first OK (Dart AdminRegistry); `ackInactivityTimeout` restarts on every frame (Dart `stream.timeout`); `awaitOk = false` → send, wait `linger`, close, `SENT` |
| `RelayClient(connector, dispatcher)` | factory for tests (`RelayClient(FakeRelayConnector { … })`) |

Only `EVENT`/`EOSE`/`CLOSED` frames carrying the request's subscription id are considered.
Signature verification runs on `Dispatchers.Default` (`@DefaultDispatcher`). A NIP-42
authenticator that throws yields `AUTH_FAILED` with the exception in `error` (logged as WARN).

### Mapping of the Dart relay services

| Dart service / call | RelayClient call |
|---|---|
| Reads with `connect(url).timeout(t)` + EOSE wait `t` (8 s defaults) | `query`/`queryAll(relays, filter)`; `fanOut = SEQUENTIAL` where Dart loops with `await` |
| `ReputationPublisher._publishToRelays`, `PromotionClaimService` publish, `CalendarEventService` publish (connect 5 s → `ws.add` → 2 s → close, no OK check, sequential) | `publishAll(relays, ev, RelayPublishOptions(awaitOk = false), RelayFanOut.SEQUENTIAL).count { it.succeeded }` |
| `AdminRegistry` list publish (connect 5 s, first `["OK",…]` with any id, `ws.stream.timeout(5 s)`, sequential) | `RelayPublishOptions(ackTimeout = Duration.INFINITE, ackInactivityTimeout = 5.seconds, matchEventId = false)`, `SEQUENTIAL`, count `accepted` |
| Publishes that wait for the matching OK (Security Audit M8) | `publish`/`publishAll` with defaults, count `accepted` |
| `HumanityProofService._searchZapActivity` (one socket, REQs `zap-recv-`/`zap-req-`/`zap-sent-` with `limit` 3, 3 EOSEs or 12 s, `verifiedEvent` only) | `queryMulti(relay, listOf(RelaySubscriptionRequest(SubscriptionIds.secure("zap-recv"), …), …), RelayQueryOptions(responseTimeout = 12.seconds, matchFilters = false, logTag = "HumanityProof", parseTally = tally))` |
| `EventChatService` load (`q`, all read targets in parallel, connect 4 s, EOSE/CLOSED 7 s) | `queryAll(targets, filter, RelayQueryOptions(connectTimeout = 4.seconds, responseTimeout = 7.seconds, subscriptionId = "q"))` |
| `EventChatService` live (`evtchat`, first reachable read target, connect 4 s each) | `subscribeFirstReachable(targets, filter, RelayQueryOptions(connectTimeout = 4.seconds, subscriptionId = "evtchat"))`, `catch` the final `RelayConnectException` to degrade silently like Dart |
| `AdminRegistry` reads ending with `["CLOSE", sub]` | `query` (the socket is closed instead of sending CLOSE; equivalent for the relay) |
| Profile picture discovery | `ProfileRelayQuery` (not `RelayClient`) |

## Sockets and protocol pieces — `core.nostr.relay`

| API | Purpose |
|---|---|
| `RelayConnector.connect(url): RelaySocket` (inject) | suspends until the handshake completed; throws `RelayConnectException`; cancel (e.g. `withTimeout`) aborts the handshake. This is the NIP-46 "relay opener" |
| `RelaySocket` | `url`, `listen(onFrame, onDone)` (single listener, frames before `listen` are buffered, callbacks serialized and in order), `frames: Flow<String>`, `send(text): Boolean`, `close()`, `cancel()`, `isClosed`, `awaitClosed()`, `invokeOnClose { }` (a throwing handler is logged, never propagated). Frames > `RelaySocket.MAX_MESSAGE_CHARS` (512 Ki chars) are dropped (M4); binary frames ignored; no pings/reconnects |
| `BufferedRelaySocket` | base class with those rules, for custom transports and fakes |
| `RelayMessage.parse(frame)` / `RelayMessage.of(array)` | `Event`, `Eose`, `Closed(reason)`, `Ok(eventId, accepted, message)`, `Notice`, `Auth(challenge)`, `Other` |
| `RelayFrames.req/close/event/auth` | client frames, byte-identical to Dart `jsonEncode` |
| `SubscriptionIds.secure(prefix)` | `"<prefix>-<16 hex from CSPRNG>"` |
| `Nip42.KIND_CLIENT_AUTH = 22242`, `Nip42.authTags(relay, challenge)` | AUTH event content `""` |
| `fun interface RelayAuthenticator { suspend fun authenticate(socket, onMessage: (JsonArray) -> Unit): RelaySocket? }` | NIP-42 hook for `RelayQueryOptions`/`RelayPublishOptions`. The state machine of spec 11 §8.2 (Dart `ChatService.authenticateSocket`) is implemented in `:data:chat`; it must own the socket's listener, forward every non-handshake array to `onMessage`, and return the socket after the matching `OK` (or close it and return `null`) |
| `ProfileRelayQuery` / `ProfileRelayClient(client, timeout = 6 s, dispatcher = Default)` (inject `ProfileRelayQuery`) | hardened profile read (`["REQ","profile",{"kinds":[0,10002],"authors":[hex],"limit":2}]`): 6 s total incl. handshake, ≤ 64 frames, ≤ 64 Ki chars/frame, ≤ 8 events, stops on EOSE/CLOSED, returns **unverified** objects, aborts stalled upgrades. Runs on `@DefaultDispatcher` (safe to call from Main); connect errors/timeouts are logged on DEBUG without the pubkey |

## Testing — `core.nostr.testing`

- `FakeRelaySocket(url, onSend = { frame, socket -> socket.push(listOf("EOSE", sub)) })`: `push(list)`,
  `pushFrame(text)`, `finish(error)`, `sent`, `sentJson`, `closedByClient`. Same buffering/order rules as the real socket.
- `FakeRelayConnector { url -> FakeRelaySocket(url) }`, `FakeRelayConnector.unreachable()`; `connects`, `sockets`.
- Use `RelayClient(connector, UnconfinedTestDispatcher(testScheduler))` for virtual-time timeouts.
- JVM tests that sign events need `testImplementation(libs.secp256k1.jvm)`.

## Tests

`tools/gradlew-locked :core:nostr:testDebugUnitTest` — 94 JVM tests (~3 s), MockWebServer for the
OkHttp transport.

Ported Dart tests: `relay_event_signature_test` (all cases except the calendar parse → `:data:calendar`),
`zap_sent_filter_test` (filter matching; the filter builder → `:data:proofs`), `relay_socket_test`,
`profile_relay_client_test` (all 7 cases). `relay_parse_tally_test` lives in `:core:common`
(`RelayParseTally`). `chat_auth_handshake_test` belongs to `:data:chat`. Plus spec 07 §10.11
vectors (`spec/fixtures/07-nostr-infra-vectors.json`), §10.7 case 11/16 URL vectors and
Dart-3.13-generated `Uri` parity vectors.

## Deviations from the Dart code

- `verifiedEvent`: scalar tag values (numbers, booleans, null) are stringified like Dart
  `toString()`; nested arrays/objects inside a tag are rejected (Dart would hash `[1, 2]`), and
  a `kind` outside 32-bit range is rejected. Only affects malformed/adversarial events.
- `RelayClient` ignores `EVENT`/`EOSE`/`CLOSED` frames for other subscription ids (the Dart
  one-socket-per-query services did not check the id). Identical for well-behaved relays.
- A local `close()` ends the socket immediately (`onDone(null)` right away) instead of after the
  close handshake; binary frames are never delivered (Dart passed them on and the callers' cast failed).
- `ProfileRelayClient` and `RelayUrls.normalize` live here (spec 07 §13 put them in
  `:data:profile`; they are transport/URL logic). Spec 07 §13's separate `:core:relay` is merged
  into this module per ARCHITECTURE.md.
- `DartRelayUri` emulates the subset of `Uri.tryParse` relevant to relay URLs (backslash as `/`
  in authority and path, signed/hex/whitespace ports via `int.tryParse` rules, default-port
  removal, IPvFuture `[v1.x]`, IPv6 zone ids `%25<zone>`); IPv6 addresses are only checked for
  hex/`:`/`.` characters. Where Dart *throws* from `Uri.tryParse` (port beyond 64 bit, non-ASCII
  in an IPvFuture literal) the URL is rejected here.
- Fire-and-linger publishes report `CONNECT_FAILED` when the socket was already closed before
  `send` (Dart's `ws.add` on a closed sink would silently count as success).
- `ackInactivityTimeout` ignores a non-JSON frame (Dart's `jsonDecode` threw and ended the wait
  as "not confirmed").
- `queryMulti` counts one EOSE per subscription (Dart counted any 3 EOSE frames) and, with
  `stopOnClosed`, treats CLOSED as done (Dart waited for the 12 s timeout); the collected events
  are the same. Events with an unknown subscription id are ignored.
