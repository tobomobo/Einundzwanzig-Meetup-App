# 06 — Proofs: Platform proofs, Proof of Humanity, Zap verification, NIP-05, SatoshiDuell

Porting specification for the native Android rewrite (Kotlin, Jetpack Compose, Material 3 Expressive).
Source of truth: the Flutter app at the commit this spec was written against (`623bdc9`).

Dart files this spec covers, all read completely:

| File | Lines | Role |
|---|---|---|
| `lib/services/platform_proof_service.dart` | 506 | Creates and parses `21rep::` verify strings, stores platform proofs, verifies them against the reputation event |
| `lib/screens/platform_proof_screen.dart` | 451 | UI for creating and revoking platform links |
| `lib/services/humanity_proof_service.dart` | 573 | "Proof of Humanity": finds a provider-issued zap receipt (kind 9735) for the user on relays |
| `lib/screens/humanity_proof_screen.dart` | 364 | UI for the humanity check |
| `lib/services/nip05_service.dart` | 268 | NIP-05 verification, domain classification, score, NIP-05 lookup from kind 0 |
| `lib/services/zap_verification_service.dart` | 494 | Zap statistics (sent/received/diversity/months) and the `lightningScore` |
| `lib/services/zap_receipt_validator.dart` | 274 | Security Audit H3: local zap-receipt validation, bolt11 description-hash decoding, LNURL provider matching |
| `lib/services/satoshiduell_service.dart` | 195 | Read-only Supabase/PostgREST client that counts open SatoshiDuell games for the home/community tile |

Helpers read in order to describe behaviour exactly (owned by other domains): `relay_socket.dart` (`verifiedEvent`, `answersFilter`, `replaceableCreatedAt`, frame limit), `relay_config.dart` (relay list, timeouts), `badge_security.dart#canonicalJsonEncode`, `signing_service.dart` (`npub`, `canSign`, `signEvent`), `reputation_publisher.dart` (kind 30078 content, `PlatformProof` model, `fetchByNpub`, `publish`, `publishInBackground`), `news_zap_service.dart#fetchLightningAddress`, `news_reactions_service.dart#readTargets`, `backup_service.dart` (proof backup/restore), `badge_sync_service.dart#clearIdentityProofs`, `app_logger.dart#RelayParseTally`, plus the `nostr` 1.5.0 and `bech32` 0.2.2 packages.

---

## 1. Overview and user-visible features

This domain lets a user attach optional identity signals to their Nostr identity. It also judges those signals for other people:

- **Platform proofs ("Plattform-Verknüpfung").** The user links a username on another platform (Satoshi-Kleinanzeigen, Telegram, RoboSats, Nostr, or a custom one) to their npub. The app signs a kind-21003 Nostr event. The event is never published; only its 64-byte Schnorr signature is kept. The proof is stored locally, embedded in the reputation QR (`pp`) and published inside the kind-30078 reputation event (`platform_proofs`). Third parties check a `21rep::` verify string or an npub against the published reputation event.
- **Proof of Humanity.** The app searches relays for a NIP-57 zap receipt (kind 9735) in which the user is the sender or the recipient. The receipt must pass full local validation and must be signed by the recipient's LNURL provider. One such receipt marks the user as "human". The result goes into the reputation event as `humanity_proof`.
- **Zap statistics (Lightning layer).** These count validated, provider-issued zap receipts sent and received over the last 180 days, plus diversity and active months. They feed `lightningScore` (0–2.5) and the `lightning` block of the reputation event. They are also shown for other users when a reputation QR or verify string is checked.
- **NIP-05 check.** The app reads the `nip05` field from the user's newest kind-0 profile, resolves `/.well-known/nostr.json` and classifies the domain as community, custom or public provider. The class sets a score (1.0/0.7/0.3). This is used only when checking other people's reputation.
- **SatoshiDuell tile badge.** The app counts SatoshiDuell quiz duels that need the user's action (my turn + lobby) and those waiting for an opponent. It reads the public Supabase REST API. The counts appear on the home tile and the community-hub card. Tapping either opens satoshiduell.de with `?npub=` in the external browser.

Every user-visible feature, including small ones:

1. Platform proof screen: an info box that explains the four steps.
2. Platform proof screen: a list of active links, one card per stored proof (platform name, username in monospace, creation date, QR status icon, revoke button).
3. Platform proof screen: the primary button reads "PLATTFORM VERKNÜPFEN" when the list is empty and "WEITERE PLATTFORM" otherwise.
4. Bottom sheet "AUF PLATTFORM TEILEN": platform chips. A chip shows a green check and a green border when a proof already exists for that platform id. The selected chip is orange.
5. Choosing the "Andere Plattform" chip shows an extra "Name der Plattform" text field.
6. Choosing any chip shows the username field (prefix icon `alternate_email`, hint text specific to the platform) and the "VERKNÜPFUNG ERSTELLEN" button. Changing the chip clears the username field.
7. Silent validation: an empty username, or a custom name left empty for "other", does nothing. There is no error text.
8. Create: the sheet closes, the signer signs kind 21003 (with an external signer this can open Amber or a NIP-46 approval), the proof is stored and the reputation is republished in the background (forced). A green snackbar "Verknüpfung gespeichert! …" shows for 3 s, then **the platform proof screen itself closes** and the user lands back on the profile (see §14, R3).
9. Create failure: a red snackbar shows the raw service message for 10 s with a close icon (for example "Fehler: Der Signer hat abgelehnt: Permission denied for sign_event kind:21003").
10. Revoke: a confirmation dialog (red title "VERKNÜPFUNG AUFHEBEN?", body naming the username and raw platform id, buttons ABBRECHEN/WIDERRUFEN). Confirming deletes the proof, republishes the reputation (forced, fire-and-forget), closes the dialog and reloads the list. No snackbar.
11. Loading state: a centred orange `CircularProgressIndicator` while stored proofs load.
12. Humanity screen: a status card (verified → green shield `verified_user`, "MENSCH VERIFIZIERT", text with the first-zap date, "Lightning-Beweis aktiv" chip; not verified → amber `person_search`, "NICHT VERIFIZIERT", explanation).
13. Humanity screen: a "WIE FUNKTIONIERT DAS?" card with four numbered steps and an amber shield hint box.
14. Humanity screen: the check button, 56 dp tall. Not verified → amber "JETZT PRÜFEN" with a bolt icon. Verified → dark grey "ERNEUT PRÜFEN" with a refresh icon. While checking → disabled, a black spinner and "SUCHE AUF RELAYS...".
15. Humanity screen: "Letzte Prüfung: …" with a relative time ("Gerade eben", "Vor N Min.", "Vor N Std.", or `d.M.yyyy`). Shown only when `lastCheckedAt > 0`.
16. Humanity screen: a result box after a check. Green with a `check_circle` icon when found, amber with `info_outline` otherwise. It shows the service message.
17. Humanity screen: after a successful check the status reloads and a background reputation publish is requested (this is a no-op in Flutter; see §14, R5).
18. App start (home screen): if a humanity proof was restored from a backup and is pending re-verification, the app silently re-checks relays. On success it marks the user verified.
19. Profile edit (read-only and edit modes): two "IDENTITÄT STÄRKEN" actions. "Plattformen verknüpfen" shows the subtitle "N Plattform(en) aktiv" or "Telegram, X, Kleinanzeigen". "Proof of Humanity" shows "Lightning-Beweis aktiv" or "Einmal gezappt? Jetzt prüfen". Each action shows a done-state when satisfied. Returning from either screen refreshes the counts and keeps unsaved form input in edit mode.
20. Other users' reputation (verify screen and QR scan result): the verification level header (full, partial, signature only, invalid) with the service message. Lightning layer: zaps sent and received, unique counterparts, months active, activity label. Identity layer: the NIP-05 identifier with its domain label, plus linked platforms.
21. Reputation QR: platform proofs are embedded as `pp: {platform: {u, s}}`. The proof count appears on the QR screen.
22. Backup: platform proofs and the humanity status are included. On restore, platform proof signatures are format-validated. The humanity proof is restored as "pending re-verification", never as verified. The restore snackbar lists "N Plattform-Proofs" and "Humanity-Proof".
23. Identity switch (badge-sync restore with a different npub): all humanity keys and platform proofs are wiped.
24. SatoshiDuell home tile: the title "SATOSHIDUELL", then either a large number (my turn + lobby) or the subtitle "Quiz-Duelle um Sats — spiele gegen die Community". Below that, a coloured status line: "⚡ N dran" in gold, "N in der Lobby" in orange, "N warten auf Gegner" in green, separated by "  ·  ". Tap opens `https://satoshiduell.de/?npub=<npub>`.
25. SatoshiDuell community-hub card: the same data, laid out as a card with a count chip and an `open_in_new` icon.
26. Haptics: none in this domain. Share actions: none in this domain. The verify string is generated but never shown or shared.

---

## 2. Screens and UX flows

Colours used in Flutter, from `lib/theme.dart`: `cDark=#0C0C0E` (scaffold), `cCard=#131315`, `cOrange=#F7931A`, `cGreen=#22C55E`, `cRed=#E53935`, `cText=#FFFFFF`, `cTextSecondary=#9CA0A8`, `cTextTertiary=#868A94`. Flutter `Colors.green`, `Colors.amber` and `Colors.red` are also used directly. SatoshiDuell gold is `#FFC93C`. In M3 Expressive, map these to the app's colour scheme roles (primary = orange, tertiary or success = green, warning = amber container, error = red) and keep the semantics.

ARB keys map 1:1 to Android string resource names (see `android-native/tools/arb2xml.py`).

### 2.1 PlatformProofScreen (`PlatformProofScreen`, no constructor arguments)

**Entry points.**
- `ProfileEditScreen` read-only view, the "Plattformen verknüpfen" identity action (`Navigator.push`, then `_loadData()` on return).
- `ProfileEditScreen` edit view, the same action. Before navigating it saves the nickname and home-meetup input. On return it calls `_refreshIdentityOnly()`, which reloads only the proof count and humanity flag, and restores the saved form input.
- No deep links or other callers.

**State.** `savedProofs: List<PlatformProof>` (from storage), `isLoading = true` initially.

**Layout (top to bottom).**
1. TopAppBar title `ppTitle` ("PLATTFORM-VERKNÜPFUNG").
2. While loading: a centred circular progress indicator in orange. Otherwise a vertically scrolling column with 20 dp padding:
   1. **Info box.** Card with radius 12 and an orange 20% border. Row: `Icons.link` in orange (22 dp) + `apHowItWorks` ("SO FUNKTIONIERT'S", orange, 13 sp, weight 800). Below, one text built from `ppHowStep1 + ppHowStep2 + ppHowStep3 + ppHowStep4` concatenated as is (the pieces contain `\n`), grey, 12 sp, line height 1.6.
   2. 24 dp spacer.
   3. If `savedProofs` is not empty: section label `ppActiveLinks` ("AKTIVE VERKNÜPFUNGEN", grey, 11 sp, bold, letter spacing 1). Then one **proof card** per proof, in stored order, then a 20 dp spacer.
   4. Full-width 50 dp button, orange background, black content, `Icons.add`. Label `ppLinkPlatform` when the list is empty, otherwise `ppAnotherPlatform`. Opens the **create sheet**.

**Proof card.** Card with radius 12 and a green 20% border; 16 dp padding.
- Leading 40×40 tile (green 10% background, radius 10) with the platform icon in green. The icon comes from `PlatformInfo.icon`, or `language` for unknown/custom ids. Mapping: `shopping_cart`→ShoppingCart, `send`→Send, `smart_toy`→SmartToy, `hub`→Hub, `language`→Language, anything else→Link.
- Column: the platform display name (`PlatformInfo.name`, or the raw platform id for custom platforms; white, 14 sp, weight 700). Then the username (green-300, 13 sp, monospace). Then `ppCreated(date)`, where `date = "${d.day}.${d.month}.${d.year}"` in local time with no zero padding, from `createdAt * 1000` ms; grey, 10 sp.
- A 32×32 green tile with `Icons.qr_code` (shows the proof is embedded in the QR; not interactive).
- An `IconButton` with `delete_outline` in red-400, size 18, tooltip `ppRevokeTooltip`. It opens the **revoke dialog**.

**Create sheet** (modal bottom sheet, `isScrollControlled`, top radius 20, background `cCard`, padding `24,16,24, imeBottom+24`). Local sheet state: `selectedPlatform: String? = null`, `customPlatformName = ""`, `usernameController`.
1. Drag handle, 40×4, grey-700.
2. Title `ppShareOnPlatform` ("AUF PLATTFORM TEILEN", orange, 16 sp, weight 900).
3. `ppIntro` (grey-500, 13 sp).
4. Label `ppPlatform` ("PLATTFORM", grey, 11 sp, bold).
5. A wrap of chips (spacing 8/8), in the order of `PlatformProofService.platforms`: `satoshikleinanzeigen`, `telegram`, `robosats`, `nostr`, `other`. Each chip shows the icon (18 dp) and `PlatformInfo.name`.
   - Selected: orange 20% fill, orange 2 dp border, orange icon and text in weight 700.
   - Not selected: white 5% fill, 1 dp border. The border is green 30% if `savedProofs.any { it.platform == key }`, otherwise white 10%.
   - If a proof exists, a trailing `check_circle` icon (green-400, 14 dp) is shown. Custom platforms are stored under their own id, so `other` never shows the check.
   - Tap: `selectedPlatform = key`, and **the username field is cleared**.
   - Native: use M3 `FilterChip` with a leading icon. The trailing check is the "exists" indicator.
6. If `selectedPlatform == "other"`: a text field with hint `ppPlatformName` ("Name der Plattform"). `onChanged` writes `customPlatformName`. It is not cleared when switching chips.
7. If `selectedPlatform != null`:
   - Label `ppUsername` ("BENUTZERNAME").
   - Username text field. The hint is `PlatformInfo.hint` (hard-coded German, see §2.6), with fallback `ppYourUsername`. Prefix icon `alternate_email`.
   - A full-width 50 dp orange button with `Icons.lock` and label `ppCreateLink`. On press:
     - If `username.trim()` is empty, return silently.
     - If `selectedPlatform == "other"` and `customPlatformName.trim()` is empty, return silently.
     - Close the sheet.
     - Call `generateProof(platformId = selectedPlatform, username = username.trim(), customName = if other then customPlatformName.trim() else null)`.

**generateProof flow.**
1. `result = PlatformProofService.createProof(...)` (§8.1). There is no progress indicator in Flutter. Native should show one (for example a blocking `LinearProgressIndicator` or a loading button state) because external signers can take seconds. This UX addition does not affect compatibility.
2. On success:
   1. `proofs = getProofsForPublishing()`.
   2. Fire and forget: `ReputationPublisher.publish(badges = myBadges, platformProofs = proofs, force = true)`.
   3. Reload the list.
   4. Snackbar `ppLinkSaved`, green background, 3 s.
   5. `Navigator.pop(context)`, which **closes PlatformProofScreen** because the sheet was already closed.
3. On failure: snackbar with `result.message`, red, **10 s**, `showCloseIcon = true`. The screen stays open.

**Revoke dialog** (`AlertDialog`, background `cCard`).
- Title `ppUnlinkQ` in red bold.
- Body `ppUnlinkBody(username, platform)`, where `platform` is the **raw platform id**, not the display name.
- Actions: `TextButton(ppCancel)` in grey closes the dialog. `ElevatedButton(ppRevoke)` with a red background and white text runs:
  1. `removeProof(proof.platform)`
  2. `proofs = getProofsForPublishing()`
  3. Fire and forget: `ReputationPublisher.publish(badges = myBadges, platformProofs = proofs, force = true)`
  4. Close the dialog
  5. Reload the list
- Native: hold on to the dialog dismissal before the suspend calls (Flutter captured the navigator before the `await`).

**Unused ARB keys** (legacy, no need to port, harmless if they exist): `ppMustUpdate`, `ppUnlinkBody1`, `ppUnlinkBody2`.

**German reference texts** (`app_de.arb`):
- `ppHowStep1` = "1. Wähle eine Plattform und gib deinen Usernamen ein\n"
- `ppHowStep2` = "2. Die App erstellt einen kryptographischen Beweis\n"
- `ppHowStep3` = "3. Der Beweis wird automatisch in deinen Reputation-QR eingebettet\n"
- `ppHowStep4` = "4. Andere scannen deinen QR und sehen die verifizierte Verknüpfung"
- `ppUnlinkBody` = "Die Plattform-Verknüpfung für \"{username}\" auf {platform} wird gelöscht.\n\nDu musst dein Reputation-Event danach aktualisieren." (args in this order: username, platform)
- `ppCreated` = "Erstellt: {date}"

### 2.2 HumanityProofScreen (`HumanityProofScreen`, no constructor arguments)

**Entry points.** `ProfileEditScreen` "Proof of Humanity" identity action, in both the read-only and the edit view, with the same return handling as §2.1.

**State.** `status: HumanityStatus?`, `isLoading = true`, `isChecking = false`, `resultMessage: String? = null`, `resultSuccess: Boolean? = null`.

**Layout.** TopAppBar `humTitle` ("PROOF OF HUMANITY"). While loading: a centred orange spinner. Otherwise a scrolling column with 20 dp padding: status card, 24 dp, explanation card, 24 dp, action section.

**Status card.** Full width, 24 dp padding, radius 16, 2 dp border (green 40% if verified, amber 30% otherwise). `verified = status?.verified ?? false`. `needsReverification` forces `verified = false`; see §8.3.
- A 72 dp circle (green or amber at 10%) with a 36 dp icon: `verified_user` in green when verified, `person_search` in amber otherwise.
- Title: `humVerified` ("MENSCH VERIFIZIERT", green) or `humNotVerified` ("NICHT VERIFIZIERT", amber). 16 sp, weight 900, letter spacing 1.
- Body, centred, grey-400, 13 sp, line height 1.5:
  - Verified: `humPaidOn(firstZapDateStr) + humProofInEvent1 + humReputationSaved`. In German: "Du hast am 3.5.2025 eine Lightning-Zahlung auf dem Nostr-Netzwerk geleistet. Dieser Beweis ist in deinem Reputation-Event gespeichert." The pieces carry their own trailing spaces; concatenate them as is.
  - Not verified: `humIntro1 + humIntro2 + humIntro3`.
- Verified only: a chip (green 10% background, radius 8) with `Icons.bolt` in amber and `humLightningActive` in green-300, 12 sp, weight 600.

**Explanation card** (radius 12, white-10 border):
- Title `humHowTitle` (grey-300, 12 sp, weight 800).
- Four step rows. Each has a 24 dp orange-15% circle with the number in orange (12 sp, weight 800), a title (white, 13 sp, weight 600) and a subtitle (grey-500, 11 sp):
  1. `humStep1` / `humStepInstruction`
  2. `humStep2` / `humCryptoProof`
  3. `humStep3` / `humCheckInstruction`
  4. `humVerifiedSub` / `humProofPrivacy`
- An amber hint box (amber 5% fill, amber 20% border, radius 8): `Icons.shield` in amber + `humExplain1 + humExplain2 + humExplain3` (grey-400, 11 sp).

**Action section.**
- Button, full width, 56 dp, radius 14:
  - `isChecking`: disabled (amber 50% disabled background), 20 dp black spinner (stroke 2), label `humSearchingRelays`.
  - Verified: grey-800 background, white text, `Icons.refresh`, label `humCheckAgain`.
  - Otherwise: amber background, black text, `Icons.bolt`, label `humCheckNow`.
  - Label style: weight 900, letter spacing 0.5.
- If `status.lastCheckedAt > 0`: `humLastCheck(status.lastCheckedStr)` in grey-600, 11 sp.
- If `resultMessage != null`: a result box (14 dp padding, radius 10). Green when `resultSuccess == true`, amber otherwise (8% fill, 30% border). Icon `check_circle` or `info_outline`; message text in green-300 or amber-300, 12 sp.

**Check flow** (`checkForZaps`):
1. Set `isChecking = true`, `resultMessage = null`, `resultSuccess = null`.
2. `result = HumanityProofService.checkForZaps()` (§8.3).
3. Set `isChecking = false`, `resultMessage = result.message`, `resultSuccess = result.found`.
4. If found: reload the status and call `ReputationPublisher.publishInBackground([])`. With an empty badge list this returns immediately, so nothing is published (§14, R5).

**Unused ARB keys:** `humCheckAgainShort`, `humZapReturn`.

### 2.3 Profile edit entry tiles (owned by the profile domain; listed for mapping)

Strings: `profileStrengthen` ("IDENTITÄT STÄRKEN"), `profileStrengthenDesc` (edit mode only), `profileLinkPlatforms`, `profilePlatformsSub` ("Telegram, X, Kleinanzeigen"), `profileProofHumanity`, `profileLightningActive`, `profileZapCheck`.

The active subtitle "`$n Plattform${n > 1 ? 'en' : ''} aktiv`" is hard-coded German. Native should use a new plural resource (proposed `proofsPlatformsActive`: one "%d Plattform aktiv", other "%d Plattformen aktiv").

Data used: `PlatformProofService.getSavedProofs().size` and `HumanityProofService.getStatus().verified`.

### 2.4 Screens that render this domain's data (owned elsewhere; contract only)

- **ReputationVerifyScreen** (domain: reputation/trust).
  - Calls `PlatformProofService.verifyProofString(input)` (no `expectedUsername`).
  - Renders `ProofVerifyResult.level`, `.message`, `.platform`, `.claimedUsername`, `.proofInEvent`, `.npub`, `.reputation`.
  - If `result.npub != null && level != invalid`, it loads three layers in parallel:
    - `ZapVerificationService.analyzeZapActivity(pubkeyHex, useCache=false)`
    - `Nip05Service.fetchNip05FromProfile(pubkeyHex, RelayConfig.getActiveRelays())`, then `Nip05Service.verify(nip05, pubkeyHex)` when not null or empty
    - the social graph
- **QR scanner `_VerificationResultScreen`** (domain: reputation QR). Loads the same three layers for `identity.np`. Renders the QR `pp` map:
  - username from `data['u'] ?? data['username']`
  - "hasSig" from a non-empty `data['s'] ?? data['proof_sig']`
  - labels `qrSigVerifiedShort` or `qrLinkedShort`
  - its own platform label and icon table: `telegram`→Telegram/Send, `satoshikleinanzeigen`→Satoshi-Kleinanzeigen/ShoppingCart, `robosats`→RoboSats/SmartToy, `nostr`→Nostr/Hub, `other`→`qrPlatformOther`/Language, anything else→raw id/Language
- **ReputationLayersWidget** (domain: trust score). Uses:
  - `ZapStats.totalCount`, `lightningScore` (weight 0.25), `sentCount`, `uniqueRecipientCount`, `receivedCount`, `uniqueSenderCount`, `activeMonths`, `activityLabel`
  - `Nip05Result.valid`, `.nip05`, `.domainLabel`
  - `Nip05Service.score()`, which adds into the identity score (identity = nip05Score + min(platformCount×0.3, 0.5) + (accountAgeDays > 30 ? 0.5 : 0), clamped to 0..2)
- **ReputationQrScreen**:
  - `getSavedProofs()` for the count and the `pp` map: `{platform: {"u": username, "s": proofSig}}`
  - the manual "publish" action: `publish(badges, platformProofs = getProofsForPublishing(), force = true)`

### 2.5 SatoshiDuell tiles (rendered by the home and community domains; data from this domain)

**Home tile** (`_buildSatoshiDuellTile`, watermark asset `assets/images/satoshiduell.png`, accent `#FFC93C`):
- Tap opens `https://satoshiduell.de/?npub=<SigningService.npub()>`, or `https://satoshiduell.de/` when there is no npub, with `LaunchMode.externalApplication`. If the launch fails, a snackbar shows `homeCouldNotOpen(url)`.
- Content from a single `fetchStatus()` call:
  - Row: `bolt_rounded` in gold + "SATOSHIDUELL" (gold, 12 sp, letter spacing 1.1, weight 800, scales down to fit).
  - Then `n = myTurn + lobby`. If `n > 0`, show "$n" (26 sp, weight 800, white). Otherwise show `chDuellSub` (14 sp, secondary, max 2 lines).
  - Then, if any status pieces exist, a single line with spans separated by "  ·  " (tertiary colour):
    - "⚡ ${myTurn} ${sdShortTurn}" in gold, weight 700, if `myTurn > 0`
    - "${lobby} ${sdShortLobby}" in orange, weight 600, if `lobby > 0`
    - "${waiting} ${sdShortWait}" in green, if `waiting > 0`
- While loading, or on error, `DuellStatus.EMPTY` is shown.

**Community hub card** (`_duellCard(subtitle = chDuellSub)`, 120 dp tall, gradient gold 16% to card):
- Same tap target. The hub's `_openUrl` swallows errors silently.
- Row: `bolt_rounded` (22 dp) … a count chip "$n" (gold) if `n > 0` … `open_in_new_rounded`.
- Title "SatoshiDuell" (15 sp, weight 700).
- Then the joined status line, or the subtitle when it is empty.
- Guide target: `CommunityTour.duellKey` (`guide_c_duell`) with `guideCommunityFunTitle`/`guideCommunityFunBody`.

**Unused ARB keys:** `sdMyTurn`, `sdWaiting`, `sdLobby`.

### 2.6 Hard-coded German strings in services (not in the ARB files)

The native app should move these into string resources. The German default values below must match exactly; also add EN and ES translations. Proposed resource keys are in brackets. Better still, return typed results (enums or sealed classes) from the domain layer and localise in the UI.

**Platform catalogue** (`PlatformProofService.platforms`, order matters):

| id | name | icon | hint |
|---|---|---|---|
| `satoshikleinanzeigen` | Satoshi-Kleinanzeigen | `shopping_cart` | Dein Benutzername auf Satoshi-Kleinanzeigen [`ppHintSka`] |
| `telegram` | Telegram | `send` | Dein Telegram @username (ohne @) [`ppHintTelegram`] |
| `robosats` | RoboSats | `smart_toy` | Dein RoboSats Robot-Name [`ppHintRobosats`] |
| `nostr` | Nostr | `hub` | Dein Nostr-Profilname (NIP-05 oder Display Name) [`ppHintNostr`] |
| `other` | Andere Plattform [`ppNameOther`] | `language` | Dein Benutzername auf der Plattform [`ppHintOther`] |

**PlatformProofService messages:**
- create, no key or cannot sign: "Kein Schlüssel vorhanden" [`proofErrNoKey`]
- create, empty username: "Benutzername darf nicht leer sein" [`ppErrEmptyUsername`]
- create, success: "Verify-String erstellt ✓" (not shown by the UI)
- create, exception: "Fehler: $e" [`proofErrGeneric`]. Show the exception message; for a signer rejection this is the signer's reason.
- verify, bad format: "Ungültiges Format. Erwartet: 21rep::npub::plattform::username::sig=..."
- verify, bad signature: "WARNUNG: Signatur ungültig! Dieser Verify-String wurde manipuliert."
- verify, username mismatch: "WARNUNG: Username stimmt nicht überein! String sagt \"<u>\", Account ist \"<expected>\"."
- verify, no reputation: "Signatur gültig ✓ — Aber keine Reputation auf Relays gefunden."
- verify, full: "Identität und Reputation vollständig bestätigt ✓"
- verify, partial with proof: "Reputation teilweise bestätigt — <bound> von <total> Badges gebunden."
- verify, partial without proof: "Reputation verifiziert, aber Plattform-Proof nicht im Event gefunden."
- verify, signature only: "Signatur gültig, aber keine gebundenen Badges."
- verify, exception: "Verifikations-Fehler: $e"
- npub only, no reputation: "Keine Reputation auf Relays gefunden für diesen npub."
- npub only, with bindings: "Reputation gefunden — aber kein Plattform-Proof. Identitäts-Verknüpfung nicht bestätigt."
- npub only, without bindings: "Reputation gefunden, aber keine gebundenen Badges."

**HumanityProofService messages:**
- "Kein Schlüssel vorhanden"
- "Ungültiger npub"
- "Lightning-Zahlung gefunden — du bist verifiziert!"
- "Kein Zap-Receipt gefunden. Hast du schon einmal jemanden auf Nostr gezappt? Zappe irgendjemanden (egal wieviel Sats) und komm zurück."

**HumanityStatus.lastCheckedStr:** "Nie", "Gerade eben", "Vor N Min.", "Vor N Std.", `d.M.yyyy`.

**Nip05Result.domainLabel:** community "Community-verifiziert", custom "Eigene Domain", public "Public Provider", unknown "".

**ZapStats.activityLabel:** "Keine Zap-Aktivität" (total 0), "Wenig Aktivität" (<5), "Regelmäßig aktiv" (<20), "Sehr aktiv".

### 2.7 Tours and guides

- There are no active tour steps on PlatformProofScreen or HumanityProofScreen.
- `guide_overlay.dart` still maps `guideOnboardPlatformsTitle`, `guideOnboardHumanityTitle`, `guideOnboardHumanityBody` and `guideHintHumanity`, but no tour references them (orphans).
- SatoshiDuell: `CommunityTour` step 4 targets the duell card. `HomeTour` step 9 ("guideHomeMoreTitle") mentions SatoshiDuell but has no target.

---

## 3. Data models

All timestamps are Unix **seconds** unless marked ms. Hex values are lowercase unless stated otherwise.

### 3.1 `PlatformInfo` (constant catalogue)
`id: String`, `name: String`, `icon: String` (icon key), `hint: String`. The order is the one in §2.6.

### 3.2 `PlatformProof`

| Field | Type | Local JSON key (`platform_proofs` pref, backup) | Reputation event key |
|---|---|---|---|
| platform | String | `platform` | map key |
| username | String | `username` | `username` |
| proofSig | String (128 hex, Schnorr sig of the kind-21003 event) | `proof_sig` | `proof_sig` |
| createdAt | Int (Unix s, device clock at creation) | `created_at` | `created_at` |

- Local list serialisation, from `_saveAllProofs`, key order as written: `[{"platform":..,"username":..,"proof_sig":..,"created_at":..}, ...]`.
- Local parse is strict. Every entry must have `platform`, `username` and `proof_sig` as strings and `created_at` as an int. **Any** failure returns an empty list for the whole list. See §14, R9.
- Reputation event value (`PlatformProof.toJson`): `{"username":..,"proof_sig":..,"created_at":..}`. Event parse (`PlatformProof.fromJson`) is lenient, with defaults `''`, `''`, `0`.
- QR (`pp`) value: `{"u": username, "s": proofSig}`.
- Uniqueness: at most one proof per `platform` string (exact, case-sensitive in the service; backup union compares lowercased).

### 3.3 `ParsedVerifyString`
`npub`, `platform`, `username`, `sig` (all String).

### 3.4 `ProofCreateResult`
`success: Boolean`, `verifyString: String = ""`, `proof: PlatformProof?`, `message: String`.

### 3.5 `VerifyLevel`
`full` (stage 3), `partial` (2), `signatureOnly` (1), `invalid` (0).

### 3.6 `ProofVerifyResult`
`level`, `npub?`, `platform?`, `claimedUsername?`, `reputation: ReputationEvent?` (reputation domain model), `proofInEvent: Boolean = false`, `message: String`.

### 3.7 `HumanityStatus`
- `verified: Boolean`
- `firstZapAt: Int = 0`
- `receiptEventId: String = ""`
- `lastCheckedAt: Int = 0`
- `needsReverification: Boolean = false`

Derived:
- `firstZapDateStr`: `""` if 0, otherwise `"${day}.${month}.${year}"` in local time with no padding.
- `lastCheckedStr`: `"Nie"` if 0. Otherwise compute `diff = now - dt`: `inMinutes < 1` gives "Gerade eben" (negative diffs land here too); `inHours < 1` gives "Vor ${inMinutes} Min."; `inDays < 1` gives "Vor ${inHours} Std."; anything else gives `d.M.yyyy`. Durations truncate toward zero (Dart `inMinutes`, `inHours`, `inDays`).

### 3.8 `HumanityCheckResult`
`found: Boolean`, `receiptEventId: String?`, `zapTimestamp: Int?`, `message: String`.

### 3.9 Humanity proof published in the reputation event (`getProofForPublishing`)
`null` unless `getStatus().verified`, otherwise exactly:
```json
{"verified": true, "method": "lightning_zap", "first_zap_at": <HumanityStatus.firstZapAt>}
```
Reader side (reputation domain): `humanityVerified = content.humanity_proof.verified as bool ?? false`, `humanityReceiptId = content.humanity_proof.receipt_event_id`. The writer never sets `receipt_event_id`, so the reader always gets null.

### 3.10 Backup representation (owned by the backup domain; produced from this domain)
```json
"platform_proofs": [{"platform":..,"username":..,"proof_sig":..,"created_at":..}, ...],
"humanity_proof": {"verified": <bool>, "first_zap_at": <int>, "receipt_event_id": "<hex or ''>", "checked_at": <int>}
```
Backup format version 5. `humanity_proof.verified` here is `HumanityStatus.verified`, so it is **false** while re-verification is pending.

### 3.11 `ZapReceiptCheck`
- `ok: Boolean`
- `reason: String`, one of `kind`, `receipt_sig`, `tags`, `description_json`, `request_kind`, `request_sig`, `recipient_mismatch`, `bolt11`, `description_hash`, `ok`
- `recipientPubkey: String = ""`: first `p` tag of the receipt
- `senderPubkey: String = ""`: `pubkey` of the embedded zap request
- `receiptPubkey: String = ""`: `pubkey` of the receipt

### 3.12 `ZapReceipt` (internal)
`eventId`, `senderPubkey`, `recipientPubkey`, `createdAt: Int`, `isSent: Boolean`, `isReceived: Boolean`, `hasBolt11: Boolean` (always true after `checkLocal`), `receiptPubkey: String = ""`.

### 3.13 `ZapStats`

| Field | Type | JSON key |
|---|---|---|
| sentCount | Int | `sent` |
| receivedCount | Int | `received` |
| uniqueRecipientCount | Int | `unique_recipients` |
| uniqueSenderCount | Int | `unique_senders` |
| activeMonths | Int | `active_months` |
| hasLightningProof | Boolean | `lightning_proof` |

- `toJson` key order: `sent, received, unique_recipients, unique_senders, active_months, lightning_proof`.
- `fromJson`: each key is `as int? ?? 0` (or `as bool? ?? false`). A non-int number such as `3.0` throws in Dart. Native should parse leniently (accept an integral double), because a strict parse would reject the whole reputation event in the reader (§14, R12).
- `empty()`: all zero and false.
- Derived: `totalCount = sent + received`; `lightningScore` (§8.5); `activityLabel` (§2.6).
- Used as the `lightning` block in kind 30078 content when `totalCount > 0`, and as the cache value.

### 3.14 `Nip05Result`
- `valid: Boolean`
- `nip05: String` (the input, unmodified)
- `domain: String?` (lowercased)
- `domainType: Nip05DomainType = unknown`. `Nip05DomainType` is one of `community`, `custom`, `publicProvider`, `unknown`.
- Derived: `domainLabel` (§2.6).

### 3.15 `DuellStatus`
`myTurn: Int`, `waiting: Int`, `lobby: Int`. Derived: `hasAny = any > 0`. `EMPTY = (0, 0, 0)`.

### 3.16 Provider cache entry (`zap_provider_pubkey_<recipient>`)
JSON `{"pk": "<64 lowercase hex>", "ts": <epoch ms>}`.

---

## 4. Persistence

Flutter used `SharedPreferences` (legacy API). On Android these keys live in `FlutterSharedPreferences.xml` with the prefix **`flutter.`**. Ints are stored as Long. The native app keeps the same `applicationId` (`space.einundzwanzig.meetup`), so `:data:migration` must copy the keys below on first start (strip `flutter.`). Afterwards, read and write them through the shared `:core:data` `KeyValueStore` (DataStore file `app_prefs`, spec 14 §4.1) under the **same un-prefixed names**; this domain opens no DataStore file of its own. That keeps backup and sync code and the tests aligned.

| Key (exact) | Type | Written by | Read by | Notes |
|---|---|---|---|---|
| `platform_proofs` | String (JSON list, §3.2) | `PlatformProofService._saveAllProofs` (create, remove); backup restore (replace or union); `BadgeSyncService.clearIdentityProofs` (remove) | `getSavedProofs` (profile, QR, platform screen, backup) | One entry per platform; a new proof replaces the old one and is appended at the end |
| `humanity_verified` | Bool | `_saveProof` (true); `reverifyIfNeeded` (true); backup restore (false); `clearIdentityProofs` (false); `reset` (remove) | `getStatus` | |
| `humanity_first_zap_at` | Int (s) | `_saveProof` (`created_at` of the accepted receipt); backup restore (if > 0) | `getStatus` | Misnomer: this is the accepted receipt's time, not the earliest zap |
| `humanity_receipt_id` | String (64 hex) | `_saveProof`; backup restore (if not empty) | `getStatus` | |
| `humanity_checked_at` | Int (s) | `checkForZaps` (always, found or not); `_saveProof`; backup restore (if > 0) | `getStatus` | |
| `humanity_needs_reverification` | Bool | backup restore (true); `reverifyIfNeeded` on success (remove); `clearIdentityProofs` (remove) | `getStatus`, `reverifyIfNeeded` | When true, `getStatus` reports `verified=false` |
| `zap_stats_cache_v2_<pubkeyHex>` | String (ZapStats JSON) | `analyzeZapActivity` (every run, **any** pubkey, including others with `useCache=false`) | `analyzeZapActivity` when `useCache` | v2 suffix: old v1 keys (`zap_stats_cache_<pk>`) are ignored and never deleted |
| `zap_cache_ts_v2_<pubkeyHex>` | Int (epoch **ms**) | same | same | TTL 6 h: `now - ts > 6h` means expired; a missing key means 0, which means expired |
| `zap_provider_pubkey_<recipientPubkey>` | String `{"pk","ts"}` | `ZapReceiptValidator.providerPubkeyFor` on success only | same | TTL 7 days; empty `pk` is never trusted |

In-memory (process) caches:
- `ZapReceiptValidator._providerCache: Map<recipient, providerPk>`. Only non-empty values are stored. It never expires within the process.
- `SatoshiDuellService._cachedStatus`/`_cachedAt`, TTL 2 min.

No secure storage is used in this domain. The npub and signing keys come from the signing domain.

**Identity switch.** `BadgeSyncService.clearIdentityProofs()` (badge-sync domain) must set `humanity_verified=false` and remove `humanity_needs_reverification`, `humanity_receipt_id`, `humanity_first_zap_at`, `humanity_checked_at` and `platform_proofs`. Then it sets `badge_restore_proofs=true`. If any write fails it throws. **Zap caches and provider caches are not cleared.** They are keyed by pubkey, so that is harmless.

**Backup restore rules** (backup domain, data from here):
- Platform proofs: keep only entries whose `proof_sig` is not empty and matches `^[0-9a-fA-F]+$` with length 128.
  - Additive mode: union with the device list. The key is `platform.lowercase()` and the device entry wins. Write the result only if the union is not empty.
  - Replace mode: write the validated list only if it is not empty. Otherwise the device list stays.
  - Entries are written as is; extra keys survive.
- Humanity: `humanityAlready = additive && humanity_verified == true`. If `!humanityAlready && backup.humanity_proof.verified == true`, set `humanity_verified=false` and `humanity_needs_reverification=true`, and copy `receipt_event_id` (if not empty), `first_zap_at` (if > 0) and `checked_at` (if > 0).

Migration note: none needed within this domain beyond copying the keys. The `_v2` zap cache keys can also be dropped; they are only a cache.

---

## 5. Network (HTTP)

The Flutter `package:http` defaults apply: redirects are followed (up to 5), there is no retry, and the user agent is `Dart/x (dart:io)`. Use one shared OkHttp client from the core network module. No custom headers except for SatoshiDuell.

### 5.1 NIP-05 resolution (`Nip05Service.verify`)
- `GET https://<domain>/.well-known/nostr.json?name=<name>`. `name` and `domain` are lowercased, and the URL is built by **string interpolation without encoding** (see §14, R14).
- Timeout: 5 s for the whole request.
- Valid only if: HTTP 200, `bodyBytes.length <= 65536`, the body **decoded as UTF-8 regardless of charset** parses as a JSON object, `names` is an object, `names[name]` is a String and equals `expectedPubkeyHex` exactly (case-sensitive).
- Any exception or non-200 → `valid=false` (with `domain` set if parsing got that far).
- Redirects: Flutter follows them. NIP-05 says fetchers MUST ignore redirects. Pick one deliberately (§14, R14). The default recommendation is to follow, for parity of the displayed result.

### 5.2 LNURL-pay metadata (`ZapReceiptValidator.providerPubkeyFromLnurlp`)
- `GET https://<domain>/.well-known/lnurlp/<user>`.
  - `domain` = lud16 domain part, trimmed and lowercased, must match `^[a-z0-9.-]+$`.
  - `user` = trimmed local part, **not** URL-encoded and **not** lowercased.
- Timeout 6 s. HTTP 200 required; body ≤ 64 KiB; UTF-8 decode; JSON object.
- `allowsNostr` must be exactly boolean `true`.
- `nostrPubkey` is converted with `toString().lowercase()` and must match `^[0-9a-f]{64}$`. Otherwise the result is `null`.
- Exceptions propagate to `providerPubkeyFor`, which catches them, logs at debug level and returns null **without caching**.

### 5.3 SatoshiDuell (Supabase PostgREST, read-only)
- Base: `https://uydjemquyogdemjtxyyv.supabase.co/rest/v1`
- Headers on every request:
  - `apikey: sb_publishable_RESZrjWqb-l_AIiqZcE7-g_JenFWO_d`
  - `Authorization: Bearer sb_publishable_RESZrjWqb-l_AIiqZcE7-g_JenFWO_d`
  - `Accept: application/json`
- The key is the public "publishable" client key, the same one the web app ships, and is limited by RLS. It is not a secret, but keep it in `BuildConfig` or constants rather than strings.xml. The old JWT anon key returns 401.
- Timeout 8 s per request. Three **sequential** requests:
  1. `GET {base}/profiles?npub=eq.{enc(npub.lowercase())}&select=username&limit=1` → `[{"username": "..."}]`. Non-200 → null, which gives `EMPTY`. An empty array, a non-map entry or an empty username → null ("never played").
  2. `GET {base}/duels?select=status,creator,challenger,target_player,creator_score,challenger_score&or=(creator.eq.{Q},challenger.eq.{Q},target_player.eq.{Q})&order=created_at.desc&limit=50`
     - `Q = enc("\"" + me.replace("\"", "") + "\"")`, with `me = username.lowercase()`.
     - Non-200 → `myTurn = waiting = 0`; continue.
  3. `GET {base}/duels?select=creator,target_player&status=eq.open&limit=100`. Non-200 → `lobby = 0`.
- `enc` = Dart `Uri.encodeQueryComponent`. Encode as UTF-8 bytes. Keep `[A-Za-z0-9-._~]` unescaped. Space becomes `+`. Every other byte becomes `%XX` with **uppercase** hex.
  - `java.net.URLEncoder` is **not** identical: it leaves `*` alone and encodes `~`. Write a small encoder.
- Build the final URL string verbatim. `(`, `)`, `,` and `.` stay literal; OkHttp `toHttpUrl()` keeps them. Verify the exact request line with MockWebServer.
- Bodies are parsed with `jsonDecode(r.body)`. Supabase sends `charset=utf-8`; decode as UTF-8.
- Caching: an in-memory result for 2 minutes, unless `forceRefresh`. Only a successful run (one that reached step 3) is cached. "No npub" and "no username" return `EMPTY` without caching.
- Any exception (timeout, parsing) → returns the **last cached status** if one exists, otherwise `EMPTY`. It never surfaces an error.

### 5.4 External link
`https://satoshiduell.de/?npub=<npub>` (npub as returned by the signer, not lowercased), or `https://satoshiduell.de/`. Open in the external browser: `Intent.ACTION_VIEW`. Flutter used `LaunchMode.externalApplication`, so do not use Custom Tabs if strict parity is wanted; Custom Tabs would also be acceptable UX. The web app reads `npub`, logs in, and removes the parameter.

### 5.5 Lightning address lookup (owned by the news domain, used here)
`NewsZapService.fetchLightningAddress(pubkeyHex)`:
- Queries kind 0 on `readTargets()` = `{wss://nostr.einundzwanzig.space, wss://relay.damus.io, wss://nos.lol, wss://relay.nostr.band} ∪ activeRelays`, all in parallel.
- Connect timeout 4 s; overall 6 s; fixed sub id `zapmeta`. Uses `verifiedEvent` + `answersFilter` + `replaceableCreatedAt`.
- The newest profile wins. On equal timestamps the later arrival wins.
- Returns: `lud16.trim()` if it contains `@`; else `"lud06:"` if `lud06` is non-empty; else null.
- `_resolveProvider` treats `"lud06:"` (no `@`) as no provider.

---

## 6. Nostr

### 6.1 Kind 21003 — platform proof (signed, never published)
- Signed through the active signer (`SigningService.signEvent`): local key, Amber (NIP-55), NIP-07 or NIP-46. NIP-46 connect permissions must include `sign_event:21003` (already in the `signedKinds` list of the signing domain; vouching/distrust uses the same kind).
- Tags, in this order:
  ```
  [["t","platform_proof"],["platform",<platform>],["username",<trimmedUsername>]]
  ```
- Content is `BadgeSecurity.canonicalJsonEncode(map)`: top-level keys sorted ascending by UTF-16 code unit, encoded with Dart `jsonEncode` (no whitespace, non-ASCII emitted raw, `"` `\\` escaped, control characters as `\b \t \n \f \r` or `\u00XX`, `/` not escaped):
  ```json
  {"action":"platform_proof","created_at":<now_s>,"platform":"<platform>","protocol":"21rep","username":"<username>","version":2}
  ```
  `now_s = DateTime.now().millisecondsSinceEpoch ~/ 1000` on the device. The event's own `created_at` is chosen by the signer and **not stored**.
- Kept: `signed.sig` (128 lowercase hex). It goes into `PlatformProof.proofSig` and into the verify string.
- 21003 is in the ephemeral range (20000–29999). The app never sends it to relays.

### 6.2 Kind 30078 — reputation event (owned by the reputation domain; fields contributed here)
- `d` = `einundzwanzig-reputation`, tags `[["d","einundzwanzig-reputation"],["v","2"],["client","einundzwanzig-meetup-app"]]`. The content is a plain `jsonEncode` (not canonical).
- Content keys from this domain:
  - `"lightning"`: `ZapStats.toJson()`, only if `totalCount > 0`. The stats come from `ZapVerificationService.getMyStats()` with a 10 s timeout (falling back to `ZapStats.empty()`).
  - `"humanity_proof"`: §3.9, only if verified.
  - `"platform_proofs"`: `{ "<platform>": {"username","proof_sig","created_at"} }`, only if the caller passed a non-empty map. Only `PlatformProofScreen` and `ReputationQrScreen` pass it (§14, R4).
- Reading for verification: `ReputationPublisher.fetchByNpub(npub)`:
  - Decode the npub; on failure return null.
  - Visit **every** active relay **sequentially**, with connect timeout 8 s and response timeout 8 s per relay.
  - Filter `{"kinds":[30078],"authors":[hex],"#d":["einundzwanzig-reputation"],"limit":1}`, sub id `rep-fetch-<16hex>`.
  - The **first** event that passes `Event.isValid()` and has `pubkey == hex` wins; it is not necessarily the newest (§14, R11).
  - Content parsed into `ReputationEvent`. `platformProofs` = `content.platform_proofs` as a raw map.

### 6.3 Kind 9735 zap receipts / kind 9734 zap requests (NIP-57)

**Humanity search**, one socket per relay, three REQs sent back-to-back right after subscribing:
```
["REQ","zap-recv-<16hex>",{"kinds":[9735],"#p":["<me>"],"limit":3}]
["REQ","zap-req-<16hex>",{"kinds":[9734],"authors":["<me>"],"limit":3}]
["REQ","zap-sent-<16hex>",{"kinds":[9735],"#P":["<me>"],"limit":3}]
```
- Completion: after **3 EOSE messages** (any subscription), on socket error or close, or after **12 s**. Then the socket is closed. No CLOSE frames are sent.
- Events: `verifiedEvent` must pass (no `answersFilter` check here). Only `kind == 9735` is processed. **Kind 9734 events are ignored** (self-signed requests prove nothing); the 9734 REQ survives only so the 3-EOSE count works. The native port may drop that REQ if it waits for 2 EOSEs instead.

**Zap statistics**, one socket per relay:
```
["REQ","zaps-<16hex>",{"kinds":[9735],"since":<now-180d>,"limit":100,"#p":["<pk>"]}]   // received
["REQ","zaps-<16hex>",{"kinds":[9735],"since":<now-180d>,"limit":100,"#P":["<pk>"]}]   // sent
```
- Key order in the filter map: `kinds, since, limit, #p|#P`.
- **Never put `#p` and `#P` in one filter.** `answersFilter` would then require both tags.
- Events must pass `verifiedEvent` **and** `answersFilter(event, sameFilterMap)`.
- Completion: on EOSE, on close (collected events), on error (returns `[]`, **discarding collected events**), or after an 8 s timeout (collected events).

**Receipt existence** (`verifyReceiptExists`, currently unused):
```
["REQ","verify-<16hex>",{"ids":["<id>"],"kinds":[9735],"limit":1}]
```
True on a verified event with `id == eventId && kind == 9735`; false on EOSE, error, close or the 8 s timeout. Active relays `take(3)`, sequential.

### 6.4 Kind 0 — NIP-05 lookup (`fetchNip05FromProfile`)
- Relays: the caller's list (`RelayConfig.getActiveRelays()`), **first 2 only**, sequential.
- Per relay: connect timeout 5 s, then `["REQ","nip05-<16hex>",{"kinds":[0],"authors":["<pk>"],"limit":1}]`.
  - Each event must pass `verifiedEvent` and `answersFilter`, and `replaceableCreatedAt(event)` must be non-null (an int ≥ 0 and ≤ now + 600 s).
  - Skip an event if `at < best.at` (strictly). Equal timestamps replace the earlier one.
  - The `nip05` value is parsed from the JSON content: `profile['nip05'] as String?`. Content that is not JSON, or a value that is not a string, gives `null`. That newer profile still counts as the newest and so **erases** an older NIP-05.
  - Complete with the best hit on EOSE, error or close, or after 5 s.
- Across relays: a hit replaces the best only if its timestamp is **strictly greater**.
- Returns `best.nip05` if it is not empty, otherwise null.

### 6.5 Relays used

- Active relays (`RelayConfig.getActiveRelays()`): defaults `wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.nostr.band`, `wss://nostr.einundzwanzig.space`, minus disabled defaults, plus custom relays, de-duplicated in insertion order.
- `checkForZaps`: LinkedHashSet order = active relays, then `wss://relay.damus.io`, `wss://relay.nostr.band`, `wss://nos.lol`, `wss://relay.snort.social`, `wss://relay.primal.net`, `wss://nostr.wine`.
- `checkForZapsByPubkey` (unused): `activeRelays.take(2)` + `wss://relay.damus.io`, `wss://relay.nostr.band`, `wss://nos.lol`.
- Zap stats: `activeRelays.take(3)`, sequential, stopping early once ≥ 50 receipts are collected.
- NIP-05: `activeRelays.take(2)`.
- Lightning address: see §5.5.

### 6.6 Signature and event rules (core Nostr domain; behaviour this domain relies on)

`RelaySocket.verifiedEvent(raw)` returns the raw map only if all of these hold:
- `raw` is a JSON object.
- An `Event` built from `id`, `pubkey` (lowercased for hashing), `created_at` (must be an **int**), `kind` (int), tags (each element converted to a String with `toString()`), `content` and `sig` passes `isValid()`:
  - `created_at.toString().length == 10`
  - `id == sha256(json([0, pubkey.lower, created_at, kind, tags, content]))` in **lowercase hex**, compared exactly
  - BIP-340 `verify(pubkey, id, sig)`

Anything else, including thrown exceptions, returns null and increments a rejection counter that is logged sparingly.

`answersFilter(event, filter)`:
- If `kinds` is set, it must contain `event.kind`.
- If `authors` is set, it must contain `event.pubkey`.
- If `since` or `until` is set, `created_at` must be an int within the bounds.
- For every `#x` key: the list must be non-empty and the event must have a tag `[x, v, ...]` with `v` in the list.
- `limit` and `ids` are ignored.

Frame limit: a message longer than `512 * 1024` chars (or bytes) is dropped before parsing.

Subscription ids: prefix + 16 lowercase hex chars from 8 bytes of a CSPRNG (`Random.secure()`). Prefixes: `zap-recv-`, `zap-req-`, `zap-sent-`, `zaps-`, `verify-`, `nip05-`. Use `SecureRandom` in native.

Parse failures are counted per subscription (`RelayParseTally`) and logged once at warn level with the first error at debug level. Mirror this with the native logger.

---

## 7. Crypto and encoding

### 7.1 Verify string (`21rep` v2)
Built in `createProof`:
```dart
final verifyString = '21rep::$npub::$platform::$trimmedUsername::sig=${signed.sig}';
```
- `npub` = `SigningService.npub()` (bech32 `npub1…` of the active identity).
- `sig` = 128 hex characters.

Parse (`parseVerifyString`), verbatim logic:
```dart
final trimmed = input.trim();
if (!trimmed.startsWith('21rep::')) return null;
final parts = trimmed.split('::');
if (parts.length < 5) return null;          // extra parts beyond index 4 are IGNORED
final npub = parts[1]; final platform = parts[2]; final username = parts[3]; final sigPart = parts[4];
if (!npub.startsWith('npub1')) return null;
if (!sigPart.startsWith('sig=')) return null;
final sig = sigPart.substring(4);
if (sig.length != 128) return null;
```
Kotlin: `trimmed.split("::")` (literal split, keeps empty parts, exactly like Dart). Dart `trim()` removes Unicode whitespace; Kotlin `trim()` matches closely enough.

"Signature" check (`_verifySignature`) is **format only, not cryptographic**:
- `pubkeyHex = Nip19.decodePubkey(npub)` succeeds: bech32 with max length 90, checksum constant 1, hrp must be `npub`, 5→8-bit conversion without padding, invalid padding throws. It yields a non-empty hex string, with no 32-byte length check.
- `sig.length == 128`.
- `BigInt.parse(sig, radix: 16)` succeeds. Dart accepts an optional leading `+` or `-`.

Native: implement as `Nip19.decodeNpub(npub) != null && sig.length == 128 && sig.matches(Regex("^[+-]?[0-9a-fA-F]+$"))`. Real Schnorr verification is impossible because the signer's `created_at` and the content's `created_at` are unknown (see §14, R2 for an optional improvement).

### 7.2 Canonical JSON (for the 21003 content)
`canonicalJsonEncode` sorts **only top-level** keys with Dart `List.sort()` (UTF-16 code-unit order) and then calls `jsonEncode`. All values here are flat, so this is enough.

Kotlin: build a `JsonObject` with keys in sorted order and serialise with a Dart-compatible JSON writer. The core Nostr module's NIP-01 serializer must emit exactly the same escaping. Signature compatibility only matters to the signer; nobody re-verifies this content (§7.1).

### 7.3 Event id (core)
`id = lowercase_hex(SHA-256(UTF-8(jsonEncode([0, pubkeyLower, created_at, kind, tags, content]))))`.

### 7.4 Bolt11 description hash (`descriptionHashOf`) — byte-exact
Verbatim Dart:
```dart
static String? descriptionHashOf(String bolt11) {
  try {
    final decoded = Bech32Decoder().convert(bolt11.trim(), 4096);
    if (!decoded.hrp.toLowerCase().startsWith('ln')) return null;
    final data = decoded.data;
    // 7 Gruppen Timestamp + Tagged Fields + 104 Gruppen Signatur
    const sigWords = 104;
    if (data.length < 7 + sigWords) return null;
    var i = 7;
    final end = data.length - sigWords;
    while (i + 3 <= end) {
      final type = data[i];
      final len = (data[i + 1] << 5) | data[i + 2];
      i += 3;
      if (i + len > end) return null;
      if (type == 23 && len == 52) {
        final bytes = _convertBits(data.sublist(i, i + len), 5, 8);
        if (bytes.length < 32) return null;
        return bytes.sublist(0, 32).map((b) => b.toRadixString(16).padLeft(2, '0')).join();
      }
      i += len;
    }
    return null;
  } catch (_) {
    return null;
  }
}
static List<int> _convertBits(List<int> input, int from, int to) {
  var acc = 0; var bits = 0; final out = <int>[]; final maxv = (1 << to) - 1;
  for (final v in input) {
    acc = (acc << from) | v; bits += from;
    while (bits >= to) { bits -= to; out.add((acc >> bits) & maxv); }
  }
  return out;   // no padding, leftover bits dropped
}
```

Bech32 decoder semantics (package `bech32` 0.2.2), which the native decoder must reproduce:
- Throws if `input.length > maxLength` (4096 here, 90 for npub).
- Throws on mixed case (`lower != input && upper != input`).
- Throws if there is no `'1'`. The separator is the **last** `'1'`.
- Throws if fewer than 6 chars follow the separator, or if the separator is at index 0.
- The input is lowercased.
- Every hrp char must be in 33..126, and every data and checksum char must be in `qpzry9x8gf2tvdw0s3jn54khce6mua7l`.
- The polymod over `hrpExpand(hrp) + data + checksum` must equal **1** (bech32, not bech32m).
- The returned data excludes the 6 checksum words. The hrp is returned lowercased.

Kotlin reference:
```kotlin
fun descriptionHashOf(bolt11: String): String? = runCatching {
    val (hrp, data) = Bech32.decode(bolt11.trim(), maxLength = 4096)   // IntArray of 5-bit words
    if (!hrp.lowercase().startsWith("ln")) return null
    val sigWords = 104
    if (data.size < 7 + sigWords) return null
    var i = 7
    val end = data.size - sigWords
    while (i + 3 <= end) {
        val type = data[i]
        val len = (data[i + 1] shl 5) or data[i + 2]
        i += 3
        if (i + len > end) return null
        if (type == 23 && len == 52) {
            val bytes = convertBitsNoPad(data, i, i + len, 5, 8)      // mask acc to avoid overflow; same output
            if (bytes.size < 32) return null
            return bytes.copyOf(32).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        i += len
    }
    null
}.getOrNull()
```
- The first `h` field with `len == 52` wins. An `h` with any other length is skipped.
- The amount, payee, signature and expiry are **not** checked.

### 7.5 Zap request ↔ invoice binding
`expected = lowercase_hex(SHA-256(UTF-8(descriptionTagValue)))`, where `descriptionTagValue` is the exact string value of the receipt's first `description` tag, as decoded from the relay JSON, with no re-serialisation. Must equal `descriptionHashOf(bolt11)`.

### 7.6 Supabase query encoding
See §5.3, Dart `Uri.encodeQueryComponent` (RFC 3986 unreserved set; space becomes `+`).

---

## 8. Business rules and algorithms

### 8.1 Create platform proof (`createProof(platformId, username, customPlatformName?)`)
1. `npub = SigningService.npub()`. If `npub == null || !SigningService.canSign()`, fail with "Kein Schlüssel vorhanden".
2. `trimmedUsername = username.trim()`. If it is empty, fail with "Benutzername darf nicht leer sein".
3. `platform = customPlatformName?.toLowerCase().replaceAll(' ', '_') ?? platformId`.
   - Only U+0020 is replaced; `::`, `@`, umlauts and the rest are kept.
   - Use `lowercase(Locale.ROOT)`.
   - The screen passes the custom name already trimmed, and only for `other`.
4. `now = epochSeconds()`.
5. Sign kind 21003 (§6.1).
6. Build the verify string (§7.1).
7. Save `PlatformProof(platform, trimmedUsername, sig, now)`: load the list, remove every entry with an equal `platform`, append the new one and write it back.
8. Return success. Any exception, including a signer rejection or timeout, fails with "Fehler: $e".

### 8.2 Verify a verify string or npub (`verifyProofString(input, expectedUsername?)`)

1. `parsed = parseVerifyString(input)`.
   - If null and `input.trim().startsWith("npub1")`, run **npub mode**:
     1. `rep = fetchByNpub(trimmed)`.
     2. If null: `signatureOnly`, npub set, "Keine Reputation … für diesen npub."
     3. Otherwise: `partial` if `rep.boundBadges > 0`, else `signatureOnly`. `proofInEvent = false`. Messages per §2.6.
   - If null in any other case: `invalid`, format message.
2. Format check (§7.1) fails → `invalid`, "WARNUNG: Signatur ungültig! …"
3. If `expectedUsername` is not empty and `parsed.username.lowercase() != expectedUsername.lowercase()` → `invalid`, with npub, platform and claimedUsername set, mismatch message.
4. `rep = fetchByNpub(parsed.npub)`. If null → `signatureOnly`, "Signatur gültig ✓ — Aber keine Reputation auf Relays gefunden."
5. `proofInEvent = rep.platformProofs[parsed.platform] is Map && that map["username"] == parsed.username`. The username comparison is **exact and case-sensitive**.
6. `hasBindings = rep.boundBadges > 0`; `allBound = rep.boundBadges == rep.totalBadges && rep.totalBadges > 0`.
7. Decision table:

| proofInEvent | hasBindings | allBound | level | message |
|---|---|---|---|---|
| true | true | true | `full` | "Identität und Reputation vollständig bestätigt ✓" |
| true | true | false | `partial` | "Reputation teilweise bestätigt — B von T Badges gebunden." |
| false | true | – | `partial` | "Reputation verifiziert, aber Plattform-Proof nicht im Event gefunden." |
| – | false | – | `signatureOnly` | "Signatur gültig, aber keine gebundenen Badges." |

8. Any exception → `invalid`, "Verifikations-Fehler: $e".

### 8.3 Proof of Humanity

**`getStatus()`**
- If `humanity_needs_reverification == true`: return `verified=false`, `needsReverification=true`, and the other fields from prefs.
- Otherwise: `verified = humanity_verified ?? false`, and the other fields from prefs (defaults 0 or "").

**`checkForZaps()`**
1. `npub = SigningService.npub()`. If it is null or empty, return not found with "Kein Schlüssel vorhanden".
2. `pubkeyHex = Nip19.decodePubkey(npub)`. If that throws, return not found with "Ungültiger npub". A non-npub hrp returns `""` without throwing, and the search then runs with `""`.
3. For each relay in the order from §6.5, **sequentially**. Errors on one relay skip to the next.
   1. Connect with a timeout of `RelayConfig.relayTimeout` = 8 s.
   2. Send the three REQs (§6.3). Collect candidates until 3 EOSEs or 12 s.
   3. For each kind-9735 event that passes `verifiedEvent`, run `ZapReceiptValidator.checkLocal(event)`.
      - Not ok: count it as `zap_receipt:<reason>` in the tally and skip.
      - `mine = check.recipientPubkey == pubkeyHex || check.senderPubkey == pubkeyHex` (exact string compare). **Received zaps count as well as sent ones.**
      - If mine and `candidates.size < 6`: add `(eventId, created_at, receiptPubkey, recipientPubkey, senderPubkey)`.
   4. Close the socket. Then for each candidate **in arrival order**: if `isFromRecipientProvider(receiptPubkey, recipientPubkey, senderPubkey)`, it is the result. Otherwise log at debug level and continue.
   5. If there is a result: `_saveProof(receiptEventId, zapTimestamp)`, which sets `humanity_verified=true`, `humanity_receipt_id`, `humanity_first_zap_at = zapTimestamp` and `humanity_checked_at = now`. Return found with "Lightning-Zahlung gefunden — du bist verifiziert!" **Stop.**
4. Nothing found: set `humanity_checked_at = now` and return not found with the "Kein Zap-Receipt gefunden …" message.
   - `humanity_verified` is **not** reset. A previously verified user stays verified.
   - `humanity_needs_reverification` is **not** cleared by `checkForZaps` (§14, R6).

Worst-case duration: N relays × (8 s connect + 12 s wait + up to 6 provider look-ups, each 6 s lightning address lookup + 6 s HTTP). Keep the work off the main thread and cancellable. Querying relays in parallel is allowed if "first provider-confirmed candidate in relay order" is kept, or if any confirmed candidate is accepted: the result content is local only.

**`reverifyIfNeeded()`** (called from home `_loadIdentityData` at start when `getStatus().needsReverification`):
- If the flag is not true, return false.
- Run `checkForZaps()`.
  - Found: set `humanity_verified=true`, remove `humanity_needs_reverification`, return true.
  - Not found or exception: return false. The flag stays; it is retried at the next start.

**`reset()`:** removes `humanity_verified`, `humanity_receipt_id`, `humanity_first_zap_at` and `humanity_checked_at`. It is unused in the UI.

**`checkForZapsByPubkey(pubkeyHex)`:** the same search over the reduced relay list (§6.5); returns true on the first confirmed candidate. Unused.

**`verifyReceiptExists(id)`:** §6.3. Unused. Port both for API completeness or drop them (they are not on the wire).

### 8.4 Zap receipt validation (`ZapReceiptValidator`)

**`checkLocal(receipt)`**. Order matters, because the tests assert the reason codes:
1. `receipt.kind != 9735` (must be the int 9735) → `kind`
2. `verifiedEvent(receipt) == null` → `receipt_sig`
3. Scan tags. Skip any tag that is not a list or has fewer than 2 elements. Take `key = tag[0].toString()` and `val = tag[1].toString()`. Keep the **first** `p`, the first `bolt11` and the first `description`. If any of them is missing or empty → `tags`.
4. `jsonDecode(description)` must be a JSON object → else `description_json`
5. `zapReq.kind != 9734` → `request_kind`
6. `verifiedEvent(zapReq) == null` → `request_sig`
7. `reqP` = the first tag in `zapReq.tags` with `tag[0] == "p"` and length ≥ 2, taking `tag[1].toString()`. If it is empty or `!= p` → `recipient_mismatch`
8. `h = descriptionHashOf(bolt11)`. If null → `bolt11`
9. `h != sha256hex(utf8(description))` → `description_hash`
10. Otherwise ok, with `recipientPubkey = p`, `senderPubkey = zapReq.pubkey.toString()` (`""` if null) and `receiptPubkey = receipt.pubkey.toString()`.

Not checked: the `amount` tag against the invoice amount, the `lnurl` tag, or the invoice signature and payee.

**`isFromRecipientProvider(receiptPubkey, recipientPubkey, senderPubkey = "")`**
1. Either of the first two empty → false.
2. Lowercase all three. `receipt == recipient` → false (self-issued). If the sender is not empty and `receipt == sender` → false.
3. `provider = providerPubkeyFor(recipientPubkey)`. Null or empty → false (logged at debug level using `recipientPubkey.substring(0, 8)`; in Dart this throws for keys shorter than 8 chars, so native must guard it and return false).
4. Return `provider.lowercase() == receiptPubkey.lowercase()`.

**`providerPubkeyFor(recipientPubkey)`**
1. If the memory cache has an entry, return it (an empty entry means null, but empty entries are never stored).
2. Pref `zap_provider_pubkey_<recipient>`: parse `{pk, ts}`. If `pk` is not empty and `now_ms - ts < 7 days`, store it in memory and return it.
3. Otherwise `_resolveProvider`:
   - `lud16 = fetchLightningAddress(recipient)`.
   - It must contain `@` and split into exactly 2 parts. `user` = part 0 trimmed, `domain` = part 1 trimmed and lowercased. Both must be non-empty and the domain must match `^[a-z0-9.-]+$`.
   - Call `providerPubkeyFromLnurlp("https://domain/.well-known/lnurlp/user")`.
4. If the result is empty or there was an exception, return null and **do not cache**. Otherwise store it in memory and in prefs (`{"pk":pk,"ts":now_ms}`; write errors are ignored) and return it.

The memory cache key is the recipient pubkey **as given**, without lowercasing.

### 8.5 Zap statistics (`ZapVerificationService`)

**`analyzeZapActivity(pubkeyHex, useCache = true)`**
1. If `useCache`, `loadCachedStats(pubkeyHex)` (6 h TTL on `ts`, JSON parse; errors give null). On a hit, return it.
2. `relays = getActiveRelays()`. Start `fetch(received)` and `fetch(sent)` **concurrently** and await both.
3. `stats = computeStats(...)`.
4. Write the cache (always; §4). Return `stats`.

**`fetch(isReceived)`**
1. `since = (now - 180 days)` in seconds, computed with `DateTime.now().subtract(Duration(days:180))`.
2. For each relay in `relays.take(3)`, sequentially:
   1. `receipts = fetchFromRelay(relay, pk, since, sent = !isReceived)`.
   2. Keep a receipt only if `isReceived ? r.isReceived : r.isSent`, and de-duplicate by `eventId` (first one wins) into `all`.
   3. If `all.size >= 50`, break.
   4. Exceptions are logged at debug level and skipped.
3. Return `onlyFromProviders(all)`.

**`fetchFromRelay`** parses each accepted event with `parseZapReceipt(event, pk)`:
- `checkLocal` must be ok.
- `isSent = senderPubkey == pk`; `isReceived = recipientPubkey == pk`.
- If both are false → null.
- Otherwise `ZapReceipt(..., hasBolt11 = true, receiptPubkey)`.

**`onlyFromProviders(receipts)`**
1. Group by `recipientPubkey` in a **LinkedHashMap** (insertion order).
2. Process at most **15 groups**. `if (lookups++ >= 15) break;` means groups 1–15 are processed and groups 16+ are **dropped**, not kept.
3. For each processed group: `provider = providerPubkeyFor(recipient)`. If null, drop the whole group.
4. Otherwise keep each receipt where `receiptPubkey.lowercase() == provider`, `receipt != senderPubkey.lowercase()` and `receipt != recipientPubkey.lowercase()`.
5. Log the number of dropped receipts at debug level.

**`computeStats(pk, received, sent)`**
- `all` = LinkedHashMap by `eventId` over `received + sent` (later entries overwrite).
- `sentZaps = all.filter { isSent }`; `receivedZaps = all.filter { isReceived }`. A self-zap where sender = recipient = pk counts in both.
- `uniqueRecipientCount = sentZaps.map { recipientPubkey }.toSet().size`.
- `uniqueSenderCount = receivedZaps.map { senderPubkey }.filter { it.isNotEmpty() }.toSet().size`.
- `first` and `last` = min and max of `createdAt` over `all`, considering only `createdAt > 0`.
- `activeMonths = if (first != null && last != null) ceil((last - first) / 2_592_000.0).toInt().coerceIn(0, 24) else 0`. The divisor is 30 days in seconds; the division is floating point.
- `hasLightningProof = all.any { hasBolt11 }`, which equals `all.isNotEmpty()`.

**`lightningScore`**, constants exact:
```dart
double score = 0;
if (hasLightningProof) score += 0.5;
if (sentCount > 0) score += (sentCount / (sentCount + 10)) * 0.75;
if (receivedCount > 0) score += (receivedCount / (receivedCount + 10)) * 0.75;
if (uniqueRecipientCount > 2) score += 0.25;
if (activeMonths >= 3) score += 0.25;
return score.clamp(0.0, 2.5);
```

**`activityLabel`:** total 0 → none; < 5 → little; < 20 → regular; otherwise very active (§2.6).

**`getMyStats()`:** `npub = NostrService.getNpub()` (which is `SigningService.npub()`).
- Null or empty → `ZapStats.empty()`.
- Decode error → warn and return empty.
- Otherwise `analyzeZapActivity(hex)` with the cache.

### 8.6 NIP-05

**`verify(nip05, expectedPubkeyHex)`**
1. Empty, or no `@` → `valid=false`.
2. `split('@')` must have exactly 2 parts, otherwise `valid=false`.
3. `name = parts[0].lowercase()`, `domain = parts[1].lowercase()`.
4. HTTP per §5.1.
5. Domain classification, evaluated in this order, applied whenever the JSON parsed and `names` exists:
   - `community`: `domain == d || domain.endsWith(".$d")` for `d` in `einundzwanzig.space`, `einundzwanzig.ch`, `bitcoin.de`, `nostr.com`
   - `publicProvider`: the same rule for `nostrplebs.com`, `nostr.wine`, `iris.to`, `snort.social`, `damus.io`
   - `custom`: anything else
6. `_@domain` is handled like any other name. A bare `domain` without `@` is invalid; NIP-05 would allow it as `_@domain`, but the app does not.

**`score(result)`:** not valid → 0.0; community → 1.0; custom → 0.7; publicProvider → 0.3; unknown → 0.2 (unreachable when valid).

**`fetchNip05FromProfile`:** §6.4.

### 8.7 SatoshiDuell categorisation (`fetchStatus`)
`me = username.lowercase()`.

**Mine** (request 2). For each map `g` whose `status` is `open` or `active` (anything else, such as finished, is skipped):
- `isCreator = g.creator.toString().lowercase() == me`
- `myScore = if (isCreator) g.creator_score else g.challenger_score`. JSON null and a missing key are treated the same.
- If `myScore == null`:
  - `status == "open" && isCreator` → `waiting++` (my own open game, no opponent yet)
  - otherwise → `myTurn++` (this includes games where I am only `target_player`)
- Otherwise → `waiting++`

**Lobby** (request 3). For each `g`:
- `creator = g.creator.lowercase()`; if `creator == me`, skip it.
- `target = g.target_player?.toString()?.lowercase()`.
- If `target == null || target.isEmpty() || target == me` → `lobby++`.

A targeted challenge to me therefore counts in **both** `myTurn` and `lobby` (parity; see §14, R16).

**Badge count** = `myTurn + lobby` (`openDuelCount()`, unused, has the same value).

Known simplification: arena games where the user is only in `participants` are not detected.

---

## 9. Security measures to preserve

| Ref | Measure | Where |
|---|---|---|
| Audit H3 | Zap receipts only count if: (1) the receipt signature is valid; (2) `description` is a validly signed kind-9734 request; (3) the receipt `p` equals the request `p`; (4) the bolt11 `h` equals sha256(description); (5) the receipt pubkey equals the LNURL `nostrPubkey` of the recipient's **current** lud16. Self-issued receipts (issuer == recipient or issuer == sender) are rejected. The sender is taken from the **signed** zap request, never from the unsigned `P` tag. | `ZapReceiptValidator`, Humanity, ZapVerification |
| Audit H3 | Kind 9734 events found on relays are **not** proof (anyone can create them for free). | Humanity search |
| Audit H2/M1 | Every relay event goes through the central id and signature check. Query-scoped reads also use `answersFilter` with the **same** filter map that was sent. | all relay reads |
| Audit M4 | Subscription ids come from a CSPRNG. | all REQs |
| Audit M4 | HTTP body limit of 64 KiB for nostr.json and lnurlp; relay frame limit of 512 KiB. | NIP-05, LNURL, sockets |
| – | lud16 domain must match `^[a-z0-9.-]+$` (prevents URL injection); `nostrPubkey` must match `^[0-9a-f]{64}$`; `allowsNostr` must be literally `true`. | `_resolveProvider`, `providerPubkeyFromLnurlp` |
| – | Failed provider look-ups are never cached, so a transient error cannot hide receipts for 7 days. Only positive results are cached, with a 7-day TTL. | `providerPubkeyFor` |
| – | NIP-05 and LNURL bodies are decoded as UTF-8 regardless of `Content-Type` charset. | §5 |
| – | Kind-0 replaceable handling: the newest profile wins even if it removes `nip05`. Timestamps more than 10 minutes in the future are ignored. | NIP-05 lookup, lud16 lookup |
| Audit 2, finding #4 | A humanity proof from a backup is **never** trusted directly. It is restored as `needs_reverification` and re-verified against relays at the next start. | Backup restore, `getStatus`, `reverifyIfNeeded` |
| Audit 2, finding #4 | Platform proofs from a backup are accepted only with a 128-hex-char `proof_sig`. | Backup restore |
| – | On identity switch, the old identity's proofs are wiped before the backup's proofs are written. | `clearIdentityProofs` |
| – | Platform-proof username and platform are inside the signed event tags and content, so they are not transferable. Verifiers check the platform proof against the npub's own signed kind-30078 event (`proofInEvent`). That is the real binding; the verify-string "signature" check is format only. | §8.2 |
| – | Privacy: the reputation event never contains zap amounts, counterparts, invoices or receipt ids. Only counts plus `{verified, method, first_zap_at}` are published. | `getProofForPublishing`, `ZapStats.toJson` |
| – | The SatoshiDuell key is the public publishable key under RLS. PostgREST `or()` values are wrapped in double quotes with embedded quotes stripped, and quoting is **only** used inside `or()`. Single-column filters (`eq.`/`neq.`) take a literal value, so the lobby filtering is done on the client instead. | §5.3 |
| – | SatoshiDuell failures never show errors (the badge simply disappears). | §5.3 |

---

## 10. Tests and vectors to port

The relevant test files are `test/zap_receipt_validator_test.dart`, `test/zap_sent_filter_test.dart` and `test/security_followup_test.dart` (one case). Two more touch this domain's data and belong to other domains: `test/backup_restore_decision_test.dart` (one case) and `test/badge_sync_suspend_test.dart` (two cases). There are no fixtures in `test/fixtures` for this domain; `nip44.vectors.json` belongs to the crypto domain.

### 10.1 `zap_receipt_validator_test.dart` (JVM unit tests with secp256k1-kmp-jni-jvm)

Helpers to port:
- `toWords(bytes)`: 8→5-bit conversion **with** padding.
- `bolt11WithHash(hashHex)` builds the data words `[1,1,1,1,1,1,1, 23, (52>>5)&31=1, 52&31=20, ...52 hash words..., ...104 zeros]` and encodes them as bech32 with hrp `lnbc210n` and max length 4096.
- `zapRequest(sender, recipient)` = a signed kind-9734 event with tags `[["p",recipient],["relays","wss://x"]]` and content `""`, serialised with `jsonEncode` of `{id,pubkey,created_at,kind,tags,content,sig}` in that key order.
- `receipt(issuer, recipient, description, bolt11?)` = a signed kind-9735 event with tags `[["p",recipient],["bolt11",invoice],["description",description]]` and content `""`. The default invoice uses `sha256(description)`.

Keys: `attacker`, `victim` and `provider` are random keypairs.

| # | Test | Input | Expected |
|---|---|---|---|
| 1 | Correctly built receipt passes | issuer=provider, recipient=victim, description=zapRequest(attacker→victim) | `ok == true`; `recipientPubkey == victim`; `senderPubkey == attacker`; `receiptPubkey == provider` |
| 2 | bolt11 does not belong to the request | issuer=attacker, bolt11WithHash("0"*64) | `ok == false`, `reason == "description_hash"` |
| 3 | Receipt without bolt11 or description | kind 9735 tags `[["p",victim]]`, signed by attacker | `reason == "tags"` |
| 4 | Unsigned zap request | request `sig = "ff"*64` | `reason == "request_sig"` |
| 5 | Recipient mismatch | receipt p=victim, request p=attacker | `reason == "recipient_mismatch"` |
| 6 | Broken receipt signature | valid receipt, then `sig = "aa"*64` | `reason == "receipt_sig"` |
| 7 | `descriptionHashOf` | `bolt11WithHash("ab"*32)` | `"ab"*32`; `descriptionHashOf("lnbc1kaputt")` → `null` |

### 10.2 `zap_sent_filter_test.dart`

`me="aa"`, `other="bb"`, `since=1700000000`. Event = `{kind:9735, pubkey:"provider", created_at: since+10, tags}`.

| # | Test | Expected |
|---|---|---|
| 1 | Sent queries use `#P`, received queries use `#p` | `receiptFilter(me, since, sent=true)["#P"] == ["aa"]` and it has no `#p` key; `receiptFilter(..., sent=false)["#p"] == ["aa"]` and it has no `#P` key |
| 2 | Received query keeps receipts without a `P` tag | event tags `[["p","aa"]]`: `answersFilter(event, received) == true`, `answersFilter(event, sent) == false` |
| 3 | Sent filter matches the sender, not the recipient | tags `[["p","bb"],["P","aa"]]`: `answersFilter(sent) == true`, `answersFilter(received) == false` |

### 10.3 `security_followup_test.dart` — "Quittung vom eigenen Schlüssel zählt nicht als Provider"
`self = "aa"*32`.
- `isFromRecipientProvider(receipt=self, recipient=self)` → false.
- `isFromRecipientProvider(receipt=self, recipient="bb"*32, sender=self)` → false.
- Both return before any network call. Assert with a fake provider resolver that throws if called.

### 10.4 `backup_restore_decision_test.dart` — "Plattform-Proofs: das Gerät behält denselben Namen" (backup domain)
```
unionPlatformProofs(
  device   = [{platform:"telegram", username:"Ada", proof_sig:"device"}],
  incoming = [{platform:"telegram", username:"ada", proof_sig:"backup"},
              {platform:"x", username:"ada", proof_sig:"extra"}])
→ length 2; telegram entry has proof_sig "device".
```

### 10.5 `badge_sync_suspend_test.dart` (badge-sync domain, touches this domain's keys)
- "Wechsel räumt Nachweise der alten Identität": with `humanity_verified=true` and `platform_proofs='[{"platform":"a"}]'`, after an identity-switch settle: `humanity_verified == false`, `platform_proofs == null`, and `badge_restore_proofs` is not true.
- "schon geschriebene Nachweise der neuen Identität bleiben": proofs written after `clearIdentityProofs()` (`humanity_verified=true`, `platform_proofs='[{"platform":"b"}]'`) survive the settle unchanged.

### 10.6 New tests recommended for the native port (vectors derived from the Dart logic)

**Verify-string parse.** Use `S = "a".repeat(128)`.
- `"21rep::npub1x::telegram::alice::sig=$S"` → parsed `(npub1x, telegram, alice, S)`. The subsequent format check fails because the npub checksum is invalid, giving `invalid` with the "Signatur ungültig" message.
- `"  21rep::npub1x::t::u::sig=$S  "` → parsed (trimmed).
- `"21rep::npub1x::t::u::sig=$S::extra"` → parsed (extra parts ignored).
- `"21rep::npub1x::t::u"` → null (4 parts).
- `"21rep::nsec1x::t::u::sig=$S"` → null.
- `"21rep::npub1x::t::u::$S"` → null (no `sig=`).
- `"21rep::npub1x::t::u::sig=${"a".repeat(127)}"` → null.
- `"21rep::npub1x::t::a::b::sig=$S"` → null (a username containing `::` breaks the parse).
- `"npub1…valid"` → npub mode.

**Custom platform id.**
- `"My Market"` → `my_market`
- `"Bisq  P2P"` → `bisq__p2p`
- `"ÄRGER"` → `ärger`

**`lightningScore`:**
- `empty` → `0.0`
- `(sent=1, recv=0, uniqRec=1, months=0, proof=true)` → `0.5 + 0.75/11 = 0.5681818181818182`
- `(10, 10, 3, 3, true)` → `1.75`
- `(90, 90, 50, 24, true)` → `2.35`
- `(0, 5, 0, 0, true)` → `0.5 + 0.25 = 0.75`
- `(3, 0, 3, 2, true)` → `0.5 + 0.75*3/13 + 0.25 = 0.9230769230769231`

**`activeMonths`:**
- `last - first = 0` → 0
- `= 2_592_000` → 1
- `= 2_592_001` → 2
- `= 100 * 2_592_000` → 24 (clamped)

**`activityLabel`:** total 0, 4, 5, 19, 20 → none, little, regular, regular, very active.

**NIP-05 classification:**
- `einundzwanzig.space` → community
- `meetup.einundzwanzig.space` → community
- `xeinundzwanzig.space` → custom
- `nostr.com` → community
- `iris.to` → public
- `sub.damus.io` → public
- `example.org` → custom

**NIP-05 score:** invalid → 0; community 1.0; custom 0.7; public 0.3.

**NIP-05 HTTP (MockWebServer):**
- non-200 → invalid
- body of 65 537 bytes → invalid
- `{"names":{"bob":"<hex>"}}` with input `Bob@Domain` → request path `/.well-known/nostr.json?name=bob`, valid
- pubkey mismatch (case differs) → invalid
- `names` missing → invalid with domain set
- latin1 `Content-Type` with UTF-8 body → still parsed as UTF-8

**LNURL provider:**
- `allowsNostr:"true"` (a string) → null
- `nostrPubkey` in uppercase hex → accepted and lowercased
- 63-char key → null
- 7-day cache honoured
- an empty `pk` in cache → re-resolved

**`descriptionHashOf`:**
- an uppercase copy of a valid invoice → same hash
- mixed case → null
- hrp `bc1…` (not `ln`) → null
- an `h` field with `len != 52` before a valid one → the second is returned
- data shorter than `7+104` → null

**`checkLocal` reason order:**
- `kind:"9735"` (a string) → `kind`
- `description = "not json"` → `description_json`
- description is valid JSON with `kind 1` → `request_kind`

**SatoshiDuell (MockWebServer)**, with username `Alice`:
- profiles → `[{"username":"Alice"}]`
- mine →
  - `{status:open, creator:alice, creator_score:null}` → waiting
  - `{status:active, creator:bob, challenger:alice, challenger_score:null}` → myTurn
  - `{status:active, creator:alice, creator_score:5}` → waiting
  - `{status:open, creator:bob, target_player:alice}` → myTurn
  - `{status:finished, …}` → ignored
- lobby →
  - `{creator:alice}` → skip
  - `{creator:bob, target_player:null}` → +1
  - `{creator:carl, target_player:""}` → +1
  - `{creator:dan, target_player:alice}` → +1
  - `{creator:eve, target_player:bob}` → skip
- Expected `DuellStatus(myTurn=2, waiting=2, lobby=3)`, badge 5.
- Assert the exact request URLs:
  - `.../profiles?npub=eq.npub1…&select=username&limit=1`
  - `.../duels?select=status,creator,challenger,target_player,creator_score,challenger_score&or=(creator.eq.%22alice%22,challenger.eq.%22alice%22,target_player.eq.%22alice%22)&order=created_at.desc&limit=50`
  - `.../duels?select=creator,target_player&status=eq.open&limit=100`
- Assert the headers.
- Profiles returns 500 → EMPTY, not cached. A second call within 2 min re-requests.
- A thrown error after a successful run → the cached value is returned.

**Encoder:** `enc("\"a b~*'\"")` → `%22a+b~%2A%27%22`.

**Humanity flow** (fake relays and fake resolver):
- A provider-confirmed receipt on the second relay → found; prefs `humanity_verified=true`, `receipt_id`, `first_zap_at = receipt.created_at`, `checked_at = now`.
- Only self-issued receipts → not found; `checked_at` set; `verified` unchanged.
- A kind-9734 event alone → not found.
- More than 6 valid candidates → only the first 6 are checked.
- `needs_reverification=true` → `getStatus().verified == false`; `reverifyIfNeeded()` with a hit → verified and flag removed.

**Platform proof storage:**
- Create telegram twice → one entry, the newest, moved to the end.
- `removeProof("telegram")` → gone.
- Malformed stored JSON → `getSavedProofs() == []`.

---

## 11. Android capabilities and libraries

- **Permission:** only `android.permission.INTERNET` (and `ACCESS_NETWORK_STATE` if the core module uses it). No runtime permissions.
- **HTTP:** OkHttp 5 (`libs.okhttp`) from the shared core network module. Use per-call timeouts (`call.timeout()` 5 s, 6 s or 8 s as in §5) and a streaming body read with a 64 KiB cap (`source.request(65537)`) instead of reading everything first.
- **WebSocket relays:** the core Nostr relay client (OkHttp WebSocket), with the 512 KiB frame limit, `verifiedEvent` and `answersFilter`.
- **Schnorr / BIP-340:** `fr.acinq.secp256k1:secp256k1-kmp-jni-android` (`libs.secp256k1.android`; `-jvm` for unit tests) via the core crypto module's `verifySchnorr(pubkey, msg32, sig)`.
- **SHA-256:** `java.security.MessageDigest`.
- **Bech32:** a hand-written, shared decoder in the core crypto module (bech32 constant 1, configurable max length 90 or 4096). The same decoder is used for npub (NIP-19).
- **JSON:** kotlinx.serialization `JsonElement` for dynamic relay, LNURL and PostgREST payloads, plus the core Dart-compatible JSON writer for NIP-01 serialisation.
- **Storage:** the shared `:core:data` `KeyValueStore` (Preferences DataStore `app_prefs`), keys per §4. Flutter migration lives in `:data:migration`.
- **Signing:** the core signing module (local key in Android Keystore-wrapped storage, Amber NIP-55 intents, NIP-46, NIP-07 is not applicable). Kind 21003 must be signable in every mode.
- **External browser:** `Intent(Intent.ACTION_VIEW, uri)` for satoshiduell.de. `androidx.browser` Custom Tabs are optional.
- **Background:** none required. If Humanity re-verification should survive process death, a `OneTimeWorkRequest` (WorkManager, network-constrained) on app start is optional; Flutter simply retried at the next start.
- **Concurrency:** coroutines on `@IoDispatcher`; Humanity and zap searches must be cancellable from the ViewModel.
- No camera, NFC, location, notifications, biometrics or Credential Manager in this domain.

---

## 12. Cross-domain dependencies

**This domain calls:**

| Domain / module | API used | Purpose |
|---|---|---|
| Signing (`SigningService`) | `npub()`, `canSign()`, `signEvent(kind=21003, tags, content)` | Platform proof creation; npub for the Humanity search, zap stats and SatoshiDuell |
| Nostr core (`RelaySocket`, `RelayConfig`, `Nip19`) | connect, REQ, `verifiedEvent`, `answersFilter`, `replaceableCreatedAt`, `getActiveRelays()`, `relayTimeout` (8 s), npub decode, sub-id RNG, `RelayParseTally` | All relay reads |
| Badge security (`BadgeSecurity.canonicalJsonEncode`) | canonical JSON | Kind-21003 content |
| Reputation (`ReputationPublisher`) | `publish(badges, platformProofs, force)`, `publishInBackground(badges)`, `fetchByNpub(npub)` → `ReputationEvent{boundBadges, totalBadges, platformProofs}` | Republish after create or revoke; verification of other users |
| Badges model (`myBadges`) | current badge list | Passed to `publish` |
| News (`NewsZapService.fetchLightningAddress`, `NewsReactionsService.readTargets`) | lud16 of a pubkey | LNURL provider resolution |
| Logging (`AppLogger`) | debug, warn, diag | Diagnostics |

**Other domains call this one:**

| Caller | API | Notes |
|---|---|---|
| Reputation publisher | `ZapVerificationService.getMyStats()`, `HumanityProofService.getProofForPublishing()`, `ZapStats.toJson/fromJson`, `PlatformProof.toJson/fromJson` | Kind-30078 content |
| Reputation verify screen, QR scanner result | `PlatformProofService.verifyProofString`, `ZapVerificationService.analyzeZapActivity(useCache=false)`, `Nip05Service.fetchNip05FromProfile` + `verify` | Other users |
| Reputation layers widget / trust | `ZapStats.*`, `Nip05Service.score`, `Nip05Result.domainLabel` | Combined score: lightning × 0.25, identity × 0.1 |
| Reputation QR screen | `getSavedProofs()`, `getProofsForPublishing()` | `pp` payload, manual publish |
| Profile edit | `getSavedProofs().size`, `HumanityProofService.getStatus().verified`, navigation to both screens | Identity tiles |
| Home screen | `HumanityProofService.getStatus()` + `reverifyIfNeeded()` at start; `SatoshiDuellService.fetchStatus()` | Start-up re-verification, tile |
| Community hub | `SatoshiDuellService.fetchStatus()` | Card |
| Backup service | `getSavedProofs()`, `getStatus()`; writes the `platform_proofs` and `humanity_*` prefs directly | Backup v5 |
| Badge-sync service | writes `humanity_*` and `platform_proofs` directly (`clearIdentityProofs`) | Identity switch |
| Signing / NIP-46 | must request `sign_event:21003` | Permissions |

Recommendation: hide the raw pref writes from backup and badge-sync behind this domain's repositories (`PlatformProofRepository.replaceAll/union`, `HumanityProofRepository.markPendingReverification/clearForIdentitySwitch`). That keeps the keys in one place while the semantics stay the same.

---

## 13. Proposed Kotlin package and module structure

Base package `space.einundzwanzig.meetup`. Gradle modules follow `settings.gradle.kts` (`:core:*`, `:data:*`, `:feature:*`).

```
:data:proofs                       space.einundzwanzig.meetup.data.proofs
  platform/
    PlatformCatalog.kt             // ordered List<PlatformInfo>, byId()
    PlatformProof.kt               // @Serializable model(s)
    VerifyStringCodec.kt           // build()/parse()/isWellFormed()
    PlatformProofRepository.kt     // KeyValueStore (app_prefs) key "platform_proofs" (JSON string, §4)
    PlatformProofVerifier.kt       // verifyProofString()
  humanity/
    HumanityStatus.kt
    HumanityProofRepository.kt     // prefs + checkForZaps + reverify
    ZapActivitySearcher.kt         // relay search (3 subs, 12 s, ≤6 candidates)
  zap/
    Bolt11.kt                      // descriptionHashOf()
    ZapReceiptValidator.kt         // checkLocal()
    ZapProviderResolver.kt         // isFromRecipientProvider, providerPubkeyFor (+mem/KeyValueStore cache)
    ZapStats.kt
    ZapVerificationService.kt      // analyzeZapActivity, getMyStats, receiptFilter
  nip05/
    Nip05Service.kt                // verify, fetchNip05FromProfile, score
:data:satoshiduell                 space.einundzwanzig.meetup.data.satoshiduell
    SatoshiDuellClient.kt, DuellStatus.kt, PostgrestEncoding.kt
:feature:proofs                    space.einundzwanzig.meetup.feature.proofs
    platform/PlatformProofScreen.kt, PlatformProofViewModel.kt, CreatePlatformProofSheet.kt
    humanity/HumanityProofScreen.kt, HumanityProofViewModel.kt
    navigation: PlatformProofRoute, HumanityProofRoute (no args)
```
The SatoshiDuell tiles stay in `:feature:home` and `:feature:community` and consume `SatoshiDuellClient`.

Key public API (signatures):

```kotlin
// ---- platform ----
data class PlatformInfo(val id: String, @StringRes val nameRes: Int?, val fallbackName: String,
                        val icon: PlatformIcon, @StringRes val hintRes: Int)
enum class PlatformIcon { SHOPPING_CART, SEND, SMART_TOY, HUB, LANGUAGE, LINK }
object PlatformCatalog {
    val platforms: List<PlatformInfo>               // satoshikleinanzeigen, telegram, robosats, nostr, other
    fun byId(id: String): PlatformInfo?
    fun customPlatformId(customName: String): String = customName.lowercase(Locale.ROOT).replace(" ", "_")
}

@Serializable data class PlatformProof(
    val platform: String,
    val username: String,
    @SerialName("proof_sig") val proofSig: String,
    @SerialName("created_at") val createdAt: Long,
) {
    fun toReputationJson(): JsonObject   // {"username","proof_sig","created_at"}
    fun toQrJson(): JsonObject           // {"u","s"}
    companion object { fun fromReputationJson(platform: String, json: JsonObject): PlatformProof }
}

data class ParsedVerifyString(val npub: String, val platform: String, val username: String, val sig: String)
object VerifyStringCodec {
    fun build(npub: String, platform: String, username: String, sigHex: String): String
    fun parse(input: String): ParsedVerifyString?
    fun isWellFormed(p: ParsedVerifyString): Boolean   // npub decodes + 128 hex-ish chars
}

sealed interface ProofCreateResult {
    data class Success(val verifyString: String, val proof: PlatformProof) : ProofCreateResult
    data class Failure(val error: ProofCreateError, val detail: String? = null) : ProofCreateResult
}
enum class ProofCreateError { NO_KEY, EMPTY_USERNAME, SIGNER_OR_UNKNOWN }

interface PlatformProofRepository {
    val proofs: Flow<List<PlatformProof>>
    suspend fun getSavedProofs(): List<PlatformProof>
    suspend fun createProof(platformId: String, username: String, customPlatformName: String?): ProofCreateResult
    suspend fun removeProof(platformId: String)
    suspend fun getProofsForPublishing(): LinkedHashMap<String, PlatformProof>
    // used by backup / badge-sync instead of raw prefs:
    suspend fun replaceAllRaw(validated: List<JsonObject>)
    suspend fun unionRaw(incoming: List<JsonObject>)
    suspend fun clear()
}

enum class VerifyLevel { FULL, PARTIAL, SIGNATURE_ONLY, INVALID }
data class ProofVerifyResult(
    val level: VerifyLevel, val npub: String? = null, val platform: String? = null,
    val claimedUsername: String? = null, val reputation: ReputationEvent? = null,
    val proofInEvent: Boolean = false, val message: VerifyMessage,
)
sealed interface VerifyMessage { /* BadFormat, BadSignature, UsernameMismatch(claimed, expected), NoReputation,
                                  Full, PartialWithProof(bound,total), PartialNoProof, SignatureOnlyNoBindings,
                                  NpubNoReputation, NpubWithBindings, NpubNoBindings, Error(detail) */ }
class PlatformProofVerifier @Inject constructor(private val reputation: ReputationFetcher) {
    suspend fun verify(input: String, expectedUsername: String? = null): ProofVerifyResult
}

// ---- humanity ----
data class HumanityStatus(val verified: Boolean, val firstZapAt: Long = 0, val receiptEventId: String = "",
                          val lastCheckedAt: Long = 0, val needsReverification: Boolean = false)
data class HumanityCheckResult(val found: Boolean, val receiptEventId: String? = null,
                               val zapTimestamp: Long? = null, val outcome: HumanityOutcome)
enum class HumanityOutcome { FOUND, NOT_FOUND, NO_KEY, INVALID_NPUB }
interface HumanityProofRepository {
    val status: Flow<HumanityStatus>
    suspend fun getStatus(): HumanityStatus
    suspend fun checkForZaps(): HumanityCheckResult
    suspend fun reverifyIfNeeded(): Boolean
    suspend fun reset()
    suspend fun getProofForPublishing(): JsonObject?      // {"verified":true,"method":"lightning_zap","first_zap_at":N}
    suspend fun checkForZapsByPubkey(pubkeyHex: String): Boolean
    suspend fun verifyReceiptExists(receiptEventId: String): Boolean
    suspend fun markPendingReverification(receiptId: String, firstZapAt: Long, checkedAt: Long) // backup
    suspend fun clearForIdentitySwitch()                                                        // badge-sync
}

// ---- zap ----
data class ZapReceiptCheck(val ok: Boolean, val reason: String, val recipientPubkey: String = "",
                           val senderPubkey: String = "", val receiptPubkey: String = "")
object ZapReceiptValidator { fun checkLocal(receipt: JsonObject): ZapReceiptCheck }
object Bolt11 { fun descriptionHashOf(bolt11: String): String? }
interface ZapProviderResolver {
    suspend fun isFromRecipientProvider(receiptPubkey: String, recipientPubkey: String, senderPubkey: String = ""): Boolean
    suspend fun providerPubkeyFor(recipientPubkey: String): String?
    suspend fun providerPubkeyFromLnurlp(url: String): String?
}
@Serializable data class ZapStats(
    @SerialName("sent") val sentCount: Int, @SerialName("received") val receivedCount: Int,
    @SerialName("unique_recipients") val uniqueRecipientCount: Int,
    @SerialName("unique_senders") val uniqueSenderCount: Int,
    @SerialName("active_months") val activeMonths: Int,
    @SerialName("lightning_proof") val hasLightningProof: Boolean,
) { val totalCount: Int; val lightningScore: Double; val activity: ZapActivity; companion object { val EMPTY: ZapStats } }
enum class ZapActivity { NONE, LITTLE, REGULAR, VERY_ACTIVE }
interface ZapVerificationService {
    suspend fun analyzeZapActivity(pubkeyHex: String, useCache: Boolean = true): ZapStats
    suspend fun getMyStats(): ZapStats
    companion object { fun receiptFilter(pubkeyHex: String, since: Long, sent: Boolean): JsonObject }
}

// ---- nip05 ----
enum class Nip05DomainType { COMMUNITY, CUSTOM, PUBLIC_PROVIDER, UNKNOWN }
data class Nip05Result(val valid: Boolean, val nip05: String, val domain: String? = null,
                       val domainType: Nip05DomainType = Nip05DomainType.UNKNOWN)
interface Nip05Service {
    suspend fun verify(nip05: String, expectedPubkeyHex: String): Nip05Result
    suspend fun fetchNip05FromProfile(pubkeyHex: String, relays: List<String>): String?
    companion object { fun score(r: Nip05Result): Double; fun classify(domain: String): Nip05DomainType }
}

// ---- satoshiduell ----
data class DuellStatus(val myTurn: Int, val waiting: Int, val lobby: Int) {
    val hasAny get() = myTurn > 0 || waiting > 0 || lobby > 0
    val actionCount get() = myTurn + lobby
    companion object { val EMPTY = DuellStatus(0, 0, 0) }
}
interface SatoshiDuellClient {
    suspend fun fetchStatus(forceRefresh: Boolean = false): DuellStatus
    suspend fun openDuelCount(forceRefresh: Boolean = false): Int
    fun webAppUrl(npub: String?): String   // "https://satoshiduell.de/?npub=$npub" | "https://satoshiduell.de/"
}
```

ViewModels:
- `PlatformProofViewModel`: state `{loading, proofs, sheet: {selected, customName, username}, creating}`. Events: `ShowSnackbar(success|failure(message, 10s, dismissable))`, `CloseScreen`.
- `HumanityProofViewModel`: state `{loading, status, checking, result}`. Cancel the search in `onCleared`; keep it running if the screen should survive configuration changes, which the ViewModel does by default.

---

## 14. Risks and compatibility pitfalls

- **R1 — Verify-string format is a wire format.** `21rep::<npub>::<platform>::<username>::sig=<128hex>` may be pasted from old installs. Keep the parser exactly as in §7.1: a prefix check, `split("::")`, ≥ 5 parts with extra parts ignored, `npub1` prefix, `sig=` prefix, length 128. Usernames or custom platform names that contain `::` produce unparseable strings. Flutter does not validate this. Native may add input validation (reject `::` in the username or custom name), which creates no wire incompatibility.
- **R2 — No real signature check.** `_verifySignature` checks the format only. Do not present it as cryptographic verification beyond the existing wording. An optional native improvement: try BIP-340 verification by rebuilding the 21003 event with `created_at = platform_proofs[platform].created_at` from the fetched reputation event, used both as the event `created_at` and inside the content. That works for local signers when the timestamps match, but can fail for remote signers. If you add it, use it only to **upgrade** the confidence label, never to downgrade.
- **R3 — Screen closes after create.** Flutter pops PlatformProofScreen after a successful create (a stale "close dialog" pop). For parity the native app should navigate back with the snackbar shown on the previous screen. Keeping the user on the screen would be the better UX, but it changes behaviour. Decide explicitly and document the choice.
- **R4 — Platform proofs disappear from the published event.** Every background publish (`publishInBackground` after a badge scan, at home start, or from the badge wallet) passes `platformProofs = null`. That overwrites the replaceable kind-30078 event **without** `platform_proofs`. Verifiers then report "Plattform-Proof nicht im Event gefunden". The native reputation publisher should always include `getProofsForPublishing()`. This is wire-compatible (the field is optional) and fixes verification for others. Coordinate this with the reputation domain spec.
- **R5 — Humanity proof is not published right away.** `HumanityProofScreen` calls `publishInBackground([])`, which exits early with "Keine Badges vorhanden". Later background publishes are throttled by a badge-only content hash and a 5-minute interval, so a new humanity proof may not reach relays until the badges change or the user publishes manually. Native should call `publish(myBadges, proofs, force = true)` after a successful check.
- **R6 — Re-verification flag is never cleared by a manual check.** After a backup restore, `humanity_needs_reverification=true`. A successful `checkForZaps()` from the screen sets `humanity_verified=true` but leaves the flag, so the status card keeps showing "NICHT VERIFIZIERT" until the next app start runs `reverifyIfNeeded()`. Recommended native fix: `_saveProof` also removes `humanity_needs_reverification`. This is local only and safe.
- **R7 — `first_zap_at` is a misnomer.** It is the `created_at` of the first provider-confirmed receipt found (relay and arrival order, `limit: 3`), not the earliest zap. Keep it that way; don't "fix" it by searching history, which would change published values without benefit.
- **R8 — Provider binding uses the recipient's current lud16.** If a recipient has changed wallets since the zap, old genuine receipts fail. Accepted behaviour; do not relax it (Audit H3).
- **R9 — Strict local parse loses data.** `getSavedProofs()` returns `[]` if **any** entry is malformed; the next `_saveProof` then overwrites everything. Backups from older versions without `created_at` (only `proof_sig` is validated on restore) trigger this. Native: parse per entry (skip bad entries) **and** keep unknown or malformed entries on write. Alternatively, set `created_at` to 0 when it is missing. This does not affect backup or wire compatibility.
- **R10 — Case sensitivity.**
  - Proof uniqueness and `removeProof` compare `platform` exactly, but backup union lowercases. Keep both.
  - Humanity `mine` checks, zap `isSent` and `isReceived` compare hex exactly. The relay `pubkeyHex` from npub decoding is lowercase; a zap request with an uppercase `pubkey` would not match. Keep the exact comparison.
  - The provider comparison lowercases.
- **R11 — `fetchByNpub` takes the first valid event, not the newest.** With several relays holding different versions of the replaceable event, results depend on relay order. This belongs to the reputation domain. The native app should ideally pick the newest (`replaceableCreatedAt`), which improves correctness with no wire change.
- **R12 — Number types.** Dart's `as int` rejects `3.0`. kotlinx would also reject it with strict int decoding. Use lenient decoding (`jsonPrimitive.longOrNull ?: doubleOrNull?.toLong()`) for `ZapStats` and the reputation content so that events from third-party writers are accepted. Always **write** integers.
- **R13 — Dart JSON quirks in event ids.** Event-id hashing must reproduce Dart `jsonEncode`: no escaping of `/`, raw non-ASCII, and `\u00XX` for other C0 controls. Tag values are `toString()`-ed before hashing (a numeric tag value `1` becomes `"1"`). `created_at` must be a 10-digit int. A port that is stricter or looser changes which receipts and profiles are accepted. This is shared with the core Nostr domain.
- **R14 — NIP-05 URL and redirects.**
  - The URL is interpolated without percent-encoding. `name` values with `#`, `&`, `?` or spaces build a different URL (or throw, which gives invalid). OkHttp's `toHttpUrlOrNull()` will canonicalise differently from Dart `Uri.parse` for exotic input; treat any parse failure as invalid.
  - Flutter follows redirects (NIP-05 says not to). Choose deliberately: parity (follow) or spec (ignore). This only affects locally displayed scores for other users.
- **R15 — Lightning address user part.** It is not encoded or lowercased: `https://domain/.well-known/lnurlp/<user>` with the raw user. Do not lowercase it; some providers are case-sensitive.
- **R16 — SatoshiDuell.**
  - A challenge targeted at me is counted in both `myTurn` and `lobby` (badge count +2). This matches Flutter; keep it unless the web app's semantics are re-checked.
  - The `Uri.encodeQueryComponent` set differs from `URLEncoder` (`~` and `*`).
  - Do not apply quoting to the single-column filters (`eq.`, `neq.`).
  - The publishable key may be rotated by the SatoshiDuell project. Keep it configurable (BuildConfig field), and treat a 401 the same as any other failure (EMPTY or the cached value).
- **R17 — Performance.** Sequential relay loops with 8 s connects plus a 12 s wait over up to 10 relays mean that a check from the Humanity screen can take minutes when relays are dead (several defaults such as `relay.nostr.band` are often unreachable). Native may parallelise relays and cap total time, but must keep acceptance semantics: local validation, then provider confirmation, with only confirmed receipts counting.
- **R18 — Zap stats cache grows without bound.** It is written for **every** analysed pubkey (others too, despite the comment "only for own pubkey"). Port as is for parity, or limit the cache to the own pubkey. It is local only, so no compatibility impact. Old v1 keys can be deleted during migration.
- **R19 — Kind 21003 with remote signers.** Amber or NIP-46 may prompt for, or reject, kind 21003. Failure messages are long ("Permission denied for sign_event kind:21003") and need the 10 s dismissable snackbar. Ensure the NIP-46 connect permissions include `sign_event:21003` (same list as Flutter's `Nip46Client.signedKinds`).
- **R20 — Identity coupling.** Platform proofs and the humanity status are bound to the npub that created them, but they are stored without the npub. Any native code path that switches identity (import nsec, connect a different signer, restore a backup) must call `clearForIdentitySwitch()` / clear the proofs, exactly like the badge-sync flow does. Otherwise proofs signed by the old key get published under the new key.
- **R21 — Hard-coded German.** Several user-visible texts live in the services (§2.6). Moving them to resources is recommended. Keep the German defaults exactly as they are, because users and screenshots refer to them. They do not appear on the wire.
- **R22 — Time zone and format.** All dates use the device's local time with the `d.M.yyyy` format and no zero padding (`3.5.2025`). Do not use locale-aware formatters if visual parity is wanted.
- **R23 — Unhandled errors in the Humanity screen.** `checkForZaps()` has no outer try/catch: per-relay errors are caught, but an exception from `getActiveRelays()` or the prefs writes propagates. The screen's `_checkForZaps` does not catch it, so the button stays in the "SUCHE AUF RELAYS..." state forever. Native: wrap the call, reset `checking`, and show the not-found or generic message.
