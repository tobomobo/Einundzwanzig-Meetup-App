# 07 — Nostr infrastructure & profile (relays, event verification, NIP-44, profile discovery, avatar/npub widgets, profile screen, Blossom)

Porting specification for the native Android rewrite (Kotlin, Jetpack Compose, Material 3 Expressive).
The source is the Flutter app in this repository. Everything here must stay **wire- and data-compatible** with
users who still run the Flutter app.

Dart sources covered (all read completely):

| Dart file | Role |
|---|---|
| `lib/services/nostr_service.dart` | Key generation/import facade, NIP-19 helpers, legacy kind-21000 sign/verify |
| `lib/services/relay_socket.dart` | WebSocket wrapper used by *every* relay consumer + central event verification (`verifiedEvent`, `answersFilter`, `replaceableCreatedAt`) |
| `lib/services/relay_config.dart` | Default/custom relay list, URL validation, reachability probe |
| `lib/screens/relay_settings_screen.dart` | "NOSTR-RELAYS" settings screen |
| `lib/services/nip44.dart` | NIP-44 v2 encryption (used by NIP-46 transport and badge sync) |
| `lib/services/nostr_profile_service.dart` | Display names (kind 0 / kind 30078 nickname), batch names cache, local profile picture, cache reset |
| `lib/services/nostr_profile_lookup.dart` | Profile picture discovery (kind 0 + NIP-65 kind 10002, purplepag.es index), signed cache |
| `lib/services/profile_relay_connection_io.dart` | Abortable WebSocket for the profile lookup (the variant to port; see 5.3) |
| `lib/services/profile_relay_connection_web.dart` | Browser-only variant (conditional import on `dart.library.js_interop`); not ported, semantics noted in 5.3 for completeness |
| `lib/services/blossom_upload_service.dart` | BUD-02 image upload (event badge images) |
| `lib/widgets/nostr_avatar.dart` | Avatar with kind-0 picture + letter fallback |
| `lib/widgets/npub_chip.dart` | Shortened npub with name, open-on-njump, copy |
| `lib/screens/profile_edit.dart` | Profile screen (read-only / edit), key create/import/show/export, signer switch/disconnect |
| `docs/NOSTR_PROFILE_DISCOVERY.md` | Design notes for the profile discovery |

Helpers followed for behaviour: `lib/models/user.dart`, `lib/services/secure_key_store.dart`, `lib/services/local_key_vault.dart`,
`lib/services/screen_secure.dart`, `lib/features.dart`, parts of `lib/services/signing_service.dart`, `lib/services/haptic_service.dart`,
`lib/screens/home_screen.dart` (callers), `nostr-1.5.0`, `bip340-0.2.0`, `bech32-0.2.2` package sources (exact verification semantics).

---

## 1. Overview and user-visible features

This domain is the Nostr plumbing everything else stands on, plus the user's own profile screen.

* **Relay infrastructure.** Four default relays, user-added custom relays (validated + live-probed), per-default enable switch.
  Every other domain asks `RelayConfig.getActiveRelays()` for where to read/publish.
* **Central event guard.** Every `["EVENT", sub, {...}]` received from any relay must pass id + BIP-340 signature
  verification (`verifiedEvent`), must match the filter that was sent (`answersFilter`), and replaceable events with a
  `created_at` more than 10 min in the future are not allowed to win (`replaceableCreatedAt`). Frames > 512 KiB chars are dropped.
* **NIP-44 v2** primitive (conversation key, encrypt, decrypt) — verified against the official 128 test vectors.
* **Profile discovery** (pictures): signed kind-0 + NIP-65 relay list from app relays + `wss://purplepag.es` + up to
  4 author write relays; signed cache 12 h; outage-safe; legacy cache migration.
* **Display names** for other people (chat, trust network, npub chips): kind 0 `display_name`/`name`, fallback to the
  `identity.nickname` of the app's own kind-30078 reputation event; persistent batch cache (1 day).
* **Blossom upload** (BUD-02) for event-badge images (3 servers, single signed auth event).
* **Widgets:** `NostrAvatar` (picture or first-letter circle) and `NpubChip` (name + npub, tap → njump.me, copy).
* **Profile screen** (user's own identity).

User-visible features (complete list, including small ones):

Relay settings screen
1. List of 4 default relays, each with a switch (on/off persists immediately).
2. Status icon per relay is *neutral*: `cloud_queue` when enabled, `cloud_off` when disabled (deliberately not green — it does not mean "reachable").
3. "HINZUFÜGEN" pill opens an add dialog prefilled with `wss://`.
4. Adding validates syntax, rejects duplicates, then performs a live WebSocket handshake (≤5 s); shows "Verbindung wird geprüft …" snackbar and a spinner on the button while testing; button disabled during test.
5. Results: added (green snackbar, dialog closes, list reloads) / invalid URL (red snackbar, dialog stays open) / unreachable (red snackbar, dialog stays) / already present (neutral snackbar, dialog closes).
6. Custom relays list with delete icon (no confirmation, no snackbar), empty state "Keine eigenen Relays konfiguriert.".
7. Summary box: green "N aktive Relays" or red "Keine Relays aktiv!".

Profile screen (`ProfileEditScreen`)
8. Two modes: read-only (when `isAdminVerified` = verified organizer) and edit form (everyone else).
9. Read-only: avatar (radius 44), "VERIFIZIERT", info tiles (nickname, home meetup, shortened npub), signer management, identity strengthening tiles, red "BEARBEITEN (Status verlieren)" → warning dialog → edit mode.
10. Edit: nickname (validated: required, not "anon" case-insensitive, ≥2 chars), home meetup picker (multi-select favourites sheet; first favourite = home meetup; required with red border + message), Nostr key section, identity strengthening tiles, "PROFIL SPEICHERN".
11. Save → validation snackbar listing missing fields ("Bitte ausfüllen: Nickname und Home-Meetup"), else persists profile (drops verified status) and resets navigation to the app shell.
12. No key: "NEUEN KEY ERSTELLEN" (with a long education dialog first), "MIT AMBER VERBINDEN", "MIT REMOTE-SIGNER VERBINDEN", (NIP-07 only on web), "BESTEHENDEN NSEC IMPORTIEREN", footnote.
13. Key creation → device authentication → non-dismissible nsec backup dialog (screenshot-blocked; copy with 45 s clipboard auto-clear).
14. Key active card: full npub, "NPUB" copy, "NSEC" show (local key only; confirm dialog + device auth), "VERSCHLÜSSELT EXPORTIEREN (ncryptsec)" (local key only; device auth; reuse vault wrap or password+confirm; scrypt spinner; bottom sheet with copy).
15. Signer management: "VERBINDUNG PRÜFEN" (NIP-46 only, ping), "Anderen Signer verbinden" (bunker/Amber), "SIGNER TRENNEN" with confirmation; confirmation before replacing an existing identity; orange warning snackbar when the new signer has a different npub; offers re-connect of the *active* signer type when the app currently cannot sign.
16. Identity strengthening tiles: platform proofs ("N Plattform(en) aktiv"), proof of humanity ("Lightning-Beweis aktiv"); navigate to other domains' screens; form input is preserved across that navigation.
17. Error snackbars are red, 10 s, with close icon.

Widgets
18. `NostrAvatar`: shows kind-0 picture of a pubkey (or of the active identity if none given), falls back to coloured circle with first letter of nickname (upper-case) or `?`; never shows a stale picture from a previous identity; failed image download reverts to the letter.
19. `NpubChip`: shortened npub `npub1abcde…wxyz1234` (head 10, tail 8, U+2026), monospace + `open_in_new` icon; after name lookup shows **bold name** + small "npub ⧉" copy chip. Tap → haptic (selection click) → opens `https://njump.me/<npub>` externally; if that fails → copy. Long-press (no-name variant) or tap on copy chip (name variant) → medium haptic → copy full npub → floating snackbar "npub kopiert" (2 s).

Other
20. Home screen profile picture (caller in home domain): locally picked gallery image has precedence; otherwise discovered kind-0 picture.
21. App reset clears all profile caches (`clearCache`).
22. Event badge image upload via Blossom with failover across three servers (caller in events domain).

---

## 2. Screens & UX flows

### 2.1 Relay settings screen (`RelaySettingsScreen`)

**Entry point:** Home → settings bottom sheet → group `settingsSecNetwork` → row `settingsRelays` ("Nostr-Relays") /
`settingsRelaysSub` ("Relays konfigurieren"), icon `hub_rounded` purple. No arguments. (The row carries the guide
anchor `SettingsTour.relaysKey` with `guideSettingsRelaysTitle` / `guideSettingsRelaysBody` — the tour lives in the
settings/home domain; there is no tour inside this screen.)

**State:** `relayStatus: Map<String,Boolean>`, `customRelays: List<String>`, `isLoading=true`, `isTestingRelay=false`.
On open: `getRelayStatus()` + `getCustomRelays()` → `isLoading=false`.

**Layout (top → bottom), scroll, padding 20 dp, background `cDark #0C0C0E`:**
1. Top app bar title `rsTitle` ("NOSTR-RELAYS").
2. Loading: centered circular progress, orange.
3. Info card (card colour `cCard`, radius 12, border gold `cCyan #D4A017` @20 %): icon `hub` gold 20 dp + text
   `rsRelaysIntro + rsAllRelaysInfo` (two strings concatenated; grey 12 sp, line height 1.5).
4. 24 dp gap. Section label `rsDefaultRelays` ("DEFAULT-RELAYS"; grey 11 sp bold, letter spacing 1).
5. One tile per default relay, **fixed order** of `defaultRelays`: card radius 10, border `cBorder` if enabled else white10;
   leading icon `cloud_queue` (`cTextSecondary`) or `cloud_off` (grey 700) 18 dp; URL in monospace 12 sp, white if enabled
   else grey 600, single line ellipsis; trailing `Switch(value=enabled, activeThumb=green)`.
   `enabled = relayStatus[url] ?: true`. Toggle → `setDefaultRelayEnabled(url, v)` → reload.
6. 24 dp gap. Row: label `rsCustomRelays` ("EIGENE RELAYS") + pill button (orange 15 % bg, radius 6): `+` icon 14 dp + `rsAdd` ("HINZUFÜGEN", orange 10 sp w700) → add dialog.
7. If no custom relays: centered `rsNoCustomRelays` (grey 700, 13 sp, vertical padding 20).
   Else one tile per custom relay (same tile, always "enabled", trailing `delete_outline` red 400, 18 dp, tooltip **hard-coded "Entfernen"**) → `removeCustomRelay(url)` → reload. No confirmation, no snackbar.
8. 30 dp gap. Summary box (recomputed on every rebuild via `getActiveRelays()`): `count>0` → green 8 % bg, green 20 % border,
   `check_circle` green, text **hard-coded German** `"$count aktive Relays"` (green 300, 13 sp w600);
   `count==0` → red variants, `error_outline`, text `rsNoRelaysActive`.
   (Recommend adding a plural string resource for "N aktive Relays" natively; ARB has none.)

**Add-relay dialog** (AlertDialog, bg `cCard`):
* Title `rsAddRelay` ("RELAY HINZUFÜGEN", white bold).
* TextField autofocus, initial text `wss://`, monospace 14 sp white, hint `rsRelayPlaceholder` ("wss://mein-relay.de"), filled white 5 %, radius 10, no border.
* Actions: `apCancel` ("Abbrechen", grey) → close; primary orange button label `rsAdd` (black text) or a 16 dp black spinner while testing; disabled while testing.
* Primary pressed:
  1. `isTestingRelay = true` (re-render dialog), show snackbar `rsTesting` ("Verbindung wird geprüft …", 6 s, bg `cCard`).
  2. `result = RelayConfig.addCustomRelay(text)`.
  3. `isTestingRelay = false`; hide current snackbar.
  4. `added` → close dialog, reload lists, snackbar `rsRelayAdded` (green).
     `invalidUrl` → dialog stays, snackbar `rsInvalidUrl` (red).
     `unreachable` → dialog stays, snackbar `rsRelayUnreachable` (red).
     `alreadyPresent` → close dialog, snackbar `rsRelayAlreadyAdded` (bg `cCard`).
* When the dialog is dismissed during a test, `isTestingRelay` is reset to false. (Note: the testing snackbar is shown even for an invalid URL; it is replaced immediately.)

No relay can be "forced" on: disabling all defaults with no custom relays is allowed (summary turns red).

### 2.2 Profile screen (`ProfileEditScreen`)

**Entry points (no arguments):**
* Home → settings sheet → group `settingsSecAccount` → row `settingsProfile` ("Profil bearbeiten") / `settingsProfileSub`
  ("Name, Nostr-Schlüssel & Home-Meetup"), icon `person_rounded` orange. Guide anchor `SettingsTour.profileKey`
  (`guideSettingsProfileTitle` / `guideSettingsProfileBody`).
* Radar screen (`radar.dart`) edit icon on the user card; on return the radar reloads the user.

**Load (`loadData`)** — all in parallel-safe order:
`UserProfile.load()`; `MeetupService.fetchMeetups()` sorted by `city` ascending; `NostrService.hasKey()` (local nsec present);
`NostrService.getNpub()` (= `SigningService.npub()`, mode-dependent); `SigningService.isAmber`, `isAmberSupported` (Android only),
`isNip07`, `nip07ExtensionAvailable()` (always false on Android), `isNip46`, `canSign()`;
`PlatformProofService.getSavedProofs().length` and `HumanityProofService.getStatus().verified` (errors → 0/false).

Derived state:
```
nickname       = user.nickname
selectedHome   = user.homeMeetupId
favorites      = user.favoriteMeetupIds.ifEmpty { if (home.isNotEmpty) [home] else [] }
isAmber        = isAmber && amberSupported        // Android: amberSupported = true
hasNostrKey    = hasKey || isAmber || isNip07 || isNip46
nostrNpub      = npub ?: user.nostrNpub
isEditing      = !user.isAdminVerified
```
While loading (or user null): full-screen orange spinner on `cDark`.

**App bar title:** `profileEditTitle` ("PROFIL BEARBEITEN") when editing, else `profileTitle` ("DEIN PROFIL").
Body: scroll, padding (24, 24, 24, 100).

#### 2.2.1 Read-only view (verified organizer)
1. 20 dp. `NostrAvatar(key = npub, pubkeyHex = decode(npub) or null, fallbackText = nickname, background = cGreen, radius = 44)`.
2. `profileVerified` ("VERIFIZIERT", green bold 18).
3. 30 dp. Info tile `profileNickname` / nickname (icon person). Info tile `profileHomeMeetup` / `homeMeetupId` or `"-"` (icon home).
   If `hasNostrKey`: info tile label **"Nostr"** (hard-coded) / `shortenNpub(npub)` (icon key).
   Info tile = card radius 12, padding 16, orange icon 20 dp, grey 12 sp label over white 16 sp value.
4. If `hasNostrKey`: signer management section (§2.2.4).
5. 24 dp. Label `profileStrengthen` (green bold 12).
6. Identity action tiles (§2.2.5). After returning from either target screen → full `loadData()`.
7. 30 dp. Outlined red button, icon edit, `profileEditLoseStatus` → **unlock dialog**: title `profileWarning` ("Achtung!"),
   body `profileEditWarnDesc`, actions `dialogCancelMixed` ("Abbrechen") and red button `profileEdit` ("Bearbeiten") → `isEditing = true`.

#### 2.2.2 Edit form
1. Label `profileIdentity` ("DEINE IDENTITÄT", orange bold) + `profileIntro` (grey 500, 12 sp).
2. Nickname field: filled `cCard`, radius 12, label `profileNickname`, prefix icon person. Validator on trimmed value:
   empty → `profileNicknameReq`; `lowercase == "anon"` → `profileNicknameAnon`; `length < 2` → `profileNicknameMin`.
3. Home-meetup field (tappable box, card bg, radius 12, border `cRed` width 1.5 if missing else `cBorder` 1): icon home
   (red if missing else orange), text = `selectedHome` (**raw meetup ID string** is displayed) or `profileChooseMeetup` (grey),
   trailing `arrow_drop_down`. Tap → opens meetup picker sheet and clears the missing flag immediately.
   If missing: below it `profileMeetupReq` (red 12 sp, padding top 6 left 12).
   Picker = `MeetupSearchSheet` (meetups domain; modal bottom sheet, bg `cCard`, top radius 20, scroll-controlled) with
   `initialSelected = favorites`; `onDone(favs)`: `favorites = favs; selectedHome = favs.firstOrNull ?: ""; missing = favs.isEmpty()`.
4. 30 dp. Label `profileNostrKey` ("NOSTR SCHLÜSSEL", purple bold) + `profileKeyDesc`.
5. **Key card** (if `hasNostrKey`) — card radius 12, border green 50 %:
   * Row: green `check_circle` + `profileKeyActiveCaps` ("SCHLÜSSEL AKTIV", green bold 12).
   * Full npub in a dark box (monospace 11 sp, max 2 lines, ellipsis).
   * Row of buttons: outlined gold "NPUB" (hard-coded label, copy icon) → plain clipboard copy (no auto-clear) + snackbar
     `profileNpubCopied` (orange bg, 1 s). If **local key** (`!isAmber && !isNip07 && !isNip46`): outlined orange "NSEC"
     (hard-coded label, key icon) → show-nsec flow (§2.2.3).
   * If local key: full-width outlined green button `keyExportEncrypted` (lock icon; 14 dp green spinner while exporting; disabled while exporting) → export flow (§2.2.3).
   * Signer management section (§2.2.4).
   **No-key card** (else) — card radius 12, border `cBorder`, centered:
   * `key_off` icon grey 32 dp, `profileNoKey` (grey 13).
   * Full-width purple button: icon `add_circle_outline` or 16 dp white spinner; label `profileCreateNewKey` or `profileCreating` while generating; disabled while generating → create flow.
   * (Android) outlined gold `profileConnectAmber` (shield icon), disabled while generating → Amber flow.
   * Outlined gold `profileConnectBunker` (lock icon), disabled while generating → bunker flow. **Unconditional.**
   * (Web only, not ported) `profileConnectExtension`.
   * Outlined orange `profileImportNsec` (download icon) → import flow (not disabled while generating).
   * `profileNoNostrNeeded` (grey 11 sp, centered).
6. 24 dp. Label `profileStrengthen` (green bold) + `profileStrengthenDesc`.
7. Identity action tiles (§2.2.5). In edit mode, before navigating the current nickname text and selected home are
   remembered; after return only proof counts are refreshed (`refreshIdentityOnly`) and the remembered form values are restored.
8. 30 dp. Full-width 50 dp button `profileSave` → save flow.

#### 2.2.3 Key flows

**Create key**
1. (Web-only gate `kWebLocalNsecLoginEnabled=false` → error; irrelevant on Android.)
2. Education dialog (Dialog, bg `cCard`, radius 18, inset 20/40): header icon `key_rounded` in orange 15 % box + `keyEduTitle`;
   scrollable: `keyEduIntro`; sections (icon, colour, title, body): (`public_rounded`, gold, `keyEduWhatNostrH`, `keyEduWhatNostrB`),
   (`vpn_key_rounded`, orange, `keyEduPairH`, `keyEduPairB`), (`badge_outlined`, green, `keyEduNpubH`, `keyEduNpubB`),
   (`warning_amber_rounded`, red, `keyEduNsecH`, `keyEduNsecB`, **highlighted box**), [web only: `webKeyWarnH`/`webKeyWarnB`+`webKeyWarnAdvice`],
   (`hub_rounded`, gold, `keyEduIdentityH`, `keyEduIdentityB`); then shield icon + `keyEduProtectH` and four green-check bullets
   `keyEduProtect1..4`. Buttons: full-width orange `keyEduUnderstood` (→ true), text button `keyEduCancel` (→ false). Back/outside = null → abort.
3. `isGeneratingKey = true`.
4. `NostrService.generateKeyPair()` (writes secure storage), `SigningService.useLocalMode()`, `LocalKeyVault.clearAll()`.
5. State: `hasNostrKey=true, isAmber=isNip07=isNip46=false, nostrNpub=new npub, isGeneratingKey=false`. (`canSign` is *not* refreshed.)
6. Device authentication (`ScreenSecure.authenticate`, reason "Bitte Identität bestätigen, um den Schlüssel anzuzeigen" — hard-coded).
   Only if true → nsec backup dialog. If false the key exists but is not shown (user can view later via NSEC).
7. Any exception → `isGeneratingKey=false`, error snackbar `errorGeneric(e)`.

**nsec backup dialog** (also used by "show nsec"): set FLAG_SECURE on; non-dismissible AlertDialog: title row `warning_amber`
orange + `profileSecureKey` ("SICHERE DEINEN KEY!"); body `profileSaveKeyDesc + profileWhoHasKey`; selectable nsec in dark box
(orange monospace 11 sp, orange 50 % border); `profileKeyNotShownAgain` (red bold 12). Actions: orange `profileCopy` (copy icon) →
`copySecret(nsec)` (45 s auto-clear) + snackbar `profileNsecCopied` (orange); green text button `profileKeySecured` → close.
On close: FLAG_SECURE off.

**Show existing nsec** (local key only): `NostrService.loadKeys()` (null → nothing). Confirm dialog: title `profileShowNsecQ` (red bold),
body `profileShowNsecWarn`, actions `dialogCancel` / red `profileShow`. If confirmed → device authentication → nsec backup dialog.

**Import nsec**: dialog title `profileImportNsecShort`; text `profileEnterNsec`; (web warning only on web); obscured single-line
monospace field, hint "nsec1..." (hard-coded), orange enabled border; `profileNsecNeverLeaves` (orange 11 sp). Actions `dialogCancel`,
orange `profileImport`. On import: trimmed empty → no-op. Else `NostrService.importNsec(nsec)` → `useLocalMode()` →
`LocalKeyVault.clearAll()` → close dialog → state (`hasNostrKey=true`, external flags false, npub) → green snackbar `profileKeyImported`.
Error → dialog stays, error snackbar with `e.toString()` (Dart shows e.g. `FormatException: Ungültiger nsec Key: …`).
No device auth, no identity-change confirmation.

**Encrypted export (NIP-49 ncryptsec)** — lock flag `exportingKey` set *before* anything (prevents multiple dialogs/parallel scrypt):
1. Device authentication (reason "Bitte Identität bestätigen, um den Schlüssel zu exportieren"). Fail → abort.
2. `privHex = SecureKeyStore.getPrivHex()`; empty → error snackbar `keyExportNoKey`.
3. Unless `forceNewPassword`: `existing = LocalKeyVault.getPasswordWrap()`; `npub = SecureKeyStore.getNpub()`;
   if existing non-empty && npub != null && `passwordWrapMatchesNpub(npub)` → unlock button and show sheet with `fromVault=true`.
4. Password dialog (non-dismissible): title `keyExportTitle`, desc `keyExportDesc`, field 1 obscured autofocus label `backupPassword`
   (shows inline error), field 2 obscured label `backupPasswordConfirm`, note `keyExportDuration` (orange 11 sp).
   Actions `dialogCancel` → null; green `keyExportAction`: empty → error `backupPasswordEmpty`; mismatch → `keyExportMismatch`; else return password.
5. Wait 50 ms (let spinner render), `Nip49.encrypt(privHex, password, keySecurity = 0x00 insecure, logN = 16 default)`.
   `Nip49Exception` → message; other → toString. Failure → error snackbar `errorGeneric(msg)`.
6. Show sheet. Finally: unlock.
**ncryptsec sheet** (FLAG_SECURE while open; bottom sheet bg `cCard`, top radius 16, padding 20): lock icon green + `keyExportReadyTitle`;
`keyExportReadyBody`; if fromVault `keyExportFromVault` (green); selectable ncryptsec (white monospace 11); full-width green `keyExportCopy`
→ `copySecret` → close → snackbar `keyExportCopied` (green); if fromVault text button `keyExportOtherPassword` → close and rerun export with `forceNewPassword=true` (device auth again).
A newly generated ncryptsec is **not** saved to the vault.

#### 2.2.4 Signer management section (both views, only when `hasNostrKey`)
* If `isNip46`: outlined gold `bunkerCheck` (icon `wifi_tethering` or 14 dp spinner while checking) →
  `SigningService.nip46SessionAlive()` → snackbar `bunkerAlive` (green, 3 s) or `bunkerDead` (red, 10 s, close icon). Never automatic.
* Switch section: `offerAgain = !canSign`. Options:
  - Bunker if `!isNip46 || offerAgain` (lock icon, gold, `profileConnectBunker`)
  - Amber if Android && (`!isAmber || offerAgain`) (shield, gold, `profileConnectAmber`)
  - NIP-07 (web only).
  `canDisconnect = isAmber || isNip07 || isNip46`. If no options and not canDisconnect → render nothing.
  Else: 16 dp, divider (`cBorder`), 12 dp; if options non-empty: heading `profileSwitchSignerHeading` (white bold 12) and hint
  `profileSignerUnusable` (orange) if offerAgain else `profileSwitchSignerHint` (grey) at 11 sp; then each option button (outlined,
  full width, 11 sp label, disabled while generating); then if canDisconnect orange `profileDisconnectSigner` (icon `link_off`).
* **Confirm signer switch** (before Amber/bunker connect, only if `hasNostrKey`): title `profileSwitchSignerTitle`, body
  `profileSwitchSignerBody`, `dialogCancel` / gold `profileSwitchSignerContinue`. Dismiss = false.
* **Amber connect:** remember previous npub, spinner flag, `SigningService.connectAmber()`:
  success → state (isAmber=true, others false, canSign=true, npub) + green snackbar `profileAmberConnected` + identity-change warning;
  missing → dialog title `profileAmberNotFound`, body `profileAmberDesc + profileAmberInstall + profileAmberRetry`, action "OK" (hard-coded);
  cancelled → snackbar `profileAmberAborted`; error → red `errorAmber(msg)`; exception → `errorGeneric`.
* **Bunker connect:** confirm → `BunkerConnectSheet.show()` (NIP-46 domain) → on non-null result: state (isNip46=true, canSign=true, npub)
  + green snackbar `bunkerConnected` + identity-change warning.
* **Identity-change warning:** if previous npub non-empty and ≠ new → snackbar `profileIdentityChanged` (orange, 6 s).
* **Disconnect:** dialog `profileDisconnectTitle` / `profileDisconnectBody`, `dialogCancel` / orange `profileDisconnectSigner`.
  Confirm → `disconnectNip46()` / `disconnectNip07()` / `disconnectAmber()` (priority in that order by current flag) → full `loadData()` →
  green snackbar `profileDisconnectDone`. Exception → `errorGeneric`. Local keys are never deleted by connect/disconnect.

#### 2.2.5 Identity action tiles
Card (radius 12, padding 14, border colour@30 % if done else white10), leading 38 dp box with icon (colour if done else grey), title (white 14 w600),
subtitle (colour@80 % if done else grey 500, 12 sp), trailing `check_circle` (done) or `arrow_forward_ios` grey.
* Platform proofs: icon link, green, title `profileLinkPlatforms`, subtitle **hard-coded** `"$n Plattform${n>1?'en':''} aktiv"` if n>0 else `profilePlatformsSub`; → `PlatformProofScreen`.
* Humanity: icon bolt, amber, title `profileProofHumanity`, subtitle `profileLightningActive` if verified else `profileZapCheck`; → `HumanityProofScreen`.

#### 2.2.6 Save
1. Validate form + `selectedHome.trim().isNotEmpty()`; set missing flag.
2. If invalid: snackbar (bg `cRed`) `profileFillIn(fields)` where fields = [`profileNickname`] if form invalid + [`profileHomeMeetupDash`] if home missing, joined with **hard-coded `" und "`**. Return.
3. Spinner; build a **new** `UserProfile` (see §3.3 for the exact field mapping, incl. side effects), `save()`.
4. Navigate: clear back stack, root = app shell (`Navigator.resetTo(AppShell)`).

#### 2.2.7 Snackbar catalogue
| Trigger | String key | Style |
|---|---|---|
| any error | `errorGeneric`, `errorAmber`, raw exception, `keyExportNoKey`, `webKeyWarnH: webKeyWarnAdvice` | red, 10 s, close icon |
| npub copied | `profileNpubCopied` | orange, 1 s |
| nsec copied | `profileNsecCopied` | orange |
| key imported | `profileKeyImported` | green |
| amber ok / aborted | `profileAmberConnected` / `profileAmberAborted` | green / default |
| bunker ok | `bunkerConnected` | green |
| identity changed | `profileIdentityChanged` | orange, 6 s |
| disconnect done | `profileDisconnectDone` | green |
| bunker ping | `bunkerAlive` / `bunkerDead` | green 3 s / red 10 s + close |
| export copied | `keyExportCopied` | green |
| save validation | `profileFillIn` | `cRed` |

### 2.3 `NostrAvatar` (widget)
Params: `fallbackText: String`, `backgroundColor = cOrange`, `radius = 20`, `pubkeyHex: String? = null`, `pictureLoader: (suspend (String)->String?)? = null` (test seam; default `NostrProfileService.fetchProfilePicture`).
Behaviour:
* On first composition and whenever `pubkeyHex`, `fallbackText` or `pictureLoader` changes: `request = ++counter`; **immediately clear** the picture URL;
  resolve hex = `pubkeyHex` or (if null/empty) `NostrService.getNpub()` → NIP-19 decode (failure → stop); call loader; apply result only if
  still the latest request and still attached.
* Render: if URL null/empty → circle with `backgroundColor`, centered letter = `fallbackText[0].uppercase()` or `?` (black, bold, size `radius*0.8`).
  Else circle with `backgroundColor` and the network image; on image load error (and URL unchanged) → revert to letter.
Callers: profile read-only view (r 44, green), radar user card (r 20, verification colour), chat screen (2 places).

### 2.4 `NpubChip` (widget)
Params: `npub`, `head = 10`, `tail = 8`, `style` (default colour `cTextSecondary`, 12 sp), `showIcon = true`, `showName = true`.
Static `shorten(npub, head, tail)`: `v = npub.trim(); if (v.length <= head+tail+1) v else v.take(head) + "…" + v.takeLast(tail)` (U+2026).
* Empty trimmed npub → render nothing.
* If `showName`: background `NostrProfileService.fetchDisplayName(nip19Decode(npub))` (decode failure → no lookup); show name only if non-blank (trimmed). Reset name when `npub` changes.
* Without name: `Row{ Text(short, mono, 1 line, ellipsis); if showIcon: 5 dp + Icon(open_in_new_rounded, 12 dp, colour@80 %) }`; tap = open, long-press = copy.
* With name: `Row{ Text(name, bold w700, 1 line ellipsis) [tap = open]; 7 dp; chip(bg cSurface, radius 5, border cTileBorder 0.5, padding 6×2){ Text("npub", mono, cTextTertiary, size*0.78); 4 dp; Icon(copy_rounded, size*0.78+1, cOrange) } [tap = copy] }`.
* Open: `HapticService.light()` (selection click, respects `haptic_enabled`), launch `https://njump.me/<trimmed npub>` in an external app; failure (no handler / false) → log warn + copy.
* Copy: `HapticService.medium()` (medium impact), clipboard = full trimmed npub, floating snackbar `npubCopied` 2 s.
Callers: portal meetups, event calendar (2), admin panel; `NpubChip.shorten` also used by meetup details and my network.

---

## 3. Data models

### 3.1 Nostr event (wire)
JSON object, keys exactly: `id` (64 lowercase hex), `pubkey` (64 lowercase hex, x-only), `created_at` (int, unix seconds),
`kind` (int), `tags` (array of arrays of strings), `content` (string), `sig` (128 hex). Serialisation order used by the app
(`Event.toJson`, `SignedEvent.toJson`): `id, pubkey, created_at, kind, tags, content, sig`.

Kotlin:
```kotlin
data class NostrEvent(
  val id: String, val pubkey: String, val createdAt: Long, val kind: Int,
  val tags: List<List<String>>, val content: String, val sig: String,
) { fun toJson(): JsonObject /* key order above */ }
```
Raw incoming events must be kept as `JsonObject` too, because `verifiedEvent` returns the *raw* map and filter matching runs on raw values.

### 3.2 Relay types
* `enum RelayAddResult { ADDED, INVALID_URL, UNREACHABLE, ALREADY_PRESENT }`.
* Relay status: `LinkedHashMap<String,Boolean>` — defaults first (in default order) → enabled flag, then custom → true.

### 3.3 `UserProfile` fields touched here (model owned by the identity domain)
Save from the profile screen constructs a fresh object:
```
nickname          = nicknameField.trim()
fullName          = old.fullName ?: ""
homeMeetupId      = selectedHome
favoriteMeetupIds = favorites.ifEmpty { if (selectedHome.isNotEmpty) [selectedHome] else [] }
nostrNpub         = nostrNpub (screen state)
telegramHandle    = old.telegramHandle ?: ""
twitterHandle     = old.twitterHandle ?: ""
isAdminVerified   = false                    // "Status verlieren"
isAdmin           = old.isAdmin ?: false     // constructor maps true → adminViaVouch = true ONLY
isNostrVerified   = hasNostrKey
hasNostrKey       = hasNostrKey
```
Side effects of the constructor that persist via `save()` (parity): `adminViaPortal=false`, `adminViaSeed=false`,
`adminViaVouch = old.isAdmin`, `isReviewDemo=false`, `promotion_source = old.isAdmin ? "trust_score" : ""`.
(Admin flags are later recomputed by `reVerifyAdmin`/portal checks in other domains. Flag this as a decision point:
preserving the original source flags natively is safer but changes stored values.)

### 3.4 Profile caches (JSON in preferences)
* **Metadata cache** `nostr_profile_metadata_v2_<hex>`: `{"event": <kind-0 event JSON>, "fetchedAt": <int ms since epoch>}`.
* **Relay-hint cache** `nostr_profile_relays_v2_<hex>`: `<kind-10002 event JSON>` (the event itself, no wrapper).
* **Display-name cache** `nostr_display_names_v1`: `{"<hex>": {"n": "<name>", "t": <int ms>}, ...}`.
Cached events are **re-verified** (id, signature, author, kind, not in future) on every read.

### 3.5 Blossom
`UploadResult { url: String?, error: String? ; ok = !url.isNullOrEmpty() }` — `success(url)` / `failure(error)`.

### 3.6 NostrService maps (legacy API shape)
`generateKeyPair()`/`importNsec()`/`importPrivHex()`/`importNcryptsec()` return `{"nsec","npub","pubHex"}`.
`signBadge()` (unused) returns `{"event_id","pubkey","npub","signature","content","created_at"}`.

---

## 4. Persistence

The Flutter app stores preferences in Android `SharedPreferences` file **`FlutterSharedPreferences`**, every key prefixed
with **`flutter.`** (e.g. `flutter.custom_relays`). Types: `String`, `Long` (Dart int), `Boolean`; string lists are stored as a
String `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!" + JSON array` (shared_preferences_android 2.4.20; older installs may
still hold the legacy `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu" + base64(Java-serialized list)` form). The native app is
an update over the same applicationId `space.einundzwanzig.meetup`; a one-time migration (owned by the persistence/core
domain) must import these keys into DataStore. All keys below are the logical (un-prefixed) names.

| Key | Type / format | Writer | Reader | Notes |
|---|---|---|---|---|
| `custom_relays` | StringList, insertion order, exact strings as typed (trimmed) | `RelayConfig.addCustomRelay/removeCustomRelay` | `getActiveRelays`, `getCustomRelays`, `getRelayStatus` | |
| `disabled_default_relays` | StringList of default URLs | `setDefaultRelayEnabled` | same | |
| `nostr_profile_metadata_v2_<hex>` | String JSON (§3.4) | `NostrProfileLookup` | same | `<hex>` = 64 lowercase hex |
| `nostr_profile_relays_v2_<hex>` | String JSON event | `NostrProfileLookup` | same | |
| `nostr_profile_picture_<hex>` | String URL (legacy v1) | — (only removed) | lookup (fallback) | removed once a signed profile arrives |
| `nostr_profile_picture_time_<hex>` | Long, ms since epoch (legacy v1) | — (only removed) | lookup | |
| `nostr_profile_picture*` (any key with this prefix, incl. old global keys) | — | — | — | removed by `clearCache` |
| `nostr_display_names_v1` | String JSON (§3.4) | `fetchDisplayNames` | `cachedDisplayNames`, `fetchDisplayNames` | **not** cleared by `clearCache` (app reset clears all prefs anyway) |
| `local_profile_picture` | String absolute file path | `setLocalPicture` (home screen gallery pick) | `getLocalPicture` (home) | removed by `clearCache` |
| `haptic_enabled` | Boolean, default true | settings | `HapticService` (NpubChip) | |
| `nickname`, `full_name`, `telegram`, `nostr`, `twitter`, `nostr_verified`, `admin_verified`, `is_admin`, `admin_via_portal`, `admin_via_vouch`, `admin_via_seed`, `review_demo`, `home_meetup`, `favorite_meetups` (StringList), `promotion_source` | see identity domain | `UserProfile.save()` from profile save | many | |

Secure storage (flutter_secure_storage 10.3.4, owned by identity/key domain; listed because this domain calls it):
`nostr_nsec_key`, `nostr_npub_key`, `nostr_priv_hex` (written by `generateKeyPair`/`importPrivHex`, read by show-nsec/export);
`easyauth_password_ncryptsec`, `easyauth_password_wrap_npub`, `easyauth_passkey_ncryptsec`, `easyauth_passkey_credential_id`
(read by export; **all four deleted** by `LocalKeyVault.clearAll()` after key generation and nsec import).

In-memory (process lifetime): display-name cache `Map<hex, String?>` (negative results cached for the session);
profile lookup in-flight map + generation counter; `RelaySocket.rejectedEventCount`.

Migrations in this domain:
* Legacy picture cache → signed metadata cache (automatic on first successful fetch; see §8.4).
* Nothing to migrate for relays (same keys).
* `local_profile_picture` path points into the Flutter app's cache dir; keep it if the file still exists, otherwise ignore (letter/remote fallback).

---

## 5. Network

### 5.1 Relays (WebSocket, `wss://`)
Default relays (exact strings, exact order):
```
wss://relay.damus.io
wss://nos.lol
wss://relay.nostr.band
wss://nostr.einundzwanzig.space
```
Additional read-only targets: profile index `wss://purplepag.es`; up to 4 author write relays discovered from NIP-65.

Timeouts (constants used across domains): `RelayConfig.relayTimeout = 8 s`, `RelayConfig.publishTimeout = 5 s`.
This domain: probe 5 s; `NostrProfileService` 6 s connect + 6 s wait (per relay); `ProfileRelayClient` 6 s **total** (handshake + answer).

`RelaySocket.connect(url)`: opens and **suspends until the handshake completed**; failures throw at the call site so the caller's
`withTimeout` covers the connect. On failure close the channel without awaiting. Incoming text frames longer than
`512 * 1024` characters are dropped (debug log `Frame über 524288 verworfen`). Binary frames are effectively ignored.
No pings, no automatic reconnect, no CLOSE messages are sent by this domain (sockets are simply closed).

`RelayConfig.probeRelay(url, timeout = 5 s)`: success = WebSocket handshake completes within timeout; always closes afterwards. No NIP-11, no REQ.

### 5.2 REQ subscriptions sent by this domain (exact)
| Purpose | Frame |
|---|---|
| Profile lookup (per relay, one socket each) | `["REQ","profile",{"kinds":[0,10002],"authors":["<hex>"],"limit":2}]` |
| Single display name (kind 0) | `["REQ","nam-<16 hex>",{"kinds":[0],"authors":["<hex>"],"limit":1}]` |
| Single nickname | `["REQ","nick-<16 hex>",{"kinds":[30078],"authors":["<hex>"],"#d":["einundzwanzig-reputation"],"limit":1}]` |
| Batch names (≤100 authors) | `["REQ","names-<16 hex>",{"kinds":[0],"authors":[...],"limit":<2*n>}]` |
| Batch nicknames (≤100) | `["REQ","names-<16 hex>",{"kinds":[30078],"authors":[...],"limit":<2*n>,"#d":["einundzwanzig-reputation"]}]` |
`<16 hex>` = 8 bytes from a CSPRNG, lowercase hex, zero-padded.

Responses handled: `["EVENT", sub, ev]`, `["EOSE", sub]`; profile client also `["CLOSED", sub, ...]`. `NOTICE`/`OK`/`AUTH` ignored.

### 5.3 Profile lookup client limits (`ProfileRelayClient`)
`maxMessages = 64`, `maxEvents = 8`, `maxMessageLength = 64 * 1024` chars, `timeout = 6 s` total. Listen before the handshake completes.
Every received frame counts; >64 frames, non-text frame, or frame >64 KiB → stop. Frames that are not JSON arrays with `[1] == "profile"`
are ignored (but counted). `EVENT` with exactly 3 elements and an object at [2] → collected (unverified here); 8 events → stop.
`EOSE`/`CLOSED` → stop. Partial results are returned on timeout. Teardown: cancel listener, close sink **without awaiting**, and
abort the underlying connection (must also abort a hanging HTTP upgrade). Errors → whatever was collected (possibly empty).

Connection factory `openProfileRelay(uri)` → `(channel, abort)`, selected by conditional import in `nostr_profile_lookup.dart`
(`profile_relay_connection_io.dart` by default, `profile_relay_connection_web.dart` if `dart.library.js_interop`). Teardown always calls
`abort()` exactly once, after the non-awaited listener cancel and sink close, on every exit path (success, timeout, error).
- **io variant (the one to port)**: creates a dedicated `HttpClient` per connection and calls `WebSocket.connect(uri, customClient: client)`.
  `abort()` sets `aborted = true` and calls `client.close(force: true)`, which kills an in-flight HTTP upgrade as well as an open socket.
  Race guard: if the connect future resolves to a socket *after* `abort()` was called, that socket is closed immediately
  (`socket.close()`, errors swallowed); connect errors are swallowed too (the stream's `onError` already ends the query).
  Android: OkHttp `WebSocket.cancel()` covers both cases (cancels the handshake call or tears down the open socket); the late-socket
  race still needs a guard: if `onOpen` fires after the abort flag is set, call `cancel()` on that socket.
- **web variant (not ported)**: `HtmlWebSocketChannel.connect(uri)`; `abort()` = `channel.innerWebSocket.close()`, i.e. the browser
  `WebSocket.close()`, which also works while CONNECTING. No dedicated client, no aborted flag, no late-socket guard (the browser socket
  object is the same before and after the handshake, so closing it is sufficient). Observable behaviour (limits, timeout, partial results,
  teardown order) is identical to the io variant; there is no web-only difference the Android port must reproduce.

### 5.4 njump
`https://njump.me/<npub>` opened via `ACTION_VIEW` (external app/browser).

### 5.5 Blossom (BUD-02)
Servers tried in order, first success wins:
```
https://blossom.band
https://blossom.primal.net
https://blossom.nostr.build
```
Request per server: `PUT <server>/upload`, headers `Authorization: Nostr <base64>` and `Content-Type: <mime>`, body = raw file bytes
(Content-Length set by the client), timeout **45 s** per server. Success iff status 200 or 201 **and** JSON body has non-empty `url`
(converted with `toString()`); 2xx without url → `lastError="Antwort ohne URL"`, try next. Other status → `lastError = header "x-reason" ?: "HTTP <code>"`.
Exception → `lastError = e.toString()`. All failed → `failure(lastError ?: "Kein Server erreichbar")`.
MIME by lower-cased extension after the last `.`: `jpg|jpeg → image/jpeg`, `png → image/png`, `webp → image/webp`, `gif → image/gif`, else `application/octet-stream`.
Early failures: file missing `"Datei nicht gefunden"`, empty `"Datei ist leer"`, signing failed `"Signieren fehlgeschlagen"`.
These raw German strings are shown to the user via `evBadgeUploadFailed(error)` by the caller.

### 5.6 Profile pictures
Any `http`/`https` URL from kind-0 `picture`. Loaded by the image loader (Flutter: in-memory cache only). Cleartext is blocked by the
app's network security config except `*.onion`, `*.local`, `127.0.0.1`, `localhost` → `http://` pictures fail and show the letter fallback.
Keep the same `network_security_config.xml` natively.

No retries, rate limits or HTTP caching headers are used anywhere in this domain (beyond what is described).

---

## 6. Nostr

### 6.1 Kinds
| Kind | NIP | Use here | Direction |
|---|---|---|---|
| 0 | NIP-01 metadata | `picture`, `display_name`, `name` | read |
| 10002 | NIP-65 relay list | `r` tags → author write relays for profile lookup | read (+cached) |
| 30078 | NIP-78 app data, `d = "einundzwanzig-reputation"` | `content.identity.nickname` as name fallback (event published by reputation domain) | read |
| 24242 | Blossom auth (BUD-01/02) | upload authorization | sign (not published) |
| 21000 | custom, legacy | `NostrService.sign/verify/signBadge` — **no callers in lib/**; badge_security (other domain) uses kind 21000 itself | sign |
| 24133 | NIP-46 | uses this domain's NIP-44 for content encryption (NIP-46 domain) | — |

NIPs relevant: 01 (events, ids, REQ/EOSE/CLOSED, replaceable ordering), 19 (npub/nsec), 44 v2, 65, 78, 49 (export; implemented elsewhere),
46/55/07 (signer modes; elsewhere), Blossom BUD-01/02.

### 6.2 Replaceable semantics
* Profile lookup (`_newest`): highest `created_at` wins; equal `created_at` → **lexicographically smallest `id`** wins (NIP-01). Candidates
  include the cached event, so older relay answers can never downgrade the cache. A newer kind 0 without a valid `picture` removes the picture.
  A newer kind 10002 with no `r` tags removes all author hints.
* Name service per relay: an event replaces the current best if `created_at >= best` (later arrival wins ties). Across relays in
  `fetchDisplayName`: strictly greater wins (first relay in list order wins ties). Batch `_batchNames` per relay and `_mergeNewest`
  across relays: strictly greater (first wins ties).
* "Not too far in the future" differs by path: profile lookup rejects `created_at > now` (0 s tolerance); name service and all other
  domains use `replaceableCreatedAt` (≤ now + 600 s).

### 6.3 Blossom auth event (kind 24242) — exact
```dart
SigningService.signEvent(
  kind: 24242,
  content: 'Bild fuer ein Einundzwanzig-Event-Badge',
  tags: [
    ['t', 'upload'],
    ['x', digest],            // sha256 of the file bytes, lowercase hex
    ['expiration', '${DateTime.now().add(const Duration(minutes: 10)).millisecondsSinceEpoch ~/ 1000}'],
  ],
);
authHeader = 'Nostr ${base64.encode(utf8.encode(jsonEncode(signed.toJson())))}';
```
Signed **once** and reused for all servers (Amber/remote signer = one prompt). base64 = standard alphabet with `=` padding.
`created_at` = signer's "now". Signing goes through the active signer (local/Amber/NIP-46).

### 6.4 Legacy kind 21000 (port for API parity only)
`sign(data)` = signEvent(kind 21000, tags [], content data) → returns `sig`. `verify(data, signature, pubkeyHex, eventId, createdAt, tags=[], kind=21000)`
= construct event with the given id/created_at and run the standard validity check. `signBadge(meetupId, meetupName, date, blockHeight)`:
content = `jsonEncode({"meetup_id","meetup_name","date","block_height","npub","timestamp": ms})`, tags `[["meetup",id],["block","<height>"]]`.
None of these is called from the Flutter UI today.

### 6.5 Signer relationship
Signing never happens in this domain directly except via `SigningService.signEvent(kind, tags, content)` (signing domain). The local
signer uses `Event.from` = id per §7.1 + BIP-340 with 32 random aux bytes.

---

## 7. Crypto & encoding

### 7.1 Event id (byte-exact)
```dart
List data = [0, pubkey.toLowerCase(), createdAt, kind, tags, content];
String serializedEvent = json.encode(data);
Uint8List hash = SHA256Digest().process(Uint8List.fromList(utf8.encode(serializedEvent)));
return hex.encode(hash);   // lowercase
```
Dart `json.encode` string escaping (must be reproduced exactly; do **not** use org.json, Gson-with-HTML-escaping or Moshi defaults):
* `"` → `\"`, `\` → `\\`
* U+0008 `\b`, U+0009 `\t`, U+000A `\n`, U+000C `\f`, U+000D `\r`
* other U+0000..U+001F → `\u00xx` (**lowercase** hex)
* lone UTF-16 surrogates → `\udxxx` (lowercase hex)
* everything else literal (no escaping of `/`, U+007F, U+2028/2029, `<>&`, non-ASCII), then UTF-8.
* Integers as plain decimal, no whitespace anywhere.
Recommendation: a tiny hand-written serializer `NostrJson.canonicalIdPayload(...)` used **only** for id computation. kotlinx.serialization's
string escaping matches except for lone surrogates (it emits them raw → `?` after `toByteArray`).

### 7.2 Validity check (= `Event.isValid()` in nostr 1.5.0) — used by `verifiedEvent`, `NostrService.verify`, lookup `_validEvent`
```dart
bool isValid() {
  String verifyId = getEventId();
  if (createdAt.toString().length == 10 && id == verifyId && bip340.verify(pubkey, id, sig)) return true;
  return false;
}
```
Rules to replicate:
* pubkey is lower-cased **before** id computation and verification.
* `created_at` decimal string must have exactly 10 characters (i.e. 1 000 000 000 ≤ t ≤ 9 999 999 999 for positive values).
* `id` must equal the recomputed id **case-sensitively** (uppercase ids are rejected).
* BIP-340 verify of 32-byte message = id bytes. Malformed hex anywhere → invalid (never throw).
* Implementation detail of bip340 0.2.0: sig is left-padded to 128 hex and only the first 64 bytes are used; pubkey left-padded to 64.
  Native should require exactly 64/128 hex chars (stricter; only affects malformed events).

`verifiedEvent(raw)` builds the event with defaults: `id ?: ""`, `pubkey ?: ""`, `created_at ?: 0`, `kind ?: 0`, `tags ?: []` (each element
`toString()`-ed), `content ?: ""`, `sig ?: ""`. Wrong JSON types (e.g. non-int kind, non-string content, non-list tags) → reject.
Native: any non-string tag element → reject (equivalent outcome). Returns the **raw** object if valid, else `null`; increments
`rejectedEventCount` and logs (debug) at count 1 and every multiple of 10:
`Event mit ungültiger Signatur verworfen (kind <kind>, gesamt: <n>)`.

Lookup `_validEvent(raw, pubkey, now)` additionally requires: object; `raw.pubkey == pubkey` (exact); `kind ∈ {0, 10002}`; `created_at` int,
`0 ≤ created_at ≤ now_seconds`; full verification (tags elements must be strings); for kind 0 the content must parse to a JSON **object**.

### 7.3 Keys & NIP-19
* Key generation: 32 bytes `SecureRandom` → private hex; public = x-only of d·G, 64 lowercase hex (left-padded). Native: also check `0 < d < n` (retry).
* `importPrivHex(hex)`: `trim().lowercase()`, must match `^[0-9a-f]{64}$` else `FormatException('Ungültiger privater Schlüssel (hex).')`; derive npub/nsec; save.
  Native: also reject `d == 0 || d >= n` with the same message (Dart would crash inside the key derivation).
* `importNsec(nsec)`: trim; must start with `nsec1` else `FormatException('Ungültiges Format. Der Key muss mit "nsec1" beginnen.')`;
  bech32 decode + `importPrivHex`; any error → `FormatException('Ungültiger nsec Key: $e')`.
* `importNcryptsec(ncryptsec, password)`: `Nip49.decrypt(trim, password)` → `importPrivHex`.
* NIP-19 encode: bech32 (BIP-173 constant 1, **not** bech32m), hrp `npub`/`nsec`, 8→5 bit with padding. Decode: max length 90,
  mixed case rejected (all-upper accepted, lower-cased), checksum verified, 5→8 bit without padding (error if ≥5 leftover bits or non-zero pad).
  **`decodePubkey` returns `""` (no exception) when the hrp is not `npub`**; payload length is not checked.
* `isValidNpub(s)`: `s.startsWith("npub1")` (case-sensitive) and decode does not throw. `isValidNsec` analogous.
* `npubToHex`, `hexToNpub`, `nsecToHex` = raw NIP-19 helpers.
* `shortenNpub(npub, chars = 8)`: `if (npub.length < chars*2+5) npub else npub.substring(0, 4+chars) + "..." + npub.takeLast(chars)` (three ASCII dots).

### 7.4 NIP-44 v2 (byte-exact; must pass the official vectors)
```
conversation_key = HMAC-SHA256(key = utf8("nip44-v2"), data = x32(ECDH(priv, lift_x_even(pub))))     // = HKDF-Extract
keys(76)         = HKDF-Expand(PRK = conversation_key, info = nonce(32), L = 76)
chacha_key = keys[0..32), chacha_nonce = keys[32..44), hmac_key = keys[44..76)
padded     = u16_be(len) || plaintext_utf8 || zeros   (total 2 + calcPaddedLen(len))
ciphertext = ChaCha20-IETF(RFC 7539, 12-byte nonce, counter starts at 0)(chacha_key, chacha_nonce, padded)
mac        = HMAC-SHA256(hmac_key, nonce || ciphertext)
payload    = base64_std_padded( 0x02 || nonce(32) || ciphertext || mac(32) )
```
Verbatim padding function:
```dart
static int calcPaddedLen(int unpaddedLen) {
  if (unpaddedLen <= 32) return 32;
  final nextPower = 1 << (unpaddedLen - 1).bitLength;
  final chunk = nextPower <= 256 ? 32 : nextPower ~/ 8;
  return chunk * ((unpaddedLen - 1) ~/ chunk + 1);
}
```
Kotlin: `(n-1).bitLength` = `32 - Integer.numberOfLeadingZeros(n-1)`.

Validation (all failures throw `Nip44Exception`, messages are free text):
* privkey: 64 hex (case-insensitive) → 32 bytes, `0 < d < n`.
* pubkey: 64 hex → 32 bytes; lift with prefix `0x02`; reject if not on curve / infinity (covers twist points).
* encrypt: plaintext UTF-8 length 1..65535.
* decrypt order: (1) payload starts with `#` → unknown version; (2) `payload.length` (chars) in 132..87472; (3) base64 decode (invalid → error);
  (4) decoded length 99..65603; (5) `data[0] == 2`; (6) MAC check **constant-time** before decrypting; (7) unpad: `len = u16be`, `1 ≤ len ≤ 65535`
  and `padded.size == 2 + calcPaddedLen(len)`; (8) UTF-8 decode of `padded[2 .. 2+len)`.
* Nonce: 32 bytes from CSPRNG. A `nonce` parameter exists only for tests.
* ECDH output = the **raw x coordinate** (32 bytes big-endian), *not* libsecp256k1's default hashed ECDH. With secp256k1-kmp:
  `val shared = Secp256k1.pubKeyTweakMul(byteArrayOf(2) + pubX, priv); x = shared.copyOfRange(1, 33)` after `pubkeyParse` validation
  and `secKeyVerify(priv)`.
* Recommended primitives: BouncyCastle `HKDFBytesGenerator(SHA256Digest())` with `HKDFParameters.skipExtractParameters(ck, nonce)`,
  `ChaCha7539Engine`, `javax.crypto.Mac("HmacSHA256")`, `java.util.Base64.getEncoder()/getDecoder()` (strict). Dart's decoder also accepts
  the URL-safe alphabet; no peer produces that, so strict decoding is acceptable.
* Property: `conversationKey(a, B) == conversationKey(b, A)`.

### 7.5 SHA-256 / hex
Blossom `x` tag and upload hash = SHA-256 over the exact uploaded bytes, lowercase hex. Base64 for Blossom header: standard, padded.

---

## 8. Business rules & algorithms

### 8.1 Active relays
```
active = defaultRelays.filter { it !in disabled } + customRelays   // then de-duplicate keeping first occurrence
```
`getRelayStatus()` = defaults→!disabled, then custom→true.

### 8.2 Relay URL validators (two different ones — keep both)
**User input — `RelayConfig.isWellFormedRelayUrl(url)`** (trimmed):
1. literally starts with `wss://` (case-sensitive), parses as URI with scheme `wss`;
2. host non-empty, contains `.`, does not start or end with `.`, contains no space;
3. path empty or `/`; no query; no fragment. (User-info and port are not checked.)
`addCustomRelay(url)`: trimmed → invalid? `INVALID_URL` → already in custom list **or** default list (exact string compare) → `ALREADY_PRESENT`
→ `probeRelay` false → `UNREACHABLE` → append the trimmed string as typed → `ADDED`. (`wss://nos.lol/` is not detected as duplicate of `wss://nos.lol`; parity.)
`removeCustomRelay(url)`: remove exact string. `setDefaultRelayEnabled(url, enabled)`: remove from / add (once) to the disabled list.

**Lookup normalisation — `NostrProfileLookup.relayUrl(value, discovered = false)`** (verbatim logic):
```dart
final uri = Uri.tryParse(value.trim());
if (uri == null || uri.scheme != 'wss' || uri.host.isEmpty || uri.userInfo.isNotEmpty || uri.hasFragment) return null;
final host = uri.host.toLowerCase().replaceFirst(RegExp(r'\.$'), '');
if (discovered) {
  if (!host.contains('.') || host == 'localhost' || host.endsWith('.local') || host.endsWith('.localdomain') ||
      host.endsWith('.localhost') || host.contains(':') || host.contains('[')) return null;
  final parts = host.split('.').map(int.tryParse).toList();
  if (parts.every((p) => p != null)) {
    if (parts.length != 4 || parts.any((p) => p! < 0 || p > 255)) return null;   // also rejects 127.1
    final a = parts[0]!, b = parts[1]!;
    if (a == 0 || a == 10 || a == 127 || a >= 224 || (a == 169 && b == 254) ||
        (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 100 && b >= 64 && b <= 127)) return null;
  }
}
return uri.path == '/' && !uri.hasQuery ? uri.replace(path: '').toString() : uri.toString();
```
Kotlin notes: Dart `Uri` lower-cases scheme **and host** in `toString()`; keeps explicit ports for `wss`; IPv6 host is reported without
brackets (so `:` check rejects it). `int.tryParse` accepts optional `+`/`-` and `0x` hex — emulate (any label parseable that way counts as numeric).
Output examples: `wss://relay.example/` → `wss://relay.example`; `wss://relay.example/path` unchanged.

### 8.3 Profile picture lookup (`NostrProfileLookup.fetchPicture(pubkey)`)
Constants: `cacheDuration = 12 h`, `indexRelay = "wss://purplepag.es"`, `maxInitialRelays = 8`, `maxAuthorRelays = 4`.
1. `pubkey` must match `^[0-9a-f]{64}$` else return null (no network).
2. Coalesce: if a fetch for this pubkey is pending, return the same deferred. Remove from the pending map on completion only if the
   generation did not change.
3. Read metadata cache; `cached = validEvent(value.event)` and kind must be 0 (else null); `fetchedAt = value.fetchedAt as Int`
   (if that read throws, `cached` stays set and `fetchedAt = 0`). If `cached != null && isFresh(fetchedAt)` → return `picture(cached)`.
   `isFresh(at) = at > 0 && 0 ≤ now−at < 12 h` (ms).
4. Legacy: `legacy = pictureUrl(prefs["nostr_profile_picture_<hex>"])`, `legacyAt = prefs["nostr_profile_picture_time_<hex>"] ?: 0`.
   If `cached == null && legacy != null && isFresh(legacyAt)` → return legacy (no network).
5. `configured = getActiveRelays()` (exception → `defaultRelays`).
   `initial = LinkedHashSet( configured.mapNotNull(relayUrl).filter{ != indexRelay }.distinct().take(7) ) + indexRelay`.
6. `cachedRelays = validEvent(prefs["nostr_profile_relays_v2_<hex>"])` with kind 10002 (else null).
7. `events = queryAll(initial)` — all relays **in parallel**; each: `query(relay, pubkey)` (errors → []), `.take(8)`, keep only `validEvent`s.
8. `relayList = newest(cachedRelays + events.kind10002)`. If non-null: iterate its tags in order:
   skip unless `tag.size ≥ 2 && tag[0] == "r"`; skip if `tag.size > 2 && tag[2] != "write"`;
   `relay = relayUrl(tag[1], discovered = true)`; add if non-null and not in `initial` (set semantics); stop when 4 collected.
   `events += queryAll(authorRelays)` (one level only, no recursion).
9. `latestRelays = newest(cachedRelays + events.kind10002)`; if non-null and generation unchanged → write `toJson()` to relay cache.
10. `latest = newest(cached + events.kind0)`; `receivedProfile = events.any{kind==0}`. If `latest != null && receivedProfile && generation unchanged`
    → write metadata cache `{"event": latest, "fetchedAt": now_ms}` and **remove** both legacy keys.
11. Return `latest?.let(picture) ?: legacy` — i.e. if a signed profile exists its picture (possibly null) wins; legacy URL is only an outage fallback.
`picture(ev) = pictureUrl(parse(ev.content)["picture"])`; `pictureUrl(v)`: must be non-empty String, parse as URI, scheme `http|https`, host non-empty,
no user-info → return `uri.toString()`, else null.
No negative caching: if no kind 0 was received the cache is not touched and the next call queries again.
`invalidate()`: `generation++`, clear pending (running fetches still resolve their callers but cannot write caches).

### 8.4 Display names
**`fetchDisplayName(hex)`** (single; used by NpubChip, chat):
1. Empty → null. If the in-memory cache contains the key (even with null) → return it.
2. `relays = getActiveRelays()`; **sequentially** per relay `fetchNameFromRelay` → `(created_at, name)`; keep strictly newer across relays.
   Per relay: verified + `answersFilter` + `replaceableCreatedAt != null` + `at ≥ best` ; name = trimmed `display_name` if non-empty else trimmed `name` or `""`
   (non-object content or non-string fields → event ignored). Completes on `EOSE`, error, close, or 6 s.
3. If any kind-0 was seen and the newest name is empty → `null` (a profile without name **clears** the name; nickname fallback is not used).
4. If no kind-0 seen: sequentially per relay `fetchNicknameFromRelay` (kind 30078, `#d`), newest wins; usable nickname =
   `identity.nickname` trimmed, non-empty, `lowercase != "anon"`, else `""`. Result may be `""`.
5. Exceptions → keep whatever was found. Store in memory cache (including null/""), return.
Parallelising the relay calls natively is fine **if** the newest-wins/tie rules produce identical results.

**`cachedDisplayNames()`**: read `nostr_display_names_v1`, return `{hex: n}` for entries whose `n` is a String; also `putIfAbsent` into the memory cache. Errors → empty.

**`fetchDisplayNames(hexes)`** (batch; trust network):
```
wanted = non-empty distinct
stored = parse(nostr_display_names_v1)  (errors → {})
for hex in wanted:
  s = stored[hex]
  if s.n is non-empty String: result[hex]=n; mem.putIfAbsent(hex,n); if s.t is Int && now-t < 1 day: continue
  else if mem.contains(hex): if mem[hex] non-empty: result[hex]=mem[hex]; continue
  missing += hex
if missing empty → return result
relays = getActiveRelays() (error → []); if empty → return result
profiles = {}; for chunk of 100 in missing: answers = parallel over relays batchNames(chunk, kinds=[0], d=null, parseProfileName); mergeNewest(answers, profiles)
noProfile = missing - profiles.keys
fresh = { hex: profiles[hex] for hex in missing if non-empty }
for chunk of 100 in noProfile: answers = parallel batchNames(chunk, kinds=[30078], d="einundzwanzig-reputation", parseNickname); mergeNewest(answers, fresh)
for hex in missing:
  if fresh[hex] non-empty: result[hex]=…; mem[hex]=…; stored[hex]={"n":…,"t":now}
  else if profiles[hex] == "": result.remove(hex); mem[hex]=null; stored.remove(hex)      // newest profile removed the name
  else: mem.putIfAbsent(hex, null)                                                      // keep stale stored name (relay outage)
write stored (whole map); log diag "Namen: <missing> abgefragt, <fresh> gefunden." (tag NostrProfile)
return result
```
`batchNames(relay, authors, kinds, d, parse)`: connect (6 s), REQ (see §5.2), for each EVENT: verified + answersFilter + `replaceableCreatedAt`,
hex = `ev.pubkey` (non-empty), name = `parse(content) ?: ""`, keep strictly newer per hex; EOSE → done; wait ≤6 s; close.
`mergeNewest(answers, into)`: per hex strictly greatest `created_at` across answers (first answer wins ties), then `into.putIfAbsent`.
`parseProfileName` = trimmed display_name non-empty → it; trimmed name non-empty → it; else null. `parseNickname` as above (null if unusable).

### 8.5 `answersFilter(event, filter)` (verbatim semantics)
* `kinds` list present → must contain `event.kind`.
* `authors` list present → must contain `event.pubkey` (exact).
* If `since` or `until` present → `created_at` must be int; `since` int → `created ≥ since`; `until` int → `created ≤ until`.
* For every key `#X` (length ≥ 2): value must be a **non-empty list** (else false); event `tags` must be a list; need a tag with
  `size ≥ 2 && tag[0] == X && value.contains(tag[1])`. Tag names are case-sensitive (`#p` ≠ `#P`).
* `limit`, `ids` and anything else are ignored.

### 8.6 `replaceableCreatedAt(event)` → `created_at` if int, `≥ 0` and `≤ now_s + 600`, else null.

### 8.7 Avatar/npub display rules
`NpubChip.shorten` (head 10 / tail 8 / `…`) vs `NostrService.shortenNpub` (12 chars + `...` + 8) — both used; keep both.

### 8.8 Home screen picture precedence (caller rule)
`local_profile_picture` non-empty → show local file, never fetch; else fetch with the active npub (also for external signers and
public-only identities). Late responses for a previous identity are discarded (request counter + npub comparison). Picking a new local
image (gallery, max 400×400, quality 80) bumps the counter and writes `local_profile_picture`.

---

## 9. Security measures that must be preserved

| ID / origin | Measure |
|---|---|
| H2/M1 (commit a6151f1, 264f038) | Every incoming EVENT is id+signature verified centrally before parsing; invalid events are dropped, counted, rate-limited logging; never throw. No double verification. |
| 263648a | Signed is not enough: event must match the sent filter (`answersFilter`); replaceable events from >10 min in the future cannot win. |
| M4 | Relay frames > 512 KiB chars are discarded unparsed. |
| Profile discovery (#70, docs) | Only read requests with the public key; per-relay hard limits (6 s total incl. handshake, 64 frames, 64 KiB/frame, 8 events); ≤8 initial relays incl. index; ≤4 author relays, no recursion; discovered relays must be `wss`, no user-info/fragment, no local names, no private/reserved IPv4 literals, no IPv6 literals; abortable handshake; close without awaiting. Cached events re-verified on read; author + kind + JSON-object + not-in-future checks. |
| Picture URLs | Only http/https, host required, no embedded credentials (blocks `file:`, `data:`, `javascript:`). Cleartext blocked by network security config. |
| M3 (c19bfbf) | Device authentication (biometric or device credential) before showing a freshly generated nsec, an existing nsec, or exporting ncryptsec; cancel shows nothing; devices without any lock are allowed through. |
| M3 | FLAG_SECURE while the nsec dialog or ncryptsec sheet is open; secrets copied with auto-clear after 45 s if the clipboard still holds the same text. Natively also mark the clip as sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`, API 33+). |
| Export lock (a635494, eb346ce) | Export flag set before device auth; no parallel dialogs/scrypt runs (~64 MiB each). |
| Vault binding | Existing password wrap is only reused if bound to the current npub; generating/importing a key clears all EasyAuth wraps (prevents exporting another identity). |
| Signer switch | Confirmation before a signer replaces an existing identity; warning when the npub changes; local keys are never deleted by connecting/disconnecting signers. |
| Stale-write guards | Lookup generation counter (no cache refill after reset); avatar/home request counters (late answers of previous identity ignored). |
| `clearCache` | Removes metadata cache, relay hints, legacy picture keys and the local picture path on app reset. |
| H4 (a53396f) | Web-only: no local nsec in browser. N/A on Android, but do not add a web-like weaker key store. |
| Blossom | Auth event bound to file hash, 10-minute expiration, signed once. |
| Nickname | "anon" (case-insensitive) is rejected as a nickname and treated as "no name" in lookups. |
| SecurityAudit Fix 1 | Keys only in secure storage (Keystore-backed), never in plain prefs (identity domain). |

---

## 10. Tests & vectors to port

Fixture: copy `test/fixtures/nip44.vectors.json` verbatim (sha256 `269ed0f69e4c192512cc779e78c555090cebc7c785b609e338a62afc3ce25040`,
from github.com/paulmillr/nip44) to the native test resources. Additional deterministic vectors computed for this spec are in
`android-native/spec/fixtures/07-nostr-infra-vectors.json`.

### 10.1 `test/nip44_vectors_test.dart`
* `get_conversation_key`: 35 cases, `hex(conversationKey(sec1, pub2)) == conversation_key`.
* `get_message_keys`: 32 cases with fixed `conversation_key = a1a3d60f3470a8612633924e91febf96dc5366ce130f658b1f0fc652c20b3b54`; check chacha_key / chacha_nonce / hmac_key per nonce.
  First: nonce `e1e6f880560d6d149ed83dcc7e5861ee62a5ee051f7fde9975fe5d25d2a02d72` → chacha_key `f145f3bed47cb70dbeaac07f3a3fe683e822b3715edb7c4fe310829014ce7d76`, chacha_nonce `c4ad129bb01180c0933a160c`, hmac_key `027c1db445f05e2eee864a0975b0ddef5b7110583c8c192de3732571ca5838c4`.
* `calc_padded_len`: 24 pairs: 16→32, 32→32, 33→64, 37→64, 45→64, 49→64, 64→64, 65→96, 100→128, 111→128, 200→224, 250→256, 320→320, 383→384, 384→384, 400→448, 500→512, 512→512, 515→640, 700→768, 800→896, 900→1024, 1020→1024, 65536→65536.
* `encrypt_decrypt`: 10 cases; ck from (sec1, pub(sec2)) **and** reverse (sec2, pub(sec1)) equal `conversation_key`; `encrypt(plaintext, ck, nonce) == payload`; `decrypt(payload) == plaintext`.
  First: sec1 `…01`, sec2 `…02`, ck `c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d`, nonce `…01`, plaintext `a`, payload
  `AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb`.
* `encrypt_decrypt_long_msg`: 3 cases (`x`×65535, `!`×65535, `🦄`×16383): sha256(plaintext) check, sha256(payload) == `payload_sha256`, roundtrip.
* Invalid `get_conversation_key` (8): sec1 > n, sec1 = 0, pub2 all-ff (no sqrt), sec1 == n, pub2 no sqrt, twist points of order 3, 13, 3319 → must throw.
* Invalid `encrypt_msg_lengths` [0, 65536, 100000, 10000000] → `encrypt("a"*len, zeroKey)` throws.
* Invalid `decrypt` (12): unknown version (×2: `#…`, version 0), invalid base64, invalid MAC ×2, invalid padding ×3, payload lengths 0/4/48/92 → throw.
* Two encryptions of `hallo` with key 0×32 differ (random nonce).
* Payload made with key A does not decrypt with key B (`…01`): throws; with A returns `geheim`.
* Private key out of range with valid pub (`pub(01×32)`): sec 0, n, ff…ff throw; sec `02×32` works (32-byte result).
* Short hex (`ab`, `cd`) throws.

### 10.2 `test/nip44_web_smoke_test.dart` (subset, literal values)
* ck(`315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268`, `c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133`) = `3dfef0ce2a4d80a25e7a328accf73448ef67096f65f79588e358d9a0eb9013f1`.
* ck(`…01`, `c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5`) = `c41c…412d` and encrypt `a` with nonce `…01` = payload above; decrypt back.
* bad MAC: key `cff7bd6a3e29a450fd27f6c125d5edeb0987c475fd1e8d97591e0d4d8a89763c`, payload `Agn/l3ULCEAS4V7LhGFM6IGA17jsDUaFCKhrbXDANholyySBfeh+EN8wNB9gaLlg4j6wdBYh+3oK+mnxWu3NKRbSvQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` → throw.
* bad padding: key `5254827d29177622d40a7b67cad014fe7137700c3c523903ebbe3e1b74d40214`, payload `Anq2XbuLvCuONcr7V0UxTh8FAyWoZNEdBHXvdbNmDZHB573MI7R7rrTYftpqmvUpahmBC2sngmI14/L0HjOZ7lWGJlzdh6luiOnGPc46cGxf08MRC4CIuxx3i2Lm0KqgJ7vA` → throw.
* Roundtrip of `Grüße vom Meetup 🧡` with random nonce.

### 10.3 `test/relay_event_signature_test.dart` (`verifiedEvent`, `answersFilter`, `replaceableCreatedAt`)
Signed kind-31923 event (tags `d=test-meetup`, `title=Echtes Meetup`, `start=now+86400`):
* valid → returned (and parses as calendar event with title "Echtes Meetup" — calendar domain).
* `sig = ""` → null. Tampered title tag → null. Pubkey replaced by another key → null. id replaced by `ab×32` → null.
* sig `zz×64` and pubkey `xyz` → null (no throw).
* `answersFilter`: `{kinds:[31923], authors:[pk], #d:[test-meetup]}` true; kinds `[0]` false; other author false; `#d:[anderer-termin]` false.
* `replaceableCreatedAt(valid) == created_at`; with `created_at = now+3600` → null.
* `verifiedEvent("kein objekt")` and `{kind:"x", tags:42}` → null; `rejectedEventCount > 0`.

### 10.4 `test/zap_sent_filter_test.dart` (filter matching part)
Receipt `{kind:9735, pubkey:"provider", created_at: since+10, tags:[["p","aa"]]}`: matches received-filter (`#p:["aa"]`), not sent-filter (`#P:["aa"]`).
Tags `[["p","bb"],["P","aa"]]`: matches sent, not received. (Filter builder belongs to zaps domain.)

### 10.5 `test/relay_socket_test.dart`
`connect("ws://127.0.0.1:1")` with 5 s timeout must throw (connection error or timeout), not return.

### 10.6 `test/profile_relay_client_test.dart` (local WebSocket server; use OkHttp MockWebServer WebSocket upgrade)
* REQ equals `["REQ","profile",{"kinds":[0,10002],"authors":["test-author"],"limit":2}]`; server sends `invalid JSON`, `["EOSE","other"]`, `["EVENT","other",{...}]`, `["EVENT","profile",{"kind":0}]`, `["EOSE","profile"]` → result `[{"kind":0}]`; server sees the socket closed within 2 s.
* Timeout 150 ms, server sends one EVENT and no EOSE → result `[{"kind":0}]`, socket closed.
* 20 EVENTs → exactly 8 returned.
* One frame of 64 KiB + 1 chars → empty result.
* 65 noise frames → empty result.
* Connection refused (`ws://127.0.0.1:1`, 100 ms) → empty, no unhandled error.
* Stalled handshake (raw TCP server that never answers the upgrade), 150 ms → empty and server sees disconnect within 2 s.

### 10.7 `test/nostr_profile_lookup_test.dart` (inject relays/query/clock; key = privkey `…01`; now = 2026-10-06T00:00Z; events `created_at = now − age`, default age 10, content `{"picture":"https://images.example/avatar.png"}`)
1. First import: index returns profile → image; calls include app relay and index; second call → cached, no calls.
2. NIP-65: index returns profile(age 100) + 10002 tags `[r read.example read]`, `[r write.example/ write]`, `[r both.example]`, `[r write.example]`; write.example returns newer profile `new.png` → `new.png`; write.example queried exactly once; both.example queried; read.example not. After +13 h with index silent → still `new.png` and write.example queried (relay hints from cache).
3. Older list (app relay, `old.example`) vs newer empty list (index) → null and old.example not queried.
4. Two equal-timestamp profiles + an older one, arrival order b, old, a → picture of the event with the smaller id.
5. Rejects tampered content, other author (`…02`), kind 1, future (age −1), content `[]`, content `not JSON`; valid one still → image.
6. Stale signed cache survives outage (+13 h, no answers → image) and an older response (age 100000, `old.png`) cannot downgrade → image.
7. Picture removal: after cached image, +13 h, legacy key set to image, relay returns `{}` → null; next call no network (fresh cache) → null; +13 h outage → null; legacy key removed.
8. Legacy migration: legacy URL + time=now → image with no calls; +13 h (no answers) → image (fallback); with answer → image, metadata key exists, legacy key gone.
9. Invalid cache (kind 10002 stored in metadata key) + invalid key `npub-invalid` → null, no calls; valid pubkey no answers → null; with answer → image; `"0"*64` → null (cache isolation).
10. Coalescing: two concurrent calls → one query; `invalidate()` before completion; both get image; metadata key **not** written.
11. Discovered relay list with `ws://insecure.example`, `wss://127.0.0.1`, `wss://10.0.0.1`, `wss://user:pass@host.example`, `wss://localhost`, `wss://relay0..9.example` → exactly 4 `wss://relay*` calls, 6 calls total.
12. 30 configured relays, all throw except index → image; exactly 8 calls.
13. `pictureUrl`: null, 12, "", `file:///tmp/picture`, `data:image/png,abc`, `javascript:alert(1)`, `https://user:pass@images.example/a` → null; valid https → same string.
14. Author relay returns a newer list pointing to `new.example` → not queried in the same round (null); next round with only new.example answering → image, new.example queried.
15. `clearCache()` removes metadata/relays/legacy/legacy-time/local picture, preserves `nickname`.
16. `relayUrl(discovered=true)` rejects hosts `127.1, 10.1, 192.168.1.1, 172.16.0.1, 169.254.1.1, 100.64.0.1, 0.0.0.0, 999.1.1.1, [::1], relay.local, relay.local., localhost.localdomain, 127.0.0.1.`; `wss://relay.example/` → `wss://relay.example`; `wss://relay.example/path` unchanged.

### 10.8 `test/nostr_avatar_test.dart` (Compose UI tests with a fake loader; image requests never complete unless told)
* Late result of previous identity (alice) after switching to bob does not replace bob's picture.
* Identity switch immediately clears the old picture and shows letter `A` (fallback "Alice"); null result keeps the letter.
* Failed image download → letter fallback.
* `pubkeyHex = null` with NIP-07-mode public identity (`fa5e3477d2d6b92d667dcab66c8bbb1527014599c5eecddd545e3a39d7870268`) → loader queried with that hex → picture shown (native: use Amber/bunker restore equivalent).
* Completion after disposal → no exception.

### 10.9 `test/user_profile_test.dart` (identity domain; relevant to avatar resolution)
Public npub in `nostr` pref without private key → `hasNostrKey=false`, npub resolvable; restored NIP-46 without session → `canSign=false`, npub preserved.

### 10.10 `test/signer_nip44_test.dart` (signing domain; uses this NIP-44)
Local signer NIP-44 roundtrip and interoperability with `Nip44.conversationKey(priv, otherPub)`.

### 10.11 Additional vectors for native unit tests (computed/verified for this spec)
* NIP-19 (official): pub `7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e` ↔ `npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg`;
  priv `67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa` ↔ `nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5`;
  priv `…01` ↔ `nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl`, pub `79be667e…f81798` ↔ `npub10xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpkge6d`.
* Valid signed events (BIP-340 verified):
  `{id:4b697394206581b03ca5222b37449a9cdca1741b122d78defc177444e2536f49, pubkey:981cc2078af05b62ee1f98cff325aac755bf5c5836a265c254447b5933c6223b, created_at:1672175320, kind:1, tags:[], content:"Ceci est une analyse du websocket", sig:797c47bef50eff748b8af0f38edcb390facf664b2367d72eb71c50b5f37bc83c4ae9cc9007e8489f5f63c66a66e101fd1515d0a846385953f5f837efb9afe885}`;
  `{id:67bd60e47d7fdddadebff890143167bcd7b5d28b2c3008eae40e0ac5ba0e6b34, pubkey:36685fa5106b1bc03ae7bea82eded855d8f56c41db4c8bdef8099e1e0f2b2afa, created_at:1674403511, kind:1, tags:[], content:"Block 773103 was just confirmed. The total value of all the non-coinbase outputs was 61,549,183,849 sats, or $14,025,828", sig:4912a6850a711a876fd2443771f69e094041f7e832df65646a75c2c77989480cce9b41aa5ea3d055c16fe5beb7d11d3d5fa29b4c4046c150b09393c4d3d16eb4}`.
* Escaping vector: pubkey `79be667e…f81798`, created_at 1700000000, kind 30078, tags `[["d","einundzwanzig-reputation"],["t","Ünï"]]`,
  content = `line1⏎line2⇥"q" \ / U+0001 U+007F ä€😀 U+2028 <&>` → id `7152cbaa303fca438bcb1f1490f20614a7989caa620127eec874c909a9f7a275`
  (exact strings in the fixture JSON).
* Suggested extra tests: created_at with 9 or 11 digits → invalid; uppercase id → invalid; uppercase pubkey with id computed on the lowercase pubkey → valid.

---

## 11. Android capabilities / permissions & recommended APIs

| Need | Recommended native API |
|---|---|
| WebSockets to relays | OkHttp 5 `WebSocket` (`newWebSocket`), coroutine wrapper; `cancel()` for abort (also aborts handshake). Permission `INTERNET`. |
| Schnorr (BIP-340), key derivation, ECDH | `fr.acinq.secp256k1:secp256k1-kmp-jni-android` (`signSchnorr`, `verifySchnorr`, `pubkeyCreate`, `pubKeyTweakMul`, `pubkeyParse`, `secKeyVerify`) |
| SHA-256, HMAC, HKDF, ChaCha20 (RFC 7539) | `java.security.MessageDigest`, `javax.crypto.Mac`, BouncyCastle `HKDFBytesGenerator`, `ChaCha7539Engine` (minSdk 23 has no reliable JCA ChaCha20) |
| Bech32 | small own implementation (BIP-173) |
| JSON | kotlinx.serialization `JsonElement` for parsing; custom serializer for event ids (§7.1) |
| Preferences | DataStore Preferences (+ one-time import from `FlutterSharedPreferences`) |
| Secure key storage | identity domain (Keystore-backed); this domain only calls it |
| Device authentication | `androidx.biometric.BiometricPrompt` with `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` (API 30+; on 23–29 use `setDeviceCredentialAllowed(true)`); `BiometricManager.canAuthenticate` == NONE_ENROLLED/no lock → allow (parity with `isDeviceSupported()==false`); activity must be `FragmentActivity`/AppCompat theme |
| Screenshot protection | `window.addFlags(FLAG_SECURE)` scoped to the secret dialog/sheet lifetime (`DisposableEffect`) |
| Clipboard | `ClipboardManager`; secrets flagged `EXTRA_IS_SENSITIVE` (API 33+) and cleared after 45 s if unchanged (coroutine in application scope) |
| Open njump | `Intent(ACTION_VIEW, Uri.parse("https://njump.me/$npub"))`, catch `ActivityNotFoundException` → copy |
| Haptics | `HapticFeedbackType` / `View.performHapticFeedback` (Flutter engine mapping, spec 12 §8.11: selectionClick → `CLOCK_TICK`, lightImpact → `VIRTUAL_KEY`, mediumImpact → `KEYBOARD_TAP`, heavyImpact → `CONTEXT_CLICK`; use the shared `Haptics` helper), gated by `haptic_enabled` |
| Images | Coil 3 (`coil-compose`, `coil-network-okhttp`) with error → letter fallback |
| Blossom upload | OkHttp `PUT` with `RequestBody` of bytes; caller picks image via Photo Picker (`PickVisualMedia`), re-encodes ≤1200 px q85 to a temp file |
| Network security | keep `network_security_config.xml` (cleartext only for onion/local/loopback) |
| Amber (NIP-55) queries | `<queries>` for `com.greenart7c3.nostrsigner` + `nostrsigner:` scheme (signing domain) |

---

## 12. Cross-domain dependencies

Calls **out** of this domain:
* Signing/identity domain: `SigningService.signEvent/npub/canSign/isAmber/isAmberSupported/isNip07/nip07ExtensionAvailable/isNip46/useLocalMode/connectAmber/disconnectAmber/disconnectNip07/disconnectNip46/nip46SessionAlive`;
  `BunkerConnectSheet.show` (NIP-46 UI); `SecureKeyStore.saveKeys/loadKeys/hasKey/getPrivHex/getNpub/deleteKeys`; `LocalKeyVault.getPasswordWrap/passwordWrapMatchesNpub/clearAll`; `Nip49.encrypt/decrypt`, `KeySecurity`.
* Identity/user model: `UserProfile.load/save` and its keys.
* Meetups domain: `MeetupService.fetchMeetups()`, `Meetup` model, `MeetupSearchSheet`.
* Proofs domain: `PlatformProofService.getSavedProofs()`, `PlatformProofScreen`; `HumanityProofService.getStatus()`, `HumanityProofScreen`.
* App shell/navigation: reset to `AppShell` after save.
* Platform helpers: `ScreenSecure.set/authenticate/copySecret`, `HapticService.light/medium`, `AppLogger.debug/warn/diag`.
* Reputation domain (data contract only): kind 30078 `d="einundzwanzig-reputation"`, `content.identity.nickname` (published with `"Anon"` when empty).

Calls **into** this domain:
* `RelayConfig.getActiveRelays/defaultRelays/relayTimeout/publishTimeout`: reputation publisher, promotion claims, admin registry, coattendance, calendar events, humanity proof, zap verification, social graph, news reactions, QR scanner, reputation verify screen, NIP-46 pairing (`take(3)`, fallback `defaultRelays.take(2)`).
* `RelaySocket.connect/listen/stream/add/close`, `verifiedEvent`, `answersFilter`, `replaceableCreatedAt`: event chat, news, news reactions/zaps, calendar events, NIP-05, promotion claims, badge sync, zap receipts/verification, reputation publisher, RSVP, admin registry, social graph, NIP-46 client, humanity proof, coattendance, group chat (incl. AUTH handshake; tests fake `RelaySocket`).
* `Nip44`: NIP-46 client (kind 24133), `LocalNostrSigner.nip44Encrypt/Decrypt` (badge sync).
* `NostrService`: `importNsec`/`importNcryptsec` (identity setup), `importPrivHex` (EasyAuth), `npubToHex`/`hexToNpub`/`isValidNpub`/`shortenNpub`/`getNpub` (many screens/services).
* `NostrProfileService.fetchProfilePicture/getLocalPicture/setLocalPicture/clearCache` (home screen + app reset), `fetchDisplayName` (chat, NpubChip), `cachedDisplayNames/fetchDisplayNames` (my network).
* `BlossomUploadService.uploadImage` (event calendar badge image).
* `NostrAvatar` (radar, chat, profile), `NpubChip` (portal meetups, event calendar, admin panel, my network, meetup details).
* `RelayParseTally` (logging domain) is the per-subscription parse-failure counter used by other relay consumers — keep it in `:core:common` (package `core.common.log`, next to `AppLogger`).

---

## 13. Proposed Kotlin modules / packages / public API

Base package `space.einundzwanzig.meetup` (matches the existing skeleton; modules auto-included from `core/`, `data/`, `feature/`).

```
:core:nostr     space.einundzwanzig.meetup.core.nostr        event model, ids, schnorr, keys, NIP-19, NIP-44, filters
:core:relay     space.einundzwanzig.meetup.core.relay        RelayConfig, RelayConnection(OkHttp), EventGuard, RelayUrls
:data:profile   space.einundzwanzig.meetup.data.profile      NostrProfileLookup, ProfileRelayClient, DisplayNameRepository, LocalPictureStore
:data:blossom   space.einundzwanzig.meetup.data.blossom      BlossomUploader
:core:ui        space.einundzwanzig.meetup.core.ui.nostr     NostrAvatar, NpubChip
:feature:profile space.einundzwanzig.meetup.feature.profile  ProfileScreen + ProfileViewModel
:feature:relays  space.einundzwanzig.meetup.feature.relays   RelaySettingsScreen + RelaySettingsViewModel
```

```kotlin
// ---- :core:nostr ----
object NostrJson { fun idPayload(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String }
object EventIds { fun compute(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String }
object Schnorr {
  fun sign(id32: ByteArray, priv32: ByteArray, aux32: ByteArray = randomBytes(32)): ByteArray
  fun verify(sig64: ByteArray, id32: ByteArray, xOnlyPub32: ByteArray): Boolean
}
object Keys {
  fun generatePrivHex(): String
  fun pubHexFromPriv(privHex: String): String
  fun isValidPriv(privHex: String): Boolean
}
object Nip19 {
  fun encodeNpub(hex: String): String; fun encodeNsec(hex: String): String
  fun decodeNpub(npub: String): String      // "" if hrp != npub (Dart parity); throws on bech32 errors
  fun decodeNsec(nsec: String): String
  fun isValidNpub(s: String): Boolean; fun isValidNsec(s: String): Boolean
  fun shortenNpub(npub: String, chars: Int = 8): String
}
data class NostrEvent(/* §3.1 */) {
  fun isValid(): Boolean               // §7.2
  fun toJson(): JsonObject
  companion object { fun fromJsonOrNull(o: JsonElement): NostrEvent? }
}
class Nip44Exception(message: String) : Exception(message)
object Nip44 {
  fun conversationKey(privkeyHex: String, pubkeyHex: String): ByteArray
  fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray? = null): String
  fun decrypt(payload: String, conversationKey: ByteArray): String
  internal fun messageKeys(ck: ByteArray, nonce: ByteArray): MessageKeys
  internal fun calcPaddedLen(len: Int): Int
}
data class NostrFilter(val kinds: List<Int>? = null, val authors: List<String>? = null,
                       val tags: Map<String, List<String>> = emptyMap(), // key without '#'
                       val since: Long? = null, val until: Long? = null, val limit: Int? = null) {
  fun toJson(): JsonObject               // "#d" etc.
}

// ---- :core:relay ----
enum class RelayAddResult { ADDED, INVALID_URL, UNREACHABLE, ALREADY_PRESENT }
interface RelayConfig {
  val defaultRelays: List<String>         // §5.1
  suspend fun activeRelays(): List<String>
  suspend fun customRelays(): List<String>
  suspend fun disabledDefaults(): List<String>
  suspend fun relayStatus(): Map<String, Boolean>
  suspend fun addCustomRelay(url: String): RelayAddResult
  suspend fun removeCustomRelay(url: String)
  suspend fun setDefaultRelayEnabled(url: String, enabled: Boolean)
  suspend fun probeRelay(url: String, timeoutMs: Long = 5_000): Boolean
  fun observeActiveRelays(): Flow<List<String>>
  companion object { const val RELAY_TIMEOUT_MS = 8_000L; const val PUBLISH_TIMEOUT_MS = 5_000L }
}
object RelayUrls {
  fun isWellFormedRelayUrl(url: String): Boolean              // §8.2 user input
  fun normalize(value: String, discovered: Boolean = false): String?   // §8.2 lookup
}
interface RelayConnection {                    // = RelaySocket
  val incoming: Flow<String>                   // text frames ≤ 512*1024 chars
  fun send(text: String): Boolean
  suspend fun close()
}
interface RelayConnector { suspend fun connect(url: String): RelayConnection }   // suspends until open; throws on failure
object EventGuard {
  val rejectedEventCount: Int
  fun verifiedEvent(raw: JsonElement?, tag: String = "RelaySocket"): JsonObject?
  fun answersFilter(event: JsonObject, filter: JsonObject): Boolean
  fun replaceableCreatedAt(event: JsonObject, nowSeconds: Long): Long?
}

// ---- :data:profile ----
fun interface ProfileRelayQuery { suspend fun query(relay: String, pubkeyHex: String): List<JsonObject> }
class ProfileRelayClient(val timeoutMs: Long = 6_000) : ProfileRelayQuery {
  companion object { const val MAX_MESSAGES = 64; const val MAX_EVENTS = 8; const val MAX_MESSAGE_LENGTH = 64 * 1024 }
}
class NostrProfileLookup(relays: suspend () -> List<String>, query: ProfileRelayQuery, time: TimeSource, prefs: ProfileCacheStore) {
  suspend fun fetchPicture(pubkeyHex: String): String?
  fun invalidate()
  companion object { const val CACHE_MS = 12*3600_000L; const val INDEX_RELAY = "wss://purplepag.es"
                     const val MAX_INITIAL_RELAYS = 8; const val MAX_AUTHOR_RELAYS = 4
                     fun pictureUrl(value: Any?): String? }
}
interface NostrProfileService {
  suspend fun fetchProfilePicture(pubkeyHex: String): String?
  suspend fun fetchDisplayName(pubkeyHex: String): String?
  suspend fun cachedDisplayNames(): Map<String, String>
  suspend fun fetchDisplayNames(pubkeysHex: Collection<String>): Map<String, String>
  suspend fun setLocalPicture(path: String); suspend fun getLocalPicture(): String?
  suspend fun clearCache()
}
interface NostrKeyService {             // = NostrService facade
  suspend fun generateKeyPair(): KeyTriple          // nsec, npub, pubHex
  suspend fun importNsec(nsec: String): KeyTriple   // throws InvalidKeyFormatException(message)
  suspend fun importPrivHex(privHex: String): KeyTriple
  suspend fun importNcryptsec(ncryptsec: String, password: String): KeyTriple
  suspend fun loadKeys(): Pair<String, String>?     // nsec, npub
  suspend fun hasKey(): Boolean
  suspend fun getNpub(): String?                    // active identity (any signer mode)
  suspend fun deleteKeys()
}

// ---- :data:blossom ----
data class UploadResult(val url: String?, val error: String?) { val ok get() = !url.isNullOrEmpty() }
interface BlossomUploader { suspend fun uploadImage(file: File): UploadResult
  companion object { val SERVERS = listOf("https://blossom.band","https://blossom.primal.net","https://blossom.nostr.build") } }

// ---- :core:ui ----
@Composable fun NostrAvatar(fallbackText: String, modifier: Modifier = Modifier, backgroundColor: Color = BrandColors.Orange,
                            radius: Dp = 20.dp, pubkeyHex: String? = null, pictureLoader: (suspend (String) -> String?)? = null)
@Composable fun NpubChip(npub: String, modifier: Modifier = Modifier, head: Int = 10, tail: Int = 8,
                         style: TextStyle? = null, showIcon: Boolean = true, showName: Boolean = true)
object NpubFormat { fun shorten(npub: String, head: Int = 10, tail: Int = 8): String }

// ---- :feature:profile / :feature:relays ----
@Composable fun ProfileScreen(viewModel: ProfileViewModel = hiltViewModel())         // NavKey: ProfileEdit (no args)
@Composable fun RelaySettingsScreen(viewModel: RelaySettingsViewModel = hiltViewModel()) // NavKey: RelaySettings (no args)
```
ViewModel state for the profile screen should model: `mode (ReadOnly/Edit)`, `nickname`, `favorites`, `selectedHome`, `homeMissing`,
`signerMode (Local/Amber/Nip46/None)`, `hasNostrKey`, `canSign`, `npub`, `busy flags (generating, exporting, checkingBunker)`,
`platformProofCount`, `humanityVerified`, plus one-shot events (snackbars, dialogs, navigation).

---

## 14. Risks / compatibility pitfalls

1. **Event id serialisation**: any JSON library that escapes `/`, `<`, `>`, `&`, `=`, `'`, U+2028/2029 or non-ASCII produces different ids → all
   signatures of this app become invalid for others and others' events are rejected. Use the dedicated serializer (§7.1) and the escaping vector.
2. **ECDH for NIP-44** must use the unhashed x coordinate; libsecp256k1's `ecdh()` hashes → silently incompatible with Amber/nsec.app/bunkers. Test with vectors.
3. **ChaCha20 variant**: must be IETF (12-byte nonce, 32-bit counter starting at 0), not the original 8-byte-nonce ChaCha.
4. **10-digit `created_at` rule** of `isValid()` rejects events with timestamps < 1 000 000 000; native must reject them too or the Flutter
   and native apps disagree on which events exist (e.g. counts in trust score).
5. **Filter binding** (`answersFilter`) must be applied on the *raw* event exactly like Dart (case-sensitive tag names, empty tag list → no match,
   `since/until` require int `created_at`). Skipping it re-opens the H2 hole; being stricter changes results.
6. **Different "future" tolerances** (lookup 0 s vs. 600 s elsewhere) and **tie rules** (lowest id in lookup; arrival/relay order in names) — keep them.
7. **`Nip19.decodePubkey` returns `""` for wrong hrp** instead of throwing; callers (e.g. NpubChip → `fetchDisplayName("")` → null) rely on that.
8. **Dart `Uri` normalisation** (lower-case host, root-slash stripping) determines relay set de-duplication in the lookup; `java.net.URI` does not
   lower-case hosts. Custom relays are stored *as typed* — do not normalise them on migration.
9. **SharedPreferences migration**: keys carry the `flutter.` prefix; string lists use the `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!` + JSON
   format (or legacy base64 serialized form). Ints are Longs. Forgetting this loses custom relays and caches.
10. **Profile save side effects**: Dart resets portal/seed admin flags and `review_demo` on every save (§3.3). Deciding to "fix" this changes
    stored data; coordinate with the admin/identity domain.
11. **Hard-coded German strings** not in ARB: `"$count aktive Relays"`, tooltip `"Entfernen"`, `"Nostr"`, `"NPUB"`, `"NSEC"`, `"OK"`, `"nsec1..."`,
    `"$n Plattform(en) aktiv"`, `" und "` joiner, device-auth reasons, NostrService/Blossom error texts. Decide whether to add string resources
    (recommended) — wording should stay identical in German.
12. **Sequential relay loops** in `fetchDisplayName` can take up to ~12 s per relay; parallelising is fine but the newest-wins and tie
    semantics (first relay wins ties across relays) must stay.
13. **No negative caching** for pictures, but **session-long negative caching** for single display names (memory) — replicate, or names will
    flicker / relays will be hammered.
14. **Lookup generation guard**: if `invalidate()` is not honoured, a fetch running during app reset re-creates caches of the old identity.
15. **Coil disk cache** differs from Flutter's memory-only image cache; after a picture removal (signed `{}`), ensure the UI shows the letter
    (the URL becomes null, so this is fine) — but avoid caching by pubkey key.
16. **Device authentication on Android < 11**: `BIOMETRIC_STRONG|DEVICE_CREDENTIAL` combos are not supported on API 28–29; use the
    compat path. `local_auth` allowed access when no lock screen exists — keep that, otherwise users without PIN can never see their nsec.
17. **Blossom**: hash must be over the exact bytes uploaded (after re-encoding); MIME derived from the file extension; servers may require
    `Content-Length` (OkHttp sets it for byte bodies). Reusing one auth event for all servers is required to avoid multiple Amber prompts.
18. **NIP-44 length checks order** (char length before base64 decode, `#` prefix) — invalid vectors depend on it.
19. **512 KiB frame cap** is in UTF-16 chars, profile client cap is 64 KiB chars; OkHttp delivers whole messages, so measure `String.length`.
20. **Relay probe** treats any successful WebSocket upgrade as reachable (no NIP-11) — do not tighten without UX decision (custom relays that
    reject REQs would otherwise become un-addable).
21. **Avatar letter**: Dart takes the first UTF-16 unit (`[0]`) — for emoji nicknames that is half a surrogate pair; natively use the first
    code point/grapheme (visual improvement, no data impact).
22. **`NostrService.verify` semantics** (createdAt/eventId from the original event, never regenerated) — badge security in another domain uses
    the same rule; reuse `NostrEvent.isValid()`.
