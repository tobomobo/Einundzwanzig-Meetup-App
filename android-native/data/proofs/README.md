# :data:proofs

Identity signals of the user and the checks for other people's signals, without UI: platform
proofs (kind 21003, `21rep::` verify strings), Proof of Humanity (NIP-57 zap receipts), zap
statistics (Lightning layer), NIP-05 checks and the SatoshiDuell tile data.
Spec: `spec/06-proofs.md`. Package root: `space.einundzwanzig.meetup.data.proofs`.

```kotlin
implementation(project(":data:proofs"))   // exposes :core:common, :core:data, :core:nostr (+ :core:crypto) as api
```

Ports `platform_proof_service.dart` (storage, creation, parsing), `humanity_proof_service.dart`,
`zap_receipt_validator.dart`, `zap_verification_service.dart`, `nip05_service.dart`,
`satoshiduell_service.dart`. SatoshiDuell lives here (ARCHITECTURE.md module map), not in a
separate `:data:satoshiduell` as spec 06 §13 proposed. Screens (`PlatformProofScreen`,
`HumanityProofScreen`, tiles) belong to `:feature:proofs` / `:feature:home` / `:feature:community`.

## Public API (inject the interfaces; all `@Singleton`)

Every suspend entry point and flow is main-safe: implementations switch to `@DefaultDispatcher`
(BIP-340 checks, JSON parsing, Dart case tables); network I/O is suspending (OkHttp/relay helpers).
Platform-proof read-modify-writes run in one atomic `KeyValueStore.edit` transaction.
Every proof commit checks `native_proof_identity_generation` and its intended identity
`native_proof_identity_npub` inside that transaction; the synchronous transition hook advances
the generation, binds it to the next npub, and clears both proof groups atomically. Native writes
record a separate owner npub for platform and humanity state. UI flows, backup/QR reads and
publishing getters read payload and owner from one preferences snapshot and hide foreign
ownership. The binding rejects departing-key writes started after cleanup, even before the new
key is persisted or has written any proof, including after repository/process recreation.
A new identity's writes never merge foreign-owned state.
Unowned Flutter/migrated values and explicit backup restores keep their previous semantics;
proof JSON, backup entries, reputation content and verify strings do not gain metadata fields.

Transition contract for identity/passkey integration: `IdentitySwitchBarrier` invokes
`IdentityChangeHook.beforeIdentityChange(previousNpub, nextNpub)` before persisting the key or
notifying signer readiness. Empty `previousNpub` is the first key, including after logout:
the proof hook advances/binds the generation without clearing unowned migrated/backup proofs
or removing existing ownership. Null `nextNpub` binds to the explicit empty string for logout;
the following first-key hook rebinds it. A missing binding remains compatible with legacy
storage. Failed preparation leaves proofs, generation and binding unchanged and blocks key
persistence. Failure after preparation retains the binding and rejects the departing key's
proof operations until a transition completes. Later badgesync cleanup advances the generation
and retains the binding. No passkey implementation changes are required by this contract.

### `platform`

| API | Purpose (Dart origin) |
|---|---|
| `PlatformProofRepository` | `proofs: Flow<List<PlatformProof>>`, `getSavedProofs()`, `createProof(platformId, username, customPlatformName?)` → `ProofCreateResult`, `removeProof(platformId): Boolean`, `getProofsForPublishing(): LinkedHashMap<platform, PlatformProof>`; backup: `replaceAllRaw(validated)`, `unionRaw(incoming)`, `clear()` |
| `ProofCreateResult` (`Success(verifyString, proof)` / `Failure(error: ProofCreateError, detail)`), `ProofCreateError` (`NO_KEY`, `EMPTY_USERNAME`, `SIGNER_OR_UNKNOWN`) | `.success`, `.message` = the Flutter German text (`"Fehler: <signer reason>"`, show it raw for 10 s with a close icon) |
| `PlatformProof(platform, username, proofSig, createdAt)` | `toStorageJson()` (`platform_proofs` / backup), `toReputationJson()` (kind-30078 `platform_proofs` value), `toQrJson()` (QR `pp` value `{"u","s"}`), `fromReputationJson(platform, json)`, `fromStorageJson(json)`, `STORAGE_KEY = "platform_proofs"` |
| `PlatformCatalog` | `platforms` (ordered), `byId`, `displayName(id)`, `iconFor(id)`, `customPlatformId(name)`, `OTHER`. `PlatformInfo(id, name, icon, hint)`, `PlatformIcon` (`SHOPPING_CART, SEND, SMART_TOY, HUB, LANGUAGE, LINK`) |
| `VerifyStringCodec` | `build(npub, platform, username, sig)`, `parse(input): ParsedVerifyString?` (verbatim Dart), `isWellFormed(parsed)` (format only, not crypto) |
| `PlatformProofVerifier` (injectable `@Singleton`, `@DefaultDispatcher`) `.verify(input, expectedUsername?, fetchByNpub, facts)` | Dart `verifyProofString` minus the relay fetch — **seam for `:data:reputation`** (see below). Returns `ProofVerifyResult<R>(level: VerifyLevel, message: VerifyMessage, npub, platform, claimedUsername, reputation: R?, proofInEvent)` |
| `VerifyLevel` (`FULL, PARTIAL, SIGNATURE_ONLY, INVALID`), `VerifyMessage` (sealed, `.german`), `ReputationFacts(boundBadges, totalBadges, platformProofs)` | |
| `PlatformProofBackup` | `restorable(entries)` (128-hex `proof_sig` only; `replaceAllRaw`/`unionRaw` apply it themselves), `union(device, incoming)` (Dart `unionPlatformProofs`) |

### `humanity`

| API | Purpose |
|---|---|
| `HumanityProofRepository` | `status: Flow<HumanityStatus>`, `getStatus()`, `checkForZaps(): HumanityCheckResult` (cancellable; minutes when relays are dead — run from a ViewModel), `reverifyIfNeeded()`, `reset()`, `getProofForPublishing(): JsonObject?`, `checkForZapsByPubkey(hex)`, `verifyReceiptExists(id)`; backup: `restoreFromBackup(humanityProofJson, additive): Boolean`; identity switch: `clearForIdentitySwitch()` |
| `HumanityStatus(verified, firstZapAt, receiptEventId, lastCheckedAt, needsReverification)` | `firstZapDateStr(zone)`, `lastChecked(nowMillis, zone): LastChecked` (`Never/JustNow/MinutesAgo/HoursAgo/OnDate`, `.german`), `lastCheckedStr(...)`, `toBackupJson()` |
| `HumanityCheckResult(found, outcome: HumanityOutcome, receiptEventId, zapTimestamp)` | `.message` = German text. `HumanityOutcome` = `FOUND, NOT_FOUND, NO_KEY, INVALID_NPUB, ERROR`. **Switch on `outcome`**: `ERROR` means "check failed, try again" (write failed, result discarded by an identity/humanity-key change, unexpected error), never "you have not zapped"; its `.message` is the generic `"Prüfung fehlgeschlagen — bitte erneut versuchen."` |
| `HumanityKeys` | the five `humanity_*` keys, `clearIdentityProofs(editor)` (for `:data:badgesync`, inside its own `store.edit { … setBool("badge_restore_proofs", true) }`) |
| `AppStartupTask` `HumanityReverify` (contributed with `@IntoSet`) | watches `humanity_needs_reverification` in `@ApplicationScope` and runs `reverifyIfNeeded()` at start and whenever a restore sets the flag (Dart home `_loadIdentityData` ran after every restore → AppShell). While the flag stays set after a failure it retries on `SigningService.signerReady` and after 1, 5 and 15 min. Returns at once, never blocks later startup groups. Screens need not call `reverifyIfNeeded()`; `:feature:home` may call it on resume for Dart's per-home-load retry (single flight, cheap no-op without the flag) |

### `zap`

| API | Purpose |
|---|---|
| `ZapReceiptValidator` | `checkLocal(receipt: JsonObject): ZapReceiptCheck` (Audit H3 steps 1–4, reason codes `kind`…`ok`), `descriptionHashOf(bolt11)` (→ `:core:crypto` `Bolt11`) |
| `ZapProviderResolver` | `isFromRecipientProvider(receipt, recipient, sender = "")`, `providerPubkeyFor(recipient)`, `providerPubkeyFromLnurlp(url)`; cache `zap_provider_pubkey_<recipient>` (7 d, positives only) |
| `ZapVerificationService` | `analyzeZapActivity(hex, useCache = true)`, `getMyStats()` (never throw except cancellation: unreadable relay config → `ZapStats.EMPTY`, failed cache write only logged); companion `receiptFilter(hex, since, sent)`, cache prefixes, constants |
| `ZapStats(sentCount, receivedCount, uniqueRecipientCount, uniqueSenderCount, activeMonths, hasLightningProof)` | `totalCount`, `lightningScore`, `activity: ZapActivity` / `activityLabel`, `toJson()`, `fromJson()` (lenient), `EMPTY`, `compute(received, sent)`, `activeMonthsOf(span)` |

### `nip05`

`Nip05Service`: `verify(nip05, expectedHex): Nip05Result`, `fetchNip05FromProfile(hex, relays)`;
companion `classify(domain)`, `score(result)`, domain lists. `Nip05Result(valid, nip05, domain,
domainType)` with `domainLabel`; `Nip05DomainType` (`.german`).

### `satoshiduell`

`SatoshiDuellClient`: `fetchStatus(forceRefresh = false): DuellStatus`, `openDuelCount()`,
`webAppUrl(npub)` (open with `ACTION_VIEW`); constants `BASE_URL`, `PUBLISHABLE_KEY` (default
key), `CACHE_TTL` (2 min). The key actually sent is `BuildConfig.SATOSHIDUELL_PUBLISHABLE_KEY`
of this module, set with the Gradle property `-PsatoshiduellPublishableKey=…` (spec 06 §14 R16);
unset/blank → `PUBLISHABLE_KEY`. `DuellStatus(myTurn, waiting, lobby)` with `hasAny`, `actionCount`, `EMPTY`.

## Wiring for other modules

**`:data:reputation`** — verify-string check (Dart `PlatformProofService.verifyProofString`):

```kotlin
@Inject lateinit var verifier: PlatformProofVerifier
verifier.verify(input, expectedUsername,
    fetchByNpub = { npub -> reputationFetcher.fetchByNpub(npub) },
    facts = { rep -> ReputationFacts(rep.boundBadges, rep.totalBadges, rep.platformProofs) })
```

Publisher content: `"lightning"` = `zapVerification.getMyStats().toJson()` (10 s timeout →
`ZapStats.EMPTY`, only if `totalCount > 0`), `"humanity_proof"` = `getProofForPublishing()`,
`"platform_proofs"` = `getProofsForPublishing().mapValues { it.value.toReputationJson() }`.
Spec 06 §14 R4: pass the platform proofs on **every** publish. QR `pp`:
`getSavedProofs().associate { it.platform to it.toQrJson() }`.

**`:feature:proofs`** — after `createProof`/`removeProof` success republish the reputation
(forced). After `checkForZaps().found` publish with `force = true` (R5; Flutter's
`publishInBackground([])` was a no-op). Show a progress state while creating (external signers).
Platform hints/names: `:core:l10n` has none. `:feature:proofs` owns them as its own
`native_`-prefixed resources (e.g. `native_ppHintSka`, `native_ppHintTelegram`,
`native_ppHintRobosats`, `native_ppHintNostr`, `native_ppHintOther`, `native_ppNameOther`;
DE/EN/ES, German defaults = `PlatformInfo.hint`/`name`) and maps `PlatformInfo.id` to its
`R.string` ids. Brand names are not translated; custom platforms show the raw id.

**`:data:backup`** — export `getSavedProofs().map { it.toStorageJson() }` and
`humanity.getStatus().toBackupJson()`; restore with
`platformProofs.replaceAllRaw(list)` or `unionRaw(list)` (both keep only
`PlatformProofBackup.restorable` entries; pre-filtering is harmless) and
`humanity.restoreFromBackup(json, additive)`.

**`:data:badgesync`** — identity switch:
`store.edit { HumanityKeys.clearIdentityProofs(this); setBool("badge_restore_proofs", true) }`
(throw if `false`). Zap and provider caches are deliberately not cleared.

## Tests

`tools/gradlew-locked :data:proofs:testDebugUnitTest` — 136 JVM proof tests.

Transition-fence validation (2026-10-22, JDK 17 at `/home/tg/development/jdk17`):
`tools/gradlew-locked :data:proofs:testDebugUnitTest :data:identity:testDebugUnitTest --tests 'space.einundzwanzig.meetup.data.identity.ParitySafetyTest'`
passed. The proof task was up to date with current XML results of 136 tests, zero failures,
errors or skips; the identity task executed eight `ParitySafetyTest` tests with zero failures,
errors or skips. No device/emulator validation was performed for this change.

- `ProofIdentityRaceTest`: deterministic pauses after the identity check and before the prefs
  transaction, platform create/remove/replace/union/clear, humanity search/restore/reset/clear,
  and work started between cleanup and key persistence. B commits fresh platform and humanity
  proofs **before** A's paused transaction is released; its entire preferences snapshot and
  publishing/UI getters must survive unchanged. Also covers rejection before B is active,
  repository recreation, logout followed by first-key creation, retained binding after badgesync
  cleanup, active UI refresh, failed cleanup and legacy/backup compatibility.
- `ProofIdentityChangeHookTest`: synchronous cleanup and next-key binding, atomic failure,
  first-key adoption of unowned legacy proofs, retained foreign ownership and logout binding.
- Identity barrier contract tests:
  `tools/gradlew-locked :data:identity:testDebugUnitTest --tests '*ParitySafetyTest'` checks first-key
  and post-logout persistence waits for binding, failure/missing-hook rejection, and the existing
  changed-key/Amber signer-ready barriers.

- `ZapReceiptValidatorTest`: all 7 cases of `zap_receipt_validator_test.dart` + §10.6 reason-order
  and `descriptionHashOf` cases.
- `ZapStatsTest`: the 3 cases of `zap_sent_filter_test.dart` (filter builder + `EventGuard`
  matching; the matcher cases also live in `:core:nostr`), §10.6 score/months/label vectors, JSON.
- `ZapProviderResolverTest`: `security_followup_test.dart` "Quittung vom eigenen Schlüssel …"
  (throwing lookup), LNURL cases, 7-day cache, no negative caching (MockWebServer).
- `ZapVerificationServiceTest` (fake relays): provider filter, cache hit/TTL boundary,
  `useCache = false` rewrites the cache (R18), 15-recipient-group cap, stop after 50 receipts,
  relay error discards / normal close keeps, self-zap in both directions, no throw on relay-config
  or cache-write failure.
- `HumanityProofTest` (fake relays): §10.6 humanity flow, re-verification, backup/identity-switch
  keys, status strings; identity switch during a running search or re-verification (R20),
  startup task cancelled when the flag is cleared, concurrent checks share one search, a waiter
  behind a discarded run searches again, startup retries (backoff, signer ready), `ERROR` text.
- `PlatformProofTest`: §10.6 verify-string/custom-id/storage vectors, kind-21003 content, the
  verification decision table, `backup_restore_decision_test.dart` "Plattform-Proofs: das Gerät
  behält denselben Namen".
- `Nip05ServiceTest`, `SatoshiDuellClientTest` (MockWebServer, exact request targets and headers).

## Deviations from the Dart code

- **R9 storage:** `platform_proofs` is parsed per entry (a malformed entry is skipped, a missing
  `created_at` reads 0) and entries not touched by a write are kept verbatim; Dart lost the
  whole list on one bad entry. Consequence: on `createProof`/`removeProof` the other entries are
  written back as stored (extra keys and malformed entries persist), while Dart's
  `_saveAllProofs` rewrote every entry as exactly `{platform, username, proof_sig, created_at}`.
  `unionRaw` keeps an unreadable stored value (not a JSON list) untouched and returns `false`
  (Dart threw before writing).
- **Failed writes:** `checkForZaps` reports `ERROR` (not `FOUND`) if the proof could not be
  stored; `removeProof` returns `false`.
- **R6:** a successful `checkForZaps` also removes `humanity_needs_reverification`.
- **R12:** `ZapStats.fromJson` and `PlatformProof.fromReputationJson` are lenient (integral
  doubles accepted, wrong types → default) instead of throwing.
- **R23:** `checkForZaps` never throws; unexpected errors give `HumanityOutcome.ERROR` (own
  generic message; Flutter had no such outcome and spun forever).
- **Zap stats:** `analyzeZapActivity`/`getMyStats` never throw (Dart let relay-config and
  cache-write errors escape).
- **R20 (identity switch during long work):** a humanity search writes (proof or `checked_at`)
  only if the npub is unchanged, the durable identity generation is unchanged and bound to that npub, and every `humanity_*` key
  still has the value it had when the search started (checked inside the write transaction, so
  a `HumanityKeys.clearIdentityProofs` from `:data:badgesync` is caught too); otherwise the result
  is discarded as `ERROR`. Re-verification accepts only the search's own atomic confirmation;
  it cannot confirm a backup restored afterward. The startup task cancels a running attempt
  when the flag is cleared. Platform create, remove, replace-restore and additive-restore
  commits check the same generation and intended-identity binding inside their transaction.
  Clear/reset operations are fenced too. Separate durable ownership hides foreign data on reads.
  A failed cleanup leaves proofs, owners, generation and binding unchanged, and the synchronous `IdentityChangeHook` blocks
  key persistence and signer-ready notifications as before.
- **Single flight:** `checkForZaps` runs one search at a time; a caller that waited for a
  search finished meanwhile gets its result instead of searching again, only if that search was
  not discarded, the npub is the same and no `humanity_*` key changed since it finished (a
  restore in between always gets a fresh relay check). Proof writes and this bookkeeping are
  non-cancellable, so an attempt cancelled by its own write still shares its result.
- **Re-verification retries** within the process (signer ready, 1/5/15 min backoff) instead of
  Dart's retry on every home-screen load.
- Platform entries match `removeProof`/`createProof` only with a **string** `platform` (the
  same rule as the reader; Dart's `as String` threw on such an entry).
- Service messages are typed (`ProofCreateError`, `VerifyMessage`, `HumanityOutcome`,
  `LastChecked`, `ZapActivity`, `Nip05DomainType`) with the exact German texts as `.message` /
  `.german`.
- The humanity search uses `RelayClient.queryMulti`: it ends after one EOSE **per**
  subscription (Dart: any 3 EOSE frames) or on CLOSED, 12 s max — same collected events.
- All Dart `toLowerCase`/`trim` go through `:data:badges` `DartString` (byte-exact, spec 00 #12),
  e.g. the custom platform id, which is signed.
- Kind comparisons in `checkLocal` use Dart number equality (`9735.0 == 9735`); such an event
  then fails at `receipt_sig` as in Dart.
- `providerPubkeyFromLnurlp(url)` takes a `String` (Dart `Uri`) and decodes the body strictly
  as UTF-8 (malformed → exception → not cached), like `utf8.decode`.
- `SatoshiDuellClient` caps every response at 1 MiB and decodes it strictly as UTF-8 (Dart: no
  cap, `http`'s charset-based `r.body`). An oversized or malformed body is an error, so the
  cached value (or `DuellStatus.EMPTY`) is used.
- Spec 06 §10.6 vector `lightningScore(1,0,1,0,true) = 0.5681818181818182` is the decimal value;
  IEEE doubles (Dart and JVM with the same operation order) give `0.5681818181818181`, which the
  test asserts.

Kept for parity (documented in spec 06 §14): first-found humanity receipt as `first_zap_at`
(R7), current-lud16 provider binding (R8), exact-case `mine`/`isSent` comparisons (R10),
NIP-05 redirects followed and unencoded URL (R14), raw lud16 user part (R15), targeted challenge
counted in `myTurn` and `lobby` (R16), zap-stats cache written for every pubkey (R18), sequential
relay loops (R17), verify-string check is format only (R2 not implemented).

## Known gaps

- `PlatformProofVerifier` needs `:data:reputation` to supply `fetchByNpub` (spec §13 put a
  `ReputationFetcher` into its constructor; that would be a module cycle); the screens call it
  through that module.
- Reputation publishing after create/revoke/humanity check is the caller's job (no dependency
  on `:data:reputation` here, it would be a cycle).
- Old `zap_stats_cache_<pk>` (v1) keys are neither read nor deleted.
