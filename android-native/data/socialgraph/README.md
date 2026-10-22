# :data:socialgraph

NIP-02 social graph and the opt-in co-attendance trust network. There is no UI here. Spec:
`spec/05-social-graph-admin.md` §3.1–§3.2, §4, §6, §8.1–§8.2, §9 items 11/12/15, §10.2; spec 00
item 5. Dart sources: `lib/services/social_graph_service.dart`, `lib/services/coattendance_service.dart`.

```kotlin
implementation(project(":data:socialgraph"))
```

`api` dependencies (types in the public API): `:core:common` (`DartDateTime`, `DiagnosticLog`,
`JsonObject`) and `:data:badges` (`MeetupBadge`). `implementation`: `:core:data`, `:core:nostr`,
`:data:identity`, `:data:admin`, `:data:mempool` (block height of the organizer marker badge). A
feature that needs e.g. `Nip19` declares `:core:crypto` itself.

Package root: `space.einundzwanzig.meetup.data.socialgraph`. Hilt binds both internal
implementations as `@Singleton` (`@Binds` in `di/SocialGraphModule`, `@Inject` constructors). Inject the interfaces. Signing goes through `:data:admin`'s
`AdminSigner` (bound to `SigningService`). Relays come from `RelayConfig.getActiveRelays()`, i.e.
the user's relay settings (not the 4 hard-coded admin relays).

## `social.SocialGraphService`

| Method | Behavior |
|---|---|
| `getMyFollows(forceRefresh = false): Set<String>` | Own follows (64-char hex). Cached in `social_my_follows` / `social_cache_ts` for 2 h (an empty result is cached too). Returns `{}` without an own npub, and then caches nothing |
| `fetchContactList(hex): Set<String>` | Newest kind 3 across all active relays, queried one after another (connect 8 s, EOSE 8 s). Relays that fail are skipped. An empty newest list wins. Events dated more than 10 min in the future are ignored |
| `analyze(targetHex): SocialAnalysis` | `iFollow`, `followsMe`, `isMutual`, `commonContactCount`, `orgFollowerCount` (fresh `AdminRegistry.fetchFromRelays()`, first 10 admins), `hops` (1 / 2 / -1). Returns `SocialAnalysis.empty()` for yourself or when you have no key |
| `getMyStats(): SocialStats` | Follow count; mutuals estimated from the first 20 follows; organizer followers. The caller applies the 10 s timeout (reputation publisher) |

Models: `SocialAnalysis` (`socialScore` 0..3, `connectionLabel` in German as in Flutter, `empty()`)
and `SocialStats` (`toJson()` → `{"follows","mutuals","org_followers"}` in this order, plus
`fromJson`, `empty()`). The reputation publisher embeds `toJson()` as `content.social`, but only
when `followCount > 0`.

## `coattendance.CoAttendanceRepository`

| Method | Behavior |
|---|---|
| `publishAttendance(badge): Int` | Call only after opt-in (`caOptIn*` dialog, scanner). Requires `badge.isNostrSigned` and a non-degenerate `meetupEventId`; otherwise returns 0 and records no status. Publishes kind 30079 `[["d",key],["e_ref",sigId],["client","einundzwanzig-meetup-app"]]` with content `{"event","meetup","t"}` to every active relay in parallel. It counts only `["OK", id, true]` received within 6 s. Stores `coatt_publish_status[sigId] = n` (0 when signing throws). Show `caPublished` if n > 0, else `caPublishFailed` |
| `recordOrganizerAttendance(meetupName, date: DartDateTime, blockHeight = 0, lat, lng, isEvent = false): MeetupBadge?` | Admin panel (awaited) and event-badge session (`isEvent = true`). Saves the marker badge `org-<eventId>` with `BadgeRepository.update`, never twice; if that fails it uses `keepAside`. Then it publishes `[["d",key],["role","organizer"],["client",…]]` with content `{…,"role":"organizer"}`. Returns `null` if the badge was stored nowhere or the signer failed |
| `publishStatus(): Map<String, Long>` / `failedBadges(all)` / `retryFailed(all): Int` | Failed-publication banner on "Meine Vernetzung" (`mnFailedBanner`, `mnRetry`, `mnRetryResult(fixed, failedCount)`) |
| `cachedNetwork(myNpub, maxDepth = 3): MyNetwork?` | Shown immediately when the screen opens (no relay traffic). Returns `null` without a cache or when the cache belongs to another owner. Parsing and BFS run on `@DefaultDispatcher`, so calling it from `viewModelScope` is safe |
| `buildMyNetwork(myNpub, maxDepth = 3, forceFull = false): Flow<NetworkBuildEvent>` | Emits `Stage(network, depth)` after each finished crawl stage (0 to `maxDepth` of them; the crawl stops early when the frontier is empty or a stage finds no new people, and with no decodable seed or no relays there is no `Stage` at all), always followed by exactly one `Done(network)`. Clear an "updating" indicator on `Done`, never on a particular depth. Pull-to-refresh passes `forceFull = true`; a full sync also happens on its own every 24 h. When no relay answers, `Done` carries the cached network with `stale = true` (or an empty stale one), and the cache stays untouched. The build runs in `@ApplicationScope`: a second collector for the same npub attaches to the running build, and cancelling a collector does not stop the build (it detaches at once). If the build itself is cancelled (application scope torn down), the flow fails with `IllegalStateException` |
| `awaitMyNetwork(myNpub, maxDepth, forceFull, onStage)` | The same build as a suspend call with a stage callback. `onStage` runs on a background thread (the application scope), so it must be thread-safe: post to a `StateFlow` |
| `verifyPerson(myNpub, targetNpub, maxDepth = 6): PresenceCheck` | "Person prüfen". Loads the graph to depth 3 from you and the target; no cache. Computation runs on `@DefaultDispatcher`. Validate the npub first (`Nip19.isValidNpub`, input lower-cased, `nostr:` stripped) |
| `analyze(myNpub, targetNpub): CoAttNetwork` | Library only; Flutter has no callers |

Screen notes (spec 05 §2.1): use the profile npub (`UserProfile.nostrNpub`) as `myNpub`, as Flutter
does. Replace the shown network with `Done.network` unless it is `stale && isEmpty` while something
is already shown. Set `updateFailed = network.stale`. Use `MyNetwork.updatedAt` (`Instant`) for
`mnStandAt`.

### Models (`coattendance`)

- `MyNetwork`: `byDegree` (keys 1..3), `myMeetupCount`, `updatedAt: Instant?`, `stale`,
  `meetupsOf`, `contactsByNpub` (degree order), `pathTo(npub)`, `sharedBetween(a, b)` (newest
  first), `degreeOf`, `degree1Count..3`, `totalReach`, `isEmpty`, `markStale()`, `MyNetwork.empty(npub)`.
- `NetworkContact(npub, degree, sharedMeetupsWithMe, bridges, parent)`.
- `PresenceCheck(targetNpub, degree, path, sharedMeetups, targetInNetwork, targetTotalMeetups)`,
  with `found`, `isDirect` and `isSelf`.
- `CoAttNetwork`, `CoAttNode` (mutable `meetups`/`events`), `CoAttendanceRecord`, `NetworkBuildEvent`.

### Pure helpers

| API | Purpose |
|---|---|
| `AttendanceKeys` | `EVENT_PREFIX = "ev\|"`; `attendanceKey(meetupEventId, signerNpub, isEvent)` (d tag: `<id>@<last 12 of signer npub>`, `ev\|<id>`, or legacy `<id>` with no signer); `sharedKeys`, `keysMatch`, `wantedKeys`, `base`, `isEventKey`, `isDegenerateEventId`. Issuance/scanner must use `attendanceKey` with `normalized['admin_npub']` as the signer |
| `AttendanceKeyLabel` | `label(key)` → `Wuerzburg, 12.09.2026`; `place`, `date` (`LocalDate`, overflow like Dart), `dateText(d, withYear)`, `newestFirst`, `newest` |
| `NetworkCalculator` | `adjacency`, `networkFromGraph` (BFS, bridges, parents), `mergeStage`, `shortestPath`, `presenceCheck`, `analyze`; `DEFAULT_MAX_DEPTH = 3`, `VERIFY_MAX_DEPTH = 6` |

## Storage (shared `KeyValueStore`, Flutter key names)

| Key | Format |
|---|---|
| `social_my_follows` / `social_cache_ts` | JSON array of hex strings / int ms |
| `coatt_publish_status` | JSON object `{sigId: relays}` |
| `coatt_graph_cache_v1` | `{"v":1,"owner","full","sync","ka","kk","n":{npub:{"m":[…],"e":[…]}}}` (`e` omitted when empty) |

App reset: `KeyValueStore.clear()` covers all of these.

## Tests

`tools/gradlew-locked :data:socialgraph:testDebugUnitTest` runs the JVM tests. They port the
spec 05 §10.2 vectors (no Flutter tests exist for this domain): attendanceKey, sharedKeys,
degenerate ids, labels, the BFS network vector, pathTo/sharedBetween/mergeStage,
verifyPerson ME→EMIL/GUS/BEN, socialScore, the mutual estimate, the graph-cache round-trip,
stale-without-answer, publish status. They also cover the staged crawl with incremental
`since`, partial answers that keep cached data, job coalescing, the relay paths on fake
sockets (kind-3 newest-wins, filters, OK counting), and the organizer marker badge.

## Deviations from Dart

- `Nip19.decodeNpub` returning `""` (wrong HRP) is treated as "no key" or skipped, never queried
  as `authors: [""]`. The result is the same.
- `SocialStats.fromJson` reads non-int fields as 0 (Dart's `as int?` would throw), as spec 05 §3.1 says.
- Relay `CLOSED` ends a co-attendance or contact query right away. Dart ignored `CLOSED` and waited
  for the timeout. The result is the same (partial or incomplete), just faster.
- Sorting uses `DartSort` (Dart's `List.sort` algorithm), so tie order matches Flutter exactly. The
  spec would also have allowed a stable sort.
- A relay rejection (`["OK", id, false, ...]`) with an empty reason logs "ohne Grund". Dart logged
  the empty string as given. `RelayPublishResult` cannot tell a missing 4th element from an empty one,
  so the log text differs only in that case. The accepted count is the same.
- Publish status values are exposed as `Long` (Dart `int`) instead of `Int`.
- `buildMyNetwork` is a cold `Flow`: coalescing happens when collection starts. A `forceFull`
  request that joins a running build is ignored, as in Flutter (spec risk 13).
