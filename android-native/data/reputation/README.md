# :data:reputation

The user's published reputation and the reputation QR, without UI: the kind-30078 reputation
event (build, sign, publish, throttle, fetch, parse), the signed reputation QR (`21v3:`, payload
v6) and its scanner-side parser, and the verify-string check. Spec: `spec/04-trust-reputation.md`
§3.7–§3.10, §4, §5.2–§5.3, §6, §7.5–§7.8, §8.6–§8.8, §8.10–§8.11, §8.13, §9; spec 06 §14 R4.
Dart sources: `lib/services/reputation_publisher.dart`, `lib/screens/reputation_qr.dart`
(`_generateQRData`), `lib/screens/qr_scanner.dart` (`_verifyAndShow`, `_showV3Result`),
`PlatformProofService.verifyProofString`.

```kotlin
implementation(project(":data:reputation"))
```

`api` dependencies (types in the public API): `:core:common`, `:core:nostr`, `:data:identity`
(`UserProfile`), `:data:badges` (`MeetupBadge`, `QrSignResult`), `:data:trust` (`TrustScore`),
`:data:proofs` (`PlatformProof`, `ZapStats`, `ProofVerifyResult`), `:data:socialgraph`
(`SocialStats`). `implementation`: `:core:data`.

Package root: `space.einundzwanzig.meetup.data.reputation`. Inject the interfaces (all
`@Singleton`, bound in `di/ReputationModule`). Pure Trust Score / layer / level logic lives in
`:data:trust`; screens belong to `:feature:reputation`.

## Publish — `…publish`

| API | Purpose |
|---|---|
| `ReputationPublisher.publish(badges, platformProofs = null, force = false): PublishResult` | Dart `publish`. Order: `NoKey` → `NoBadges` → throttle (`UpToDate`) → social/zap stats (10 s timeout each, best-effort) → humanity proof → sign kind 30078 → send to every active relay **sequentially** (connect 5 s, send, linger 2 s, no OK parsing). Pass `BadgeRepository.badges.value`. `platformProofs = null` loads `PlatformProofRepository.getProofsForPublishing()` (not best-effort: a load failure is `Error` and nothing is sent) |
| `ReputationPublisher.publishInBackground(badges)` | Fire-and-forget non-forced publish in `@ApplicationScope`; skipped in Amber (NIP-55) mode (R-19) |
| `sealed PublishResult` | `NoKey`, `NoBadges`, `UpToDate` (success, 0 relays), `Sent(relayCount, eventId)`, `NoRelay(eventId)`, `Error(cause)`; `success`, `relayCount`, `eventId`, `germanMessage` (Flutter text; localize by type in the UI, spec 04 §2.10) |
| `PublishThrottleStore` (`read(): PublishThrottleState`, `save(millis, hash, pubkeyHex)`, `clear()`), `PublishThrottleState.shouldPublish(now, hash, pubkey, minInterval)` | `reputation_last_publish` (Long ms), `reputation_last_hash` (16 hex) + native `reputation_last_pubkey` |
| `ReputationProtocol` | `EVENT_KIND` 30078, `D_TAG`, `PROTOCOL_VERSION` 2, `CLIENT`, `TAGS`, key names, `MIN_PUBLISH_INTERVAL_MS` |

Callers (spec 04 §8.13):
- **Nothing to do after badge saves.** The module contributes the `AppStartupTask`
  `ReputationPublish`: once `BadgeRepository.loaded`, after every `BadgeRepository.savedEvents`
  (scan, merge, cleanup, claims, restore) and after every `SigningService.signerReady`, debounced
  by 3 s (a burst publishes once, with the final wallet), it calls `publishInBackground(wallet)`
  unless the wallet is unreadable or empty. Calling `publishInBackground` yourself is harmless
  (throttled).
- **Forced** (`force = true`): the QR screen button ("AUF RELAYS AKTUALISIEREN"), platform proof
  create/revoke (`:feature:proofs`), and a successful humanity check (spec 04 R-6 / 06 R5:
  `publish(badges, force = true)`, not Flutter's no-op `publishInBackground([])`).
- Show `PublishResult` in a snackbar (green if `success`) for 3 s, plus the message under the button.

## Content — `…content`

`object ReputationContentBuilder`: `publishBasis(badges)` (signed, non-organizer),
`build(basis, nickname, social?, zaps?, humanity?, platformProofs?, time, zone): JsonObject`
(spec 04 §3.10 key order and types; `social` only if `followCount > 0`, `lightning` only if
`totalCount > 0`, `humanity_proof` if non-null, `platform_proofs` if non-empty), `encode(content)`
(Dart `jsonEncode`), `changeHash(basis)`. Math comes from `:data:trust` `RelayReputationMath`.

## Fetch and parse — `…fetch`, `…event`

| API | Purpose |
|---|---|
| `ReputationFetcher.fetchByNpub(npub)` / `fetchByPubkeyHex(hex)` | REQ `rep-fetch-<16 hex>` `{"kinds":[30078],"authors":[hex],"#d":["einundzwanzig-reputation"],"limit":1}` to all active relays (parallel, 8 s); events verified (id, BIP-340, 10-digit `created_at`) and filter-matched by `RelayClient`; exact author; newest valid `created_at` ≤ now + 600 s wins. `null` if none |
| `data class ReputationEvent` | All fields of spec 04 §3.8; `ageInHours(nowSec)`, `isFresh(nowSec)`, `platformProof(platform): PlatformProof?`, `toFacts(): ReputationFacts` |
| `object ReputationEventParser` | `parse(NostrEvent)`, `parseRaw(JsonObject)`, `parse(pubkey, id, sig, createdAt, contentText)`, `fromContent(…, JsonObject)`. `null` only if the content is not a JSON object. Does not verify |

## Reputation QR — `…qr`

| API | Purpose |
|---|---|
| `ReputationQrGenerator.generate(badges)` / `generateForCurrentBadges()` → `ReputationQr` | REPUTATION screen data. `Empty(user, platformProofCount)` or `Content(user, platformProofCount, qrData, isSigned, signError, score: TrustScore, verifiedBadgeCount, boundBadgeCount, countedBadgeCount, organizerMeetupCount)` with `hasIdentity` (warning banner) and `proofStatus: ProofStatus` (`ALL_BOUND, PARTLY_BOUND, ALL_VERIFIED, SOME_VERIFIED, NONE`; string table in its KDoc, args `(total, bound)` per R-17). Signs kind 21001 via `BadgeSigner` (Amber/NIP-46 may prompt: call from a ViewModel). Signer failure → unsigned `21:` code + `signError` (no endless spinner) |
| `ReputationQr.organizerMeetupCount(badges)` | §8.11 (`reputationOrganizerNote`) |
| `object ReputationQrCodec` | `buildPayload(user, badges, score, proofs, nowMillis)` (payload v6, §3.9), `payloadJson`, `base64`, `encode(json, QrSignResult)`, `encodeSigned`, `encodeUnsigned`, `roundedScore`, `isReputationCode(code)` (scanner filter: `21:`/`21v2:`/`21v3:`), `parse(code): QrParseResult` |
| `sealed QrParseResult` | `Verified(data: ReputationQrData, signerNpub, message, identityMismatch)` (+ `hasTrustedIdentity`, `layersNpub`) → title `qrVerified`; `SignatureInvalid(version, message)` → `qrSigInvalid` (bad v3 sig and every legacy code); `UnknownFormat` → `qrFormatUnknown`/`qrCantRead`; `ReadError(cause)` → `qrReadError`/`qrProcessError(cause.toString())` |
| `data class ReputationQrData` | `_showV3Result` fields: `identity`, `nickname`, `claimedNpub`, `telegram`, `twitter`, `hasIdentity`, `trustLevel` (local level wire string; localize with `TrustLevelStyle`), `trustScore`, `badgeCount`, `verifiedBadgeCount`, `boundBadgeCount`, `meetupCount`, `signerCount`, `accountAgeDays`, `meetupList`, `badgeProof`, `proofTotalCount`, `proofVerifiedCount`, `platformProofs` (+ `platformUsernames`), `payloadVersion`, `timestampMillis` |

Rendering: `:core:ui` `QrCode(qrData, size = 220.dp, errorCorrection = L)` on white (byte mode,
same bytes as `qr_flutter`). The App-review demo payload is checked by the scanner before
`parse` (`BadgeReviewDemo.isReviewDemoPayload`). On `Verified.identityMismatch` show a prominent
warning (new string, e.g. `native_qrIdentityMismatch`) and load the layers for `layersNpub`.
Label `pp` entries as linked, not cryptographically verified (S-9).

## Verify string — `…verify`

`ReputationProofVerifier.verifyProofString(input, expectedUsername = null): ProofVerifyResult<ReputationEvent>`
(injectable `@Singleton`): `:data:proofs` `PlatformProofVerifier` wired to
`ReputationFetcher.fetchByNpub`. Levels `FULL/PARTIAL/SIGNATURE_ONLY/INVALID`, typed
`VerifyMessage` (`.german`). Accepts a `21rep::…` string or a bare `npub1…`.

## Tests

`tools/gradlew-locked :data:reputation:testDebugUnitTest` — 71 JVM tests, reading
`spec/fixtures/04-trust-reputation-vectors.json` directly:

- `ReputationQrCodecTest` (18): byte-exact payload JSON and base64, zero-aux signature and the
  exact 987-char `qr_string`, verify → `expected_scan_result_fields`, tampered, unsigned/legacy
  v1 and v2, unknown/read errors, int typing, builder layout vs. fixture, S-8 mismatch, lenient
  scalars, URL-safe base64.
- `ReputationEventParserTest` (9): fixture → `expected_parsed`, byte-exact `publish_frame`, `{}` → defaults, non-object
  content, lenient types, `ageInHours`/`isFresh`.
- `ReputationContentBuilderTest` (8): fixture layout (§3.10), basic-score inputs, optional
  blocks, int/double typing, publish basis, change hash, `since` zone.
- `PublishThrottleTest` (8): `publishContentHash16`, 5-min window, hash, identity switch,
  migrated state, store keys.
- `ReputationPublisherTest` (14): order of operations, sequential relays, exact frame, proofs
  auto-loaded, proof load failure sends nothing, throttle/force, throttle with empty
  `pubkeyHex()`, identity switch, no-relay, stats timeout/failure, signer error, background
  publish (local, NIP-46; skipped with Amber).
- `ReputationFetcherTest` (13): fixture fetch, exact REQ, invalid npubs, bad sig / wrong
  author / wrong `d` / non-object content ignored, newest wins, verify-string matrix.
- `ReputationQrGeneratorTest` (10): empty, signed round trip with counts, signer failure, no
  signer, proof-status table, `generateForCurrentBadges` loads first, startup task (load/save,
  existing wallet, debounced burst, `signerReady`).

The trust-score tests of spec 04 §10.1–§10.3 (`trust_score_test`, the regression test, badge
model) live in `:data:trust` and `:data:badges`; `relayBasicScore`/`toStringAsFixed1`/`meetupListHash12`
are covered in `:data:trust` (`RelayReputationMath`).

## Deviations from the Dart code (all wire-compatible)

- **R-4 / §8.8:** the publisher counts only `isNostrSigned && !isOrganizer` badges (content
  stats, proof hash, change hash and the "no badges" guard). Flutter counted all badges.
- **R-5 / spec 06 R4:** `platformProofs = null` loads the saved proofs, so background publishes
  never drop `platform_proofs` from relays.
- **R-7:** the throttle is saved only when ≥ 1 relay was reached.
- **§4 identity switch:** the throttle also stores the publishing pubkey; a publish of another
  identity is never throttled (values migrated from Flutter have no pubkey: one extra publish).
- **R-9 / §5.3:** fetch queries all relays in parallel, checks kind/author/`d` tag, and takes the
  newest valid event (Flutter: first relay that answers, no kind/`d` check, sequential).
- **Reader leniency:** `ReputationEventParser` and `ReputationQrCodec.parse` accept integral
  doubles for int fields and fall back to defaults for other wrong scalar types (Flutter
  dropped the event / showed LESEFEHLER). Writers emit Dart types exactly.
- **S-8:** QR claimed `id.np` ≠ signer is flagged (`identityMismatch`); a non-empty `id.np`
  that is not a string counts as a mismatch.
- **QR signer failure:** unsigned fallback with `signError` instead of an endless spinner;
  `isSigned` reflects the real signature (R-8; Flutter showed "signiert" when an identity was set).
- **Triggers:** background publishes come from one startup task (load + every badge save +
  signer ready, debounced 3 s), so claim batches and restores also republish (throttled) where
  Flutter relied on screen calls.
- **R-19 Amber:** no background publish in Amber mode (it shares one foreground signer slot
  with claims/QR signing and would fail them with `busy` or pop up unasked). Amber users
  publish through the forced paths. NIP-46 keeps background publishing (its connect
  permissions include `sign_event:30078`, as Flutter relies on).
- **Throttle with empty `pubkeyHex()`:** the identity check falls back to the stored pubkey,
  so the throttle still applies (the save records the signed event's pubkey).
- Publishes are serialized by a mutex (Flutter could run two concurrently).
- A non-finite Trust Score writes `sc: 0.0` (Dart `jsonEncode` would throw).

## Known gaps

- No ZXing bitmap here: QR rendering is `:core:ui` `QrCode`/`QrMatrix` (a data module has no UI).
  PNG export/share of the card belongs to `:feature:reputation`.
- `NpubExtractor` (VerifyPerson scanner) and `TrustLayersEvaluator` input mapping are feature /
  `:data:trust` concerns, not here.
- A NIP-46 bunker that asks for approval of 30078 despite the connect permissions can still
  send an `auth_url` for a background publish (as in Flutter).
