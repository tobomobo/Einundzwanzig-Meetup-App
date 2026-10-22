# :data:issuance

Badge issuance and scanning without UI (spec 02 §2.1, §2.3–2.5, §2.8, §3.4–3.6, §4.3, §7.5,
§8.5–8.10): the organizer's rolling-QR session, event-badge sessions/auth/trust chain, the
participant's scan processing and save use case, the presence check and the NFC NDEF payload
logic. Screens live in `:feature:badges` (scanner, rolling QR, NFC writer), `:feature:admin`
(meetup start), `:feature:events` (event session start, badge toggle) and `:feature:home` (LIVE tile).

Package root: `space.einundzwanzig.meetup.data.issuance`. All services are Hilt `@Singleton`s
bound to the interfaces below; inject the interface.

Depends on: `:core:common`, `:core:data`, `:core:crypto`, `:data:badges`, `:data:identity`,
`:data:admin`, `:data:socialgraph`, `:data:calendar`, `:data:meetups`, `:data:mempool`.
`:data:badges`, `:data:calendar`, `:data:meetups` and `:core:common` are `api` (their types
appear in this API). Does **not** depend on `:data:reputation`.

## Session (`session/`)

| API | Purpose |
|---|---|
| `RollingQrService` | `activeSession: StateFlow<MeetupSession?>`, `getOrCreateSession(meetupId, meetupName, meetupCountry, blockHeight, lat = 0.0, lng = 0.0, validUntilEpoch: Long? = null): MeetupSession` (throws `SessionStartException`), `loadSession(): MeetupSession?` (ends + returns `null` when expired), `endSession()`, `getBasePayload(): JsonObject?` (NFC writer), `generateQrString(session): String` (throws `IllegalStateException` without payload; both may re-sign the base payload, see Deviations), `validateNonce(payload)`, `secondsUntilNextChange()` |
| `MeetupSession` | `seed` (secret, redacted in `toString`), `startedAt`, `expiresAt`, `meetupId` (signed `m`), `meetupName`, `meetupCountry`, `blockHeight`, `pubkey` (npub), `lat`, `lng`; `isEventSession`, `isExpired(now)`, `remainingSeconds(now)`, `elapsedSeconds(now)`, `remaining(now): SessionRemaining` |
| `SessionRemaining` | `Expired` / `HoursMinutes(h, m)` / `Minutes(m)`; `toGermanString()` = Flutter text. Localize in UI. |
| `SessionStartException` | `reason: Reason` = `NO_KEY`, `SIGNING_FAILED`, `STORAGE_FAILED`; `message` = Flutter's German text |
| `RollingQr` (object, pure) | `INTERVAL_SECONDS = 10`, `TOLERANCE_STEPS = 1`, `SESSION_VALIDITY_HOURS = 4`, `timeStep(now)`, `nonce(seed, step)`, `buildQrString(baseJson, session, now)`, `validateNonce(payload, now)`, `hasNonce(payload)`, `secondsUntilNextChange(now)`, `currentProgress(now)` |
| `NonceValidation(isValid, status: NonceStatus, ageSeconds, message)` | `NonceStatus` = `NOT_ROLLING`, `FRESH`, `WITHIN_TOLERANCE`, `EXPIRED`, `MALFORMED` |
| `RollingQrKeys` | exact Flutter keys: secure `rqr_session_seed_secure`, `rqr_session_base_payload_secure`; prefs `rqr_session_start`, `rqr_session_expires` (admin-panel countdown may read it), `rqr_session_meetup_id`, `_meetup_name`, `_meetup_country`, `_block_height`, `_pubkey`, `_lat`, `_lng`; legacy `rqr_session_seed`, `rqr_session_base_payload` (migrated to secure, then removed) |
| `OrganizerSessionService` | Admin panel "MEETUP STARTEN" after the meetup was chosen: `startMeetupSession(meetupName, meetupCountry, lat = 0.0, lng = 0.0): OrganizerSessionResult(session, marker: MeetupBadge?)` — ends the old session, signs `m = MeetupKey.compose(MeetupKey.slug(name), country)`, records the organizer marker (`CoAttendanceRepository.recordOrganizerAttendance`, awaited). Throws `SessionStartException`. |

Rolling QR UI: call `generateQrString(session)` every 10 s (aligning to step boundaries is
allowed), countdown = `secondsUntilNextChange()`; render with ZXing, ECC **M**.

## Event badges (`event/`)

| API | Purpose |
|---|---|
| `EventBadgeSessionService.start(event: NostrCalendarEvent): EventSessionResult` | helper starts the session (issuer, window, venue, own GPS ≤ `ISSUER_RADIUS_KM = 5.0`); session lasts until local midnight after the last event day; organizer marker recorded in `@ApplicationScope`. Never throws. |
| `EventSessionResult(session, error: EventSessionError?, distanceKm)` | `ok`; `EventSessionError` → ARB key: `NO_IDENTITY` evSessionNoIdentity, `NOT_ISSUER` evSessionNotIssuer, `OUTSIDE_WINDOW` evSessionOutsideWindow, `NO_EVENT_LOCATION` evSessionNoEventLocation, `LOCATION_UNAVAILABLE` evSessionNoLocation, `LOCATION_SERVICE_OFF` evSessionLocationOff (+ location settings action), `LOCATION_DENIED` evSessionLocationDenied, `LOCATION_DENIED_FOREVER` evSessionLocationDeniedForever (+ app settings action), `LOCATION_NO_FIX` evSessionLocationNoFix (10 s snackbar), `TOO_FAR_AWAY` evSessionTooFar(`distanceKm.toStringAsFixed(1)`), `SESSION_FAILED` evSessionFailed |
| `EventBadgeAuthService.myRight(): EventBadgeRight`, `mayCreate()` | event editor badge toggle; `EventBadgeRight` = `NONE`, `ADMIN_REGISTRY`, `PORTAL_LEADER` |
| `EventBadgeChainService.verify(eventAddress, signerPubkey, issuedAt): EventChainResult` | scan-time trust chain; `EventChainStatus` = `VERIFIED`, `SIGNER_NOT_LISTED`, `CREATOR_NOT_AUTHORIZED`, `EVENT_HAS_NO_BADGE`, `EVENT_NOT_FOUND`; result has `eventTitle`, `creatorPubkey`, `creatorName`, `badgeImageUrl`, `ok` |

## Scanning and saving (`scan/`)

| API | Purpose |
|---|---|
| `BadgeQrParser.parse(raw): QrDetection?` / `firstMatch(rawValues)` | camera/gallery filter: `QrDetection.ReviewDemo` or `QrDetection.Badge(payload)` (JSON object with `t`/`type`); `null` = ignore (gallery stays silent) |
| `BadgeScanProcessor` | `processQrText(raw, unknownMeetupLabel): ScanOutcome?`, `processQr(scanned: JsonObject, unknownMeetupLabel): ScanOutcome`, `processNfcPayload(recordPayload: ByteArray, unknownMeetupLabel): ScanOutcome`. Pass the localized `verifyUnknownMeetup` as `unknownMeetupLabel`. Nothing is stored. |
| `ScanOutcome` | `ReviewDemo` (English "Demo scan OK" dialog), `NonceRejected(validation)` (status `EXPIRED`) → `verifyErrQrExpired(validation.message)`, `VerificationFailed(result: VerifyResult)` → `verifyErrPrefix(result.message)`, `NfcRejected(error: NfcPayloadError)`, `Malformed(detail, rollingQrFormat)` (wrongly typed `n`/`ts` → `rollingQrFormat = true`, or payload fields) → `verifyErrPrefix(detail)`; localize `VerificationFailed` via `result.reason`, `SelfScan` → `"✗ " + verifyCantSelfBadge + verifyAskScan`, `AlreadyCollected(fullName, isEvent)` → `verifyAlreadyEvent` / `verifyAlreadyToday`, `Found(pending: PendingBadge, info: FoundInfo)` |
| `FoundInfo` | `fullName`, `blockHeight`, `verifiedBy`, `sigVersion`, `claim: ClaimInfo`, `adminCheck: AdminCheckInfo`, `expiry: ExpiryInfo`, `unknownSigner` (orange accent); `message(strings: ScanStrings)` builds Flutter's exact confirmation text |
| `ScanStrings` | implement in the feature with string resources (`verifyBadgeFound`, `verifyMsg*`, `mv*`, `verifyVerifiedAdmin`); `expiry(info)` and `claimFailed(ClaimInfo.Failed)` (carries `reason: ClaimFailure?`) default to the German Flutter text — override to localize |
| `ClaimInfo` / `AdminCheckInfo` | typed binding / signer-recognition lines (see KDoc for the ARB key of each case) |
| `PendingBadge(badge, orgLat, orgLng)` | scanned, unsaved badge; keep it in the ViewModel for retries |
| `BadgeSaveUseCase.confirm(pending, openedForMeetupLat = 0.0, openedForMeetupLng = 0.0): SaveOutcome` | "ZUR WALLET HINZUFÜGEN": `openedForMeetup*` = coordinates of the meetup the scanner was opened for (Dart `widget.meetup.lat/lng`, 0/0 for the global scan button), **never the device position**; location (show a blocking `LoadingIndicator`, up to ~45 s), presence check, atomic duplicate guard + save, keep-aside |
| `SaveOutcome` | `TooFar(distanceKm, allowedKm)` → `gpsTooFar`/`gpsTooFarSub(km 1 decimal, allowedKm 0 decimals)`, badge stays pending; `Saved(badge, presenceVerified, askCoAttendance, collection)` → `verifyBadgeSaved`, **call `ReputationPublisher.publishInBackground(outcome.collection)`** (Dart step 12), opt-in dialog if `askCoAttendance`, then wallet; `Duplicate` → `verifyBadgeDuplicate` + wallet; `KeptAside` → `badgesHeldAside` (8 s); `SaveFailed(pending)` → red `badgeSaveFailed` (8 s). Every case except `TooFar` has `presenceVerified`; `false` → `badgeUnverifiedInfo` (5 s). |
| `BadgeSaveUseCase.publishCoAttendance(badge): Int` | after the user agreed in the opt-in dialog (ask every time): `> 0` → `caPublished`, `0` → `caPublishFailed` |
| `BadgeSaveUseCase.badgeSaved: SharedFlow<BadgeSavedEvent(badge, collection)>` | optional notification for `:app`-level observers; not replayed, dropped without a collector. **Not** the reputation hook: the scanner ViewModel in `:feature:badges` publishes from `SaveOutcome.Saved.collection` (this module does not know `:data:reputation`). |
| `PresenceCheck` (object, pure) | `PARTICIPANT_RADIUS_KM = 5.0`, `DERIVED_RADIUS_KM = 50.0`, `reference(signedLat, signedLng, openedForMeetupLat, openedForMeetupLng, directoryLookup)`, `findReferenceMeetup(name, meetups)`, `evaluate(locationOk, lat, lng, reference): PresenceResult` (`Verified` / `TooFar` / `Unverified(reason)`); `PresenceReference`, `ReferenceSource` (`SIGNED`, `OPENED_FOR_MEETUP`, `DIRECTORY`) |

## NFC (`nfc/`, flag off)

| API | Purpose |
|---|---|
| `IssuanceFeatures.NFC_ENABLED = false` | Dart `kNfcEnabled`; a copy of `:data:settings` `FeatureFlags.NFC_ENABLED` (this module may not depend on settings). Open handoff: move the flag to `:core:common` and reference it from both, or add an `:app` unit test asserting `IssuanceFeatures.NFC_ENABLED == FeatureFlags.NFC_ENABLED`. While false: no NFC permission, no NDEF intent filter, no NFC buttons. |
| `NfcBadgePayload` | `tagJson(basePayload)`, `encodeTextPayload(json)` (`02 'e' 'n'` + UTF-8; equals `NdefRecord.createTextRecord("en", json)`), `decodeTextPayload(payload): Result` (`Text` / `Error(NfcPayloadError)`), `actualSize(json)` (`length + 7`), `isTooLarge(json, maxSize)`, `estimatedSize(city, country)`, `classifyWriteError(message): NfcWriteError`, `RECORD_TYPE_TEXT`, `VALID_HOURS = 4` |
| `NfcPayloadError` | `NoNdef`, `TagEmpty` (the reader in the feature detects these two), `PayloadEmpty`, `InvalidFormat`, `InvalidTag(detail)`, `ReadError(detail)` → `verifyErr*` keys |
| `NfcWriteError` | `TAG_REMOVED_EARLY`, `TAG_LOST`, `OTHER` |

The reader/writer sessions (`NfcAdapter.enableReaderMode`, `Ndef`, `NdefFormatable`) belong in
the feature behind the flag; they call `BadgeScanProcessor.processNfcPayload` /
`RollingQrService.getBasePayload` + `NfcBadgePayload`.

## Deviations from Flutter (deliberate)

- Signed `x` keeps Flutter's bound `now + 4 h` (capped at the session end), the replay bound of a
  photographed QR (docs/ROLLING_QR_SESSION_KEY.md, spec 02 §9 item 10; `x = expiresAt` was
  rejected because a nonce-stripped photo would stay valid for the whole event). Instead, when
  `x` is less than 10 min away and the session continues (event sessions until midnight),
  `generateQrString`/`getBasePayload` re-sign the base payload (same `m`/`b`) and store it
  atomically: event QRs no longer die after 4 h (spec 02 §8.5/§14.4). With Amber/bunker this is
  one signer prompt per ~4 h; a refused re-sign keeps the old payload and retries after 60 s.
  Wire-compatible.
- Session start is serialized (`getOrCreateSession` check-then-create) and the expiry check and
  wipe in `loadSession` share one critical section, so concurrent starts sign once and a fresh
  session is never wiped as expired.
- `TooFar.allowedKm` is the applied radius (Flutter always displayed 5).
- Malformed rolling fields (`n` not a string, `ts` not an int) and wrongly typed payload fields
  give `ScanOutcome.Malformed` instead of Dart's silent cast error (not "QR expired").
- Save: duplicate guard and append run atomically in `BadgeRepository.update`; an unreadable
  wallet or failed write goes to `keepAside` (same outcomes as Dart steps 9–11).
- Session persistence fails loudly (`STORAGE_FAILED`) and never leaves half a session.
- `expiryInfo`/`remainingTimeString` are typed; the UI localizes them.
