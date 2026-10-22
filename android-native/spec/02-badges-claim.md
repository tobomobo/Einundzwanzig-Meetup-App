# 02 — Badges & Claim (issuance, scanning, claim binding, rolling QR, event badges, reputation-QR check)

Porting spec for the native Android rewrite (Kotlin, Jetpack Compose, Material 3 Expressive).
Source of truth: the Flutter app at the commit this file was written against (branch `t3/explain-flutter-choice`, HEAD `623bdc9`).

Machine-readable test vectors: `android-native/spec/fixtures/02-badges-claim-vectors.json`
(generated from verbatim copies of the Dart code; the generator is kept next to it as
`02-badges-claim-vectors.gen.dart.txt`). Every hash, id, signature, nonce and AES blob in this
document comes from that file.

## 0. Scope

Files read completely for this spec:

| File | Role |
|---|---|
| `lib/models/badge.dart` | `MeetupBadge` model, JSON, identity/union rules, proof hashes, export, **encrypted badge store** (blob, backups, archive, pending, incoming, reset) |
| `lib/services/badge_security.dart` | Compact badge signing/verification (kind 21000), legacy full-format verification, normalization, expiry, reputation-QR sign/verify (kind 21001), event-badge `m` field |
| `lib/services/badge_claim_service.dart` | Claim binding (kind 21002), claim verification, retroactive claiming |
| `lib/services/rolling_qr_service.dart` | Organizer session (4 h), signed base payload, rolling HMAC nonce, freshness check |
| `lib/screens/rolling_qr_screen.dart` | Organizer QR display |
| `lib/screens/qr_scanner.dart` | `SecureQRScanner` (reputation QR check) + `_VerificationResultScreen` |
| `lib/widgets/scanner_overlay.dart` | Camera overlay |
| `lib/screens/nfc_writer.dart` | NFC tag writer (feature flag OFF) |
| `lib/screens/meetup_verification.dart` | Participant badge scanner, verification, claim, presence check, save |
| `lib/services/event_badge_auth_service.dart` | Who may create an event with a badge |
| `lib/services/event_badge_chain_service.dart` | Trust chain for event badges when scanning |
| `lib/services/event_badge_session_service.dart` | Helper (issuer) starts an event badge session |
| `lib/screens/meetup_session_wizard.dart` | NFC step wizard (feature flag OFF) |
| `lib/services/meetup_key.dart` | Meetup id slug/country rules |
| `docs/ROLLING_QR_SESSION_KEY.md` | Security finding H1 + planned v3 format (not implemented) |

Helpers followed for behaviour (owned by other domains, contracts documented in §12):
`signing_service.dart`, `calendar_event_service.dart` (`NostrCalendarEvent`, `fetchByAddress`),
`meetup_location_service.dart`, `nearby_meetup_service.dart` (`getCurrentLocation`), `mempool.dart`
(`getBlockHeight`), `admin_registry.dart`, `portal_leader_service.dart`, `meetup_service.dart`,
`coattendance_service.dart` (`recordOrganizerAttendance`), `app_review_demo.dart`,
`admin_panel.dart` (session start), `event_calendar_screen.dart` (event session start),
`home_screen.dart`, `app_shell.dart`, `radar.dart`, `reputation_qr.dart` (21v3 producer),
`promotion_claim_service.dart` (consumer of `sigContent` + claim), `features.dart`.

Feature flag: `lib/features.dart` → `const bool kNfcEnabled = false;` (NFC read & write disabled since
Aug 2026; code kept). Port NFC behind the same compile-time flag (`BuildConfig.NFC_ENABLED = false`).

---

## 1. Overview and user-visible features

The domain turns **physical presence at a Bitcoin meetup or event** into a portable, verifiable
badge:

1. An **organizer** (or an event **helper/issuer**) starts a *session*. The app signs, once, a
   compact Nostr event (kind 21000, Schnorr/BIP-340) stating "meetup `m` at block `b`, valid until
   `x`". That signed *base payload* is stored for the session (4 h, or until local midnight of the
   event's last day for event sessions).
2. The organizer shows a **rolling QR code** that re-renders every 10 s: base payload + an HMAC
   nonce of the current 10-second time step (anti-screenshot) + optional unsigned venue coordinates.
   (NFC tags carrying the bare base payload exist but are switched off.)
3. A **participant** scans the QR. The app checks freshness (time step ±1), the Schnorr signature,
   expiry `x`, self-scan, duplicates, and whether the signer is a known organizer (admin registry
   → portal leader list of that meetup → portal `nostr` field) or, for event badges, the event
   trust chain (calendar event → creator in admin registry → signer in issuer list).
4. The participant's app immediately creates a **claim** (kind 21002) signed with the participant's
   own key that references the organizer signature → the badge is bound to the collector.
5. On confirmation, the app does a soft **presence check** (GPS vs. reference coordinates, 50 km
   for derived references), stores the badge in the **encrypted wallet store**, publishes reputation
   in the background, optionally publishes co-attendance (opt-in dialog), and opens the wallet.
6. Separately, anybody can **check another person's reputation QR** (`21v3:` format, kind 21001
   signature) and see live trust layers (NIP-05, Lightning/zaps, social graph).

Every user-visible feature (one line each):

- Scan FAB → bottom sheet "Badge scannen / QR-Code vom Meetup" opens the badge scanner (app shell, medium haptic if `haptic_enabled`).
- Badge scanner screen with pulsing circle icon (QR icon; NFC icon if NFC enabled), title, instructions, "QR-CODE SCANNEN" button, live status line.
- NFC "NFC TAG SCANNEN" button (flag off) with NFC-unavailable / NFC-disabled dialogs and NFC read status messages.
- Camera QR scanner for badge QR with overlay frame + animated scan line + instruction box.
- Pick a QR image from the gallery (badge scanner: silent if nothing found; reputation scanner: snackbars).
- App-review demo QR `21review:einundzwanzig-meetup-demo` shows an English "Demo scan OK" dialog instead of issuing a badge.
- Rolling-QR freshness rejection: "QR-Code abgelaufen! … Bitte direkt am Bildschirm des Organisators scannen."
- Signature/format/expiry rejection with message (e.g. "Tag abgelaufen! Abgelaufen vor 1h 3min. Bitte Organisator um neuen Tag.").
- Legacy v1 badges/QRs are rejected with an upgrade hint.
- Self-scan prevention: organizer cannot badge himself ("Du kannst dir nicht selbst ein Badge geben. Bitte lass einen Teilnehmer deinen Tag scannen.").
- Already-collected detection at scan time (one badge per meetup per local day; one per event, regardless of day) with "BEREITS GESAMMELT" view and "ZUR WALLET" button.
- Confirmation card "BADGE GEFUNDEN" listing location, block, signer (short npub), proof type, binding result, organizer check result, tag expiry.
- Organizer check outcomes: known organizer (registry), portal leader of this meetup, portal `nostr` field match, unknown signer (orange warning state), check failed (offline), legacy badge.
- Event-badge chain outcomes: issued with permission of creator / signer not listed / creator not authorized / event has no badge / event not found.
- Event badge picks up the event's `badge_image` URL as badge cover.
- Automatic claim binding ("🔗 Badge gebunden" or "⚠ Binding: <reason>").
- "ZUR WALLET HINZUFÜGEN" → blocking spinner while locating → presence check.
- "Zu weit entfernt" dialog when measurably too far (badge refused).
- Snackbar when location unavailable: badge saved as "unverified presence".
- Duplicate guard at save time → snackbar "Dieses Badge ist bereits in deiner Wallet." + jump to wallet.
- Wallet unreadable → badge is "kept aside" (encrypted pending slot) with snackbar; or save-failed snackbar.
- Success snackbar "Badge gespeichert ✓", background reputation publish.
- Co-attendance opt-in dialog (only for Nostr-signed badges with an event id) → publish → success/failure snackbar.
- Automatic navigation to the wallet after save (replaces scanner).
- Organizer: start meetup session (admin panel; other domain) → rolling QR screen.
- Rolling QR screen: session bar (meetup name, remaining time, "AKTIV" pill), QR (white card, 260 dp), circular 10-s countdown that turns red at ≤3 s ("Code erneuert sich..."), footer with block height and short npub.
- Rolling QR resumes after app comes back to foreground; auto-stops UI when the session expires.
- "Session beenden" (app-bar stop icon) with confirmation dialog → session wiped → back.
- "KEINE AKTIVE SESSION" empty state with "ZURÜCK ZUM ADMIN PANEL".
- Home dashboard "LIVE" tile for a running session (event sessions show a medal icon) → opens rolling QR.
- Event helper: start event badge session from calendar event (issuer, day window, event location, 5 km) with specific error snackbars and "Einstellungen" actions.
- Event creation: badge toggle enabled only if `EventBadgeAuthService.myRight() != none`.
- NFC writer (flag off): write session base payload to NTAG as NDEF text record, size check, format blank tags, success → auto-jump to rolling QR after 3 s.
- Session wizard (flag off): step 1 NFC / step 2 QR, "ÜBERSPRINGEN — NUR QR NUTZEN".
- Retroactive claiming at app start / reputation card load for signed-but-unclaimed badges (flagged `isRetroactive`).
- Reputation QR check ("REPUTATION PRÜFEN"): verifies `21v3:` Schnorr signature and shows trust level/score, mini stats, badge-proof card, NIP-05, Lightning, linked platforms, social graph, hashed meetup list, identity card.
- Badge export for reputation (JSON v4.0 with checksum) — function lives here, UI in wallet domain.
- Former-collections archive (identity switch / clear / rotation) with merge-back — function lives here, UI in wallet/backup domains.

---

## 2. Screens & UX flows

Colours referenced (from `lib/theme.dart`, other domain): `cOrange`, `cCyan`, `cPurple`, `cGreen`,
`cRed`, `cDark`, `cCard`, `cSurface`, `cText`, `cTextSecondary`, `cTextTertiary`, `cTileBorder`,
`cBorder`; `kTileRadius`. Map to the M3 colour scheme of the native theme.

### 2.1 `MeetupVerificationScreen` — participant badge scanner

- **Purpose:** read an organizer QR (or NFC tag), verify, bind, presence-check, save a badge.
- **Argument:** `meetup: Meetup` (only `lat`/`lng` are used, as presence fallback reference).
- **Entry points:**
  1. App shell scan FAB → `_ScanSheet` option "Badge scannen" (`scanBadge` / `scanBadgeSub`): pushes the screen with a slide-up transition (`Offset(0,1)→0`, `easeOutCubic`) and the placeholder meetup `Meetup(id:"global", city:"GLOBAL", country:"", telegramLink:"", lat:0, lng:0)`. After pop → `home.refreshAfterScan()`.
  2. Radar (city detail) `_openScanner`: meetup = first of `allMeetups` with `city == cityName`, else `fallbackMeetups`, else `Meetup(id:"temp", city:cityName, country:"DE", lat:0, lng:0)` (`id:"err"` on exception). After pop → refresh profile.
- **Result:** pops `false` on cancel/close; on success it *replaces itself* with `BadgeWalletScreen` (wallet domain).
- **Guide hook:** the home tour step 17 targets the scan FAB (`guideHomeScanTitle` "Badge einfordern", `guideHomeScanBody`) — app-shell domain.

**State:** `_tagProcessing` (NFC debounce), `_success` (false = scanner view, true = confirmation view),
`_isUnknownSigner`, `_statusText` (initially `verifyReadyToScan`), `_pendingBadge`, `_pendingOrgLat/Lng`.

**Scanner view (layout, top→bottom, centred column):**
1. 200×200 circle, 4 dp `cOrange` border, orange glow; scale animation 0.9↔1.1, 2 s, easeInOut, repeat/reverse. Icon 80: `qr_code_scanner_rounded` (or `nfc` when `kNfcEnabled`).
2. Title `verifyScanBadge` ("BADGE SCANNEN"), bold 24, letterSpacing 2.
3. Instruction `verifyScanInstruction` ("Scanne den QR-Code\ndes Meetup-Organisators.").
4. If `kNfcEnabled`: filled orange button 250×56 `verifyScanNfc` → `_startNfcRead`.
5. Outlined cyan button 250×56 `verifyScanQrCaps` ("QR-CODE SCANNEN") → `_startQRScan`.
6. Status text (`_statusText`), grey 12.

AppBar title `verifyScanBadge`.

**QR flow `_startQRScan`:** push `_QRScannerScreen` (§2.2), await `Map<String,dynamic>?`.
- `null` → nothing.
- `{'_reviewDemo': true}` → `AppReviewDemo.showSuccess` dialog (title "Demo scan OK", English body, "OK"). No badge.
- If map contains `n` or `qr_nonce` → `RollingQRService.validateNonce(map)`; invalid → status `verifyErrQrExpired(message)`, `_success=false`, stop.
- `dataToVerify` = copy minus `n`, `ts`, `d`, `qr_nonce`, `qr_time_step` (NOT `la`/`lo`; harmless, see whitelist).
- `BadgeSecurity.verify(dataToVerify)` (expiry checked); invalid → status `verifyErrPrefix(result.message)`.
- If `version >= 2 && adminNpub != ''` → `result['_verified_by'] = shortenNpub(adminNpub)`.
- If no `delivery` key → `result['delivery'] = 'rolling_qr'`.
- `_processFoundTagData(tagData: result, verifyResult)`.

**NFC flow `_startNfcRead` (flag off, port behind flag):**
- Status `verifyCheckingNfc`. Availability != enabled → AlertDialog (icon `nfc_rounded` 48, red if "not supported" else orange):
  - not supported: title `nfcUnavailable`, body `verifyNoNfcLong + verifyUseQrInstead + verifyToGetBadge`, actions [`verifyScanQrCaps` (cyan) → close + `_startQRScan`], ["OK"].
  - disabled: title `nfcDisabled`, body `"${nfcEnableHint}${verifyToGetBadge}\n\n${nfcSettingsAndroid}\n${nfcSettingsIos}"`, actions [`nfcOpenSettings` (only closes the dialog — it does NOT open settings; native should open `Settings.ACTION_NFC_SETTINGS`)], ["OK"].
  - status `verifyNoNfcDevice` / `nfcDisabledHint`.
- Else status `verifyWaitingNfc`; start reader session (ISO14443 + ISO15693). On tag: debounce (`_tagProcessing`), released 1500 ms after processing ends.
  - Not NDEF → stop, `verifyErrNoNdef`. Empty message/records → `verifyErrTagEmpty`. Empty payload → `verifyErrPayloadEmpty`.
  - NDEF Text record parse: `langLen = payload[0] & 0x3F`; text = UTF-8 decode of `payload[1+langLen ..]` (UTF-16 flag ignored). If `payload.length <= 1+langLen` → `verifyErrInvalidFormat`.
  - JSON decode as object; stop session; `BadgeSecurity.verify(tagData)`; invalid → `verifyErrPrefix(message)`.
  - `_verified_by` as above; `tagData['delivery'] = 'nfc'`; `_processFoundTagData`.
  - JSON errors → `verifyErrInvalidTag(e)`; read errors → `verifyErrReadError(e)`; session start error → `verifyErrNfcError(e)`.

**`_processFoundTagData(tagData, verifyResult)` — exact order:**
1. `normalized = BadgeSecurity.normalize(tagData)` (§8.3).
2. `meetupSlug = normalized['meetup_slug'] ?? ''`; `meetupName = normalized['meetup_name'] ?? verifyUnknownMeetup`; `meetupCountry = normalized['meetup_country'] ?? ''`.
3. Portal display name: if `meetupSlug != ''` and `MeetupService.bySlug(meetupSlug, meetupCountry)` returns a meetup → `meetupName = portal.city`; if country empty → `portal.country`. (Display only.)
4. `keySlug = meetupSlug != '' ? meetupSlug : MeetupKey.slug(meetupName)`.
5. `meetupId = normalized['meetup_id'] ?? DateTime.now().toString()` → becomes `badge.id` (NOT unique: e.g. `"wien"`; events `"evt:<dTag>"`).
6. Block height: `MempoolService.getBlockHeight()` (0 on error); if 0 → wait 1 s, retry once; if still 0 → `normalized['block_height'] as int? ?? 0` (tag `b`).
7. `fullName = meetupCountry != '' ? "$meetupName, $meetupCountry" : meetupName`.
8. Scan-time duplicate check against the **stored** badges: `storedNow = MeetupBadge.loadBadges()`; global `myBadges = storedNow`; `todayStr` = local `yyyy-MM-dd`; `prospectiveEventId = "$keySlug-$todayStr"`; `collectible` = stored where `!isOrganizer`.
   - Event badge (`event_address` present): `eventKey = "evt|<address>"`; already = any collectible with `meetupEventId == eventKey` OR (`isEvent && meetupName == fullName`).
   - Else: already = any collectible with (`meetupEventId != '' && meetupEventId == prospectiveEventId`) OR (`meetupName == fullName` AND same local calendar day as now).
9. **Self-scan prevention** (only if `admin_pubkey` (or `p`) non-empty): `myNpub = NostrService.getNpub()`; self if `myNpub == normalized['admin_npub']`, or `decode(myNpub) == adminPubkey` (hex string equality, case-sensitive). Self → `_success=false`, status `"✗ ${verifyCantSelfBadge}${verifyAskScan}"`, **return** (nothing stored). Any exception → fail-open (continue).
10. If **not** already collected:
    - `signerNpub = normalized['admin_npub'] ?? ''`; `delivery = normalized['delivery'] ?? tagData['delivery'] ?? 'nfc'`; `dateStr` = local `yyyy-MM-dd`.
    - `unknownSlug = verifyUnknownMeetup.trim().toLowerCase().replaceAll(' ', '-')` (localized! DE `"unbekanntes-meetup"`, EN `"unknown-meetup"`). `trim`/`toLowerCase` here and in every other formula of this spec mean `DartString.trim`/`DartString.lower` (§8.14), never Kotlin `trim()`/`lowercase()`.
    - `usableName = keySlug != '' && keySlug != unknownSlug && keySlug.replaceAll('-', '') != ''`.
    - `meetupEventId = eventAddress != null ? "evt|$eventAddress" : (usableName ? "$keySlug-$dateStr" : '')` (empty → no co-attendance publish).
    - `sig = normalized['sig'] ?? tagData['s'] ?? ''`; `sigId = normalized['sig_id'] ?? ''` (**empty for compact badges**); `adminPubkey = normalized['admin_pubkey'] ?? tagData['p'] ?? ''`; `sigVersion = sig.length == 128 ? 2 : (sig != '' ? 1 : 0)`.
    - Organizer check (only if `verifyResult.version >= 2 && adminPubkey != ''`):
      - **Event badge:** `EventBadgeChainService.verify(eventAddress, signerPubkey: adminPubkey, issuedAt: nowUnixSeconds)` (scanner's clock on purpose). `isKnownAdmin = chain.ok`; `eventBadgeImage = chain.badgeImageUrl`; info by status: verified → `mvEventIssuerOk(title, creatorName ?? verifyVerifiedAdmin)`; signerNotListed → `mvEventSignerNotListed(title)`; creatorNotAuthorized → `mvEventCreatorNotAuthorized(title)`; eventHasNoBadge → `mvEventHasNoBadge(title)`; eventNotFound → `mvEventNotFound`.
      - **Meetup badge:** `AdminRegistry.checkAdminByPubkey(adminPubkey)`:
        - `isAdmin` → `mvKnownOrganizer(name ?? meetup ?? verifyVerifiedAdmin)`.
        - `source == 'unavailable'` → `mvAdminCheckFailed`.
        - else second source: `ownMeetup = MeetupService.resolveFavorite(meetupName) ?? MeetupService.byName(meetupName)`; if found and `int.tryParse(ownMeetup.id)` ok: `leads = PortalLeaderService.isLeaderOf(id, hexToNpub(adminPubkey))` (null if npub conversion failed) → `true`: known, `mvPortalOrganizer(ownMeetup.name or city)`; `null`: `mvAdminCheckFailed`; `false`: fall through.
        - third source (only if info still empty and not known): `MeetupService.organizerMeetupFor(adminPubkey)` → found: known, `mvPortalOrganizer(name or city)`; else `mvUnknownSigner`.
        - any exception → `mvAdminCheckFailed`.
      - `verifyResult.version == 1` → `mvLegacyBadge` (unreachable in practice: v1 is rejected earlier).
    - `sigContent = jsonEncode(tagData minus '_verified_by')` — the **full scanned map** in decoded key order, *including* unsigned `n`, `ts`, `d`, `la`, `lo` and the added `delivery` key (appended last if it was added). Byte layout matters only in that it must re-parse to the same values; see §7.6.
    - Claim (only if `sig != '' && sigVersion >= 2`): `BadgeClaimService.createClaim(orgSig: sig, orgEventId: sigId, orgPubkey: adminPubkey, blockHeight: currentBlockHeight)` → success: fields + `claimInfo = mvBadgeBound`; failure: `claimInfo = "⚠ Binding: ${message}"`.
    - `_pendingOrgLat/Lng = BadgeSecurity.signedCoordinates(tagData)` → **always 0/0 today** (H1).
    - `_pendingBadge = MeetupBadge(id: meetupId, meetupName: fullName, date: DateTime.now() /*local*/, iconPath: "assets/badge_icon.png", blockHeight: currentBlockHeight, signerNpub, meetupEventId, delivery, sig, sigId, adminPubkey, sigVersion, sigContent, claimSig, claimEventId, claimPubkey, claimTimestamp, isRetroactive: false, isEvent: eventAddress != null, coverUrl: eventBadgeImage)`.
    - Message (exact concatenation):
      ```
      msg  = "${verifyBadgeFound}\n\n"
      msg += "${verifyMsgLocation(fullName)}\n"
      if (block > 0)                 msg += "${verifyMsgBlock(block)}\n"
      if (tagData['_verified_by'])   msg += "${verifyMsgSignedBy(shortNpub)}\n"
      if (sigVersion == 2)           msg += "${verifyMsgProof}\n"
      if (claimInfo != '')           msg += claimInfo
      if (adminCheckInfo != '')      msg += "\n\n$adminCheckInfo"
      expiry = BadgeSecurity.expiryInfo(tagData)
      if (expiry != verifyNoExpiry)  msg += "\n\n${verifyMsgTagExpiry(expiry)}"
      ```
      (`expiryInfo` returns hard-coded German; in EN locale the `verifyNoExpiry` comparison never matches — localize properly in native.)
    - `_isUnknownSigner = true` if `!isKnownAdmin && version >= 2 && adminPubkey != ''`.
11. Else (already collected): `msg = eventAddress != null ? verifyAlreadyEvent(fullName) : verifyAlreadyToday(fullName)`; `_pendingBadge = null`.
12. `_success = true`, `_statusText = msg`. No auto-dismiss.

**Confirmation view** (`_success == true`), scrollable, padding (24,48,24,40):
- `isAlreadyCollected = _pendingBadge == null`. Accent: grey (`cTextSecondary`) if already; else orange if `_isUnknownSigner`, else green. Icon: `inventory_2_rounded` / `warning_amber_rounded` / `military_tech_rounded`, in an 80 dp tinted circle.
- Title (11, w800, letterSpacing 1.6): `verifyAlreadyCollected` or `verifyBadgeFound`.
- Info card (`cCard`, radius `kTileRadius`): `_statusText`, 13, line height 1.75, left aligned.
- If already: filled orange 52 dp button `verifyOpenWallet` ("ZUR WALLET", wallet icon) → replace with wallet.
- Else: filled orange 52 dp button `verifyAddToWallet` ("ZUR WALLET HINZUFÜGEN", add icon) → `_confirmSaveBadge`.
- Text button 48 dp with border: `verifyClose` (already) or `dialogCancel` → `pop(false)`.

**`_confirmSaveBadge()` — exact order:**
1. `_pendingBadge == null` → `pop(false)`.
2. Show non-dismissible centered `CircularProgressIndicator` dialog; `loc = MeetupLocationService.resolveLocation()` (fetches location AND meetup list; up to ~45 s); close dialog.
3. `presenceVerified = false`.
4. Reference: `refLat/refLng = _pendingOrgLat/Lng`; `refMeasured = !(refLat==0 && refLng==0)` (computed BEFORE fallbacks → always false today); if 0/0 → `widget.meetup.lat/lng`; if still 0/0 → `_lookupMeetupForBadge(badge.meetupName)`:
   - `city = meetupName.split(',').first.trim().toLowerCase()` (`DartString.trim`/`lower`, §8.14); empty → null.
   - list = `MeetupService.fetchMeetups()` (exception → empty) ; empty → `allMeetups` (bundled fallback list).
   - first meetup with coords (not 0/0) where `c = m.city.trim().toLowerCase()` and (`c == city || c.contains(city) || city.contains(c)`).
5. If `loc.status == ok` and reference != 0/0: `dist = haversineKm(loc, ref)`; `allowed = refMeasured ? 5.0 : 50.0`; `dist > allowed` → dialog "Zu weit entfernt" (`gpsTooFar`, `gpsTooFarSub(dist.toStringAsFixed(1), participantRadiusKm.toStringAsFixed(0))` — **note: shows "5" even when 50 was applied**; native should show `allowed`), action "OK"; **return** (badge stays pending; user can retry). Else `presenceVerified = true`.
6. Otherwise (no location or no reference) → presence unverified, badge still saved.
7. `badge = badge.copyWith(lat: loc.lat, lng: loc.lng, presenceVerified)` — participant's own position (0/0 if no fix).
8. If `!presenceVerified` → floating snackbar `badgeUnverifiedInfo`, 5 s, background `cSurface`.
9. `stored = MeetupBadge.loadBadges()`; if `badgesUnreadable` → `aside = MeetupBadge.keepAside(badge)`; `_pendingBadge = aside ? null : badge`; floating snackbar 8 s: `badgesHeldAside` (default colour) or `badgeSaveFailed` (`cRed`); **return** (stay on screen).
10. Save-time duplicate guard: `key = identityKey(badge)`; if any stored non-organizer badge has the same `identityKey` → `_pendingBadge = null`, floating snackbar `verifyBadgeDuplicate` (orange), replace with wallet, return.
11. `stored.add(badge)`; `wrote = MeetupBadge.saveBadges(stored)`; if !wrote → keepAside path as in 9, return.
12. `myBadges = stored`; `_pendingBadge = null`; `ReputationPublisher.publishInBackground(stored)` (fire & forget).
13. Floating snackbar `verifyBadgeSaved`, `cGreen`.
14. If `badge.isNostrSigned && badge.meetupEventId != ''` → co-attendance opt-in dialog (dismissible): icon `hub_rounded` cyan, title `caOptInTitle`, body `caOptInBody`, orange warning box `caOptInPrivacy`, actions `caOptInNo` (→ false), filled cyan `caOptInYes` (→ true). True → `count = CoAttendanceService.publishAttendance(badge)` → snackbar `caPublished` (green, 3 s) if `count > 0` else `caPublishFailed` (red, 6 s). Asked every time (no persisted choice).
15. Replace with `BadgeWalletScreen`.

Note (UI quirk): steps 9/11 set `_pendingBadge = null` without `setState`; native must update UI state (switch to "already collected/aside" state or close).

### 2.2 `_QRScannerScreen` — camera for badge QR (private to 2.1)

- AppBar title `verifyScanQr` ("QR SCANNEN"); action icon `photo_library_outlined` (no tooltip) → gallery.
- Body: full-screen camera preview; `ScannerOverlay()` (§2.7); bottom (60 dp from bottom, 40 dp side insets) black 70 % box with `verifyScanQrInstruction`.
- `onDetect`: ignore after first hit. For each barcode raw value: app-review payload → pop `{'_reviewDemo': true}`; else try `json.decode(code)` as a JSON **object**; if it contains key `t` or `type` → pop the map. Non-JSON / arrays ignored.
- Gallery: pick image (`FileType.image`) → analyze → if no barcodes **silently return**; else run the same detect logic. Errors swallowed.

### 2.3 `RollingQRScreen` — organizer QR display

- **Purpose:** show the rolling QR of the current session. Does NOT create sessions.
- **Entry points (no arguments):** admin panel after starting a session (`kNfcEnabled == false`), admin panel "AKTIVES MEETUP ÖFFNEN" (`apOpenActiveMeetup`), session wizard skip and NFC writer success (both `pushAndRemoveUntil(route.isFirst)`), home "LIVE" session tile (re-checks session on return), event calendar after `EventBadgeSessionService.start` succeeded (calendar re-reads running session on return).
- **Init:** `_adminNpub = NostrService.getNpub() ?? ''`; `loadSession()`; if non-null and not expired: set session, `_blockHeight = session.blockHeight`, `_meetupInfo = country != '' ? "📍 $name, $country" : "📍 $name"`, start timers, `_refreshQR()`; else empty state.
- **Timers:** refresh every 10 s (relative to screen start, NOT aligned to step boundaries); countdown every 1 s: if session expired → stop UI only (no wipe) → empty state; else `_secondsLeft = 10 - (nowSec % 10)` (1…10).
- **Lifecycle:** on app resume, if session non-null & not expired → cancel & restart timers + refresh.
- `_refreshQR`: `RollingQRService.generateQRString(session)`; errors only logged.
- **AppBar:** title `rqTitle` ("MEETUP QR-CODE"), background orange; when active an `stop_circle` icon (tooltip `rqEndSession`) → confirm dialog: title `rqEndSessionQ`, content `rqRemainingTime(remainingTimeString) + rqEndSessionBody`, actions `apCancel` (grey), `rqEnd` (red, bold) → `RollingQRService.endSession()` then pop.
- **Active layout:**
  1. Session bar (orange 15 % bg): left column `_meetupInfo` (orange bold 14) + `rqSessionRemaining(remainingTimeString)` (white70, 11); right green pill with dot + `rqActive` ("AKTIV").
  2. Expanded centre: white card (padding 16, radius 16, orange glow) with QR 260 dp, **error correction M**, version auto; while empty a spinner of the same size. Below (24 dp): 60 dp circular progress `value = secondsLeft/10`, stroke 4, colour red if `secondsLeft <= 3` else orange, centred number (red/white, 22 bold); label `rqCodeRenewing` (red) if ≤3 else `rqNextCodeIn` (grey 12).
  3. Footer (`cCard`): `"⛓️ Block $blockHeight"` (mono 12) and `shortenNpub(npub)` (mono 11) if known.
- **Empty state:** red circled `error_outline` 80; `rqNoActiveSession`; `rqNoSessionBody`; full-width 56 dp orange button `rqBackToAdmin` (back arrow) → pop.
- `remainingTimeString` (hard-coded German in Dart): `"Abgelaufen"` if ≤0, `"${h}h ${m%60}min"` if h>0, else `"${m}min"`.
- No wake lock in Flutter. Native: optionally keep the screen on and max brightness while this screen is visible (compatible enhancement); align regeneration to 10-s boundaries (compatible enhancement).

### 2.4 `NFCWriterScreen` (flag off)

- Entry: `MeetupSessionWizard` "NFC TAG BESCHREIBEN". AppBar `writerCreateMeetupTag`, orange.
- Init: `UserProfile.load()`; empty `homeMeetupId` → status `writerNoHomeMeetup`, info `writerSelectHomeFirst`. Else meetups = `fetchMeetups()` or `allMeetups`; find `city == homeMeetupId`; found → `_meetupInfo = "📍 $city, $country"`, estimated size = length of `{"v":2,"t":"B","m":"<city-lower-dashes>-<country-lower>","b":000000,"x":0000000000,"c":0000000000,"p":"0…64","s":"0…128"}` + 10; not found → `writerHomeMeetupNotFound`, info `"Meetup: $homeMeetupId"`. (Display only — the written payload is the **session** base payload, not the home meetup.)
- Layout: pulsing 200 dp circle (scale 1.0↔1.2) with NFC icon; `writerMeetupTag`; `writerHoldTagInstruction`; box `writerYourHomeMeetup` + info; green box `writerFitsNtag215(size)` if size>0; cyan box `writerValidity` + `writerCanOverwrite`; 250×60 orange button `writerCreateTag`; status text.
- Write: no home meetup → snackbar `nwSelectHomeMeetup` (red). Status `writerLoadingSession`; `tagData = RollingQRService.getBasePayload()`; null → `writerNoActiveSession`. NFC unavailable → dialog like 2.1 (not supported: `verifyNoNfcLong + writerUseRollingQr + writerForYourMeetup`; disabled: `nfcEnableHint + writerToWriteTag + nfcSettingsAndroid`), status `writerNoNfcDevice` / `nfcDisabledHint`. Else status `writerHoldTag`.
- Payload: `jsonData = jsonEncode(tagData)` (key order v,t,m,b,x,c,p,s); NDEF record TNF well-known, type `"T"` (0x54), empty id, payload `[0x02, 'e'(0x65), 'n'(0x6E)] + UTF-8(jsonData)`. `actualSize = jsonData.length + 7`.
- On tag (debounced): no NDEF → if formatable: status `writerFormatting`, `format(message)` → success, or `writerFormatFailed`; else `writerNoNdef`. Not writable → `writerTagReadOnly`. `actualSize > maxSize` → `writerTagTooSmall(actual, max) + writerUseNtag215`. Write → success. Errors: contains `IOException` → `writerTagRemovedEarly`; contains `TagLost` → `writerTagLost`; else raw text. Errors shown as `"❌ $error"`.
- Success view: green check 100, `writerSuccess`, status `writerTagWritten + writerCompactSize(len) + writerValidHours(4) + writerJumpToQr`; after 3 s `pushAndRemoveUntil(RollingQRScreen, isFirst)`.

### 2.5 `MeetupSessionWizard` (flag off)

Entry: admin panel after session start when `kNfcEnabled`. AppBar `mwStartMeetup`. Step indicator (1 "NFC" active, line, 2 "QR"); NFC icon; `mwStep1Nfc`; `mwNfcIntro1 + mwNfcIntro2`; spacer; orange button `mwWriteNfcTag` → push NFC writer; text button `mwSkipQrOnly` → `pushAndRemoveUntil(RollingQRScreen, isFirst)`.

### 2.6 `SecureQRScanner` + `_VerificationResultScreen` — reputation check

- **Entry:** `ReputationQRScreen` app-bar icon (tooltip `reputationCheck`) and a full-width button `reputationScanQr` (reputation domain). The app-shell sheet option "Reputation prüfen" opens `ReputationQRScreen`, not this scanner directly.
- **Scanner layout:** AppBar `qrScanTitle` ("REPUTATION PRÜFEN") + gallery icon (cyan, tooltip `qrLoadFromGallery`, disabled after a hit); camera; `ScannerOverlay`; bottom (60 dp, 24 dp sides): 48 dp translucent white button `qrLoadFromGallery` (disabled after hit) and a gradient hint box `qrScanHint`.
- **Detection:** first barcode that is the app-review payload → demo dialog, then re-enable scanning; first barcode starting with `21:`, `21v2:` or `21v3:` → `_verifyAndShow`.
- **Gallery:** pick image → analyze; no barcodes → snackbar `qrNoCodeInImage` (orange); review payload → demo dialog; prefix match → verify; nothing matched → snackbar `qrNotEinundzwanzig` (orange); exception → snackbar `qrProcessError(e)` (red).
- **`_verifyAndShow(code)`** (all branches `pushReplacement` the result screen):
  - `21v3:` + ≥5 dot-separated parts: `json = utf8(base64.decode(parts[0]))` (padding required; std or url alphabet), `sig=parts[1]`, `id=parts[2]`, `createdAt=int.tryParse(parts[3]) ?? 0`, `pubkey=parts[4]` → `verifyQRv3`. Valid → v3 result; invalid → failed(`qrSigInvalid`, message).
  - `21v2:` + ≥3 parts → `verifyQRLegacy` → always invalid → failed(`qrSigInvalid`, "Legacy-QR (v1) wird nicht mehr akzeptiert. Bitte Organisator um neuen v2-Tag.").
  - `21:` + ≥2 parts → same legacy rejection.
  - otherwise → failed(`qrFormatUnknown`, `qrCantRead`). Exceptions → failed(`qrReadError`, `qrProcessError(e)`).
- **v3 result mapping** (payload keys, see §3.7): `id`→identity map, `rp`→reputation, `pf`→proof, `pp`→platform proofs. `hasIdentity = id.np || id.tg || id.tw non-empty`. Fields: trustLevel `rp.lv`, trustScore `rp.sc` (num), badgeCount `rp.bc`, verifiedBadgeCount `rp.vc ?? pf.vc`, boundBadgeCount `rp.bb ?? pf.bb`, meetupCount `rp.mc`, signerCount `rp.si`, accountAgeDays `rp.ad`, meetupList `rp.ml` (list of 12-hex hashes), badgeProof `pf.bp`, proofTotalCount `pf.tc`, proofVerifiedCount `pf.vc`. Title `qrVerified`, subtitle = verify message ("Schnorr-Signatur verifiziert ✓").
- **Result screen layout** (AppBar `qrResultTitle`, scroll, padding 20):
  1. Status header: colour red (invalid) / green (identity) / orange (no identity); icon `verified` or `gpp_bad`; title + subtitle; version pill `"v$version"` if version>0.
  2. If valid, v≥3 and level non-empty: trust card — 40 dp circle icon by level (VETERAN `bolt` amber, ETABLIERT `shield` green, AKTIV `local_fire_department` cyan, STARTER `eco` orange, other `fiber_new` grey), level text, `"N Badges · M Meetups · S Signer"`, score `toStringAsFixed(1)` mono 24; mini stats row: badges (`qrStatBadges`), meetups (`qrStatMeetups`), signers (`qrStatSigners`), bound (`qrStatBound`), days (`qrStatDays`).
  3. If valid, v≥3 and proof non-empty: proof card — green/verified if `proofVerifiedCount == proofTotalCount && total > 0` else orange/shield; `"X von Y Badges kryptographisch verifiziert"`; `"Proof: <first 16>..."`.
  4. If valid, v≥3: live trust layers (loaded only if `id.np` present: decode npub → hex; three parallel loads; status line `qrAnalyzingNetwork` / `qrCheckingLightning` / `qrCheckingNip05` with small spinner while loading):
     - NIP-05 row (only when a result exists): valid → title nip05, subtitle domainLabel, cyan; invalid → `qrNip05Invalid`, subtitle nip05, red.
     - Lightning card (when stats exist): header `qrSectionLightning`; if `hasLightningProof` → `qrHumanVerified` / `qrLightningActive`; if `totalCount > 0`: sent `qrSentCount(n)` / `qrUniqueRecipients(n)` (green if sent>5), received `qrReceivedCount(n)` / `qrUniqueSenders(n)` (green if >0), `"$activeMonths Monate aktiv"` / activityLabel if >0; else `qrNoZap` / `qrNoLightning`.
     - Platform proofs (if `pp` non-empty): header `qrSectionPlatforms`; per entry: icon (telegram `send`, satoshikleinanzeigen `shopping_cart`, robosats `smart_toy`, nostr `hub`, else `language`), label (Telegram / Satoshi-Kleinanzeigen / RoboSats / Nostr / `qrPlatformOther` for `other` / raw key) + `": @user"` (user = `u` ?? `username`); subtitle `qrSigVerifiedShort` (green) if `s` ?? `proof_sig` non-empty else `qrLinkedShort` (amber). (No cryptographic check here.)
     - Social card (when analysis exists): `qrSectionSocial`; mutual → `qrMutualFollow`/`qrBidirectional`; iFollow → `qrYouFollow`/`qrOneWay`; followsMe → `qrFollowsYou`/`qrOneWay`; else `qrNoDirectFollow`; `"N gemeinsame Kontakte"` + `qrStrongOverlap` (>3) / `qrPartiallyConnected` (>0) / `qrNoOverlap`; if orgFollowerCount>0 `"N Organisatoren folgen"` / `qrEndorsement`; if hops>0 `"N Hop(s) entfernt"` / `qrDirectConnection` (1) or `qrViaContacts`.
  5. If valid and `meetupList` non-empty: chips card `qrSectionMeetups` with the hashes.
  6. If valid and identity != null: identity card — header `qrSectionIdentity` (purple) or `qrNoIdentity` (orange); lines `qrLabelNickname` = `id.n ?? 'Anon'`, "Nostr" `np` (mono), "Telegram" `@tg`, `qrLabelTwitter` `@tw`; no identity → info box `qrNoVerifiableIdentity`; identity + signer → `"Signiert: <short npub>"` (green, mono).
  7. v1/v2 simple stats (unreachable today).
  8. 50 dp orange button `qrBack` → pop.

### 2.7 `ScannerOverlay` widget

Params: `windowSize = 260`, `accent = cOrange`. Ignores pointer events. Full-screen black 54 % dim with a 260×260 transparent hole (corner radius 20). Four 30×30 corner brackets, stroke 3, outer corner radius 18. Scan line: 2 dp tall, horizontal gradient (accent α0 → accent → accent α0), glow (accent α0.55, blur 9), inset 14 dp left/right, `top = 12 + (size-24)*t`, `t` animates 0↔1 over 2200 ms, repeat/reverse. If the system "remove animations"/reduced-motion setting is on → line stays still (`ANIMATOR_DURATION_SCALE == 0` on Android).

### 2.8 Entry points owned by other domains (contracts)

- **Admin panel "MEETUP STARTEN"** (organizer domain): confirm dialog (`apNewMeetupQ`/`apNewMeetupBody`/`apStart`) → `resolveLocation()` with soft-GPS dialog → meetup choice (1 nearby within 10 km = auto; several = bottom sheet with ≤5 candidates; none = searchable portal list with own meetups first + confirm dialog; or free-text name requiring own GPS) → `compactId = MeetupKey.compose(MeetupKey.slug(meetupId), meetupCountry)` where `meetupId` is the city name (or free text) → `RollingQRService.endSession()` → `RollingQRService.getOrCreateSession(meetupId: compactId, meetupName: <city or free text>, meetupCountry, blockHeight: 0, lat: orgLat, lng: orgLng)` (orgLat/Lng = measured GPS or 0/0; **portal coords are never passed**) → `CoAttendanceService.recordOrganizerAttendance(meetupName, now, session.blockHeight, lat, lng)` → push `RollingQRScreen` (or wizard). Errors → snackbar `wotErrorShort(e)`. Session card shows `apSessionRunning` + countdown `"Hh MMm SSs"` (reads prefs `rqr_session_expires` directly), `apOpenActiveMeetup`, `apEndMeetupEarly` (confirm `apSessionEndQ`/`apSessionEndBody`/`apEnd`) → `endSession()`.
- **Event calendar "start badge session"** (calendar domain): double-tap guard; `EventBadgeSessionService.start(event)`; failure → red snackbar with error text (§8.9), duration 10 s for `locationNoFix` else 6 s; action `evSessionOpenSettings` → location settings (`locationServiceOff`) or app settings (`locationDeniedForever`); success → pop detail sheet, push `RollingQRScreen`, re-read running session.
- **Event creation form** (calendar domain): `EventBadgeAuthService.myRight()`; badge toggle enabled iff `!= none`; also gates the event-badge guide-tour steps.
- **Home** (home domain): `RollingQRService.loadSession()` for the LIVE tile (1 s timer, `"Hh MMm"`; medal icon if `meetupId.startsWith('evt:')`; name or `statusMeetupActive`); `MeetupBadge.loadBadges()` then `BadgeClaimService.ensureBadgesClaimed(badges)` if readable; unreadable → one-time snackbar `badgesUnreadableBody` (8 s).
- **Reputation card screen** (reputation domain): `loadBadges()` + `ensureBadgesClaimed()`.

### 2.9 Hard-coded (non-localized) strings to add as new string resources

`MeetupSession.remainingTimeString` ("Abgelaufen", "h", "min"); `BadgeSecurity` messages ("Fehlende Signatur-Daten", "Ungültiges Pubkey-Format (erwartet: 64 Hex-Zeichen)", "Ungültiges Signatur-Format (erwartet: 128 Hex-Zeichen)", "Tag abgelaufen! {expiry}. Bitte Organisator um neuen Tag.", "Schnorr-Signatur gültig ✓", "Signatur ungültig!", "Verifikations-Fehler: {e}", "Schnorr-Signatur gültig (Legacy-Format)", "Legacy-Badges (v1) werden nicht mehr akzeptiert. Bitte Organisator um neuen v2-Tag (Schnorr-signiert).", "Kein Ablauf", "Abgelaufen vor {h}h {m}min", "Abgelaufen vor {m}min", "Gültig noch {h}h {m}min", "Gültig noch {m}min", QR: "Schnorr-Signatur verifiziert ✓", "Schnorr-Signatur UNGÜLTIG!", "Ungültiges Pubkey-Format", "Ungültiges Signatur-Format", "Fehler: {e}", "Legacy-QR (v1) wird nicht mehr akzeptiert. Bitte Organisator um neuen v2-Tag."); nonce messages ("QR-Code abgelaufen ({s}s alt). Bitte direkt am Bildschirm scannen.", "Frisch ✓", "Gültig ({s}s)", "Kein Rolling-QR (NFC/Static)"); claim messages ("Kein Schlüssel vorhanden — Claim nicht möglich", "Badge gebunden ✓", "Claim-Fehler: {e}"); result-screen texts listed in §2.6 ("N Badges · M Meetups · S Signer", "X von Y Badges kryptographisch verifiziert", "Proof: …", "Monate aktiv", "gemeinsame Kontakte", "Organisatoren folgen", "Hop(s) entfernt", "Signiert: …"); "⛓️ Block", "📍"; app review demo dialog (English). In native, return typed error enums from the domain layer and localize in UI.

### 2.10 Localized string keys (from `lib/l10n/app_de.arb`) per screen

- **RollingQRScreen:** `rqTitle, rqActive, rqSessionRemaining, rqNextCodeIn, rqCodeRenewing, rqEndSession, rqEndSessionQ, rqRemainingTime, rqEndSessionBody, rqEnd, apCancel, rqNoActiveSession, rqNoSessionBody, rqBackToAdmin`.
- **MeetupVerificationScreen (+ `_QRScannerScreen`):** `verifyReadyToScan, verifyScanBadge, verifyScanInstruction, verifyScanNfc, verifyScanQrCaps, verifyScanQr, verifyScanQrInstruction, verifyCheckingNfc, verifyWaitingNfc, verifyNoNfcLong, verifyUseQrInstead, verifyToGetBadge, verifyNoNfcDevice, nfcUnavailable, nfcDisabled, nfcEnableHint, nfcSettingsAndroid, nfcSettingsIos, nfcOpenSettings, nfcDisabledHint, verifyErrNoNdef, verifyErrTagEmpty, verifyErrPayloadEmpty, verifyErrInvalidFormat, verifyErrInvalidTag, verifyErrReadError, verifyErrNfcError, verifyErrQrExpired, verifyErrPrefix, verifyUnknownMeetup, verifyCantSelfBadge, verifyAskScan, verifyVerifiedAdmin, mvEventIssuerOk, mvEventSignerNotListed, mvEventCreatorNotAuthorized, mvEventHasNoBadge, mvEventNotFound, mvKnownOrganizer, mvAdminCheckFailed, mvPortalOrganizer, mvUnknownSigner, mvLegacyBadge, mvBadgeBound, verifyBadgeFound, verifyMsgLocation, verifyMsgBlock, verifyMsgSignedBy, verifyMsgProof, verifyNoExpiry, verifyMsgTagExpiry, verifyAlreadyEvent, verifyAlreadyToday, verifyAlreadyCollected, verifyOpenWallet, verifyAddToWallet, verifyClose, dialogCancel, gpsTooFar, gpsTooFarSub, badgeUnverifiedInfo, badgesHeldAside, badgeSaveFailed, verifyBadgeDuplicate, verifyBadgeSaved, caOptInTitle, caOptInBody, caOptInPrivacy, caOptInNo, caOptInYes, caPublished, caPublishFailed`.
- **SecureQRScanner / result:** `qrScanTitle, qrLoadFromGallery, qrScanHint, qrNoCodeInImage, qrNotEinundzwanzig, qrProcessError, qrSigInvalid, qrFormatUnknown, qrCantRead, qrReadError, qrVerified, qrVerifiedV2, qrV2Subtitle, qrVerifiedV1, qrV1Subtitle, qrResultTitle, qrStatBadges, qrStatMeetups, qrStatSigners, qrStatBound, qrStatDays, qrAnalyzingNetwork, qrCheckingLightning, qrCheckingNip05, qrNip05Invalid, qrSectionLightning, qrHumanVerified, qrLightningActive, qrSentCount, qrUniqueRecipients, qrReceivedCount, qrUniqueSenders, qrNoZap, qrNoLightning, qrSectionPlatforms, qrSigVerifiedShort, qrLinkedShort, qrPlatformOther, qrSectionSocial, qrMutualFollow, qrBidirectional, qrYouFollow, qrOneWay, qrFollowsYou, qrNoDirectFollow, qrStrongOverlap, qrPartiallyConnected, qrNoOverlap, qrEndorsement, qrDirectConnection, qrViaContacts, qrSectionMeetups, qrSectionIdentity, qrNoIdentity, qrLabelNickname, qrLabelTwitter, qrNoVerifiableIdentity, qrBack`.
- **NFCWriterScreen:** `writerReadyToWrite, writerNoHomeMeetup, writerSelectHomeFirst, writerHomeMeetupNotFound, nwSelectHomeMeetup, writerLoadingSession, writerNoActiveSession, nfcUnavailable, nfcDisabled, verifyNoNfcLong, writerUseRollingQr, writerForYourMeetup, nfcEnableHint, writerToWriteTag, nfcSettingsAndroid, nfcOpenSettings, writerNoNfcDevice, nfcDisabledHint, writerHoldTag, writerFormatting, writerFormatFailed, writerNoNdef, writerTagReadOnly, writerTagTooSmall, writerUseNtag215, writerTagRemovedEarly, writerTagLost, writerStartError, writerTagWritten, writerCompactSize, writerValidHours, writerJumpToQr, writerCreateMeetupTag, writerSuccess, writerMeetupTag, writerHoldTagInstruction, writerYourHomeMeetup, writerFitsNtag215, writerValidity, writerCanOverwrite, writerCreateTag`.
- **MeetupSessionWizard:** `mwStartMeetup, mwStep1Nfc, mwNfcIntro1, mwNfcIntro2, mwWriteNfcTag, mwSkipQrOnly`.
- **Event session errors (calendar screen):** `evSessionNoIdentity, evSessionNotIssuer, evSessionOutsideWindow, evSessionNoEventLocation, evSessionNoLocation, evSessionLocationOff, evSessionLocationDenied, evSessionLocationDeniedForever, evSessionLocationNoFix, evSessionTooFar, evSessionFailed, evSessionOpenSettings`.
- Other related: `scanBadge, scanBadgeSub, scanReputation, scanReputationSub` (app shell), `apSessionRunning, apOpenActiveMeetup, apSessionEndQ, apSessionEndBody, apEnd, apEndMeetupEarly, apStartMeetup, apGeneratesProof, apNewMeetupQ, apNewMeetupBody, apStart, apMeetupSession` (admin panel), `badgesUnreadableBody, statusLive, statusMeetupActive` (home), `guideHomeScanTitle, guideHomeScanBody` (tour).

Selected values (DE / EN) that carry placeholders or formatting:
`rqRemainingTime` "Restzeit: {time}\n\n"; `rqSessionRemaining` "Session: {time}"; `verifyErrQrExpired` "✗ QR-Code abgelaufen!\n{msg}\n\nBitte direkt am Bildschirm des Organisators scannen."; `verifyErrPrefix` "✗ {msg}"; `verifyAlreadyToday` "Bereits gesammelt\n\nHeute hast du bereits ein Badge von:\n{name}"; `verifyAlreadyEvent` "Du hast das Badge für „{name}“ schon. Ein Event zählt einmal — egal, an wie vielen Tagen du dabei bist."; `verifyCantSelfBadge` "Du kannst dir nicht selbst ein Badge geben.\n"; `gpsTooFarSub` "Du bist {km} km vom Meetup-Ort entfernt. Badges können nur vor Ort gesammelt werden (max. {max} km)."; `mvPortalOrganizer` "✓ Organisator von {meetup}\nIm Einundzwanzig-Portal als Leader dieses Meetups eingetragen."; `mvEventIssuerOk` "Event-Badge von „{event}“ — ausgegeben mit Erlaubnis von {creator}."; `writerTagTooSmall` "Tag zu klein! Daten: {data}B, Tag: {max}B.\n"; `evSessionTooFar` "Du bist {km} km vom Veranstaltungsort entfernt. Badges lassen sich nur vor Ort ausgeben.". Full DE/EN/ES values: `lib/l10n/app_{de,en,es}.arb`.

---

## 3. Data models

### 3.1 `MeetupBadge`

Immutable. Constructor defaults in brackets. **JSON keys are exactly the Dart field names** (camelCase), written in this order by `toJson()`:

| # | Field / JSON key | Type | Default | `fromJson` | Meaning |
|---|---|---|---|---|---|
| 1 | `id` | String | required | `as String` (missing/non-string → throws) | Badge id. Scanned badges: `normalized.meetup_id` (slug like `"wien"`, event `"evt:<dTag>"`). Organizer marker: `"org-<slug>-<yyyy-MM-dd>"`. NOT unique across visits. |
| 2 | `meetupName` | String | required | `as String` | Display name, `"City, CC"` or name. |
| 3 | `date` | DateTime | required | `DateTime.parse(String)` | Collection time (`DateTime.now()`, LOCAL). Serialized with Dart `toIso8601String()` and read with `DateTime.parse` — exact rules in §3.2, vectors `fixtures.dartIso8601`. The string is a hash input (`proofId` fallback, `getVerificationHash`, `exportBadgesForReputation`) and part of the encrypted blob plaintext (§7.7), so it must be byte-identical. |
| 4 | `iconPath` | String | required | `as String` | `"assets/badge_icon.png"` for scanned, `""` for markers/demo. |
| 5 | `coverUrl` | String | `''` | `?? ''` | Event badge image URL (from calendar `badge_image`). |
| 6 | `blockHeight` | int | `0` | `as int? ?? 0` (double → throws) | Block height at scan time. Part of the claim content — never change after claiming. |
| 7 | `signerNpub` | String | `''` | `?? ''` | Organizer npub (bech32). |
| 8 | `meetupEventId` | String | `''` | `?? ''` | Meetup: `"<slug>-<yyyy-MM-dd>"` (local date of scan); event: `"evt|<kind>:<pubkey>:<dTag>"`; `''` if no usable name. |
| 9 | `delivery` | String | `'nfc'` | `?? 'nfc'` | `'nfc'`, `'rolling_qr'`, `'organizer'` (markers). |
| 10 | `sig` | String | `''` | `?? ''` | Organizer Schnorr sig (128 hex) of the kind-21000 event. |
| 11 | `sigId` | String | `''` | `?? ''` | Organizer event id — **empty for compact badges** (only legacy full-format tags carried `sig_id`). |
| 12 | `adminPubkey` | String | `''` | `?? ''` | Organizer hex pubkey (64). |
| 13 | `sigVersion` | int | `0` | `as int? ?? 0` | 2 if `sig.length == 128`, 1 if other non-empty, 0 none. |
| 14 | `sigContent` | String | `''` | `?? ''` | JSON of the scanned map (minus `_verified_by`) for later re-verification (promotion claims). |
| 15 | `claimSig` | String | `''` | `?? ''` | Collector's Schnorr sig of the kind-21002 claim. |
| 16 | `claimEventId` | String | `''` | `?? ''` | Claim event id. |
| 17 | `claimPubkey` | String | `''` | `?? ''` | Collector hex pubkey. |
| 18 | `claimTimestamp` | int | `0` | `as int? ?? 0` | `claimed_at` = claim `created_at` (unix s). |
| 19 | `isRetroactive` | bool | `false` | `as bool? ?? false` | Claimed later (retroactive). |
| 20 | `presenceVerified` | bool | `true` | `as bool? ?? true` | Location was measurable and within radius. Old badges default to TRUE. |
| 21 | `isOrganizer` | bool | `false` | `as bool? ?? false` | Organizer marker (excluded from trust score & duplicates). |
| 22 | `isEvent` | bool | `false` | `as bool? ?? false` | Event badge (not a meetup). |
| 23 | `lat` | double | `0` | `(num?)?.toDouble() ?? 0` | Collector's position at save time (0 = unknown). For markers: organizer position. |
| 24 | `lng` | double | `0` | same | |

Note: key `presenceVerified` is written BEFORE `isOrganizer` in `toJson` (order: …, `isRetroactive`, `presenceVerified`, `isOrganizer`, `isEvent`, `lat`, `lng`). `fromJson` is strict on types: any mismatch throws, and the whole encrypted blob is then treated as "not openable with this key" (§4.2). The native writer MUST emit JSON integers for int fields (no `.0`), booleans as `true/false`.

Derived properties (exact):
- `hasCryptoProof = sig != '' && sigVersion > 0`.
- `isNostrSigned = sigVersion == 2 && sig matches ^[0-9a-fA-F]{128}$ && adminPubkey matches ^[0-9a-fA-F]{64}$ && BigInt(sig,16) != 0`.
- `isClaimed = claimSig != '' && claimPubkey != ''`.
- `isFullyBound = hasCryptoProof && isClaimed`.
- `proofId = sigId != '' ? sigId : (sig != '' ? sig.substring(0,64) : sha256hex("$id-$meetupName-${date.toIso8601String()}-$blockHeight"))` (substring(0,64) of a 64-char hash = whole hash). `sig.substring(0,64)` throws in Dart if `sig` has < 64 chars.
- `claimProofId = isClaimed ? sha256hex("$sig|$claimSig|$claimPubkey") : ''`.
- `getVerificationHash() = sha256hex("$id-$meetupName-${date.toIso8601String()}-$blockHeight").substring(0,16)`.
- `toReputationString()`:
  ```
  'Badge #$id\n'
  'Meetup: $meetupName\n'
  'Datum: ${date.day}.${date.month}.${date.year}\n'
  'Block: $blockHeight\n'
  '${isNostrSigned ? "🔐 Nostr-verifiziert" : "Verifiziert"} bei Einundzwanzig'
  '${isClaimed ? "\n🔗 Gebunden" : ""}'
  ```

Copy helpers:
- `withClaim(claimSig, claimEventId, claimPubkey, claimTimestamp, isRetroactive=false, isOrganizer?, lat?, lng?, presenceVerified=true)` — copies all, sets claim fields; **`presenceVerified` defaults to TRUE (does not keep the old value)**; `isEvent` kept.
- `copyWith(...)` — all fields overridable **except `isEvent`** (always kept).

Equality: Dart has no `==` override (identity). Comparisons use `identityKey`, `unionKey`, `sameBadges` (§8.1).

Global state: `List<MeetupBadge> myBadges` (in-memory mirror, set by scanner/home). Native: a `BadgeRepository` `StateFlow<List<MeetupBadge>>`.

### 3.2 Dart `DateTime` semantics that must be preserved

A Dart `DateTime` is (instant, isUtc). Native model: `DartDateTime(epochMicros: Long, isUtc: Boolean)`.
- `DateTime.now()` → local, microsecond precision.
- `toIso8601String()`: `yyyy-MM-ddTHH:mm:ss.mmm` + (`uuu` only if microsecond != 0) + (`Z` if UTC). Local has no offset. Years outside 0..9999 use ±yyyyyy (not relevant). Vectors: `fixtures.dartIso8601`.
- `DateTime.parse(s)`: accepts `T` or space, 0..n fractional digits (rounded/truncated to µs), optional `Z` or `±hh:mm` / `±hhmm`; with `Z`/offset → **UTC** DateTime (offset applied), else **local**.
- `.year/.month/.day` of a UTC DateTime are UTC components; of a local one, local components. `identityKey` fallback and the scanner's same-day check depend on this.
- `compareTo` compares instants.

### 3.3 Archive entry (`badges_former` list element) and `FormerCollection`

Stored entry (JSON object, insertion order): `{"at": <int unix seconds>, "reason": "replaced"|"cleared"|"rotated", "npub": "<npub or ''>", "blob": "<iv>:<ct>"}`.
`FormerCollection { index: Int (position in stored list), at: DateTime (local, from at*1000), reason, npub, badges: List<MeetupBadge>? (null if no key opens the blob) }`, listed newest first.

### 3.4 `MeetupSession`

| Field | Type | Source |
|---|---|---|
| `seed` | String | 64 lowercase hex chars (32 random bytes) — HMAC key material (as UTF-8 text!) |
| `startedAt` | int | unix s |
| `expiresAt` | int | unix s |
| `meetupId` | String | signed `m` value (`"bad-neuenahr-de"`, `"evt:…"`) |
| `meetupName` | String | display name (city, free text, or event title) |
| `meetupCountry` | String | ISO code or `''` |
| `blockHeight` | int | from mempool at session creation (0 if offline) |
| `pubkey` | String | **npub** (bech32) despite the name (`NostrService.getNpub()`) |
| `lat`,`lng` | double | organizer GPS (meetup) or event venue coords (event); 0 = none |

Derived: `isExpired = nowSec > expiresAt`; `remainingTime = max(0, expiresAt-nowSec)` s; `remainingTimeString` (§2.3); `elapsedTime = nowSec - startedAt`.

### 3.5 Badge payload formats (wire)

**Compact signed content (kind 21000)** — keys `v,t,m,b,x`:
`v`=2 (int), `t`="B", `m`=meetup id string, `b`=block height int (may be 0), `x`=expiry unix s int.

**Base payload** (stored per session, written to NFC, basis of QR) = content + `c` (event created_at int), `p` (hex pubkey), `s` (hex sig). Dart key order: `v,t,m,b,x,c,p,s`. Example (`fixtures.compactBadge.base_payload_json`):
```
{"v":2,"t":"B","m":"bad-neuenahr-de","b":917000,"x":1760014400,"c":1760000000,"p":"79be66…1798","s":"0d706e…f4f0"}
```

**Rolling QR string** = base payload + unsigned `n` (16 hex nonce), `ts` (int time step), `d`=`"rolling_qr"`, plus `la`,`lo` (doubles) only if session lat != 0 or lng != 0. Compact JSON (no spaces), key order as above, appended in order `n, ts, d, la, lo`. Example: `fixtures.rollingQr.qr_string`.

**`m` grammar:**
- Meetup: `MeetupKey.compose(slug(cityOrFreeText), country)` → `"<slug>"` or `"<slug>-<iso2 lower>"`. Older organizer apps signed only the slug.
- Event: `"evt:<kind>:<creatorPubkeyHex>:<dTag>:<titleSlug>"` (§8.4). Length ~110+ chars.

**Legacy full format** (verify-only, producer `signWithNostr` has no callers): map with `v`(≥2), `type`, `meetup_id`, `meetup_name`, `meetup_country`, `meetup_date`, `timestamp`, `block_height`, `admin_npub`, plus `sig`, `sig_id`, `admin_pubkey`, `created_at`.

**Recognised input aliases** (normalize): `qr_nonce`, `qr_time_step`, `qr_interval`, `delivery`, `n`, `ts`, `d` (`'qr'` → `'rolling_qr'`).

**Normalized map** (output of `normalize`, keys in insertion order): see §8.3 and `fixtures.normalize`.

### 3.6 Result/value types

- `VerifyResult { isValid: Boolean, version: Int, adminNpub: String, adminPubkey: String = '', message: String }`.
- `QRSignResult { signature, eventId, createdAt: Int, pubkeyHex, isNostr: Boolean }`; `QRVerifyResult { isValid, version, signerNpub, message = '' }`.
- `ClaimResult { success, claimSig='', claimEventId='', claimPubkey='', claimTimestamp=0, message='' }`; `ClaimVerifyResult { isValid, claimerNpub='', message='' }`.
- `NonceValidation { isValid, message, ageSeconds }`.
- `EventChainStatus { verified, signerNotListed, creatorNotAuthorized, eventHasNoBadge, eventNotFound }`; `EventChainResult { status, eventTitle='', creatorPubkey='', creatorName: String?, badgeImageUrl='' ; ok = status==verified }`.
- `EventSessionError { noIdentity, notIssuer, outsideWindow, noEventLocation, locationUnavailable, locationServiceOff, locationDenied, locationDeniedForever, locationNoFix, tooFarAway, sessionFailed }`; `EventSessionResult { session: MeetupSession?, error: EventSessionError?, distanceKm: Double? (only tooFarAway) ; ok = session != null }`.
- `EventBadgeRight { none, adminRegistry, portalLeader }`.
- `ParsedMeetupId { slug, country (upper ISO or '') }`.

### 3.7 Reputation QR v3 payload (scanner reads it; producer is reputation domain)

String: `"21v3:" + base64(utf8(json)) + "." + sig + "." + eventId + "." + createdAt + "." + pubkeyHex` (standard base64 with padding). Fallback producer format when not signable: `"21:" + base64 + "." + ""` (rejected by scanners).
Signed event: kind **21001**, tags `[]`, content = the exact `json` string. JSON (producer key order): `{"v":6,"id":{"n":nick or "Anon","np"?:npub,"tg"?:handle,"tw"?:handle},"rp":{"sc":double(1 decimal),"lv":level,"bc":int,"vc":int,"mc":int,"si":int,"ad":int,"ml"?:[12-hex…≤10],"bb":int},"pf":{"bp":hex,"pv":2|1,"vc":int,"tc":int,"bb":int},"pp"?:{"<platform>":{"u":user,"s":sig}},"t":<ms epoch>}`. `ml` entries = `sha256("21meetup:<meetupName>")[0:12]`. Vector: `fixtures.reputationQrV3`.

### 3.8 `exportBadgesForReputation(badges, userNpub, nickname='', telegram='', twitter='')` (used by wallet domain)

```
identity = {} ; add 'nickname','nostr_npub','telegram','twitter' when non-empty ; empty → {'status':'anonymous'}
data = {
 'version':'4.0', 'identity':…, 'total_badges':n, 'verified_badges':countVerified,
 'bound_badges':countBound, 'meetups_visited':distinct meetupName count,
 'unique_signers':distinct non-empty signerNpub count,
 'badge_proof':generateBadgeProof(badges), 'badge_proof_v2':generateBadgeProofV2(badges),
 'stats':{'total','crypto_proof','claimed','retroactive','fully_trusted'},
 'badges':[ { 'meetup','date'(iso),'block','signer_npub','sig_version','is_bound','is_retroactive',
              'presence_verified', [if hasCryptoProof:'sig','sig_id','admin_pubkey'],
              [if isClaimed:'claim_sig','claim_event_id','claim_pubkey'], 'hash':getVerificationHash() } ],
 'exported_at': DateTime.now().toIso8601String() }
checksum = sha256hex(jsonEncode(data) /*compact, before checksum*/).substring(0,8)
data['checksum'] = checksum
return JsonEncoder.withIndent('  ').convert(data)   // 2-space pretty print, checksum last
```
`getReputationStats`: `total = n`, `crypto_proof = #hasCryptoProof`, `claimed = #isFullyBound`, `retroactive = #(bound && isRetroactive)`, `fully_trusted = claimed - retroactive`.

---

## 4. Persistence

### 4.1 Keys

SharedPreferences (Flutter stores them in `FlutterSharedPreferences.xml` with prefix `flutter.`; see §4.4):

| Key | Type | Writer / reader | Content |
|---|---|---|---|
| `badges_encrypted` | String | badge store | **Primary** blob `b64(iv):b64(ct‖tag)` of `jsonEncode(List<badge.toJson()>)` |
| `badges_encrypted_preserved` | String | badge store | First backup slot (older primary) |
| `badges_encrypted_preserved_older` | String | badge store | Second backup slot |
| `badges` | List<String> | legacy | Plaintext badges (one JSON object per entry) — migrated & removed |
| `badges_pending_encrypted` | String | badge store | Badges kept aside while the wallet is unreadable; encrypted with `badge_pending_key` |
| `badges_pending` | List<String> | legacy (PR 84 test builds) | Plaintext pending — read, re-encrypted, removed |
| `badges_encrypted_incoming` | String | backup/sync via `stageIncoming` | Staged collection for identity switch (not read by `loadBadges`) |
| `badges_former` | String | badge store | JSON list of archive entries (§3.3), max 10 / 500 KiB |
| `rqr_session_start` | int | RollingQRService | unix s |
| `rqr_session_expires` | int | RollingQRService (also read by admin panel) | unix s |
| `rqr_session_meetup_id` | String | RollingQRService | signed `m` |
| `rqr_session_meetup_name` | String | RollingQRService | |
| `rqr_session_meetup_country` | String | RollingQRService | |
| `rqr_session_block_height` | int | RollingQRService | |
| `rqr_session_pubkey` | String | RollingQRService | npub |
| `rqr_session_lat` / `rqr_session_lng` | double | RollingQRService | |
| `rqr_session_seed` | String | legacy | migrated to secure storage then removed |
| `rqr_session_base_payload` | String | legacy | migrated to secure storage then removed |
| `haptic_enabled` | bool (default true) | settings (other domain) | read by app shell before opening scan sheet |

Secure storage (Flutter: `flutter_secure_storage` v10, options `resetOnError:false, migrateOnAlgorithmChange:true, migrateWithBackup:true`; iOS `first_unlock_this_device`):

| Key | Content |
|---|---|
| `badge_encryption_key` | 64 hex chars (lowercase written; reading accepts any case, trimmed) = AES-256 key |
| `badge_encryption_key_BACKUP` | Possible migration copy written by the storage plugin; read as candidate key |
| `badge_encryption_key_suspect` | Non-hex value previously found under the key alias, moved aside on deliberate replace |
| `badges_encrypted` | **Second copy** of the primary blob (survives iOS reinstall) |
| `badges_former` | Second copy of the archive JSON |
| `badge_reset_pending` | `"1"` while a reset could not delete the secure copies |
| `badge_pending_key` | 64 hex AES key for the pending blob |
| `badge_pending_key_suspect` | moved-aside invalid pending key |
| `rqr_session_seed_secure` | session seed (64 hex) |
| `rqr_session_base_payload_secure` | base payload JSON string (contains signature) |

In addition, **every** value in secure storage (`readAll()`) that is 64 hex chars is tried as a candidate badge key (this includes the pending key and possibly unrelated 64-hex secrets).

### 4.2 Badge store algorithms (port 1:1; these protect users from data loss)

Notation: `prefs`, `secure`; `hasColon(s) = s != null && s.contains(':')`; `commit(k,v)` = `setString` succeeded AND re-read equals `v` (on failure `reload()` and return false); `blob(badges,key)` = AES-GCM encrypt of `jsonEncode(badges.map(toJson))`, new random 12-byte IV; `decrypt(blob,key)` = split on `:` (must be exactly 2 parts) → decode IV & ciphertext → GCM decrypt → `jsonDecode` as list → `fromJson` each; **any** exception → `null`.

Static flags: `badgesUnreadable` (bool, process-global, starts false); `onBadgesSaved` callback (set by sync service; invoked after successful save/clear/promote).

**Key handling**
```
getBadgeKey(create):
  try existing = secure.read('badge_encryption_key') catch → return null
  if hex64(existing.trim()) → return it
  if existing non-blank:
     if !create → return null                    // never overwrite an unknown value on read
     try secure.write('badge_encryption_key_suspect', existing) catch → return null
  if !create → return null
  key = 32 random bytes ; try secure.write(alias, lowerhex(key)) catch → return null
  return key
rememberKey(key): if secure[alias].trim().lower() != hex(key) → write (errors ignored)
candidateKeys(primary): ordered, de-duplicated: [primary] + [secure['badge_encryption_key_BACKUP']] + [every hex64 value in secure.readAll()]  (read errors ignored)
```

**loadBadges()**
```
blob = prefs[primary]; preserved = prefs[preserved]; older = prefs[older]
blobs = []  (ordered, unique, only hasColon)
add(blob)
resetPending = resetStillPending()
secureCopy = resetPending ? null : secure.read('badges_encrypted') (errors → null)
add(secureCopy); add(preserved); add(older)
if blobs non-empty:
  opened = openAll(blob, blobs)
  if opened != null:
    if opened.primary == null:                   // primary missing or unreadable
      parked = true
      if hasColon(blob) && blob != opened.blob:
        if preserved == opened.blob:
          parked = appendFormer([entry('rotated','',blob)])
          if parked: parked = commit(primary, opened.blob)
          if parked: commit(preserved, blob)
        else:
          parked = stashBlob(blob)
      if parked: prefs.setString(primary, opened.blob)
    else if !resetPending && hasColon(blob) && secureCopy empty:
      writeSecureCopy(blob)                      // create 2nd copy for old installs
    rememberKey(opened.key)
    if opened.openedCount > 1 && !sameBadges(opened.anchor, opened.badges):
      badgesUnreadable = false
      pending = readPending()
      if saveBadges(opened.badges):
        return (pending null/empty) ? opened.badges : unionByIdentity(opened.badges, pending)
    return finishReadable(opened.badges)
  // nothing opened
  if preserved empty && hasColon(blob): prefs.setString(preserved, blob)   // fill only, never displace
  badgesUnreadable = true
  return []
legacy = prefs.getStringList('badges')
if legacy non-empty: badges = parse each (skip failures)
  if badges non-empty: badgesUnreadable = false; if saveBadges(badges) return badges
badgesUnreadable = false; return []

openAll(primaryBlob, blobs):
  keys = candidateKeys(getBadgeKey(create:false))
  for blob in blobs: for key in keys:
     b = decrypt(blob,key); if null continue
     openedCount++
     if firstBlob == null: firstBlob=blob; firstKey=key; anchor=b
     if blob == primaryBlob: openedPrimary=blob; openedPrimaryKey=key; anchor=b
     allRead += b ; break
  if firstBlob == null → null
  return {badges: unionByIdentity(allRead, []), anchor, openedCount,
          primary: openedPrimary, blob: openedPrimary ?? firstBlob, key: openedPrimaryKey ?? firstKey}

finishReadable(badges):
  badgesUnreadable = false
  pending = readPending(); if null or empty → return badges
  merged = unionByIdentity(badges, pending)
  return saveBadges(merged) ? merged : badges
```

**saveBadges(badges, replaceUnreadable=false, discardLocked=false) → Boolean**
```
if badges empty:                                   // "clear" — archives, never deletes
  if badgesUnreadable && !discardLocked → false
  r = retainCurrent('cleared'); if !r.ok → false
  if !deleteSecureCopy() → false                   // FIRST the secure copy
  prefs.remove(primary, preserved, older, 'badges')
  releasePending(keepKey: r.keepPendingKey)
  badgesUnreadable = false; onBadgesSaved(); → true
if badgesUnreadable && !replaceUnreadable → false
currentBlob = prefs[primary]; hasBlob = hasColon(currentBlob)
if replaceUnreadable && hasBlob && !stashBlob(currentBlob) → false
key = getBadgeKey(create: !hasBlob || replaceUnreadable); null → false
pending = readPending()                            // null = exists but unreadable → keep it
toStore = unionByIdentity(badges, pending ?? [])
try:
  stored = blob(toStore, key)
  if !commit(primary, stored) → false
  writeSecureCopy(stored)                          // errors ignored
  prefs.remove('badges') if present
catch:
  if hasBlob → false
  prefs.setStringList('badges', toStore.map(jsonEncode))   // Dart plaintext fallback (see §14)
if pending != null: writePending([])
badgesUnreadable = false; onBadgesSaved(); → true
```

**stashBlob(blob)** (rotate backups):
```
p = prefs[preserved]
if p non-empty:
  if p == blob → true
  o = prefs[older]
  if !appendFormer([entry('rotated','', o ?? '')]) → false     // entries without ':' are skipped
  if !commit(older, p) → false
return commit(preserved, blob)
```

**Pending (keep aside)**
```
keepAside(badge): pending = readPending(); null → false (log error)
  if pending.any(id == badge.id) → true
  pending += badge; if !writePending(pending) → false; → true
readPending(): out = []
  b = prefs['badges_pending_encrypted']; if non-empty:
     key = getPendingKey(create:false); bs = key==null ? null : decrypt(b,key); null → return null
     out += bs
  out += parse each of prefs.getStringList('badges_pending') (skip failures)
  return out
writePending(list):
  if empty: remove 'badges_pending'; if !contains('badges_pending_encrypted') → true
            remove it; secure.delete('badge_pending_key') (ignore errors); → true
  key = getPendingKey(create:true); null → false
  try prefs.setString('badges_pending_encrypted', blob(list,key)) catch → false
  remove 'badges_pending'; → true
getPendingKey(create): try raw = secure.read('badge_pending_key'); parsed = hex64(raw)
  if parsed || !create → parsed
  if raw non-blank: try secure.write('badge_pending_key_suspect', raw) catch → null
  key = random32; secure.write('badge_pending_key', hex); → key
  (any exception → null)
releasePending(keepKey): remove 'badges_pending', 'badges_pending_encrypted'; if !keepKey secure.delete('badge_pending_key') (ignore errors)
```

**Archive ("former collections")**
```
retainCurrent(reason, npub=''):
  blobs = unique hasColon among [prefs[primary], (resetStillPending ? — : readSecureCopy()), prefs[preserved], prefs[older], prefs['badges_pending_encrypted']]
  pendingBlob = prefs['badges_pending_encrypted']
  legacy = parse prefs lists 'badges' + 'badges_pending'
  if blobs empty && legacy empty → (ok:true, keepPendingKey:false)
  deviceKey = getBadgeKey(create:false)
  if legacy non-empty && deviceKey == null → (ok:false, keep:false)
  keys = candidateKeys(deviceKey); opened = {} ; union = []
  for blob: for key: bs = decrypt → opened += blob; union = unionByIdentity(union, bs); break
  if legacy non-empty: union = unionByIdentity(union, legacy)
  entries = []
  if deviceKey != null && union non-empty:
     try entries += entry(reason, npub, blob(union, deviceKey)) catch → (false,false)
     for blob not in opened: entries += entry(reason, npub, blob)
  else: for blob: entries += entry(reason, npub, blob)
  keep = deviceKey == null || (hasColon(pendingBlob) && pendingBlob not in opened)
  return (ok: appendFormer(entries), keepPendingKey: keep)
entry(reason,npub,blob) = {'at': nowSec, 'reason', 'npub', 'blob'}
appendFormer(adds): if empty → true
  list = loadFormer(); added=false
  for e: b=str(e.blob); skip if !b.contains(':') or list has same blob; list += e; added=true
  if !added → true
  while list.size > 1 && (list.size > 10 || jsonEncode(list).length > 500*1024): list.removeAt(0)
  return writeFormer(list)
loadFormer(): fromPrefs = parse(prefs['badges_former']) (non-list/invalid → [])
  if resetStillPending() → fromPrefs
  secure = secure.read('badges_former') (errors → null)
  merged = unionFormer(fromPrefs, parse(secure))
  if merged.size != fromPrefs.size: prefs.setString('badges_former', jsonEncode(merged)) (ignore errors)
  return merged
unionFormer(a,b): dedupe by non-empty blob (first wins) over a+b, then STABLE sort by at (int, missing=0)
writeFormer(list): if !commit('badges_former', json) → false; secure.write('badges_former', json) (ignore errors); → true
formerCollections(): entries = loadFormer(); for i from last to 0: FormerCollection(i, at, reason, npub, openFormer(blob))
openFormer(blob): first decrypt success over candidateKeys(getBadgeKey(false)) else null
mergeFormer(i): entries; bounds → false; former = openFormer → null → false
  current = loadBadges(); if badgesUnreadable → false; return saveBadges(unionByIdentity(current, former))
```

**Identity switch staging**
```
stageIncoming(badges): empty → false; key = getBadgeKey(create:true); null → false
  try stored = blob(badges,key); return commit('badges_encrypted_incoming', stored) catch → false
promoteIncoming(formerNpub=''):
  incoming = prefs['badges_encrypted_incoming']; !hasColon → false
  if prefs[primary] != incoming:                   // not yet promoted (crash-safe)
     r = retainCurrent('replaced', npub: formerNpub); !r.ok → false
     if !deleteSecureCopy() → false
     remove preserved, older
     releasePending(keepKey: r.keepPendingKey)
     if !commit(primary, incoming) → false
  if prefs[primary] != incoming → false
  writeSecureCopy(incoming)                        // failure tolerated
  remove 'badges_encrypted_incoming'; badgesUnreadable=false; onBadgesSaved(); → true
discardIncoming(): remove 'badges_encrypted_incoming'
```

**Reset (the only real delete)**
```
resetAll():
  try secure.write('badge_reset_pending','1')
  remove prefs: primary, preserved, older, 'badges', 'badges_encrypted_incoming', 'badges_former'
  writePending([]); deleteSecureCopy(); deleteFormer()   // prefs + secure 'badges_former'
  if resetStillPending(): warn ; badgesUnreadable = false
resetStillPending(): marker = secure.read('badge_reset_pending') (error → false); marker != '1' → false
  a = deleteSecureKey('badges_encrypted'); b = deleteSecureKey('badges_former')
  if a && b: deleteSecureKey('badge_reset_pending'); → false
  → true
deleteSecureKey(k): try delete; v = read(k); → v null/empty  (exception → false)
deleteSecureCopy() = deleteSecureKey('badges_encrypted')
```

### 4.3 Session persistence (`RollingQRService`)

- Create: write secure seed; prefs start, expires, meetup_id, meetup_name, meetup_country, block_height, pubkey (npub), lat, lng (doubles, always written, may be 0.0); secure base payload = `jsonEncode(basePayload)`.
- Load: migrate legacy prefs `rqr_session_seed` → secure (then remove); require seed, start, expires, meetup_id; else null. Name/country/pubkey default `''`, block 0, lat/lng 0. If expired → `endSession()` and null.
- Base payload load migrates legacy prefs `rqr_session_base_payload` → secure.
- `endSession()`: delete secure seed; remove legacy seed + all `rqr_session_*` prefs; delete secure base payload + legacy prefs key.
- There is only ONE session slot per device; starting a different session overwrites it.

### 4.4 Flutter → native upgrade (same applicationId) — must be planned with the storage/identity domain

Users updating from the Flutter APK must keep their badges and running sessions:
- SharedPreferences file `FlutterSharedPreferences`, keys prefixed `flutter.`; ints stored as `Long`; doubles as String prefixed `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu` + `Double.toString`; `List<String>` as String prefixed `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!` + JSON array (older plugin versions: same prefix WITHOUT `!` + base64 Java-serialized list); `BigInteger` prefix `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBCaWdJbnRlZ2Vy`.
- `flutter_secure_storage` 10.3.4 Android: prefs file `FlutterSecureStorage`, key prefix `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg_` (prefix + `_` + key), storage cipher `AES_GCM_NoPadding`, key cipher `RSA_ECB_OAEPwithSHA_256andMGF1Padding` (Android Keystore wrapped AES key); `_BACKUP` suffix entries from algorithm migration. Write a one-time importer (read via the same scheme or keep a tiny compatibility reader) into the native stores, then keep the native store's own keys. Do NOT drop the old files until the import verified that `loadBadges()` opens.
- `android:allowBackup="false"` in the Flutter manifest — keep it.

---

## 5. Network

This domain mostly works offline; network calls are made through other domains' services:

| Call | Endpoint / transport | When | Timeout / retry | Failure handling |
|---|---|---|---|---|
| Block height | `MempoolService.getBlockHeight()` → `GET {mempoolHost}/api/blocks/tip/height` (plain integer body; host configurable, mempool domain) | session creation (if blockHeight ≤ 0) ; scan | max 20 s; session: 1 retry after 2 s; scan: 1 retry after 1 s | 0 → session `b=0` (no hardcoded fallback); scan falls back to tag `b` |
| Admin registry check | `AdminRegistry.checkAdminByPubkey(hex)` (Nostr relays + local cache, kind 30078) | scan (meetup badges), event chain (creator) | per registry domain | `source=='unavailable'` → "check failed" message |
| Admin registry by npub | `AdminRegistry.checkAdmin(npub)` | `EventBadgeAuthService.myRight` | | exception → continue |
| Portal leader list | `PortalLeaderService.isLeaderOf(meetupIdInt, npub)` (portal API, cached) | scan | | null = no information |
| Portal meetups | `MeetupService.fetchMeetups()` (portal API, cached; `allMeetups` bundled fallback) ; `bySlug`, `byName`, `resolveFavorite`, `organizerMeetupFor` (cache) | scan display name, presence reference, NFC writer | | fallbacks as described |
| My portal meetups | `PortalApiService.getMyMeetups()` (needs portal auth token) | `myRight` | | exception → next source |
| Calendar event by address | `CalendarEventService.fetchByAddress(address)` — WebSocket to `wss://nostr.einundzwanzig.space` + all active relays in parallel; REQ `{"kinds":[kind],"authors":[author],"#d":[dTag],"limit":1}`; sub id `cal1-<12 hex>` | event badge scan | connect 8 s, EOSE wait 8 s per relay | highest `created_at` wins across relays; cancelled/none → `eventNotFound` |
| Reputation publish | `ReputationPublisher.publishInBackground(badges)` | after save | fire & forget | — |
| Co-attendance publish | `CoAttendanceService.publishAttendance(badge)` → count of accepting relays | after opt-in | | count 0 → failure snackbar |
| Organizer attendance | `CoAttendanceService.recordOrganizerAttendance(...)` | session start (awaited for meetups, unawaited for events) | | null on failure |
| Trust layers | `SocialGraphService.analyze(hex)`, `ZapVerificationService.analyzeZapActivity(hex, useCache:false)`, `RelayConfig.getActiveRelays()` + `Nip05Service.fetchNip05FromProfile(hex, relays)` + `Nip05Service.verify(nip05, hex)` | reputation QR result | parallel | errors swallowed (section hidden) |

No rate limiting beyond what those services do. None of this domain's own events (21000/21001/21002) are ever sent to relays.

---

## 6. Nostr

| Kind | Name | Published? | Signer | Tags (exact, in order) | Content |
|---|---|---|---|---|---|
| 21000 | Compact badge signature | **No** (embedded in QR/NFC) | organizer / event helper | `[["t","badge"],["m","<m>"]]` | canonical JSON of `{v,t,m,b,x}` |
| 21000 | Legacy full badge (verify only) | No | organizer | `[["t","<type lower>"],["meetup","<meetup_id>"],["block","<block_height>"]]` | canonical JSON of the full map minus `sig, sig_id, admin_pubkey, _verified_by, created_at` |
| 21001 | Reputation QR signature | No (embedded in `21v3:` QR) | profile owner | `[]` | exact QR JSON string |
| 21002 | Badge claim | No (stored in badge; transmitted inside kind-30021 promotion claims by another domain) | collector | `[["t","badge_claim"],["p","<orgPubkeyHex>"],["block","<blockHeight decimal>"]]` | canonical JSON of claim map (§7.3) |
| 31922/31923 | NIP-52 calendar event (read) | other domain | event creator | badge extensions below | |

Ephemeral range (20000–29999) on purpose: relays would not store them anyway.

**Event id / signature (NIP-01):** `id = sha256(utf8(jsonEncode([0, pubkeyLowerHex, created_at, kind, tags, content])))` as lowercase hex; `sig` = BIP-340 Schnorr over the 32-byte id. Verification (Dart `nostr` 1.5.0 `Event` constructor with default `verify:true`, then `isValid()`):
- pubkey is lower-cased before hashing; `isValid` requires `createdAt.toString().length == 10` (1 000 000 000 ≤ created_at ≤ 9 999 999 999), recomputed id == given id (case-sensitive), and `bip340.verify(pubkey, id, sig)`.
- The constructor **throws** `'Invalid event'` when invalid; callers catch it. Consequence for messages: an invalid compact signature yields `VerifyResult(isValid:false, version:0, message:"Verifikations-Fehler: Invalid event")` (not "Signatur ungültig!"); an invalid 21v3 yields "Fehler: Invalid event"; an invalid claim signature yields "Claim-Verifikation fehlgeschlagen: Invalid event".
- In `verifyCompact` the id is computed from the payload `p` **as given**; because the library lower-cases the pubkey, an upper-case `p` produces an id mismatch → invalid. Native: treat non-lowercase `p` as invalid (equivalent).

**NIP-19:** `npub` encode/decode of 32-byte pubkeys (signer display, self-scan comparison, portal lookups). Provided by the Nostr core domain.

**NIP-52 badge extensions** (read by `NostrCalendarEvent.fromEvent`, written by calendar domain):
- `["badge","true"]` → `badgeEnabled` (case-insensitive `"true"`).
- `["badge_image","<url>"]` → `badgeImageUrl`.
- `["g","<lat>","<lng>"]` → venue coords (first `g` tag with ≥3 entries; parse failures → 0).
- `["p","<hex64>","","issuer"]` → issuers (only `length==64`).
- `["p","<hex64>","","former_issuer","<unix>"]` → former issuers map hex → cutoff.
- `address = "<kind>:<pubkey>:<dTag>"`; `status` `cancelled` hides the event.
- `isIssuer(hex, atEpoch?)`: empty → false; equals creator pubkey (case-insensitive) → true; in issuers (case-insensitive) → true; if `atEpoch != null` and a former issuer matches with `atEpoch <= cutoff` → true; else false.
- `isBadgeWindowOpen`: `badgeEnabled` AND `localMidnight(start) <= now < localMidnight((end ?? start) + 1 day)`.
- Replaceable semantics in `fetchByAddress`: per relay keep the event with the greatest `created_at` (signature-verified, answering the filter, matching author/d/kind); across relays greatest `created_at` wins; `isCancelled` → treated as not found.

**Relays used by this domain directly:** none. Indirectly: `wss://nostr.einundzwanzig.space` + user's active relays (calendar fetch), registry/reputation/co-attendance relays (other domains).

**NIP-46 permissions:** the bunker client requests `sign_event` permission for kinds 21000, 21001, 21002 (among others) — keep these in the native NIP-46 permission list.

---

## 7. Crypto & encoding

### 7.1 Canonical JSON (verbatim)

```dart
static String canonicalJsonEncode(Map<String, dynamic> data) {
  final sortedKeys = data.keys.toList()..sort();
  final sortedMap = <String, dynamic>{};
  for (final key in sortedKeys) {
    sortedMap[key] = data[key];
  }
  return jsonEncode(sortedMap);
}
```
- Only **top-level** keys are sorted (UTF-16 code-unit order = Kotlin `String.compareTo`); nested maps keep insertion order (the HTML verifier sorts recursively — irrelevant because all signed contents are flat).
- `jsonEncode` = Dart compact JSON (no whitespace). String escaping (Dart `writeStringContent`): escape `"` → `\"`, `\` → `\\`, code units < 0x20 as `\b \t \n \f \r` or `\u00xx` (lowercase hex), unpaired surrogates as `\udxxx`; **everything else raw** (non-ASCII UTF-8, `/`, `<`, U+2028/2029). Do NOT use `org.json` (escapes `/` after `<` and some Unicode ranges) or Gson defaults (HTML-escapes `<`, `>`, `&`, `=`, `'`). Write a dedicated `DartJson` encoder.
- Numbers: ints as decimal; doubles via Dart `double.toString()` (`0.0`, `0.0001`, `1e-7`, `48.2`, `1e+21`, `100.0`, `-0.0`). Signed contents only contain ints and strings.
- Vectors: `fixtures.canonicalJson`.

### 7.2 Compact badge signing (verbatim core)

```dart
final int expiresAt = DateTime.now().millisecondsSinceEpoch ~/ 1000 + (validityHours * 3600);
final Map<String, dynamic> content = {'v': 2, 't': 'B', 'm': meetupId, 'b': blockHeight, 'x': expiresAt};
final contentJson = canonicalJsonEncode(content);   // {"b":…,"m":"…","t":"B","v":2,"x":…}
final tags = <List<String>>[['t', 'badge'], ['m', meetupId]];
final signed = await SigningService.signEvent(kind: 21000, tags: tags, content: contentJson);
return {...content, 'c': signed.createdAt, 'p': signed.pubkey, 's': signed.sig};
```
`validityHours` is called with `RollingQRService.sessionValidityHours = 4` (default param 6 is unused). Requires `SigningService.canSign()`; else throws `SigningException('Kein Nostr-Schlüssel vorhanden.')`.

### 7.3 Compact verification (verbatim core)

```dart
final String pubkey = data['p'] ?? ''; final String sig = data['s'] ?? ''; final int createdAt = data['c'] ?? 0;
if (pubkey.isEmpty || sig.isEmpty || createdAt == 0) → invalid 'Fehlende Signatur-Daten' (version 2)
if (!isValidPubkeyHex(pubkey)) → invalid (64 chars, [0-9a-fA-F]+)
if (!isValidSignatureHex(sig)) → invalid (128 chars)
final int expiresAt = data['x'] ?? 0;
if (checkExpiry && expiresAt > 0 && nowSec > expiresAt) → invalid 'Tag abgelaufen! ${expiryInfo(data)}. Bitte Organisator um neuen Tag.'
content = { for key in {'v','t','m','b','x'} if data.containsKey(key): key: data[key] }   // WHITELIST
contentJson = canonicalJsonEncode(content)
tags = [['t','badge'], ['m', (data['m'] ?? '').toString()]]
eventId = sha256(jsonEncode([0, pubkey, createdAt, 21000, tags, contentJson]))
Event(eventId, pubkey, createdAt, 21000, tags, contentJson, sig).isValid()   // throws if invalid → catch
adminNpub = Nip19.encodePubkey(pubkey)
```
- Type strictness: `p`,`s` must be strings, `c`,`x` must be JSON integers (a double → TypeError → caught → invalid "Verifikations-Fehler: …", version 0).
- Values re-serialized from the decoded JSON: preserve integer vs. double distinction and the exact string; parse JSON with a parser that keeps longs as longs.
- `checkExpiry=false` is used by the promotion verifier (other domain) on stored `sigContent`.
- Routing `verify(data, checkExpiry)`: compact iff `containsKey('p') && containsKey('s') && !containsKey('sig')`; else `version = data['v'] ?? 1`; `version >= 2` → `verifyNostr(data)` with `adminNpub = data['admin_npub'] ?? encode(admin_pubkey)`, message `'Schnorr-Signatur gültig (Legacy-Format)'` / `'Signatur ungültig!'`; else v1 rejection (version 1).
- `verifyNostr`: requires `admin_pubkey`, `sig`, `sig_id` non-empty, pubkey hex64, `created_at` present and `int`; content = canonical(all keys minus `sig, sig_id, admin_pubkey, _verified_by, created_at`); tags `[['t', type.lower], ['meetup', meetup_id], ['block', block_height.toString()]]` (`block_height` default 0); `Event(sig_id, …)` must be valid. Any exception → false.
- Vectors: `fixtures.compactBadge` (valid=true, tampered `b` → false).

### 7.4 Claim (kind 21002) — verbatim

```dart
final int now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
final Map<String, dynamic> claimContent = {
  'action': 'claim_badge', 'org_sig': orgSig, 'org_event_id': orgEventId,
  'org_pubkey': orgPubkey, 'block_height': blockHeight, 'claimed_at': now,
};
final contentJson = BadgeSecurity.canonicalJsonEncode(claimContent);
// → {"action":"claim_badge","block_height":…,"claimed_at":…,"org_event_id":"…","org_pubkey":"…","org_sig":"…"}
final signed = await SigningService.signEvent(kind: 21002, tags: <List<String>>[
  ['t', 'badge_claim'], ['p', orgPubkey], ['block', blockHeight.toString()],
], content: contentJson);
return ClaimResult(success: true, claimSig: signed.sig, claimEventId: signed.id,
  claimPubkey: signed.pubkey, claimTimestamp: now, message: 'Badge gebunden ✓');
```
Verification `verifyClaim(claimSig, claimEventId, claimPubkey, claimTimestamp, orgSig, orgEventId, orgPubkey, blockHeight)`: empty claimSig/claimEventId/claimPubkey → invalid "Fehlende Claim-Daten"; rebuild content with `claimed_at = claimTimestamp`; `expected = sha256(jsonEncode([0, claimPubkey, claimTimestamp, 21002, tags, contentJson]))`; `expected != claimEventId` → invalid "Claim Event-ID stimmt nicht überein"; then `Event(...).isValid()`; message "Claim verifiziert ✓ — Gebunden an <npub>" / "Claim-Signatur ungültig!".

**Critical:** verification assumes the signed event's `created_at == claimed_at == claimTimestamp`. The Flutter signer picks its own `created_at` at signing time (local: `Event.from` → now; Amber/NIP-46: `now` computed when the request is built; remote signer's value wins). If the second ticks over between `now` and signing (likely with Amber/bunker), the claim becomes unverifiable for promotion verifiers. **Native: the signer API must accept an explicit `createdAt`; pass `now` for the claim and use `claimTimestamp = signed.createdAt` only if it equals `now` — if a remote signer returns a different `created_at`, re-request or record as failure.** This is strictly compatible (verifiers recompute with `claimTimestamp`). Vector: `fixtures.claim`.

### 7.5 Rolling nonce (verbatim)

```dart
static String _generateNonce(String seed, int timeStep) {
  final key = utf8.encode(seed);                      // the 64-char HEX STRING as ASCII bytes (64 bytes)
  final data = utf8.encode(timeStep.toString());      // decimal string
  final digest = Hmac(sha256, key).convert(data);
  return digest.toString().substring(0, 16);          // first 16 lowercase hex chars
}
static int _currentTimeStep() => DateTime.now().millisecondsSinceEpoch ~/ 1000 ~/ 10;
```
Seed: 32 bytes from a CSPRNG, lowercase hex. The scanner cannot verify the nonce (does not know the seed) — only `ts` freshness is checked. Vector: `fixtures.rollingQr` (seed `0011…eeff`, ts 176000000 → nonce `13e6ad4521b7c876`).

### 7.6 Reputation QR (kind 21001)

`signQRv3(json)`: if `!canSign()` → `QRSignResult('', '', 0, '', false)`; else sign kind 21001, tags `[]`, content json. `verifyQRv3(json, sig, id, createdAt, pubkey)`: empty pubkey/sig/id → "Fehlende Signatur-Daten" (v0); pubkey hex64; sig hex128; `Event(id, pubkey, createdAt, 21001, [], json, sig).isValid()` (throws when invalid → "Fehler: Invalid event", v0); valid → v3 "Schnorr-Signatur verifiziert ✓". Base64 decode must require padding and accept both standard and URL-safe alphabets (Dart `base64.decode`). Vector: `fixtures.reputationQrV3`.

### 7.7 Encrypted badge blob

- Algorithm: AES-256-GCM, 96-bit random IV, 128-bit tag, **no AAD** (Dart `encrypt` 5.0.3 → PointyCastle `GCMBlockCipher`, `AEADParameters(key, 128, iv, [])`). Java: `Cipher.getInstance("AES/GCM/NoPadding")`, `GCMParameterSpec(128, iv)` → output `ciphertext || tag`, identical.
- Plaintext: UTF-8 of `jsonEncode(List<badge.toJson()>)`.
- Stored string: `base64(iv) + ":" + base64(ciphertext||tag)`; standard alphabet, with padding (`java.util.Base64.getEncoder()`).
- Key: 32 random bytes, stored as 64 lowercase hex chars in secure storage.
- Vector: `fixtures.aesGcmBlob` (key 00..1f, iv a0..ab).

### 7.8 Other hashes

- `claimProofId = sha256hex(utf8("$sig|$claimSig|$claimPubkey"))`.
- `generateBadgeProofV2(badges)`: `bound = badges.where(isFullyBound)`; empty → `''`; sort by `claimTimestamp` ascending (Dart sort: **stable insertion sort for ≤ 33 elements**, unstable dual-pivot quicksort above — use a stable sort; ties above 33 may diverge, see §14); `sha256hex(utf8(bound.map(claimProofId).join('|')))`.
- `generateBadgeProof(badges)` (v1): empty → `''`; copy sorted by `date` ascending; `sha256hex(join('|') of proofId)`.
- `verifyBadgeProof(badges, expected) = generateBadgeProof(badges) == expected`.
- `getVerificationHash`, `proofId` fallback — §3.1. All hex lowercase. Vectors: `fixtures.proofs`.

### 7.8 Encodings summary

hex (lowercase written, `[0-9a-fA-F]` accepted for format checks), base64 standard with padding (blobs, 21v3 payload), bech32 `npub` (NIP-19), UTF-8 everywhere, JSON per §7.1.

---

## 8. Business rules & algorithms

### 8.1 Identity, union, duplicates (verbatim)

```dart
static String identityKey(MeetupBadge b) {
  if (b.meetupEventId.isNotEmpty) return 'evt:${b.meetupEventId}';
  if (b.sigId.isNotEmpty) return 'sig:${b.sigId}';
  final d = b.date;
  final day = '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  return 'nd:${b.meetupName.trim().toLowerCase()}|$day';
}
static String unionKey(MeetupBadge b) => b.isOrganizer ? 'org:${identityKey(b)}' : identityKey(b);

static MeetupBadge _preferVariant(MeetupBadge a, MeetupBadge b) {
  final aClaim = a.claimSig.isNotEmpty; final bClaim = b.claimSig.isNotEmpty;
  if (aClaim != bClaim) return aClaim ? a : b;                       // 1. has claim
  if (a.presenceVerified != b.presenceVerified) return a.presenceVerified ? a : b; // 2. verified presence
  final byDate = a.date.compareTo(b.date); if (byDate != 0) return byDate < 0 ? a : b; // 3. older
  if (a.claimTimestamp != b.claimTimestamp) return a.claimTimestamp < b.claimTimestamp ? a : b; // 4. earlier claim
  final byId = a.id.compareTo(b.id); if (byId != 0) return byId < 0 ? a : b;  // 5. smaller id
  return a;                                                          // 6. base wins
}
static List<MeetupBadge> unionByIdentity(List<MeetupBadge> base, List<MeetupBadge> extra) {
  final byKey = <String, MeetupBadge>{};   // insertion-ordered; replacing keeps position
  void add(MeetupBadge badge) { final key = unionKey(badge); final e = byKey[key];
    byKey[key] = e == null ? badge : _preferVariant(e, badge); }
  for (final b in base) add(b); for (final b in extra) add(b);
  return byKey.values.toList();
}
static bool sameBadges(List<MeetupBadge> a, List<MeetupBadge> b) {
  String canon(MeetupBadge badge) => jsonEncode(badge.toJson());
  // sorted lists of canonical strings must be equal
}
```
`meetupName.trim().toLowerCase()` in the `nd:` fallback is `DartString.lower(DartString.trim(meetupName))` (§8.14) — Kotlin `trim().lowercase(Locale.ROOT)` yields different keys for e.g. `ΟΔΟΣ`, `İ`, U+0085/U+FEFF and would split or merge badges differently than Flutter peers in sync/backup. Use `LinkedHashMap` (Kotlin `mutableMapOf`) to keep Dart's ordering. Used everywhere (store, sync, backup, wallet cleanup, trust score) — must be identical across domains. Vectors: `fixtures.identityKey`, `fixtures.unionByIdentity`.

Scan-time "already collected" (§2.1 step 8) and save-time `identityKey` guard (step 10) both exclude `isOrganizer` badges.

### 8.2 `MeetupKey` (verbatim semantics)

- `slug(name) = name.trim().toLowerCase().replaceAll(' ', '-')` (only ASCII space; other whitespace untouched). `trim`/`toLowerCase`/`toUpperCase` in this section are `DartString.trim`/`lower`/`upper` (§8.14) — the slug is signed into `m` and becomes the co-attendance d-tag, so Kotlin `trim()`/`lowercase(Locale.ROOT)` are NOT acceptable.
- `compose(slug, country)`: `c = country.trim().toUpperCase()`; slug empty → slug; `!isIsoCountry(c)` → slug; else `"$slug-${c.toLowerCase()}"`.
- `parse(m)`: `v = m.trim()`; `i = v.lastIndexOf('-')`; if `i <= 0 || i == len-1` → `(v, '')`; `last = v.substring(i+1).toUpperCase()`; not ISO → `(v, '')`; else `(v.substring(0,i), last)`.
- `displayFromSlug(slug)`: split `-`, drop empty, first UTF-16 code unit upper-cased with `DartString.upper(w.substring(0,1))` (§8.14: keeps `ß`; a lone high surrogate stays unchanged) + rest, join with space. Do NOT use `Char.uppercaseChar()` (newer Unicode table, e.g. Georgian `ა` U+10D0 → U+1C90 on the JVM, unchanged in Dart). Same algorithm for `_prettifySlug` (§8.3) and `CoAttendanceService.place`.
- `eventId(slug, date) = "$slug-${date.toIso8601String().substring(0,10)}"`.
- `isIsoCountry(code) = code.length == 2 && ISO3166.contains(code.upper())` with this exact set (249 codes; note `UK` is NOT included, `GB` is):
  ```
  AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY
  BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK
  FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR
  IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK
  ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM
  PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF
  TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW
  ```
- Vectors: `fixtures.meetupKey`.

### 8.3 `normalize(data)` (verbatim semantics, output key order matters only for display)

```
if data.containsKey('meetup_id') → return data (same object)
n = {}
n.v = data.v ?? 1
n.type = t == 'B' ? 'BADGE' : (t is String ? t : (data.type ?? 'BADGE'))
m = data.m as String? ?? ''
if m.startsWith('evt:'):
   parts = m.split(':')
   if parts.length >= 5: n.event_address = "${p1}:${p2}:${p3}"; slug = parts[4..].join(':')
        n.meetup_id = "evt:${p3}"; n.meetup_name = prettify(slug) /* same algorithm as displayFromSlug */
        n.meetup_country = ''; n.is_event = true
   else: n.meetup_id = m; n.meetup_name = m              // no country, no slug key
else: p = MeetupKey.parse(m); n.meetup_id = p.slug; n.meetup_slug = p.slug
      n.meetup_name = p.slug == '' ? m : displayFromSlug(p.slug); n.meetup_country = p.country
if data.meetup_name != null → n.meetup_name = it ; if data.meetup_country != null → n.meetup_country = it
if data.c != null: dt = local DateTime(c*1000); n.timestamp = n.meetup_date = dt.toIso8601String()
else if data.timestamp != null: n.timestamp = it; n.meetup_date = data.meetup_date ?? it
n.block_height = data.b ?? data.block_height ?? 0
if data.x != null → n.expires_at
if data.p != null: n.admin_pubkey = p; n.admin_npub = npub(p) (ignore errors)
else if data.admin_pubkey != null: n.admin_pubkey; n.admin_npub = data.admin_npub ?? ''
n.sig = data.s ?? data.sig (if any) ; if data.sig_id → n.sig_id
copy qr_nonce, qr_time_step, qr_interval, delivery if present
if data.n → n.qr_nonce ; if data.ts → n.qr_time_step ; if data.d → n.delivery = (d == 'qr' ? 'rolling_qr' : d)
```
Note: a `dTag` containing `:` is truncated at the first `:` (parts[3]) — keep identical for compatibility. Vectors: `fixtures.normalize` (UTC rendering of `timestamp`; app uses local).

### 8.4 Event badge `m` (verbatim)

```dart
static String eventMeetupId({required String eventAddress, required String title}) {
  final slug = title.trim().toLowerCase()
      .replaceAll(RegExp(r'[^a-z0-9äöüß\s-]'), '')
      .replaceAll(RegExp(r'\s+'), '-');
  return 'evt:$eventAddress:$slug';
}
```
Dart `RegExp` `\s` = JavaScript whitespace (includes U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000, U+FEFF, plus `\t\n\v\f\r `). In Kotlin use an explicit character class with exactly that set (Java `\s` is ASCII-only by default). Dart `trim()` uses a DIFFERENT set: the regex set plus U+0085 (see §8.14 `DartString.trim`); `\s` does not match U+0085, so an inner U+0085 is removed by the `[^…]` filter instead. `trim` and `toLowerCase` here are `DartString.trim`/`DartString.lower` (§8.14). Vectors: `fixtures.eventMeetupId`, §8.14.
`eventAddressOf(normalized) = normalized['event_address']` if non-empty String else null. `eventBadgeKey(address) = "evt|$address"` (same form as `EventChatService.readKey`).

### 8.5 Session lifecycle (`RollingQRService`)

Constants: `intervalSeconds = 10`, `toleranceSteps = 1`, `sessionValidityHours = 4` (must equal `BadgeSecurity.badgeValidityHours = 4`).
```
getOrCreateSession(meetupId, meetupName, meetupCountry, blockHeight, lat=0, lng=0, validUntilEpoch?):
  existing = loadSession()
  if existing && !existing.isExpired && existing.meetupId == meetupId:
     if validUntilEpoch == null || validUntilEpoch <= existing.expiresAt → return existing   // reuse
     // else fall through: new, longer session (old codes become invalid)
  b = blockHeight; if b <= 0: b = mempool(); if b == 0: delay 2 s; b = mempool()   // stays 0 offline
  return createSession(...)
createSession: pubkey = NostrService.getNpub() ?? ''
  now = nowSec; expiresAt = (validUntilEpoch != null && validUntilEpoch > now) ? validUntilEpoch : now + 4*3600
  seed = 32 CSPRNG bytes → lowercase hex
  if !SigningService.canSign() → throw StateError('Kein Nostr-Key vorhanden. Bitte zuerst einen Key erstellen oder importieren.')
  base = BadgeSecurity.signCompact(meetupId, b, validityHours: 4)   // throws → StateError('Badge-Signierung fehlgeschlagen: …')
  persist (§4.3)
getBasePayload(): loadSession() null → null; secure payload string null → null; → jsonDecode
generateQRString(session): base = secure payload (null → throw StateError('Kein signierter Base-Payload vorhanden. Bitte neue Session starten.'))
  payload = jsonDecode(base); payload.n = nonce(seed, step); payload.ts = step; payload.d = 'rolling_qr'
  if session.lat != 0 || session.lng != 0: payload.la = lat; payload.lo = lng
  return jsonEncode(payload)
validateNonce(payload):
  nonce = payload.n as String? ?? payload.qr_nonce as String?; ts = payload.ts as int? ?? payload.qr_time_step as int?
  if nonce == null || ts == null → valid ('Kein Rolling-QR (NFC/Static)', age 0)
  diff = |currentStep - ts|; age = diff*10
  diff > 1 → invalid "QR-Code abgelaufen (${age}s alt). Bitte direkt am Bildschirm scannen."
  else valid (age == 0 ? 'Frisch ✓' : 'Gültig (${age}s)')
secondsUntilNextChange() = 10 - (nowSec % 10) ; currentProgress() = (nowSec % 10) / 10
```
**Event session quirk:** the base payload's `x` is always `now + 4 h`, even when the session runs to midnight (`validUntilEpoch`). After 4 h, scanners reject "Tag abgelaufen!" although the QR keeps rotating. Recommended native fix (wire-compatible: scanners only check `x > now`): sign with `x = session.expiresAt`. Product decision — see §14.

### 8.6 Presence check constants

`participantRadiusKm = 5.0` (measured reference — currently never used since `signedCoordinateKeys` is empty), `derivedRadiusKm = 50.0` (portal/meetup coordinates), `creationRadiusKm = 10.0` (organizer nearby list), `issuerRadiusKm = 5.0` (event helper vs. venue). Distance = Haversine with `R = 6378137.0 m` (geolocator's Dart formula):
```
dLat = rad(lat2-lat1); dLon = rad(lon2-lon1)
a = sin(dLat/2)^2 + sin(dLon/2)^2 * cos(rad(lat1)) * cos(rad(lat2))
d_m = R * 2 * asin(sqrt(a)) ; km = d_m / 1000
```
Location acquisition (`NearbyMeetupService.getCurrentLocation`, other domain): service off → `serviceDisabled`; permission check/request; `deniedForever`/`denied`; fix with medium accuracy & 25 s limit; on failure 2nd attempt via platform `LocationManager` high accuracy 20 s; then last known position if ≤ 10 min old; else `noFix`; exceptions → `error`. `resolveLocation` maps `deniedForever → denied`, `noFix → error`.

### 8.7 Signer recognition (meetup badges)

Order: admin registry (`isAdmin`) → `source == 'unavailable'` → portal leaders of the meetup resolved from the **display name** (`resolveFavorite(name) ?? byName(name)`, numeric portal id) → portal meetup whose `nostrNpub == npub(adminPubkey)` → unknown. Flat "is leader anywhere" checks are forbidden (anyone can create a portal meetup).

### 8.8 Event trust chain (`EventBadgeChainService.verify`)

```
event = fetchByAddress(address); null → eventNotFound
!event.badgeEnabled → eventHasNoBadge (title, creator, image)
!event.isIssuer(signer, atEpoch: issuedAt) → signerNotListed
try admin = AdminRegistry.checkAdminByPubkey(event.pubkey)
    !admin.isAdmin → creatorNotAuthorized ; else verified(creatorName = admin.name ?? admin.meetup)
catch → creatorNotAuthorized
```
`issuedAt` = scanner's current time (helpers' clocks are untrusted).

### 8.9 Event helper session (`EventBadgeSessionService.start(event)`)

```
myPubkey = SigningService.pubkeyHex(); empty → noIdentity
!event.isIssuer(myPubkey) → notIssuer                 // no atEpoch: former issuers cannot start
!event.isBadgeWindowOpen → outsideWindow
event.lat == 0 && event.lng == 0 → noEventLocation
loc = getCurrentLocation(): serviceDisabled→locationServiceOff, denied→locationDenied,
     deniedForever→locationDeniedForever, noFix→locationNoFix, other→locationUnavailable
distance(loc, venue) > 5.0 → tooFarAway(distanceKm)
session = getOrCreateSession(meetupId: eventMeetupId(event.address, event.title), meetupName: event.title,
          meetupCountry: '', blockHeight: 0, lat: event.lat, lng: event.lng,
          validUntilEpoch: localMidnightAfter(event.end ?? event.start))
null → sessionFailed ; exception → sessionFailed
unawaited recordOrganizerAttendance(meetupName: title, date: now, blockHeight: session.blockHeight,
          lat: event.lat, lng: event.lng, isEvent: true)
→ success(session)
```
Error → string: noIdentity `evSessionNoIdentity`; notIssuer `evSessionNotIssuer`; outsideWindow `evSessionOutsideWindow`; noEventLocation `evSessionNoEventLocation`; locationUnavailable `evSessionNoLocation`; locationServiceOff `evSessionLocationOff`; locationDenied `evSessionLocationDenied`; locationDeniedForever `evSessionLocationDeniedForever`; locationNoFix `evSessionLocationNoFix`; tooFarAway `evSessionTooFar(km 1 decimal)`; else `evSessionFailed`.

### 8.10 Who may create an event with a badge (`EventBadgeAuthService.myRight`)

```
npub = SigningService.npub(); empty → none
try AdminRegistry.checkAdmin(npub).isAdmin → adminRegistry           (errors ignored)
try PortalApiService.getMyMeetups() non-empty → portalLeader          (membership suffices, not isLeader)
try UserProfile.load().isAdmin → adminRegistry                         (adminViaPortal||adminViaVouch||adminViaSeed; review demo excluded)
→ none
mayCreate() = myRight() != none
```

### 8.11 Retroactive claiming

`ensureBadgesClaimed(badges)`: if no badge with `hasCryptoProof && !isClaimed` → 0; else `claimUnboundBadges`: for each such badge (in list order) `createClaim(sig, sigId, adminPubkey, blockHeight)`; success → replace in the list with `withClaim(…, isRetroactive: true)` (note: resets `presenceVerified` to true — native should keep the original value, see §14); after the loop, if any claimed and `saveBadges(list)` fails → log and return 0. Callers: home `_loadBadges` (only if readable) and reputation card `_load`. With Amber/bunker this prompts once per unclaimed badge per start until it succeeds.

### 8.12 Expiry info strings

`expiresAt = data.x ?? data.expires_at ?? 0`; 0 → "Kein Ablauf"; `diff = expiresAt - now`; ≤0 → "Abgelaufen vor {h}h {m%60}min" (h>0) or "Abgelaufen vor {m}min"; >0 → "Gültig noch {h}h {m%60}min" or "Gültig noch {m}min" (integer division, `Duration.inHours/inMinutes`). `isExpired(data)`: same field fallback; 0 → false; `now > expiresAt`.

### 8.13 Signed coordinates (Security Audit H1)

`compactSignedKeys = {v,t,m,b,x}`; `signedCoordinateKeys = {}` → `signedCoordinates(data)` always returns `(0,0)`. `la`/`lo` from the QR are NEVER a measured reference. NEVER add `la`/`lo` to the signed whitelist (would invalidate every existing badge); a future v3 needs new signed fields and a version bump (see `docs/ROLLING_QR_SESSION_KEY.md`).


### 8.14 `DartString` — byte-exact Dart `trim()` / `toLowerCase()` / `toUpperCase()`

Every `trim()`, `toLowerCase()` and `toUpperCase()` in this spec that feeds a key, hash, signed field or comparison MUST use these functions, not Kotlin `trim()` / `lowercase()` / `uppercase()` (with or without `Locale.ROOT`). Affected: `MeetupKey.slug` (signed `m`, co-attendance d-tag `<slug>-<date>`), `MeetupKey.parse`/`compose`/`displayFromSlug`, `eventMeetupId` title slug (§8.4), `_prettifySlug` (§8.3), `identityKey` `nd:` fallback (§8.1: dedupe/union in store, sync, backup, trust score), `unknownSlug`/`nameSlug` (§2.1 step 10), `_lookupMeetupForBadge` city match (§2.1 presence check), `CoAttendanceService` `_isDegenerateEventId` / `place`. Verified against Dart 3.13.5 (Dart VM, the runtime Flutter ships on Android).

Why Kotlin/Java differ (all verified on JDK 17 vs Dart 3.13.5):
- Java `String.toLowerCase(Locale.ROOT)` applies context rules and special casing: `"ΟΔΟΣ"` → `"οδος"` (final sigma U+03C2), Dart → `"οδοσ"` (U+03C3). `"İ"` (U+0130) → `"i̇"` (U+0069 U+0307, 2 code units) in Java, `"i"` (1 code unit) in Dart. `"ß".uppercase()` → `"SS"` in Kotlin, `"ß"` in Dart.
- Kotlin `Char.lowercaseChar()` / Java `Character.toLowerCase(int)` (simple mapping) avoid those, but use a NEWER Unicode table than Dart. Dart's table is a strict subset: 2050 code points have a mapping in Dart; JDK 17 maps 749 more (no conflicts on the shared ones), e.g. Cherokee U+13A0 → U+AB70, Georgian U+10D0 → U+1C90 (upper), U+0524/U+0525, U+2C70, U+A790, U+037F. Android's ICU-backed `Character` is newer still. Dart leaves all of these unchanged.
- Kotlin `trim()` (`Char.isWhitespace`) strips U+001C–U+001F and does NOT strip U+0085 or U+FEFF; Dart does the opposite. Java `String.trim()` strips everything ≤ U+0020 only; `strip()` is like Kotlin `trim()`.

Exact definitions:
- `DartString.trim(s)`: remove leading and trailing UTF-16 code units contained in this set, nothing else (26 code units):
  `U+0009–U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000, U+FEFF`.
  NOT included: U+001C–U+001F, U+180E, U+200B. (For comparison, Dart `RegExp` `\s` (§8.4) is the same set minus U+0085.)
- `DartString.lower(s)` / `DartString.upper(s)`: iterate the string by code point (a valid high+low surrogate pair is one code point; a lone surrogate is copied unchanged); replace each code point by its entry in the **Dart case table** if present, else keep it. Strictly 1 code point → 1 code point, no context (no final sigma), no locale (no Turkish/Lithuanian rules), no special casing (`ß`, `ŉ` U+0149, `ﬀ` U+FB00, `ΐ` U+0390 stay unchanged by `upper`; `ẞ` U+1E9E → `ß` by `lower`). BMP maps to BMP and supplementary to supplementary, so the UTF-16 length never changes. Only 80 supplementary code points are in the table (Deseret U+10400–U+1044F, e.g. U+10400 → U+10428).
- **Dart case table**: generate it once with the Dart SDK the Flutter app is built with and ship it as a resource/generated Kotlin source (`dart_case_table.txt`); do not derive it from `java.lang.Character` or ICU at runtime. Generator (run with `dart run`):
  ```dart
  import 'dart:io';
  void main() {
    final out = StringBuffer();
    for (var c = 0; c <= 0x10FFFF; c++) {
      if (c >= 0xD800 && c <= 0xDFFF) continue;
      final s = String.fromCharCode(c), l = s.toLowerCase(), u = s.toUpperCase();
      if (l != s || u != s) out.writeln('${c.toRadixString(16)} '
          '${l.runes.map((r) => r.toRadixString(16)).join("+")} '
          '${u.runes.map((r) => r.toRadixString(16)).join("+")}');
    }
    File('dart_case_table.txt').writeAsStringSync(out.toString());
  }
  ```
  Format: one line per code point with a mapping, `"<cp> <lower> <upper>\n"`, lowercase hex without padding, ascending (identity mappings are written as the code point itself; no `+` occurs, every result is a single code point). Dart 3.13.5 output: **2050 lines, SHA-256 `70a94653c2ccd1cc4bef72c531cfd65c0ba53688274b317887f6b0c5deeac138`** — a unit test must assert this hash over the shipped resource. Sample lines: `49 69 49`, `69 69 49`, `b5 b5 39c`, `ff ff 178` (no line for `df` ß), `130 69 130`, `131 131 49`, `17f 17f 53`, `1c5 1c6 1c4`, `3a3 3c3 3a3`, `3c2 3c2 3a3`, `3c3 3c3 3a3`, `1e9e df 1e9e`, `212a 6b 212a`, `2126 3c9 2126`, `10400 10428 10400`.

Kotlin reference:
```kotlin
object DartString {
  private fun isTrimWs(c: Char): Boolean = when (c.code) {
    in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
    else -> false
  }
  fun trim(s: String): String {
    var a = 0; var b = s.length
    while (a < b && isTrimWs(s[a])) a++
    while (b > a && isTrimWs(s[b - 1])) b--
    return s.substring(a, b)
  }
  private val LOWER: Map<Int, Int>; private val UPPER: Map<Int, Int>   // loaded from dart_case_table.txt
  fun lower(s: String) = map(s, LOWER)
  fun upper(s: String) = map(s, UPPER)
  private fun map(s: String, t: Map<Int, Int>): String {
    val sb = StringBuilder(s.length); var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
        val cp = Character.toCodePoint(c, s[i + 1]); sb.appendCodePoint(t[cp] ?: cp); i += 2
      } else { sb.append((t[c.code] ?: c.code).toChar()); i++ }   // lone surrogates are not in the table
    }
    return sb.toString()
  }
}
```

Vectors (Dart 3.13.5; inputs/outputs as JSON strings):

| Call | Dart result | Naive Kotlin (wrong) |
|---|---|---|
| `lower("ΟΔΟΣ")` | `"οδοσ"` (03bf 03b4 03bf 03c3) | `lowercase(Locale.ROOT)` → `"οδος"` (…03c2) |
| `lower("İstanbul")` | `"istanbul"` (8 code units) | `"i̇stanbul"` (9 code units, U+0307) |
| `upper("straße")` | `"STRAßE"` | `"STRASSE"` |
| `lower("ẞ")` | `"ß"` | `"ß"` |
| `upper("ა")` (U+10D0) / `lower("Ꭰ")` (U+13A0) / `upper("ꭰ")` (U+AB70) | unchanged | `Char` API → U+1C90 / U+AB70 / U+13A0 |
| `lower("𐐀")` / `lower("A\uD801")` | `"𐐨"` / `"a\uD801"` | — |
| `trim("\u0085x")` | `"x"` | Kotlin `trim()` → `"\u0085x"` |
| `trim("﻿x")` | `"x"` | `"﻿x"` |
| `trim("\u001Cx\u001F")` | `"\u001Cx\u001F"` | `"x"` |
| `trim("᠎x")`, `trim("​x")` | unchanged | unchanged |
| `MeetupKey.slug(" ΟΔΟΣ İstanbul ")` | `"οδοσ-istanbul"` | `"οδος-i̇stanbul"` |
| `MeetupKey.slug("﻿Wien\u0085")` | `"wien"` | `"﻿wien\u0085"` |
| `MeetupKey.slug("\u001CWien")` | `"\u001cwien"` | `"wien"` |
| `MeetupKey.parse("wien-at\u0085")` | `("wien", "AT")` | `("wien-at\u0085", "")` |
| `MeetupKey.parse("﻿wien-at")` | `("wien", "AT")` | `("﻿wien", "AT")` |
| `MeetupKey.compose("wien", "﻿at")` | `"wien-at"` | `"wien"` |
| `MeetupKey.compose("wien", "at\u001F")` | `"wien"` | `"wien-at"` |
| `MeetupKey.displayFromSlug("straße-ꭰ-ა-ǆ")` | `"Straße ꭰ ა Ǆ"` | `Char.uppercaseChar` → `"Straße Ꭰ Ა Ǆ"` |
| `identityKey` fallback, `meetupName = "ΟΔΟΣ, GR"`, local date 2026-03-07 | `"nd:οδοσ, gr|2026-03-07"` | `"nd:οδος, gr|…"` |
| same, `meetupName = "\u0085İzmir, TR "` | `"nd:izmir, tr|2026-03-07"` | `"nd:\u0085i̇zmir, tr|…"` |
| `eventMeetupId("31923:ab:d1", "﻿İstanbul ΟΔΟΣ Meetup\u0085")` | `"evt:31923:ab:d1:istanbul-meetup"` | — |
| `eventMeetupId("31923:ab:d1", "STRAẞE  Fest")` | `"evt:31923:ab:d1:straße-fest"` | — |

Other domains (wallet UI search, trust score `_badgeToEventId`, co-attendance keys) must use the same `DartString` object from `:core` so keys stay identical everywhere.

---

## 9. Security measures that must be preserved

1. **Whitelist** of signed compact keys `{v,t,m,b,x}`; unsigned add-ons (`n, ts, d, la, lo, delivery, _verified_by`, anything else) never enter the hash (SecurityChangelog Fix 2).
2. **Canonical JSON** for every signed content (SecurityAudit Fix 4).
3. **Legacy v1 disabled:** `signLegacy` returns `''`, `verifyLegacy` false, v1 badges and `21:`/`21v2:` QRs rejected (Audit C1). No shared secret anywhere.
4. **No legacy (unsigned) QR fallback** when signing is impossible — session creation throws (Audit C1).
5. **Session seed from CSPRNG**, never derived from the private key (Changelog Fix 3); seed and base payload in **secure storage** (Audit C3, Audit-2 #3); legacy prefs copies migrated and deleted.
6. **Signer must be recognised** (registry / portal leader of THIS meetup / portal nostr field / event chain) — otherwise orange "unknown signer" state (Audit Fix 2). Network failure must be phrased as "not verifiable", not as an accusation.
7. **Self-scan prevention** (Audit 2026-03), fail-open on lookup errors.
8. **Claim binding** (kind 21002) — only bound badges count for reputation/promotion (Audit K1). Promotion verifiers additionally re-verify `sigContent` and the claim.
9. **Rolling nonce freshness** (±1 step = max ~20 s) — screenshots expire quickly; NFC simulation removed (no fake writes or fake badges).
10. **4-hour validity** for meetup payloads; scanner-clock `issuedAt` for event chain (helpers can't backdate).
11. **Unsigned coordinates are not trusted** (Audit H1) → presence reference falls back to derived 50 km.
12. **Event badge chain:** creator must be in admin registry; signer must be issuer (or former issuer before cutoff); cancelled events invalid; newest replaceable version wins (a withdrawn issuer list cannot linger).
13. **Encrypted wallet at rest** (AES-256-GCM, key in Keystore-backed storage; Audit M7) and pending scans encrypted with a separate key (never plaintext).
14. **Never destroy data on read errors:** a key read failure never creates a new key; unknown key values are moved aside, not overwritten; unreadable blobs stay in place / move to backup slots / archive; clearing archives instead of deleting; only `resetAll` deletes; reset marker blocks resurrecting secure copies.
15. **Duplicate guard** at scan and save time, organizer markers excluded and never counted.
16. **Manifest:** no NDEF intent filter while NFC is off (Audit N4); `allowBackup=false`.
17. **Location:** "no evidence" ≠ "counter-evidence": unmeasurable location → badge with `presenceVerified=false`; measurable & too far → refused.

Open item from the audit: the scanner cannot verify the HMAC nonce; stripping `n`/`ts` from a photographed QR bypasses the freshness check (falls back to "NFC/static", only `x` applies). Optional native hardening (compatible with all current organizer apps, because they always add `n`/`ts` to QR codes): when a payload arrives via **camera** and NFC is disabled, require `n` and `ts`. Product decision.

---

## 10. Tests & vectors to port

Fixture file: `android-native/spec/fixtures/02-badges-claim-vectors.json` — port as JVM unit tests:
`canonicalJson` (4 cases), `compactBadge` (id, sig, valid=true, tampered=false, base payload JSON), `rollingQr` (HMAC, nonce, QR string), `claim` (content, serialized, id, sig, valid), `proofs` (claimProofId, proofV2 single, proofId fallback, proofV1, verificationHash, `ml` hash), `dartIso8601` (4), `aesGcmBlob` (decrypt and re-encrypt with given IV), `meetupKey` (parse 14, compose 5, slug 5, displayFromSlug 4, eventId), `eventMeetupId`, `normalize` (7), `identityKey` (4), `unionByIdentity` (4), `reputationQrV3` (QR string parse + verify).

Dart tests to port (test name → expectation):

`test/badge_security_test.dart`
- isValidPubkeyHex: valid 64-hex true; 10 chars false; 65 false; `'g'*64` / `'x'+'a'*63` false; `''` false; upper/mixed case 64 true.
- isValidSignatureHex: 128 true; 127/129 false; `'z'*128` false.
- canonicalJsonEncode: `{z:1,a:2,m:3,b:4}` → `{"a":2,"b":4,"m":3,"z":1}`; order-independent; `{}` → `{}`; `{b:[1,2,3], a:{nested:true}}` → `{"a":{"nested":true},"b":[1,2,3]}`.
- Legacy: signLegacy → `''`; verifyLegacy → false; verify({v:1,…}) invalid, version 1, message contains "v1" and "nicht mehr akzeptiert"; verify without v invalid.
- verifyCompact: valid payload → valid, version 2, adminPubkey == key, adminNpub non-empty, message contains "gültig"; tampered `m`, `b`, `p` (other key), `s` (first char flipped) → invalid; expired (x = now-3600) → invalid with "abgelaufen"; missing `s` → "Fehlende"; `p:'zu_kurz'` → "Pubkey-Format"; `s:'kurz'` → "Signatur-Format"; signed by key A but `p` = key B → invalid.
- verify routing: compact valid → v2 valid; v1 → version 1; v2 full without created_at → invalid.
- normalize: `{v:2,t:B,m:aschaffenburg-de,b:875000,c:1700000000,x:1700021600,p,s}` → v 2, type BADGE, meetup_id aschaffenburg, country DE, block 875000, admin_pubkey p, sig s, expires_at 1700021600; `münchen-de` → name `München`, country DE; map with meetup_id returned unchanged (same instance); `d:'qr'` → delivery `rolling_qr`; `p` → admin_npub starts with `npub1`.
- isExpired: future false; past true; `{}` / `{x:0}` false; `expires_at` past true.
- expiryInfo: `{}` → "Kein Ablauf"; past contains "Abgelaufen"; future contains "Gültig".
- verifyQRLegacy always invalid.

`test/badge_model_test.dart`
- toJson/fromJson round trip of all fields; minimal JSON `{id, meetupName, date:'2025-01-01T00:00:00.000Z', iconPath}` → sig '', sigVersion 0, claimSig '', delivery 'nfc'; jsonEncode/decode works.
- hasCryptoProof (v2 true, v0 no sig false, v1 with sig true); isNostrSigned (v2+128+pub true, v1 false, empty sig false, 64-char sig false).
- isClaimed / isFullyBound truth table (incl. claim without org sig → false).
- withClaim sets claim fields, keeps original data, original unchanged, retro flag.
- proofId: sigId; sig[0:64]; fallback 64 lowercase hex.
- claimProofId: 64 chars when claimed, '' when not, deterministic.
- generateBadgeProof deterministic, 64 chars, `''` for empty, order-independent; verifyBadgeProof true/false; V2 only bound, '' when none.
- getReputationStats {total 4, crypto_proof 3, claimed 2, fully_trusted 2, retroactive 0}; countVerifiedBadges 2; retroactive counted separately (1/1).

`test/meetup_key_test.dart` — all parse cases (incl. `-de` → `-de`, `aachen-` → `aachen-`), compose cases, compose/parse reversible for 6 cities, slug equals legacy formula, organizer key == participant key for 7 cities (with and without country), old app splitting, normalize `bad-neuenahr-de` (slug/id `bad-neuenahr`, DE, name `Bad Neuenahr`), `bad-neuenahr` keeps whole, event `m` → `is_event` true and no `meetup_slug`.

`test/badge_key_retention_test.dart` (store) — 22 cases: key created & reload; missing key → empty + unreadable, no new key, blob kept, preserved = blob; key read error → no write; wrong-length key not replaced; other 64-hex secure value opens the file and becomes the key; save refuses to overwrite unreadable file; kept-aside scan merges once the key is back; reset (`discardLocked`) clears backups; empty save doesn't delete unreadable file; pending never plaintext (no name/coords/sig visible in any prefs value, own pending key, badge key untouched); pending and its key removed after merge; legacy plaintext pending migrated encrypted; unreadable pending survives a save; keepAside true / false (unreadable pending); primary + preserved opened with different keys are united into primary, preserved untouched, stable on reload, survives removing preserved; second replace pushes backup one level older; replacing with an unusable key value moves it to `_suspect`; promoting a readable backup keeps the unreadable primary in the backup chain and reunites when its key returns; failed read never shifts backup slots; same event → variant with claim wins.

`test/badge_storage_copy_test.dart` — 17 cases: secure copy equals prefs blob; iOS-reinstall (prefs empty) restores from secure copy; unreadable copy tolerated; unwritable copy tolerated (no plaintext `badges`); reset clears both; promote works with unwritable copy, archive gets former npub; reset with stuck keychain does not resurrect, next start cleans up; clear with stuck keychain deletes nothing and stays readable; old install without copy reads; staging doesn't change active, promotion replaces, archive holds readable + unreadable (`replaced`,`replaced`); crash after promotion doesn't mislabel; archive merge back; clear and third rotation land in archive (`rotated`, then `cleared` first); archive secure copy survives empty prefs; reset clears archive; clear without reset adds to archive (`cleared`,`replaced`); legacy plaintext goes into archive; legacy plaintext without key is not deleted.

`test/badge_reputation_regression_test.dart` (with trust-score/backup domains) — monthly visits same id distinct events both kept; rolling-QR rescans same event count once; organizer marker separate; sync + restore don't inflate; markers of other cities don't add; rescans don't take weekly-cap slots; signed markers count 0.

`test/security_followup_test.dart` — `signedCoordinates({…,'la':48.1,'lo':10.2})` → (0,0).

`test/app_review_demo_test.dart` — `matches('21review:einundzwanzig-meetup-demo')` true, with surrounding spaces true, `'21:something'` false, null false.

`test/promotion_claim_k1_test.dart` (promotion domain, uses this domain's formats) — claim format identical to `createClaim`, `sig_id = sha256(sig)` in the test, bound by claimer/stranger, copy attack.

`test/backup_restore_badges_test.dart`, `test/badge_sync_format_test.dart` ("Vereinigung je Veranstaltung") — union semantics (see fixtures).

New native tests to add: `DartString` (§8.14): every vector row of the §8.14 table, plus SHA-256 of the shipped `dart_case_table.txt` == `70a94653…ac138` and 2050 lines; Dart-JSON encoder escaping/number vectors; DartDateTime parse/format; event `m` with Unicode whitespace; validateNonce boundaries (diff 0, 1, 2; missing fields); verifyCompact with double `c` → invalid; upper-case `p` → invalid; `createdAt` 9/11 digits → invalid; claim signed with explicit created_at verifies.

---

## 11. Android capabilities & recommended APIs

| Capability | Permission / feature | Recommended native API |
|---|---|---|
| Camera QR scanning | `android.permission.CAMERA`, `<uses-feature android:name="android.hardware.camera" android:required="false"/>` | CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`) + **bundled** ML Kit `com.google.mlkit:barcode-scanning` (works without Play Services, important for GrapheneOS users) restricted to `FORMAT_QR_CODE`; or zxing-cpp for a fully FOSS build |
| QR from gallery | none (Photo Picker) | `ActivityResultContracts.PickVisualMedia(ImageOnly)` → `InputImage.fromFilePath` → ML Kit |
| QR rendering | — | ZXing `core` `QRCodeWriter`, `ErrorCorrectionLevel.M`, auto version, draw to `ImageBitmap` on white |
| Location (presence, session) | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | `LocationManagerCompat.getCurrentLocation` (works without GMS) with optional Fused provider when available; 25 s + 20 s timeouts, last-known ≤ 10 min; settings intents `ACTION_LOCATION_SOURCE_SETTINGS`, `ACTION_APPLICATION_DETAILS_SETTINGS` |
| NFC (flag off) | `android.permission.NFC`, `<uses-feature android.hardware.nfc required=false>`; **no** NDEF intent filter | `NfcAdapter.enableReaderMode` (NFC_A/B/F/V, `FLAG_READER_SKIP_NDEF_CHECK` off), `Ndef`, `NdefFormatable`, `NdefRecord.createTextRecord("en", json)` (produces the same `02 65 6E` header); `Settings.ACTION_NFC_SETTINGS` |
| Schnorr / secp256k1 | — | `fr.acinq.secp256k1:secp256k1-kmp-jni-android` (`verifySchnorr`, `signSchnorr` with 32-byte aux) — via the Nostr core domain |
| SHA-256 / HMAC | — | `java.security.MessageDigest("SHA-256")`, `javax.crypto.Mac("HmacSHA256")` |
| AES-GCM | — | `javax.crypto.Cipher("AES/GCM/NoPadding")`, `SecureRandom` |
| Secure storage | — | Android Keystore AES-GCM key wrapping a small encrypted store (or Jetpack Security successor / Tink `Aead`); keep the logical key names of §4.1 |
| Key/value prefs | — | Jetpack DataStore (Preferences) with the exact logical keys; one-time import from `FlutterSharedPreferences` |
| Background publish | `INTERNET` | coroutines in an app-scoped `CoroutineScope`; optionally WorkManager for retrying reputation/co-attendance publish |
| Keep screen on (enhancement) | — | `FLAG_KEEP_SCREEN_ON` while RollingQrScreen is resumed |
| Haptics | — | `HapticFeedbackConstants` / `Vibrator` (app shell; honour `haptic_enabled`) |

---

## 12. Cross-domain dependencies

**This domain calls:**
- Nostr core / signing: `SigningService.canSign()`, `signEvent(kind, tags, content)` (**add `createdAt` param**), `npub()`, `pubkeyHex()`; `NostrService.getNpub()`, `shortenNpub(npub, chars=8)` (`first 4+8 … last 8` if length ≥ 21), `hexToNpub`; NIP-19 `encodePubkey/decodePubkey`; BIP-340 verify.
- Admin registry: `checkAdminByPubkey(hex)`, `checkAdmin(npub)` → `{isAdmin, name?, meetup?, source}` (`source` values: `nostr_relay`, `local_cache`, `super_admin`, `not_found`, `no_key`, `unavailable`).
- Portal: `PortalLeaderService.isLeaderOf(id:Int, npub) → Boolean?`; `PortalApiService.getMyMeetups()`.
- Meetup directory: `MeetupService.fetchMeetups()`, `bySlug(slug,country)`, `byName(name)`, `resolveFavorite(stored)`, `organizerMeetupFor(hexOrNpub)`; bundled `allMeetups` / `fallbackMeetups`; `Meetup` model (`id,name,city,country,lat,lng,nostrNpub,portalLink,groupName…`).
- Calendar: `CalendarEventService.fetchByAddress(address)`, `NostrCalendarEvent` (`isIssuer`, `isBadgeWindowOpen`, `badgeEnabled`, `badgeImageUrl`, `lat/lng`, `address`, `title`, `start/end`, `pubkey`).
- Location: `MeetupLocationService.resolveLocation()`, `distanceKm`, radius constants, `openLocationSettings/openAppSettings`; `NearbyMeetupService.getCurrentLocation()`.
- Mempool: `MempoolService.getBlockHeight()`.
- Co-attendance: `CoAttendanceService.publishAttendance(badge) → Int`, `recordOrganizerAttendance(meetupName, date, blockHeight, lat, lng, isEvent) → MeetupBadge?` (creates marker badge `id "org-<slug>-<date>"`, `delivery 'organizer'`, `isOrganizer true`, saves via this store).
- Reputation: `ReputationPublisher.publishInBackground(badges)`; trust layers for the result screen: `SocialGraphService.analyze`, `ZapVerificationService.analyzeZapActivity`, `Nip05Service.fetchNip05FromProfile/verify`, `RelayConfig.getActiveRelays`.
- User profile: `UserProfile.load()` (`homeMeetupId`, `isAdmin`, `promotionSource`).
- Wallet: `BadgeWalletScreen` navigation target.
- App review: `AppReviewDemo.matches/showSuccess`.
- Logging: `AppLogger` (debug/info/warn/error/diag/section) — keep the diagnostic lines (they are used in field tests).

**Other domains call this one:**
- Wallet: `loadBadges`, `saveBadges`, `unionByIdentity`, `unionKey`, `formerCollections`, `mergeFormer`, `exportBadgesForReputation`.
- Backup/restore: `loadBadges`, `saveBadges(replaceUnreadable…)`, `stageIncoming`, `promoteIncoming`, `discardIncoming`, `unionByIdentity`, `unionKey`, `badgesUnreadable`.
- Badge sync (NIP-44 relay sync): `onBadgesSaved` hook, `loadBadges`, `saveBadges`, `sameBadges`, `unionByIdentity`, `incomingPrefsKey`, `promoteIncoming`, `discardIncoming`, `badgesUnreadable`.
- Trust score: `identityKey` (dedup), model fields (`isEvent`, `isOrganizer`, `isRetroactive`, `presenceVerified`, `isNostrSigned`, `signerNpub`, `date`, `meetupName`).
- Reputation publisher / reputation QR: `generateBadgeProof`, `generateBadgeProofV2`, `getReputationStats`, `countBoundBadges`, `countVerifiedBadges`, `signQRv3`, `SecureQRScanner`.
- Promotion claims: `BadgeSecurity.verify(checkExpiry:false)`, `normalize`, `BadgeClaimService.verifyClaim`, `canonicalJsonEncode`, badge fields `sigContent/sigId/claim*`.
- Platform proofs: `BadgeSecurity.canonicalJsonEncode`.
- Co-attendance: `MeetupKey.eventId/slug`, `loadBadges/saveBadges/keepAside`.
- Home / admin panel / event calendar: `RollingQRService.loadSession/getOrCreateSession/endSession`, `EventBadgeSessionService.start`, `EventBadgeAuthService.myRight`, `BadgeClaimService.ensureBadgesClaimed`, `resetAll`, `badgesUnreadable`.
- Diagnostics: `loadBadges`, `kNfcEnabled`.
- App startup (`main.dart`): `loadBadges`.

---

## 13. Proposed Kotlin structure & public API

Modules (colon layout of ARCHITECTURE.md): `:data:badges` (model, BadgeSecurity, claim service, encrypted repository), `:data:issuance` (rolling QR, scan processing, NFC logic) and `:feature:badges` (UI), depending on `:core:crypto` + `:core:nostr` (keys, NIP-01/19, BIP-340, event model), `:data:identity` (signer / `SigningService`), `:core:data` (`KeyValueStore`, `SecureStore`; the Flutter import is `:data:migration`, spec 14), `:data:meetups` (location), `:core:network`, `:core:ui`.

```
space.einundzwanzig.meetup.badges
├─ model/
│  ├─ MeetupBadge.kt          data class + derived props + withClaim/copy (copy keeps isEvent)
│  ├─ DartDateTime.kt         (epochMicros, isUtc) + toIso8601String() + parse()
│  ├─ BadgeIdentity.kt        identityKey, unionKey, preferVariant, unionByIdentity, sameBadges
│  ├─ BadgeProofs.kt          proofId, claimProofId, verificationHash, proofV1/V2, stats, exportForReputation
│  ├─ MeetupSession.kt
│  └─ Results.kt              VerifyResult, QrVerifyResult, ClaimResult, ClaimVerifyResult, NonceValidation, …
├─ text/DartString.kt         §8.14 trim/lower/upper + res/raw/dart_case_table.txt (shared via :core, used by all domains)
├─ json/
│  ├─ DartJson.kt             Dart-compatible encoder (escaping, double format), canonicalJsonEncode
│  └─ JsonValue.kt            ordered JSON tree that preserves int vs double (or kotlinx JsonElement + custom printer)
├─ security/
│  ├─ BadgeSecurity.kt        signCompact, verifyCompact, verifyNostr, verify, normalize, isExpired, expiryInfo,
│  │                           eventMeetupId, eventAddressOf, signedCoordinates, signQrV3, verifyQrV3
│  └─ MeetupKey.kt
├─ claim/BadgeClaimService.kt
├─ store/
│  ├─ BadgeStore.kt           interface
│  ├─ EncryptedBadgeStore.kt  §4.2 algorithms
│  ├─ BadgeBlobCipher.kt      AES-GCM blob codec
│  └─ BadgeRepository.kt      StateFlow<List<MeetupBadge>>, unreadable flag, onSaved events
├─ session/
│  ├─ RollingQrService.kt
│  ├─ EventBadgeSessionService.kt
│  ├─ EventBadgeAuthService.kt
│  └─ EventBadgeChainService.kt
├─ scan/
│  ├─ BadgeScanProcessor.kt   pure use case of _processFoundTagData (returns ScanOutcome)
│  ├─ BadgeSaveUseCase.kt     _confirmSaveBadge logic (returns SaveOutcome)
│  └─ PresenceCheck.kt
├─ nfc/ (BuildConfig.NFC_ENABLED)  NfcBadgeReader.kt, NfcBadgeWriter.kt
└─ ui/
   ├─ verify/MeetupVerificationScreen.kt + MeetupVerificationViewModel.kt
   ├─ scanner/QrCameraScanner.kt, ScannerOverlay.kt
   ├─ rollingqr/RollingQrScreen.kt + RollingQrViewModel.kt
   ├─ reputation/SecureQrScannerScreen.kt, ReputationVerificationResultScreen.kt + ViewModel
   └─ nfc/NfcWriterScreen.kt, MeetupSessionWizardScreen.kt
```

Key signatures:

```kotlin
data class MeetupBadge(
  val id: String, val meetupName: String, val date: DartDateTime, val iconPath: String,
  val coverUrl: String = "", val blockHeight: Long = 0, val signerNpub: String = "",
  val meetupEventId: String = "", val delivery: String = "nfc",
  val sig: String = "", val sigId: String = "", val adminPubkey: String = "", val sigVersion: Int = 0,
  val sigContent: String = "", val claimSig: String = "", val claimEventId: String = "",
  val claimPubkey: String = "", val claimTimestamp: Long = 0, val isRetroactive: Boolean = false,
  val presenceVerified: Boolean = true, val isOrganizer: Boolean = false, val isEvent: Boolean = false,
  val lat: Double = 0.0, val lng: Double = 0.0,
) {
  val hasCryptoProof: Boolean; val isNostrSigned: Boolean; val isClaimed: Boolean; val isFullyBound: Boolean
  val proofId: String; val claimProofId: String
  fun verificationHash(): String
  fun toJson(): JsonObject            // exact key order of §3.1
  companion object { fun fromJson(o: JsonObject): MeetupBadge /* strict types, throws */ }
}

object BadgeIdentity {
  fun identityKey(b: MeetupBadge): String
  fun unionKey(b: MeetupBadge): String
  fun unionByIdentity(base: List<MeetupBadge>, extra: List<MeetupBadge>): List<MeetupBadge>
  fun sameBadges(a: List<MeetupBadge>, b: List<MeetupBadge>): Boolean
}

interface BadgeStore {
  val badgesUnreadable: StateFlow<Boolean>
  val saved: SharedFlow<Unit>                               // replaces onBadgesSaved
  suspend fun loadBadges(): List<MeetupBadge>
  suspend fun saveBadges(badges: List<MeetupBadge>, replaceUnreadable: Boolean = false, discardLocked: Boolean = false): Boolean
  suspend fun keepAside(badge: MeetupBadge): Boolean
  suspend fun stageIncoming(badges: List<MeetupBadge>): Boolean
  suspend fun promoteIncoming(formerNpub: String = ""): Boolean
  suspend fun discardIncoming()
  suspend fun formerCollections(): List<FormerCollection>
  suspend fun mergeFormer(index: Int): Boolean
  suspend fun resetAll()
}

object BadgeSecurity {
  const val BADGE_VALIDITY_HOURS = 4
  val COMPACT_SIGNED_KEYS = setOf("v","t","m","b","x")
  val SIGNED_COORDINATE_KEYS = emptySet<String>()
  suspend fun signCompact(signer: NostrSigner, meetupId: String, blockHeight: Long, validityHours: Int = 4, nowSec: Long): JsonObject
  fun verifyCompact(data: JsonObject, checkExpiry: Boolean = true, nowSec: Long): VerifyResult
  fun verifyNostr(data: JsonObject): Boolean
  fun verify(data: JsonObject, checkExpiry: Boolean = true, nowSec: Long): VerifyResult
  fun normalize(data: JsonObject, zone: ZoneId): JsonObject
  fun isExpired(data: JsonObject, nowSec: Long): Boolean
  fun expiryInfo(data: JsonObject, nowSec: Long): ExpiryInfo        // typed; UI formats
  fun eventMeetupId(eventAddress: String, title: String): String
  fun eventAddressOf(normalized: JsonObject): String?
  fun signedCoordinates(data: JsonObject): Pair<Double, Double>
  suspend fun signQrV3(signer: NostrSigner, json: String): QrSignResult
  fun verifyQrV3(json: String, signature: String, eventId: String, createdAt: Long, pubkeyHex: String): QrVerifyResult
  fun canonicalJsonEncode(map: JsonObject): String
}

object MeetupKey { fun slug(name: String): String; fun compose(slug: String, country: String): String
  fun parse(m: String): ParsedMeetupId; fun displayFromSlug(slug: String): String
  fun eventId(slug: String, date: DartDateTime): String; fun isIsoCountry(code: String): Boolean }

class BadgeClaimService(private val signer: NostrSigner, private val store: BadgeStore, private val clock: Clock) {
  suspend fun createClaim(orgSig: String, orgEventId: String, orgPubkey: String, blockHeight: Long): ClaimResult
  fun verifyClaim(claimSig: String, claimEventId: String, claimPubkey: String, claimTimestamp: Long,
                  orgSig: String, orgEventId: String, orgPubkey: String, blockHeight: Long): ClaimVerifyResult
  fun verifyBadgeClaim(badge: MeetupBadge): ClaimVerifyResult
  suspend fun claimUnboundBadges(badges: MutableList<MeetupBadge>): Int
  suspend fun ensureBadgesClaimed(badges: MutableList<MeetupBadge>): Int
}

class RollingQrService(/* prefs, secure, signer, mempool, clock, random */) {
  companion object { const val INTERVAL_SECONDS = 10; const val TOLERANCE_STEPS = 1; const val SESSION_VALIDITY_HOURS = 4 }
  suspend fun getOrCreateSession(meetupId: String, meetupName: String, meetupCountry: String, blockHeight: Long,
                                 lat: Double = 0.0, lng: Double = 0.0, validUntilEpoch: Long? = null): MeetupSession?
  suspend fun loadSession(): MeetupSession?
  suspend fun endSession()
  suspend fun getBasePayload(): JsonObject?
  suspend fun generateQrString(session: MeetupSession): String
  fun validateNonce(payload: JsonObject, nowSec: Long): NonceValidation
  fun secondsUntilNextChange(nowSec: Long): Int
  val activeSession: StateFlow<MeetupSession?>              // for home tile / admin panel
}

class EventBadgeSessionService(…) { suspend fun start(event: NostrCalendarEvent): EventSessionResult }
class EventBadgeChainService(…) { suspend fun verify(eventAddress: String, signerPubkey: String, issuedAt: Long?): EventChainResult }
class EventBadgeAuthService(…) { suspend fun myRight(): EventBadgeRight; suspend fun mayCreate(): Boolean }

class BadgeScanProcessor(…) {
  suspend fun processQr(raw: Map<String, Any?> /* ordered */): ScanOutcome   // nonce + verify + _processFoundTagData
  suspend fun processNfc(tagJson: String): ScanOutcome
}
sealed interface ScanOutcome { data class Rejected(val reason: ScanRejection) ; data class SelfScan ;
  data class AlreadyCollected(val fullName: String, val isEvent: Boolean) ;
  data class Found(val pending: MeetupBadge, val info: FoundInfo, val unknownSigner: Boolean) ; object ReviewDemo }
class BadgeSaveUseCase(…) { suspend fun confirm(pending: MeetupBadge, scannerMeetup: Meetup): SaveOutcome }
sealed interface SaveOutcome { data class TooFar(val km: Double, val allowedKm: Double); data class KeptAside(val ok: Boolean);
  object Duplicate; data class Saved(val badge: MeetupBadge, val presenceVerified: Boolean, val askCoAttendance: Boolean) }

interface NostrSigner {            // from :data:identity (SigningService)
  suspend fun canSign(): Boolean; suspend fun pubkeyHex(): String?; suspend fun npub(): String?
  suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String, createdAt: Long? = null): SignedEvent
}
```
UI (Material 3 Expressive): `LoadingIndicator` for the blocking locate dialog; `CircularWavyProgressIndicator` for the 10-s countdown; `ElevatedCard`/`OutlinedCard` for result sections; `AlertDialog` for confirmations; `SnackbarHost` with floating snackbars and durations as specified; predictive back; edge-to-edge camera with the overlay drawn in a `Canvas` (`BlendMode.Clear` hole).

---

## 14. Risks / compatibility pitfalls

1. **JSON byte-exactness**: event ids are hashes over Dart's `jsonEncode`. Any deviation in escaping (`/`, `<`, Unicode), number formatting, key order (top-level sort only) or whitespace breaks every signature check against Flutter-produced badges and makes native-produced badges invalid for Flutter scanners. Use the dedicated encoder and the fixture vectors.
2. **Int vs double in decoded payloads**: re-encoding `b`, `x`, `c`, `v` must keep integer literals (`875432` not `875432.0`); `c`/`x` as non-integers are invalid in Dart. Never round-trip payloads through `Double`.
3. **Claim `created_at` mismatch** (§7.4): Flutter can create unverifiable claims; native must sign with explicit `created_at == claimed_at`.
4. **Event sessions expire after 4 h** in Flutter despite "until midnight" sessions (§8.5) — decide whether to fix (`x = expiresAt`, compatible) or mirror.
5. **`withClaim` resets `presenceVerified` to true** during retroactive claiming — native should keep the original value (affects trust score only; no wire impact).
6. **Too-far dialog shows 5 km** while 50 km was applied — show the applied radius.
7. **Nonce stripping bypass** (§9) — optional hardening for camera scans.
8. **Badge `id` is not unique** (slug); never use it as a primary key in Room; identity is `unionKey`. `keepAside` dedups by `id` (Dart behaviour) — two different events with the same slug id kept aside in the same unreadable period would collapse (edge case, mirror or improve by using `unionKey`).
9. **Sort stability** in `generateBadgeProofV2` / v1: Dart is stable only up to 33 elements; ties in `claimTimestamp` are common after retroactive claiming (same second). Use a stable sort and preserve list order from `unionByIdentity` (LinkedHashMap) — matches Dart for ≤33 bound badges.
10. **DateTime local vs UTC**: badge dates are local without offset; `identityKey` fallback, same-day duplicate check, `meetupEventId` (`yyyy-MM-dd` local) and the `proofId`/`verificationHash` strings depend on Dart's `toIso8601String`/`parse` semantics (micro-digits, `Z`). A different timezone on the device changes day keys exactly as in Flutter — keep it.
11. **Strict `fromJson`** makes an entire blob "unopenable" if one entry has a wrong type; native writers feeding sync/backup must emit Dart-compatible types or Flutter peers will mark the wallet unreadable.
12. **Store state machine** (§4.2) is intricate (backup rotation, archive, pending, reset marker, secure second copy). Port it literally with the 39 storage tests; simplifications risk silent data loss.
13. **Upgrade from Flutter** (§4.4): reading `FlutterSharedPreferences` (prefixed doubles/lists) and `flutter_secure_storage` v10 ciphertexts; without it every upgrading user sees an empty/unreadable wallet and loses running sessions.
14. **`_candidateKeys` tries every 64-hex secret** in secure storage — keep the behaviour (it rescued real users) but scope it to the badge/legacy namespaces you import, and never log candidate values.
15. **Plaintext fallback** in `saveBadges` (encryption exception & no existing blob → `badges` string list): unreachable with JCA; recommend omitting it in native (document as deliberate deviation) — still READ legacy `badges`.
16. **Locale-dependent `unknownSlug`**: the "unusable name" check compares with the localized "Unbekanntes Meetup" slug; keep using the localized string (DE/EN/ES) to match Flutter behaviour.
17. **Event `m` parsing truncates `dTag` at `:`** and title slug regex/whitespace semantics differ between Dart and Java regex — use explicit character classes.
18. **Portal display name lookup** uses `MeetupKey.slug(m.city) == slug` and optional country; display only — never feed it into ids.
19. **Rolling QR timer drift**: Flutter refreshes every 10 s from screen open, not on step boundaries; QR may show the previous step for up to 10 s (still within tolerance). Aligning is safe; changing `INTERVAL_SECONDS` or tolerance is NOT (scanners on other versions use 10 s / ±1).
20. **Same-second session reuse**: `getOrCreateSession` reuses a running session only for the same `meetupId`; starting another meetup/event overwrites the single session slot (admin panel always ends the old one first). Keep one slot.
21. **`isValid()` 10-digit `created_at`** rule must be mirrored (rejects ms timestamps).
22. **Upper-case hex pubkey in `p`** is rejected by Flutter (id computed before lower-casing); Kotlin libs often lower-case silently — mirror the rejection to give identical results.
23. **NFC payload size**: event `m` ≈ 110+ chars → base payload > 300 bytes; NTAG213 (137 B) too small. Flag stays off.
24. **App-review demo payload** must be recognised in both scanners and must never issue a badge.
25. **QR content size** with `la/lo` and event `m` stays well within version auto at ECC M; do not raise ECC level without testing scan distance at 260 dp.
26. **Dart string case/trim semantics** (§8.14): Kotlin `trim()`/`lowercase(Locale.ROOT)`/`uppercase()`/`Char.uppercaseChar()` differ from Dart (final sigma, `İ`, `ß`, newer Unicode table, U+0085/U+FEFF/U+001C–U+001F). Using them changes signed `m` slugs, co-attendance d-tags, event `m` slugs and `nd:` identity keys for non-ASCII names → organizer/participant key mismatch and duplicate or lost badges after sync. Always go through `DartString`.
