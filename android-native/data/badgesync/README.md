# :data:badgesync

Silent background badge sync over Nostr (no UI): the wallet is stored as up to 16 addressable
kind-30078 events (`d = badges-0` … `badges-15`, tag `["client","einundzwanzig-meetup-app"]`),
content = NIP-44 v2 to the user's own pubkey of `base64(gzip(envelopeJson))`. Every run pulls and
merges first (union per event identity), then publishes only changed chunks. Plus the
crash-safe restore-hold state machine used by the backup import. Spec: `spec/08-backup-sync.md`
§1 B, §4.1, §5, §6, §7.3–7.5, §8.11–8.14. Dart: `lib/services/badge_sync_service.dart`,
`badge_sync_format.dart`, `badge_sync_codec(_io).dart`.

```kotlin
implementation(project(":data:badgesync"))   // exposes :core:common, :core:data, :core:nostr, :data:badges as api
```

Package root: `space.einundzwanzig.meetup.data.badgesync`.

## Public API

| API | Purpose (Dart origin) |
|---|---|
| `BadgeSync` (inject the `@Singleton`) | `init()`, `trigger()`, `suspend retryIfDirty()`, `suspend syncNow(): Boolean` — engine (`BadgeSyncService`) |
| restore hold on `BadgeSync` | `beginRestoreHold(fromNpub = "", expectNpubs = [], replace = false)`, `pendingFrom(): String?`, `endRestore()`, `settleRestore()`, `markRestoreClear()`, `clearRestoreMark()`, `clearIdentityProofs()`, `forgetLocalState()`, `identitySwitched(from, now)` |
| `BadgeSync.SYNC_RELAYS` | `nos.lol`, `relay.primal.net`, `nostr.mom`, `relay.snort.social` (own list, not the user's relays) |
| `RestoreHoldException` (`IllegalStateException`) | thrown by `beginRestoreHold` (pending switch without `replace`, or hold not persisted), `markRestoreClear`, `clearIdentityProofs`; `message` = the Flutter German text |
| `BadgeSyncKeys` | Flutter pref names: `badge_sync_dirty`, `badge_sync_chunk_hashes`, `badge_sync_last_gen`, `badge_restore_hold/from/expect/proofs/clear` |
| `BadgeSyncFormat` | `KIND` (30078), `MAX_CHUNKS` (16), `CHUNK_PAYLOAD_CHARS` (30 000), `CLIENT_TAG`, `dTag(i)`, `D_TAGS`, `sortBadges`, `envelopeJson(badges, index, total, generation)`, `chunkBadges(badges, measure)`, `plaintexts(badges, generation, measure)`, `chunkHash`, `contentHash`, `payloadCharsForGzip`, `parseEnvelope` → `Envelope(index, total, generation, badges)`, `merge(local, remote)` |
| `BadgeSyncFormatException` (`IllegalArgumentException`) | Dart `FormatException` of `parseEnvelope` |
| `BadgeSyncCodec` | `deflate(text): ByteArray` / `inflate(bytes): String` (RFC 1952 gzip + strict UTF-8) |

`syncNow()` returns `false` only when relays, pull or push failed (callers mark dirty — `trigger()`
does this itself); no signer, refused NIP-44 (dirty cleared, so the next wake-up does not prompt
again) and an active hold return `true`. Exceptions from the signer while encrypting/signing a
chunk propagate (`trigger()` marks dirty).

## Wiring

- **Startup (done by this module):** `BadgeSyncStartupTask` is contributed with `@IntoSet` as an
  `AppStartupTask`. It collects `BadgeRepository.savedEvents` and `SigningService.signerReady`
  (→ `trigger()`), triggers one full run (`init()`; Dart's extra start-up `retryIfDirty()` is
  dropped, it only forced a duplicate pass), and registers a
  `ProcessLifecycleOwner` observer that calls `retryIfDirty()` on every later return to the
  foreground (replaces the Dart home-screen `resumed` hook). `:app` only has to depend on the module.
  Every run settles a leftover hold first (spec 08 risk 13).
- **`:data:backup` (restore flow, spec 08 §2.5/§8.7):**
  ```kotlin
  val existing = badgeSync.pendingFrom() ?: signing.npub().orEmpty().trim()
  val wanted = if (replacing) backupIdentities(data) else emptyList()   // lower-cased npubs
  badgeSync.beginRestoreHold(existing, wanted, replace = replacing)
  try {
      // replace + empty backup: badgeSync.markRestoreClear(); else badges.stageIncoming(restored)
      // ... restore the key / signer ...
      // Prove the switch BEFORE touching proofs or the collection (backup_service.dart ~L1015-1035):
      val nowNpub = signing.npub().orEmpty().trim()
      val now = nowNpub.lowercase()
      val switched = badgeSync.identitySwitched(existing, nowNpub) && (wanted.isEmpty() || now in wanted)
      if (!switched) {
          // Old key still there: the switch never started, the buffer may go. Otherwise buffer
          // and hold stay (settleRestore resolves them later).
          if (nowNpub.isNotEmpty() && now == existing.trim().lowercase()) badges.discardIncoming()
          throw RestoreException(
              if (nowNpub.isEmpty()) "Der Schlüssel aus dem Backup ist gerade nicht lesbar, die Sammlung bleibt unverändert."
              else "Der Schlüssel aus dem Backup wurde nicht übernommen, die Sammlung bleibt unverändert.",
          )
      }
      badgeSync.clearIdentityProofs()
      //   empty → badges.saveBadges(emptyList(), discardLocked = true) && badgeSync.clearRestoreMark()
      //   else  → badges.promoteIncoming(formerNpub = existing)
  } finally {
      badgeSync.endRestore()   // NonCancellable inside: safe in viewModelScope / cancelled imports
  }
  ```
  `clearIdentityProofs()` goes through `HumanityProofRepository.clearForIdentitySwitch()`
  (`humanity_*` + `platform_proofs`, and a running humanity search can no longer write), then sets
  `badge_restore_proofs`.
- **App reset:** clearing the `KeyValueStore` wipes all sync keys (as Dart's `prefs.clear()`).
- **Migration:** the `badge_sync_*`/`badge_restore_*` keys must be carried over verbatim
  (`badge_restore_expect` may be a legacy string; it is tolerated).

## Tests

`tools/gradlew-locked :data:badgesync:testDebugUnitTest` — 63 JVM tests.

- `BadgeSyncCodecTest`: `badge_sync_codec_test.dart` (2 cases) + gzip header, foreign gzip
  (level 9, non-ASCII), fixture wire round trip, non-gzip input, gzip bomb limit.
- `BadgeSyncFormatTest`: `badge_sync_format_test.dart` (all 9 cases, same inputs) + spec 08 §10.2
  vectors from `spec/fixtures/08-backup-sync-vectors.json` (byte-exact envelope, `chunkHash`,
  `contentHash`, d-tags, full `payloadCharsForGzip` table), `parseEnvelope` error/lenience cases,
  oversized badge.
- `BadgeSyncSuspendTest`: `badge_sync_suspend_test.dart` (all 18 cases on the real
  `BadgeRepository` with in-memory stores; "process dies" = a new service instance) + legacy
  `badge_restore_expect` string, failed hold write, npub normalisation.
- `BadgeSyncEngineTest`: real NIP-44/BIP-340 against in-memory relays: event layout, hash
  skipping, two-device union, foreign-client chunk, newest-per-index / future / foreign-author
  events, empty wallet, unreachable/rejecting relays, dirty + retry, NIP-44 refusal, no signer,
  hold, generation floor, hooks, chunk limit.
- `BadgeSyncConcurrencyTest`: a settle racing `beginRestoreHold` keeps the hold, a scan saved
  between read and merge write survives and is published, the empty-replace clear of a leftover
  hold publishes nothing, a trigger during the fetch runs a second pass and releases `busy`, a
  run mid-publish during a switch does not restore the old hashes, `retryIfDirty` while busy adds
  no pass, `endRestore` in a cancelled import still settles and replays.

## Deviations from the Dart code

- **Failed chunk is retried (spec 08 §14 risk 6):** if no relay accepted chunk `i`, the previous
  hash (or `""`) is stored at index `i` instead of the new one, so the dirty retry publishes it
  again. Dart stored the new hash and skipped the chunk until its content changed. Local state
  only, wire-compatible.
- **Merge write is atomic:** the pull stores the merge through `BadgeRepository.update`
  (re-merging the remote badges onto the collection as it is at write time), so a scan saved while
  the relays were read is not lost. Dart used `saveBadges(merged)`.
- **Hold written in one transaction:** `badge_restore_from/expect/hold` are written with one
  `KeyValueStore.edit`; on failure nothing is written (Dart wrote three keys and removed the hold
  again). Same for clearing the hold keys. `beginRestoreHold` settles, checks and writes under
  the settle mutex and raises the in-memory "suspended" flag *before* the write (reset on
  failure), then bumps the epoch again; runs also treat that flag like a persisted hold. So no
  concurrent settle can remove a fresh hold and no run can *start* a publish during the switch.
- **No stale writes after a switch began:** a run re-checks the epoch/hold right before it
  stores `badge_sync_chunk_hashes` + `badge_sync_last_gen` (one transaction), `badge_sync_dirty`
  and the fetched generation floor, and inside the `BadgeRepository.update` transform of the
  merge. A run of the old identity that was mid-publish when the restore began therefore never
  writes its hashes back over `forgetLocalState()` (Dart had this gap; a chunk already in flight
  may still reach the relays under the old key, which is harmless).
- **Start-up / resume retry:** `retryIfDirty()` is a no-op while a run is busy (Dart forced a
  rerun pass), and the start-up task does not call it next to `init()`.
- **Gzip bomb guard:** `BadgeSyncCodec.inflate` throws `IOException` above 8 MiB of output
  (`MAX_INFLATED_BYTES`); the chunk is dropped like any unreadable one. Deep JSON nesting is
  already rejected by `DartJson.decode`.
- **Empty replace during settle:** Dart cleared the wallet with `onBadgesSaved` temporarily
  unset. The `savedEvents` flow cannot be muted per call, so that save triggers a run; it runs
  after `settleRestore` (serialized by a mutex), sees the hold gone (or still set → `HELD`), so
  nothing is published under the old identity.
- **Relay transport:** `RelayClient` from `:core:nostr` (one socket per relay per operation,
  `badgesync-<16 hex>` per relay, connect/response 8 s, publish connect 5 s / OK 8 s). It ignores
  frames with another subscription id and closes the socket instead of sending `CLOSE` (see
  `:core:nostr` deviations). A relay counts as "answered" only with EOSE, as in Dart.
- **Concurrency:** Dart's single isolate is replaced by a lock around the in-memory flags; the
  "rerun" check and leaving `busy` happen atomically so a trigger arriving at the very end of a
  pass is not lost. `settleRestore` runs one at a time.
- **Resume hook:** `ProcessLifecycleOwner` `ON_START` instead of the Dart home screen lifecycle.
  Only the catch-up `ON_START` of a launch in the foreground is skipped (covered by the start-up
  run); a process started in the background retries on its first real return (if that coincides
  with the start-up run, the busy check makes it a no-op).
- `parseEnvelope` throws `BadgeSyncFormatException` (an `IllegalArgumentException`) for a
  non-numeric `i`/`n`/`gen` (Dart threw a `TypeError` from `as num?`; both drop the chunk).

## Known gaps

- No `WorkManager` retry in the background (spec 08 §11 lists it as optional); retries happen on
  start, resume, badge save and signer ready, as in Flutter.
- No "badges changed by sync" signal beyond `BadgeRepository.badges` (which already updates on
  every save, including the sync's merge).
