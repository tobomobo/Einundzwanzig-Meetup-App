# :data:badges

The badge domain without UI: the `MeetupBadge` model and its wire/hash formats, the badge
security primitives (compact kind-21000 badges, kind-21001 reputation-QR envelope, kind-21002
claims), `MeetupKey`, the retroactive `BadgeClaimService`, and the encrypted `BadgeRepository`
(a 1:1 port of the Flutter store algorithms). Specs: `spec/02-badges-claim.md` §3, §4, §7, §8
and `spec/03-badge-wallet-ui.md` §3, §7, §8.8. Dart sources: `lib/models/badge.dart`,
`lib/services/badge_security.dart`, `lib/services/badge_claim_service.dart`,
`lib/services/meetup_key.dart`.

```kotlin
implementation(project(":data:badges"))
```

`api` dependencies: `:core:common` (`DartDateTime`, `DartJson`, `TimeSource`), `:core:data`
(`KeyValueStore`, `SecureStore`), `:data:identity` (`SigningService`, `AppReviewDemo`; it brings
`:core:nostr` and `:core:crypto`).

Package root: `space.einundzwanzig.meetup.data.badges`

## Model — `…model`

| API | Purpose |
|---|---|
| `class MeetupBadge(id, meetupName, date: DartDateTime, iconPath, coverUrl="", blockHeight=0L, signerNpub="", meetupEventId="", delivery="nfc", sig="", sigId="", adminPubkey="", sigVersion=0L, sigContent="", claimSig="", claimEventId="", claimPubkey="", claimTimestamp=0L, isRetroactive=false, presenceVerified=true, isOrganizer=false, isEvent=false, lat=0.0, lng=0.0)` | Immutable. **Equality is identity**, as in Dart (no `==` override). Compare with `BadgeIdentity` |
| `hasCryptoProof`, `isNostrSigned`, `isClaimed`, `isFullyBound`, `proofId`, `claimProofId` | Derived properties, byte-exact (spec 02 §3.1). A non-empty `sig` shorter than 64 characters gives `proofId = sig`. Dart throws a `RangeError` there (spec 03 risk 14) |
| `verificationHash()` | Dart `getVerificationHash()`: `sha256("$id-$meetupName-${date.toIso8601String()}-$blockHeight")[0:16]` |
| `toReputationString()` | German share text |
| `toJson()` / `toJsonString()` | Dart `toJson` key order, ints stay ints, `DartJson` encoding |
| `MeetupBadge.fromJson(JsonElement)` / `fromJsonString(s)` | Strict like the Dart casts (a double in an int field throws `BadgeFormatException`). Missing `presenceVerified` → `true`, missing `delivery` → `"nfc"` |
| `MeetupBadge.listFromJsonString(s)` / `listToJsonString(list)` | Blob plaintext (`jsonEncode(List<toJson>)`) |
| `withClaim(claimSig, claimEventId, claimPubkey, claimTimestamp, isRetroactive=false, isOrganizer?, lat?, lng?, presenceVerified=true)` | `presenceVerified` defaults to `true` and does not keep the old value (Dart) |
| `copyWith(...)` | All fields except `isEvent`, which is always kept (Dart) |
| `BadgeIdentity.identityKey(b)` / `unionKey(b)` / `preferVariant(a, b)` / `unionByIdentity(base, extra)` / `sameBadges(a, b)` | One badge per event (spec 02 §8.1). `unionByIdentity` returns the chosen instances in first-seen position |
| `BadgeProofs.generateBadgeProof(list)` / `generateBadgeProofV2(list)` / `verifyBadgeProof(list, expected)` | Proof hash chains. They sort with `DartSort`, so the order of ties matches Dart for every list size |
| `BadgeProofs.getReputationStats(list): ReputationStats` (`toMap()` gives the Dart map), `countVerifiedBadges`, `countBoundBadges` | Stats |
| `BadgeProofs.meetupListHash(name)` | `sha256("21meetup:$name")[0:12]` (`ml` entries of the reputation QR) |
| `BadgeProofs.exportBadgesForReputation(badges, userNpub, exportedAt, nickname="", telegram="", twitter="")` | JSON v4.0 export (2-space pretty, checksum last). Pass `DartDateTime.now(timeSource)` as `exportedAt` |
| `FormerCollection(index, at, reason, npub, badges?)` | Archive entry view (`badges == null`: no key on the device opens it) |
| `BadgeFormatException` | Thrown by the strict parsers |

## Security — `…security`

| API | Purpose |
|---|---|
| `BadgeSecurity.signCompact(signer, meetupId, blockHeight, nowSeconds, validityHours=4): JsonObject` | Organizer signing (kind 21000). Returns the base payload `v,t,m,b,x,c,p,s` |
| `BadgeSecurity.verifyCompact(data, nowSeconds, checkExpiry=true)` / `verifyNostr(data)` / `verify(data, nowSeconds, checkExpiry=true): VerifyResult` | Verification with Flutter's messages. v1/v0 are rejected (Audit C1) |
| `BadgeSecurity.normalize(data, zone)` | Compact/QR → normalized map (spec 02 §8.3) |
| `BadgeSecurity.isExpired(data, nowSeconds)` / `expiryInfo(data, nowSeconds): ExpiryInfo` (`toGermanString()`) | Expiry |
| `BadgeSecurity.eventMeetupId(address, title)` / `eventAddressOf(normalized)` / `eventBadgeKey(address)` | Event-badge `m` and keys (spec 02 §8.4) |
| `BadgeSecurity.signedCoordinates(data)` | Always `(0.0, 0.0)`: `la`/`lo` are unsigned (Audit H1) |
| `BadgeSecurity.signQrV3(signer, json)` / `verifyQrV3(...)` / `parseQrV3(code)` / `verifyQrLegacy(...)` | Reputation QR `21v3:` (kind 21001, tags `[]`). `parseQrV3` throws `IllegalArgumentException` for bad base64 or invalid UTF-8 (show `qrReadError`). The legacy check always rejects |
| `BadgeSecurity.canonicalJsonEncode(map)`, `isValidPubkeyHex`, `isValidSignatureHex`, `signLegacy` (→ `""`), `verifyLegacy` (→ `false`) | Helpers. Canonical JSON delegates to `DartJson` |
| `BadgeSecurity.KIND_BADGE` (21000), `KIND_REPUTATION_QR` (21001), `QR_V3_PREFIX`, `BADGE_VALIDITY_HOURS`, `COMPACT_SIGNED_KEYS` | Constants |
| `VerifyResult`, `QrSignResult`, `QrVerifyResult`, `ReputationQrV3Parts`, `ExpiryInfo` | Result types |
| `MeetupKey.slug(name)` / `compose(slug, country)` / `parse(m): ParsedMeetupId` / `displayFromSlug(slug)` / `eventId(slug, date)` / `isIsoCountry(code)` | Meetup ids (spec 02 §8.2). The full ISO-3166 set has 249 codes, and `UK` is not one of them |
| `interface BadgeSigner` (`canSign()`, `signEvent(kind, tags, content, createdAt: Long? = null)`), `BadgeSigner.of(signingService)` | The signing port. Hilt binds it to `SigningService`. `createdAt` asks for that `created_at` (claims). `SigningService` cannot take it yet, so the adapter drops it and callers compare `NostrEvent.createdAt` |

## Claims — `…claim`

| API | Purpose |
|---|---|
| `interface BadgeClaimService` (inject) | `createClaim(orgSig, orgEventId, orgPubkey, blockHeight): ClaimResult`, `verifyClaim(...)`, `verifyBadgeClaim(badge)`, `claimUnboundBadges(list): ClaimBatchResult`, `ensureBadgesClaimed(list): ClaimBatchResult`. Pass `repository.badges.value`. The batch loads the store itself if needed, does nothing while it is unreadable, and only asks the signer for unclaimed badges that are actually stored (the caller's list only selects which identities to claim). Batches are single-flight: a second concurrent caller waits and skips what the first one claimed, so there is one signer prompt per badge. Claims are signed outside the repository lock, then applied with `BadgeRepository.update` to the collection as it is at that point. Badges scanned meanwhile stay, badges removed meanwhile are not brought back, and a claim is only applied if the stored variant still carries the same org sig/event id/pubkey/block height (only the claim fields are written onto it). `ClaimBatchResult.badges` is the stored collection, or the input list if nothing was stored |
| `BadgeClaims.KIND_CLAIM` (21002), `claimContent(...)`, `claimTags(orgPubkey, blockHeight)`, `verifyClaim(...)`, `verifyBadgeClaim(badge)` | Pure claim format and verification, used by `:data:admin` (promotion claims) and `:data:reputation` |
| `ClaimResult`, `ClaimVerifyResult`, `ClaimBatchResult(badges, claimedCount)` | Results |

## Store — `…store`

`interface BadgeRepository` (inject the `@Singleton`; tests: `BadgeRepository.create(prefs, secure, timeSource, dispatcher)` with `InMemoryKeyValueStore`/`InMemorySecureStore`). Every operation runs serialized and **non-cancellable** (like Dart futures): cancelling the caller (e.g. leaving a screen) never stops a multi-step write halfway; the caller sees its cancellation after the operation returns.

| Member | Dart |
|---|---|
| `badges: StateFlow<List<MeetupBadge>>` | global `myBadges`. It is updated by load, save, clear, promote, merge and reset |
| `loaded: StateFlow<Boolean>` | Native only. `false` until the store was read once in this process. While it is `false`, `badges`/`badgesUnreadable` mean nothing: show loading and never empty-state actions (spec 03 risk 13). `badges` is published before `loaded` flips to `true` |
| `update { current -> newList or null }: Boolean` | Native only. **This is the preferred write path**: an atomic read-modify-write under the repository lock (scanner add, duplicate cleanup, organizer marker, sync merge). `null` or the same instance means no change. Returns `false` if the wallet is unreadable (the transform is not called) or the save failed. The transform must be pure and must not call the repository |
| `badgesUnreadable: StateFlow<Boolean>` | `MeetupBadge.badgesUnreadable`. While it is `true`, an empty list is not an empty wallet: never offer actions that write `[]` |
| `savedEvents: SharedFlow<Unit>` | `onBadgesSaved`. It fires after every successful save, clear, promote and merge. `:data:badgesync` collects it |
| `loadBadges()` | `loadBadges()` (repairs, unites every version that opens, merges kept-aside badges) |
| `saveBadges(list, replaceUnreadable=false, discardLocked=false): Boolean` | `saveBadges`. Replaces the whole collection, so prefer `update` for read-modify-write. `[]` clears by archiving and never deletes. Backup restore uses `replaceUnreadable = true` |
| `keepAside(badge): Boolean` | Keeps a scan aside while the wallet is unreadable. It is encrypted with its own key |
| `stageIncoming(list)` / `promoteIncoming(formerNpub="")` / `discardIncoming()` | Staging for an identity switch (crash-safe promotion). After promotion the store reloads; if no device key opens the promoted file it is marked unreadable and kept |
| `formerCollections()` / `mergeFormer(index)` | Archive (`badges_former`, max 10 entries / 500 Ki characters). An out-of-range `at` shows as the epoch |
| `resetAll()` | The only real delete (app reset) |

`BadgeStoreKeys` lists the exact Flutter storage keys: prefs `badges_encrypted`, `…_preserved`,
`…_preserved_older`, `badges`, `badges_pending_encrypted`, `badges_pending`,
`badges_encrypted_incoming`, `badges_former`; secure `badge_encryption_key` (+`_BACKUP`,
`_suspect`), `badges_encrypted`, `badges_former`, `badge_pending_key` (+`_suspect`) and
`badge_reset_pending`. The blob is `b64(iv):b64(ct‖tag)` (`AesGcmBlob` from `:core:crypto`).

Every write path (`saveBadges`, `update`, `keepAside`, `stageIncoming`, `promoteIncoming`) loads
first if nothing was loaded in this process yet. A write from a fresh process, such as a worker,
therefore never overwrites a file that only the `_BACKUP` key or another key opens. The key
rescue tries every 64-hex secure entry except the identity secrets
(`IdentityKeys.IDENTITY_SECRET_NAMES`, their `_BACKUP` copies and `:data:migration` recovery
copies).

## Review demo — `…review`

`BadgeReviewDemo.isReviewDemoPayload(raw)`: both scanners call it **before** parsing and show the
`AppReviewDemo` dialog instead of issuing a badge. `toMeetupBadge(demo)` converts identity's demo
badge. The internal `ReviewDemoBadgeWriter` binding fills identity's optional hook.

## Dart helpers — `…text`, `…util`

| API | Purpose |
|---|---|
| `DartString.trim/lower/upper(s)`, `warmUp()` | Byte-exact Dart `trim()`/`toLowerCase()`/`toUpperCase()` (spec 02 §8.14, spec 00 item 12). Uses the shipped `dart_case_table.txt`. Use it for every identity key, slug and npub normalisation. The table is read on first use, and every repository load warms it on the IO dispatcher. Call `warmUp()` off the main thread if you need it earlier. These are meant to move to `:core:common` (core owner). **Until they do, other modules must depend on `:data:badges` for `DartString`/`DartSort` and must never copy them**: a copy can drift and split or merge badges, trust ids, co-attendance keys or slugs |
| `DartSort.sort(list, cmp)` / `sorted(items, cmp)` | Dart VM `List.sort`: insertion sort (stable) for ≤ 33 elements, otherwise dual-pivot quicksort (not stable). Use it wherever Dart sorts before hashing or ordering (spec 00 item 10) |

## Tests

```
tools/gradlew-locked :data:badges:testDebugUnitTest
```

The suite ports `badge_model_test`, `badge_security_test`, `meetup_key_test`,
`badge_key_retention_test`, `badge_storage_copy_test`, the badge-level parts of
`badge_reputation_regression_test` and `promotion_claim_k1_test`, and `app_review_demo_test`
(run the task for the current count; per-suite numbers are deliberately not listed). They also
cover every badge vector of `spec/fixtures/02-badges-claim-vectors.json` except `rollingQr`,
which belongs to `:data:issuance` (the tests read the file directly), plus native tests for
`DartString`, `DartSort`, the claim batch and the repository flows.

The score-based cases of `badge_reputation_regression_test` live in `:data:trust`
(`TrustScoreRegressionTest`): 'Organisator-Marker anderer Städte erhöhen die Reputation nicht',
'Mehrfachscans belegen keine zusätzlichen Plätze im Wochenlimit' and the
`score([signedMarker]).totalScore == 0` assertion of 'auch Marker mit Signatur…'.
