# :data:profile

Profiles of other people and of the user, without UI: profile picture discovery (signed kind 0 +
NIP-65), display names (kind 0 / kind-30078 nickname, batch cache), the locally picked home
picture, the profile cache reset, the Blossom (BUD-02) image upload, and the lightning address
(LUD-16) lookup. Spec: `spec/07-nostr-infra-profile.md` §4, §5.5, §6.3, §8.3, §8.4, §10.7;
`spec/11-chat-news-community.md` §6.1/§6.9; `spec/06-proofs.md` §5.5.
Package root: `space.einundzwanzig.meetup.data.profile`.

```kotlin
implementation(project(":data:profile"))   // exposes :core:nostr (+ :core:crypto, :core:common) as api
```

Ports `lib/services/nostr_profile_service.dart`, `nostr_profile_lookup.dart` (lookup part),
`blossom_upload_service.dart`, `NewsZapService.fetchLightningAddress` and
`NewsReactionsService.readTargets`. The hardened `ProfileRelayClient` and `RelayUrls.normalize`
live in `:core:nostr` (transport/URL logic); this module injects `ProfileRelayQuery`.

## Public API (inject the interfaces; all `@Singleton`)

| API | Purpose (Dart origin) |
|---|---|
| `NostrProfileService` | `fetchProfilePicture(hex)`, `fetchDisplayName(hex)`, `cachedDisplayNames()`, `fetchDisplayNames(hexes)`, `setLocalPicture(path)`, `getLocalPicture()`, `getExistingLocalPicture()` (null if the file is gone, e.g. a migrated Flutter cache path), `observeLocalPicture(): Flow<String?>`, `clearCache()`. Constants `NAME_TIMEOUT` (6 s), `NAMES_MAX_AGE` (1 day), `NAMES_BATCH` (100), `REPUTATION_KIND`/`REPUTATION_D_TAG` |
| `NostrProfileLookup` | `fetchPicture(hex)` (coalesced, generation-guarded), `invalidate()`. Companion: `CACHE_DURATION` (12 h), `INDEX_RELAY` (`wss://purplepag.es`), `MAX_INITIAL_RELAYS` (8), `MAX_AUTHOR_RELAYS` (4), `pictureUrl(value)` (http/https + host, no user-info), `relayUrl(value, discovered)` |
| `LightningAddressLookup` | `fetchLightningAddress(hex): String?` (trimmed `lud16` with `@`, or `LUD06_MARKER` = `"lud06:"` for lud06-only, or `null`), `readTargets()` (`NEWS_RELAYS` ∪ `READ_ONLY_RELAYS` ∪ active relays). `:data:news` should use `readTargets()` and `NEWS_RELAYS`/`READ_ONLY_RELAYS` from here instead of a copy; `:data:proofs` calls `fetchLightningAddress` for the zap-receipt provider check |
| `blossom.BlossomUploadService` | `uploadImage(file: File)` / `uploadImage(path)` → `UploadResult(url, error)` (`ok`, `success(url)`, `failure(error)`). Constants `SERVERS`, `TIMEOUT` (45 s), `AUTH_KIND` (24242), `AUTH_CONTENT`, `AUTH_EXPIRATION`, `mimeFor(path)` |
| `LocalPictureImporter` | `import(uri): String`: gallery URI → scaled copy (≤ `MAX_SIZE` 400 px, JPEG `QUALITY` 80, Flutter `pickImage` parameters) in `filesDir/<DIR>` (`profile_picture`) → `setLocalPicture(path)` → only then older pictures (that dir, and a previous path inside `filesDir`, e.g. the migrated copy) are deleted. Throws on unreadable images; the old picture stays. Used by `:feature:profile` and the Home header (spec 12 F14) |
| `ProfileCacheKeys` | Flutter pref keys: `nostr_profile_metadata_v2_<hex>`, `nostr_profile_relays_v2_<hex>`, legacy `nostr_profile_picture_<hex>` / `_time_<hex>`, `nostr_display_names_v1`, `local_profile_picture` |
| `core.ui.nostr.NostrProfileSource` (bound here) | Avatar / npub-chip seam of `:core:ui`: `activePubkeyHex()` = `SigningService.npub()` decoded, `pictureUrl(hex)` = `fetchProfilePicture`, `displayNameForNpub(npub)` = NIP-19 decode → `fetchDisplayName` |

### Wiring for other modules

**`:app`** — provide the avatar seam once:

```kotlin
@Inject lateinit var profileSource: NostrProfileSource      // from :data:profile
CompositionLocalProvider(LocalNostrProfileSource provides profileSource) { … }
```

**Home screen** (spec 07 §8.8): `getExistingLocalPicture()` non-empty → show the local file
(`AvatarImage(model = File(path))`), never fetch; else `NostrAvatar(pubkeyHex = null, …)` or
`fetchProfilePicture(activeHex)`. After a gallery pick call `LocalPictureImporter.import(uri)`
(it stores the path itself).

**App reset** (`:data:settings` / reset use case): call `NostrProfileService.clearCache()`
before clearing all prefs, so running lookups cannot refill the caches.

**Event badge image** (`:feature:events` / `:data:calendar` caller): pick via Photo Picker,
re-encode (≤1200 px, q85) to a temp file, then `uploadImage(file)`; show
`evBadgeUploadFailed(result.error)` on failure.

**News zaps** (`:data:news`): `fetchLightningAddress(authorHex)`; `null` →
`ZapError.noLightningAddress`, no `@` (the `lud06:` marker) → `unsupportedAddress`.

## Behaviour notes

- Picture lookup: signed kind 0 + kind 10002 from up to 7 app relays + `wss://purplepag.es`, then
  ≤ 4 author write relays (no recursion); newest wins, lowest id on ties; cached events are
  re-verified on every read (author, kind, `0 ≤ created_at ≤ now`, JSON-object content, id + BIP-340);
  stale cache survives outages; a newer profile without picture removes it; legacy v1 keys are an
  outage fallback until the first signed profile arrives, then they are deleted. No negative caching.
- Display names: all name queries go through `RelayClient` (verified + filter-matched),
  `replaceableCreatedAt` (≤ now + 600 s). Single lookups are parallel across relays but folded in
  relay order, so "first relay wins ties" holds. Negative results are cached in memory for the session.
- The display-name cache `nostr_display_names_v1` is kept by `clearCache()` (Dart parity).
- Blossom: one kind-24242 signature (`t=upload`, `x=<sha256 of the exact bytes>`,
  `expiration=now+600 s`), header `Authorization: Nostr <base64(utf8(event JSON))>`, `PUT
  <server>/upload` with `Content-Type: <mime>`; first 200/201 with a non-empty `url` wins.

## Tests

`tools/gradlew-locked :data:profile:testDebugUnitTest` — 57 JVM tests (NostrProfileLookupTest 19, DisplayNamesTest 15,
LightningAddressLookupTest 9, BlossomUploadServiceTest 7, LocalPictureImporterTest 4, ProfileSourceTest 3).

- `LocalPictureImporterTest`: file handling and order (new file → pref → cleanup), failed decode /
  failed pref write keep the old picture, migrated picture inside `filesDir` removed, foreign paths
  kept. Bitmap decoding itself is not covered (needs Robolectric).

- `NostrProfileLookupTest`: all 16 cases of `test/nostr_profile_lookup_test.dart` (spec 07 §10.7),
  including `clearCache` via the service, plus cache wire format and the non-int `fetchedAt` case.
- `DisplayNamesTest`: single/batch names (REQ shapes, tie rules, name removal, outage keeps stored
  names, session negatives, Dart cast rules), local picture.
- `LightningAddressLookupTest`: read targets, newest/tie/clear rules, lud06 marker, overall timeout.
- `BlossomUploadServiceTest` (MockWebServer): fail-over, single signature, auth event and header,
  body/Content-Type, error texts, MIME mapping.
- `ProfileSourceTest`: avatar/npub-chip adapter (public-only identity of §10.8, wrong HRP quirk).

`profile_relay_client_test.dart` is transport-level and ported in `:core:nostr`;
`nostr_avatar_test.dart` is in `:core:ui`.

## DI

`ProfileModule` binds every implementation with `@Binds @Singleton`; the `@Inject` constructors
take `RelayConfig`, `ProfileRelayQuery`, `RelayClient`, `KeyValueStore`, `SigningService`,
`OkHttpClient`, `TimeSource`, `DiagnosticLog`, `@IoDispatcher` and `@ApplicationScope`, so tests
can replace any of them via `@TestInstallIn`.

## Deviations from the Dart code

- `pictureUrl` uses `:core:common` `SafeUrl` instead of Dart `Uri.toString()` normalisation: the
  accepted set is the same for normal URLs (http/https, host, no user-info; leading whitespace
  rejected), but the returned string is the input (control characters/spaces percent-encoded,
  trailing whitespace trimmed) and host/scheme are not lower-cased. Only display is affected.
- `fetchDisplayName` queries the relays in parallel instead of one after the other (allowed by
  spec 07 §8.4/§14 risk 12; identical results). Concurrent calls for the same pubkey share one
  in-flight lookup (run in `@ApplicationScope`), and at most `MAX_PARALLEL_NAME_LOOKUPS` (10)
  lookups query the relays at once, so a list of `NpubChip`s does not open chips × relays sockets.
  A queued lookup whose callers have all been cancelled (chip scrolled away) is dropped before it
  takes a permit, so on-screen chips are not stuck behind it; a lookup that already started runs to
  the end and caches its result. One lookup can take 12–24 s with unreachable relays, so **list
  screens with many chips (admin panel, calendar) must first call `cachedDisplayNames()` and
  `fetchDisplayNames(hexes)`** (one batch REQ per relay); both fill the in-memory cache that
  `fetchDisplayName` / the chips read before querying.
- `fetchDisplayNames` re-reads `nostr_display_names_v1` and applies only its own additions/removals
  under a mutex before writing, so overlapping calls do not overwrite each other's names (Dart
  writes back the map it read before the network phase). A failed write is swallowed, as in Dart.
- `uploadImage` treats a directory or an unreadable file as `failure("Datei nicht gefunden")`
  (Dart: directory = not found, unreadable = uncaught exception); reading and hashing run on IO.
- Profile cache writes check the lookup generation inside the atomic `KeyValueStore.edit`, so a
  lookup that was in flight during `clearCache()` cannot write its result back (spec 07 §14 risk 14).
- `RelayClient` ignores frames with a foreign subscription id and also stops on `CLOSED`; Dart
  name/lud16 sockets accepted any `EOSE`. Same results with well-behaved relays.
- Blossom error texts for exceptions are Kotlin/OkHttp `toString()`s (e.g.
  `java.io.InterruptedIOException: timeout`) instead of Dart's; response bodies are capped at
  256 KiB (a larger answer counts as `HTTP <code>`). The success body is decoded as UTF-8
  regardless of charset.
- A legacy pref of the wrong type reads as absent (Dart would throw out of the lookup).
- `lud16` debug log uses `take(8)` of the pubkey (Dart `substring(0, 8)` threw on short input).

## Known gaps

- Hard-coded German texts of the Blossom errors are returned raw (as in Dart); localisation is
  up to the caller.
- `ProfileNostrProfileSource.activePubkeyHex` follows `SigningService.npub()`; a public-only
  identity stored only in the `nostr` pref (no signer) resolves only if `SigningService.npub()`
  returns it.
