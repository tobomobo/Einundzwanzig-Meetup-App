# 08 — Backup & Badge Sync (porting spec)

Domain owner files (Flutter, read completely):

| File | Lines | Role |
|---|---|---|
| `lib/services/backup_service.dart` | 1515 | Encrypted `.21bkp` export/import, restore decision, profile/badge/key/proof restore, all backup dialogs |
| `lib/services/badge_sync_service.dart` | 753 | Background badge sync over Nostr (kind 30078, NIP-44 to self), restore "hold" state machine |
| `lib/services/badge_sync_format.dart` | 194 | Pure functions: envelope, sorting, chunking, hashes, NIP-44 size estimate, merge |
| `lib/services/badge_sync_codec.dart` | 21 | gzip deflate/inflate facade (`BadgeSyncCodec.deflate/inflate`); conditional import `badge_sync_codec_io.dart` if not `dart.library.js_interop`, else `badge_sync_codec_web.dart` |
| `lib/services/badge_sync_codec_io.dart` | 9 | **Port this one.** Native gzip: `io.gzip.encode(bytes)` / `io.gzip.decode(data)` → Kotlin `GZIPOutputStream` / `GZIPInputStream` (§7.3) |
| `lib/services/badge_sync_codec_web.dart` | 10 | **Out of scope (web build only).** Pure-Dart `archive` `GZipEncoder().encodeBytes` / `GZipDecoder().decodeBytes`; same RFC 1952 format, no native counterpart needed |
| `lib/services/pbkdf2/pbkdf2_dart.dart` | 106 | **Port this one.** PBKDF2-HMAC-SHA256 core (`pbkdf2Dart(Pbkdf2Request)` run via `compute()` isolate; `pbkdf2DartBytes` throws `ArgumentError` for `iterations < 1` or `keyBytes < 1`) — the only path used on Android/iOS (§7.1) |
| `lib/services/pbkdf2/pbkdf2_fast.dart` | 11 | Conditional export only: `pbkdf2_fast_stub.dart`, or `pbkdf2_fast_web.dart` if `dart.library.js_interop` |
| `lib/services/pbkdf2/pbkdf2_fast_stub.dart` | 16 | Mobile/desktop variant of `pbkdf2Fast(...)`: always returns `null` ("not available here"), so `backup_service` falls through to `pbkdf2Dart`. Native: no counterpart — call the PBKDF2 implementation directly |
| `lib/services/pbkdf2/pbkdf2_fast_web.dart` | 78 | **Out of scope (web build only).** WebCrypto `crypto.subtle.importKey/deriveBits` fast path; returns `null` without a secure context, on any exception, or if output length ≠ `keyBytes`. Bit-identical to the Dart core; nothing to port |

Helpers followed and summarized here (owned by other domains): `lib/models/badge.dart` (MeetupBadge JSON, unionByIdentity, storage API used by restore/sync), `lib/models/user.dart` (UserProfile), `lib/services/relay_socket.dart`, `lib/services/signing_service.dart` (modes, NIP-44, restore*), `lib/services/secure_key_store.dart`, `lib/services/admin_registry.dart` (AdminEntry), `lib/services/platform_proof_service.dart`, `lib/services/humanity_proof_service.dart`, `lib/utils/share_origin.dart`, `lib/features.dart`, call sites in `lib/main.dart`, `lib/screens/intro.dart`, `lib/screens/identity_setup_screen.dart`, `lib/screens/home_screen.dart`, `lib/tours/settings_tour.dart`, `lib/screens/badge_wallet.dart` (former collections).

Machine-readable vectors: `android-native/spec/fixtures/08-backup-sync-vectors.json` (see §10).

---

## 1. Overview and user-visible features

The domain has two independent mechanisms that both protect the user's badge collection and identity:

**A. Encrypted backup file (`.21bkp`)** — a user-initiated, password-protected file containing profile, all badges (full JSON incl. signatures/claims), the local Nostr key (if one exists), signer mode, admin registry cache, platform proofs and humanity proof. Encryption is PBKDF2-HMAC-SHA256 (600 000 iterations, 32-byte random salt) → AES-256-GCM (16-byte random IV). Import supports the current `enc_v2`, the legacy `enc_v1` (SHA-256(password) key) and unencrypted plain JSON. Import is identity-aware: same identity → merge (additive), backup without identity → add after confirmation, different identity → replace after a destructive-warning confirmation with crash-safe staging, empty device → plain restore.

**B. Badge sync over Nostr** — a silent background process (no UI at all) that stores the badge collection as up to 16 replaceable kind-30078 events (`d` = `badges-0` … `badges-15`), each content = NIP-44 (to the user's own pubkey) of base64(gzip(envelope JSON)). Every run first pulls + merges (union per event identity), then pushes only changed chunks. It never publishes an empty list, never overwrites an unreadable local file, and is suspended while a backup restore is in progress.

User-visible features (every one):

1. Settings sheet → group "DATEN & SICHERHEIT" → row **"Backup erstellen"** (`settingsBackup` / `settingsBackupSub`) starts export.
2. Settings sheet → row **"Backup laden"** (`settingsBackupLoad` / `settingsBackupLoadSub`) starts import; the home screen reloads all its data afterwards regardless of the result.
3. Intro screen button **"BACKUP LADEN"** (`introLoadBackup`) → import; on success navigates to the app shell (fade transition), replacing the intro.
4. Identity setup "existing identity" step: text button with restore icon **"BACKUP LADEN"** (`introLoadBackup`) → import; on success `pushAndRemoveUntil(AppShell)`.
5. Identity setup "secure your identity" bottom sheet, step 1/2 ("Backup-Datei"): button **"Backup erstellen"** (`idSetupSecureBackup`) → export; afterwards the sheet always advances to step 2 (ncryptsec key export, identity domain) regardless of export result. "Überspringen" (`idSetupSecureSkip`) also advances to step 2.
6. App reset flow: after the first destructive confirmation, a dialog offers **"Backup erstellen"** (recommended) / "Ohne Backup zurücksetzen" / "Abbruch". A backup that is cancelled or fails ABORTS the reset (export returns `false`). After a successful backup a second confirmation ("Backup erstellt. Jetzt zurücksetzen?") is shown.
7. Password dialog (export): two obscured fields (password + confirm), each with show/hide eye toggle (tooltips "Passwort anzeigen"/"Passwort verbergen"), live rule checklist (≥ 8 chars; both entries match) with green check/grey circle icons, live border colours (grey untouched / green ok / red not ok), primary button disabled until both rules pass. Dialog not dismissible by tapping outside.
8. Password dialog (import): single obscured field with eye toggle, no rules (old backups may have shorter passwords), button always enabled; empty password shows inline error "Passwort darf nicht leer sein".
9. Blocking loading overlay (orange circular progress indicator, not dismissible) during key derivation (export: from after password entry until the file is ready; import: only during PBKDF2 for `enc_v2`).
10. Export target: first the system "save file" dialog (SAF create document) with suggested name `Backup_<yyyy-MM-dd_HHmm>_21MeetupApp_<Nick>.21bkp`; if the user cancels that (or it is unavailable), the system share sheet is offered as fallback with subject "Einundzwanzig App Backup (Verschlüsselt)" and text "Dein verschlüsseltes Backup. Halte dein Passwort bereit, um es wiederherzustellen."
11. Export success shows NO snackbar (silent); only logs. (The reset flow shows its own follow-up dialog.)
12. Export refusal when the local badge file is unreadable: floating snackbar `badgesUnreadableBody` for 8 s; no backup is produced at all (protects the reset flow).
13. Export error: red snackbar `backupError(msg)`. Share-sheet dismissal: no message, returns false.
14. Import: system file picker (any type). Cancel → nothing happens.
15. Import wrong password / corrupt ciphertext / malformed `enc_v2`/`enc_v1` container: red snackbar "Falsches Passwort oder Datei beschädigt!" (`backupWrongPassword`). (Note: `backupCorrupt` is thrown internally but always masked by this message.)
16. Import not JSON: red snackbar `backupImportFailed("Exception: " + backupNotValid)`. Missing `user` or `badges`: `backupImportFailed("Exception: " + backupNotEinundzwanzig)`.
17. Import of a backup that belongs to a DIFFERENT identity: confirmation dialog "Backup ersetzt diese Identität" (`backupReplaceTitle`/`backupReplaceBody` with both npubs shortened; confirm "Ersetzen" in red bold).
18. Import of a backup WITHOUT identity while the device has one: confirmation dialog "Backup ohne Identität" (`backupAddUnknownTitle`/`backupAddUnknownBody`; confirm "Hinzufügen" in orange bold).
19. Import success (merge modes): green snackbar 4 s "`{count}` Badges hinzugefügt" (`backupMergeAdded`) or "Sammlung zusammengeführt, keine neuen Badges" (`backupMergeSame`).
20. Import success (fresh/replace): green snackbar 4 s "✅ Backup geladen! {items} wiederhergestellt." where items = "Profil, N Badges[, Nostr-Key][, M Plattform-Proofs][, Humanity-Proof]" (these item words are hard-coded German in Flutter).
21. Import refuses to proceed while an earlier identity switch is half-finished, unless the new import is itself a replace: red snackbar with the hard-coded message "Ein Identitätswechsel ist noch nicht abgeschlossen. Lade das Backup der neuen Identität noch einmal."
22. Replaced collections are archived on the device ("Frühere Sammlungen" in the badge wallet; badges domain), never deleted by import.
23. Double-tap protection: while an export or import is running, further export/import requests are ignored silently (shared busy flag).
24. Settings tour step 2 highlights the backup row ("Erstelle ein Backup!", `guideSettingsBackupTitle`/`guideSettingsBackupBody`, hint `guideHintBackup`).
25. Badge sync (no UI): badges collected on another device with the same Nostr identity appear automatically (app start, after any badge save, when a signer becomes ready, and on app resume if the last run failed). Requires a signer that supports NIP-44 (local key, Amber, NIP-46 bunker). If NIP-44 is refused/unsupported, sync silently stays off and the backup file remains the only path. No haptics anywhere in this domain.

---

## 2. Screens & UX flows

This domain owns no full screen. It owns: the **password dialog**, the **loading overlay**, the **identity confirmation dialogs**, the **export/import orchestration** (SAF + share), and the **snackbars**. It is invoked from screens of other domains.

### 2.1 Entry points

| Caller (domain) | Trigger | API | Uses result |
|---|---|---|---|
| Home settings sheet (home/settings) | Row "Backup erstellen" (icon `cloud_upload_rounded`, cyan, tour key `guide_set_backup`) | `createBackup()` | no (sheet is popped first) |
| Home settings sheet | Row "Backup laden" (icon `restore_rounded`, cyan) | `restoreBackup()` | no; caller always reloads all home data (`_loadAll()`) afterwards because a failed run may already have written profile/keys |
| Home reset flow (`_resetApp`) | "Backup erstellen" in reset dialog | `createBackup()` | YES: `false` ⇒ abort reset; `true` ⇒ show "Backup erstellt. Jetzt zurücksetzen?" |
| Intro screen | "BACKUP LADEN" (disabled while intro is loading) | `restoreBackup()` | `true` ⇒ `pushReplacement(AppShell)` with fade |
| Identity setup, existing-identity step | Text button "BACKUP LADEN" (restore icon, orange) | `restoreBackup()` | `true` ⇒ `pushAndRemoveUntil(AppShell)` |
| Identity setup, secure sheet step 1/2 (only shown if a password-wrapped local key exists) | "Backup erstellen" (save_alt icon) | `createBackup()` | no; always advances to step 2 |
| App start (`main`) | after `MeetupBadge.loadBadges()` | `BadgeSync.init()` then `BadgeSync.retryIfDirty()` | — |
| Home screen lifecycle `resumed` | — | `BadgeSync.retryIfDirty()` | — |
| Badge storage (`MeetupBadge.saveBadges`, `promoteIncoming`) | hook `onBadgesSaved` | `BadgeSync.trigger()` | — |
| Signing service (`useLocalMode`, `restoreAmber`, `restoreNip07`, `restoreNip46`, successful `connectAmber`, `connectNip07`, `connectNip46Bunker`, `completeNip46Pairing`) | hook `onSignerReady` | `BadgeSync.trigger()` | — |

Both `createBackup` and `restoreBackup` take the caller's context only for dialogs/snackbars; in Compose implement as a `BackupCoordinator` (singleton) + a `BackupHost` composable mounted once at the app root that renders the coordinator's dialog/overlay/snackbar state. Callers `suspend` on `coordinator.export(): Boolean` / `coordinator.restore(): Boolean`.

### 2.2 Password dialog (`_promptForPassword(isExport)`)

AlertDialog, `barrierDismissible = false`, background `#1A1A1A`, scrollable content.

Order of elements:
1. Title (white, bold): export `backupEncryptTitle` "Backup verschlüsseln"; import `backupDecryptTitle` "Backup entschlüsseln".
2. Description (grey, 13 sp): export `backupExportDesc` (contains "\n\n⚠️ …UNWIEDERBRINGLICH verloren!"); import `backupImportDesc`.
3. 16 dp gap.
4. Password field: obscured, **autofocus**, white text, hint `backupPassword` "Passwort" (white30), filled `#0A0A0A`, outline border. Suffix icon button: `visibility_outlined` when obscured / `visibility_off_outlined` when visible, white54, 20 dp, tooltip `backupPwShow` / `backupPwHide`. Border colour: import → always `#2A2A2A`; export → `#2A2A2A` while empty, else green if `length >= 8` else red (enabled width 1.2, focused 1.6). `errorText` shows `backupPasswordEmpty` after pressing the button with an empty password (import only; export button is disabled then). Typing clears the error.
5. (export only) 12 dp gap, confirm field: obscured (own toggle), hint `backupPasswordConfirm`, border grey while empty, else green if matches else red. No autofocus.
6. (export only) 14 dp gap, two rule rows (each: icon `check_circle` green when OK / `circle_outlined` white38, 15 dp; text 12 sp, green when OK else white54):
   - `backupPwRuleLength(8)` "Mindestens 8 Zeichen — eine lange Passphrase ist besser als ein kurzes, kompliziertes Passwort."
   - `backupPwRuleMatch` "Beide Eingaben stimmen überein"
7. Actions: TextButton `dialogCancelMixed` "Abbrechen" (grey) → returns `null`; ElevatedButton (orange, disabled background `#2A2A2A`) label export `backupEncryptSave` "Verschlüsseln & Speichern" / import `backupDecryptLoad` "Entschlüsseln & Laden" (black bold when enabled, white38 when disabled).

Rules (exact):
```
longEnough = password.length >= 8            // String.length = UTF-16 code units in Dart
matches    = password.isNotEmpty && password == passwordConfirm
allOk      = !isExport || (longEnough && matches)
onPressed  = allOk ? { if (password.isEmpty) errorText = backupPasswordEmpty else return password } : null
```
`BackupService.minPasswordLength = 8` (deliberately independent constant from the identity-setup ncryptsec password min length, also 8). No character-class rules. No trimming of the password. Unused legacy ARB keys: `backupPasswordMin`, `backupPasswordMismatch` (keep in resources, unused).

### 2.3 Loading overlay

Modal, not dismissible, centred `CircularProgressIndicator` in orange on the default scrim. Flutter inserts a 50 ms delay after showing it so the frame renders before the CPU-heavy derivation; in Kotlin simply run PBKDF2 on `Dispatchers.Default` (UI stays responsive). Must be closed exactly once on every path (Flutter bug #18: double pop closed the underlying page → black screen).

### 2.4 Export flow — `createBackup(): Boolean`

1. If `busy` → return `false` (silently). Set `busy = true` (shared with import; reset in `finally` on every exit).
2. Show password dialog (export). `null` → return `false`.
3. Show loading overlay.
4. Load `UserProfile`, then `MeetupBadge.loadBadges()`. If `MeetupBadge.badgesUnreadable` → close overlay, floating snackbar `badgesUnreadableBody` (8 s), return `false`. (Rationale: an empty badge list must never enter a backup; the reset flow treats `true` as permission to wipe.)
5. Read `nsec`, `npub`, `privHex` from SecureKeyStore; `signingMode = SigningService.getMode()`; `activeNpub = SigningService.npub()`; `isExternal = SigningService.isExternalSigner` (mode ∈ {amber, nip07, nip46}); `nip46Session = SigningService.nip46SessionForBackup()` (map `{bunker_uri}` or null); `AdminRegistry.getAdminList()`; `PlatformProofService.getSavedProofs()`; `HumanityProofService.getStatus()`.
6. Build the backup JSON (exact schema §3.2), `jsonEncode`.
7. Generate 32-byte salt (CSPRNG), derive key (PBKDF2 600 000), 16-byte IV (CSPRNG), AES-256-GCM encrypt UTF-8 of JSON, build `enc_v2:<b64 salt>:<b64 iv>:<b64 ct||tag>` (§7).
8. File name: `Backup_${DateFormat('yyyy-MM-dd_HHmm').format(now /*local*/)}_21MeetupApp_${safeNick}.21bkp` where `safeNick = nickname.trim().isEmpty ? 'Anon' : nickname.trim().replaceAll(RegExp(r'[^A-Za-z0-9_-]'), '_')` (each non-matching UTF-16 code unit → one `_`; an emoji (surrogate pair) becomes `__`).
9. Bytes = UTF-8 of the payload string. Close overlay.
10. Try system save dialog (`FilePicker.saveFile(dialogTitle: backupShareTitle, fileName, bytes)`; Android = SAF `ACTION_CREATE_DOCUMENT`). Non-null result → log "Backup gespeichert: <path>" → return `true`. `null` (cancelled) or exception → fall through to share.
11. If the host is gone → return `false` (never `true` without a file). Share: one file (`application/octet-stream`, name = fileName, file name override = fileName), subject `backupShareTitle`, text `backupShareText`, share-position origin = clipped rect of the triggering widget (iPad only; irrelevant on Android).
12. Return `shareResult.status != dismissed`. (`success` and `unavailable` count as `true`; on Android share_plus reports `dismissed` when the chooser closes without a chosen component.)
13. On any exception: close overlay if still open; if it is a share-dismiss PlatformException (code/message/details contain, case-insensitive, `dismiss`, `cancel`, `aborted`, `user cancelled`, `user canceled`) → return `false` silently; else red snackbar `backupError(e.toString())` → `false`.

### 2.5 Import flow — `restoreBackup(): Boolean`

Exact order matters (crash-safety). Translations are captured before the first suspension.

1. If `busy` → `false`. Set `busy = true`, `held = false`. Log "Wiederherstellung gestartet; warte auf Dateiauswahl." (tag `BackupRestore`).
2. File picker (`FileType.any`; Android `ACTION_OPEN_DOCUMENT` with `*/*`). Cancel → return `false`.
3. `content = utf8.decode(bytes)` (strict; malformed UTF-8 throws → step 25 generic failure).
4. Container dispatch:
   - starts with `enc_v2:` → password dialog (import). `null` → `false`. Then inside a try: `parts = content.split(':')`; `parts.length != 4` → throw; `salt = base64Decode(parts[1])`; `iv = base64Decode(parts[2])`; show overlay; derive key (PBKDF2 600 000); close overlay (in `finally`); AES-GCM decrypt `parts[3]` (base64) → UTF-8 decode (`allowMalformed: true`). Any failure → red snackbar `backupWrongPassword` → return `false`.
   - starts with `enc_v1:` → password dialog; `parts.length != 3` → throw; `iv = base64Decode(parts[1])`; key = SHA-256(UTF-8(password)) (32 bytes); AES-GCM decrypt `parts[2]`. Any failure → `backupWrongPassword`, `false`. No overlay.
   - else → treat `content` itself as plain JSON (no password prompt).
5. `data = jsonDecode(decrypted)` must be a JSON object; else throw `backupNotValid`.
6. Must contain keys `user` AND `badges`; else throw `backupNotEinundzwanzig`.
7. `version = data['version'] ?? 1` (int).
8. `existingNpub = BadgeSync.pendingFrom() ?? (SigningService.npub() ?? '').trim()` — if a restore hold is still set from an unfinished identity switch, the collection belongs to the identity before the switch.
9. `backupNpub = backupIdentityNpub(data)` (§8.2); `decision = restoreDecision(existingNpub, backupNpub)`; log "Backup entschlüsselt; Modus: <decision>."
10. `replacesIdentity` → replace-confirmation dialog; cancel → `false`. `addToIdentity` → add-confirmation dialog; cancel → `false`.
11. `BadgeSync.beginRestoreHold(fromNpub = existingNpub, expectNpubs = replacing ? backupIdentities(data) : [], replace = replacing)`; then `held = true`. (May throw "Ein Identitätswechsel ist noch nicht abgeschlossen…" → step 25.)
12. `additive = decision ∈ {sameIdentity, addToIdentity}`.
13. User profile: additive → `mergeProfile(UserProfile.load(), data['user'])` (§8.4); else construct from backup (§3.2 user defaults). If `decision != replacesIdentity` → `user.save()` now (replace saves later, together with the key).
14. Badges: for each entry of `data['badges']`: copy map; if `iconPath` is null or empty → set `'assets/badge_icon.png'`; `MeetupBadge.fromJson(map)` (a single malformed badge throws and aborts the whole import at this point — profile may already be saved). Log counts.
15. Badge write by decision:
    - additive: `before = loadBadges()`; `restored = BadgeSyncFormat.merge(before, restored)`; `addedBadges = addedBadgeCount(before, restored)`; `badgesSaved = restoreBadges(restored)`.
    - replacesIdentity: if `restored.isEmpty` → `BadgeSync.markRestoreClear()`; `badgesSaved = true`. Else `MeetupBadge.stageIncoming(restored)`; `false` → throw "`N` Badges aus dem Backup konnten nicht vorbereitet werden."; else `badgesSaved = true` (active file untouched until the key switch is proven).
    - fresh: `badgesSaved = restoreBadges(restored)`.
16. Nostr keys — only if `!additive && version >= 2 && data['nostr'] != null` (§8.6).
17. Replace finalisation — only if `decision == replacesIdentity` (§8.7): verify the keystore now carries one of the backup identities; else (optionally discard the staged buffer) and throw; then `BadgeSync.clearIdentityProofs()`; then empty → `saveBadges([], discardLocked: true)` (+`clearRestoreMark()` on success) or `promoteIncoming(formerNpub: existingNpub)`; if `badgesSaved` → `user.save()`.
18. Admin registry — only if `version >= 2 && data['admin_registry'] != null` (§8.8).
19. (`my_vouches` from older backups is silently ignored.)
20. Platform proofs — if `data['platform_proofs'] != null` and non-empty (§8.9).
21. Humanity proof (§8.10).
22. If `!badgesSaved` → throw "`N` Badges aus dem Backup konnten nicht gespeichert werden, der Rest wurde übernommen."
23. Log "Restore abgeschlossen; Schreiben bestätigt. N Einträge verarbeitet."
24. Success snackbar (green, 4 s) — §1 items 19/20; `hasNostr = version >= 2 && nostr != null && nostr.has_key == true`; `hasPlatformProofs = platform_proofs != null && non-empty` (count = RAW list length from the file, not the validated count); `hasHumanity = humanity_proof.verified == true`; "Humanity-Proof" only added if `!humanityAlready`. Return `true`.
25. Any exception → log warn "Import Fehler: …"; red snackbar `backupImportFailed(e.toString())` (note Dart's `Exception.toString()` prefixes "Exception: "; native should show the bare message) → `false`.
26. `finally`: `busy = false`; if `held` → `BadgeSync.endRestore()`.

#### 2.5.1 Replace-identity dialog
AlertDialog, not dismissible, bg `#1A1A1A`. Title `backupReplaceTitle` (white bold). Body `backupReplaceBody(existing, backup)` (white70) with `existing = shortenNpub(existingNpub)`, `backup = backupNpub.isEmpty ? '?' : shortenNpub(backupNpub)`. Actions: `dialogCancelMixed` (grey) → false; `backupReplaceConfirm` "Ersetzen" (red, bold) → true.

#### 2.5.2 Add-to-identity dialog
Same styling. Title `backupAddUnknownTitle`, body `backupAddUnknownBody(shortenNpub(existingNpub))`, actions `dialogCancelMixed` / `backupAddUnknownConfirm` "Hinzufügen" (orange, bold).

`shortenNpub(npub, chars = 8)`: `npub.length < 21 ? npub : npub.substring(0, 12) + '...' + npub.substring(npub.length - 8)` (three ASCII dots).

### 2.6 Reset flow (owned by home/settings, depends on export result)

1. Dialog `resetTitle` / `resetBody`; actions `resetCancel`, `resetConfirm` (red bold) → continue only on confirm.
2. Dialog (not dismissible, bg card, radius 16): title row with `shield_outlined` orange icon + `resetBackupTitle`; body `resetBackupBody`; actions (overflow vertical): ElevatedButton orange with `cloud_upload_rounded` "`resetBackupCreate`" → `backup`; TextButton red `resetBackupSkip` → `skip`; TextButton `resetCancel` → `cancel`.
3. `backup` → `createBackup()`; `false` → abort entirely. `true` → dialog `resetBackupTitle` / `resetBackupDone` with `resetCancel` / `resetNowConfirm` (red bold); only on confirm proceed.
4. `_performReset` (owned elsewhere) wipes badges (`MeetupBadge.resetAll()`), all prefs, keys, NIP-46 session, EasyAuth vault, portal session, profile cache. Badge-sync prefs are wiped implicitly by `prefs.clear()`.

### 2.7 Strings (ARB keys from `lib/l10n/app_de.arb`)

| Key | DE text (abbrev.) | Placeholders |
|---|---|---|
| `backupEncryptTitle` | Backup verschlüsseln | |
| `backupDecryptTitle` | Backup entschlüsseln | |
| `backupExportDesc` | Vergib ein Passwort … ⚠️ … UNWIEDERBRINGLICH verloren! | |
| `backupImportDesc` | Dieses Backup ist verschlüsselt. Bitte gib das Passwort ein. | |
| `backupPassword` / `backupPasswordConfirm` | Passwort / Passwort bestätigen | |
| `backupPasswordEmpty` | Passwort darf nicht leer sein | |
| `backupPwShow` / `backupPwHide` | Passwort anzeigen / verbergen | |
| `backupPwRuleLength` | Mindestens {min} Zeichen — … | `min:int` |
| `backupPwRuleMatch` | Beide Eingaben stimmen überein | |
| `backupEncryptSave` / `backupDecryptLoad` | Verschlüsseln & Speichern / Entschlüsseln & Laden | |
| `dialogCancelMixed` | Abbrechen | |
| `backupShareTitle` | Einundzwanzig App Backup (Verschlüsselt) | (also SAF dialog title) |
| `backupShareText` | Dein verschlüsseltes Backup. … | |
| `backupError` | Fehler beim Backup: {msg} | `msg:String` |
| `backupCorrupt` | Backup-Datei ist beschädigt (Formatfehler). | (never surfaced) |
| `backupWrongPassword` | Falsches Passwort oder Datei beschädigt! | |
| `backupNotValid` | Datei ist kein gültiges Backup oder das falsche Format. | |
| `backupNotEinundzwanzig` | Datei ist kein gültiges Einundzwanzig Backup. | |
| `backupLoaded` | ✅ Backup geladen! {items} wiederhergestellt. | `items:Object` |
| `backupImportFailed` | Import fehlgeschlagen: {msg} | `msg:String` |
| `backupReplaceTitle` / `backupReplaceBody` / `backupReplaceConfirm` | … | body: `existing`, `backup` |
| `backupAddUnknownTitle` / `backupAddUnknownBody` / `backupAddUnknownConfirm` | … | body: `existing` |
| `backupMergeAdded` | {count} Badges hinzugefügt | `count:int` |
| `backupMergeSame` | Sammlung zusammengeführt, keine neuen Badges | |
| `badgesUnreadableBody` | Deine Badges konnten nicht gelesen werden. … | |
| `settingsSecData`, `settingsBackup`, `settingsBackupSub`, `settingsBackupLoad`, `settingsBackupLoadSub` | settings rows | |
| `introLoadBackup` | BACKUP LADEN | |
| `idSetupSecureBackupTitle`, `idSetupSecureBackupBody`, `idSetupSecureRepeat`, `idSetupSecureBackup`, `idSetupSecureSkip` | identity secure sheet step 1 | |
| `resetTitle`, `resetBody`, `resetCancel`, `resetConfirm`, `resetBackupTitle`, `resetBackupBody`, `resetBackupCreate`, `resetBackupSkip`, `resetBackupDone`, `resetNowConfirm` | reset flow | |
| `guideSettingsBackupTitle`, `guideSettingsBackupBody`, `guideHintBackup` | settings tour step | |
| `backupPasswordMin`, `backupPasswordMismatch` | unused legacy | |

Hard-coded (not in ARB) German texts that the native app should add as NEW resource keys (suggested names in brackets): success item words "Profil", "{n} Badges", "Nostr-Key", "{n} Plattform-Proofs", "Humanity-Proof" [`backupItemProfile`, `backupItemBadges`, `backupItemNostrKey`, `backupItemPlatformProofs`, `backupItemHumanity`]; exception texts "Ein Identitätswechsel ist noch nicht abgeschlossen. Lade das Backup der neuen Identität noch einmal." [`backupErrSwitchPending`], "{n} Badges aus dem Backup konnten nicht vorbereitet werden." [`backupErrStageFailed`], "Der Schlüssel aus dem Backup ist gerade nicht lesbar, die Sammlung bleibt unverändert." [`backupErrKeyUnreadable`], "Der Schlüssel aus dem Backup wurde nicht übernommen, die Sammlung bleibt unverändert." [`backupErrKeyNotTaken`], "{n} Badges aus dem Backup konnten nicht gespeichert werden, der Rest wurde übernommen." [`backupErrBadgesNotSaved`], "Der Wechsel konnte nicht festgehalten werden." [`backupErrHoldNotPersisted`], "Die leere Sammlung konnte nicht vorgemerkt werden." [`backupErrClearNotMarked`], "Die Nachweise der bisherigen Identität konnten nicht abgeräumt werden." [`backupErrProofsNotCleared`].

---

## 3. Data models

### 3.1 Backup file container (UTF-8 text file, extension `.21bkp`)

```
enc_v2:<base64(salt[32])>:<base64(iv[16])>:<base64(ciphertext || tag[16])>     (current, written)
enc_v1:<base64(iv)>:<base64(ciphertext || tag[16])>                              (legacy, read only)
<plain JSON object>                                                              (legacy unencrypted, read only)
```
base64 = RFC 4648 standard alphabet WITH `=` padding (Dart `base64Encode`). Separator is a single ASCII `:` (base64 never contains `:`). No trailing newline. Detection is by exact prefix `enc_v2:` / `enc_v1:`; anything else is parsed as JSON.

### 3.2 Backup JSON (version 5) — exact keys, in the order written

```jsonc
{
  "version": 5,                                   // int. Import: data['version'] ?? 1. v>=2 gates "nostr" and "admin_registry" handling
  "timestamp": "2026-10-20T14:03:12.123456",      // DateTime.now().toIso8601String(): LOCAL time, no zone suffix, millis (+micros if non-zero). Not read on import.
  "app": "Einundzwanzig Meetup App",              // constant, not validated on import
  "user": {
    "nickname": "Ada",                            // String (import default "Anon")
    "fullName": "",                               // String (default "")
    "homeMeetupId": "",                           // String (default "")
    "favoriteMeetupIds": ["..."],                 // List<String>; missing → [homeMeetupId] if non-empty else []
    "isAdmin": false,                             // bool (derived adminViaPortal||adminViaVouch||adminViaSeed); import: true → adminViaVouch=true
    "isAdminVerified": false,                     // bool
    "isNostrVerified": false,                     // bool
    "nostrNpub": "",                              // String
    "telegramHandle": "",                         // String
    "twitterHandle": ""                           // String
  },
  "badges": [ <MeetupBadge.toJson()>, ... ],      // §3.3, FULL badge objects incl. organizer markers
  "nostr": {
    "nsec": "",                                   // nsec1… of the LOCAL key, or "" (exported whenever a local key exists, regardless of mode)
    "npub": "",                                   // local key's npub if a local key exists, else the active (external) npub, else keystore npub, else ""
    "priv_hex": "",                               // 64 lowercase hex of the local key, "" if none
    "has_key": false,                             // bool: local key exists (nsec non-empty)
    "signing_mode": "local",                      // "local" | "amber" | "nip07" | "nip46"
    "active_npub": "",                            // only in MIXED state (external mode AND local key): the external npub; else ""
    "nip46_bunker_uri": ""                        // bunker URI (NIP-46) WITHOUT the client session key; "" if none
  },
  "admin_registry": [ {"npub": "npub1…", "meetup": "…", "name": "…", "added_at": 1700000000} ],
  "platform_proofs": [ {"platform": "telegram", "username": "…", "proof_sig": "<128 hex>", "created_at": 1700000000} ],
  "humanity_proof": {
    "verified": false,                            // bool. NOTE: getStatus() reports false while needs_reverification is set
    "first_zap_at": 0,                            // int unix seconds
    "receipt_event_id": "",                       // String (hex event id)
    "checked_at": 0                               // int unix seconds
  }
}
```

Import-side tolerances: unknown keys ignored (`my_vouches`, `nip46_client_sk` from old test backups are explicitly ignored). Old versions lack `nostr` (v1), `platform_proofs`/`humanity_proof` (< v5), `active_npub`/`nip46_bunker_uri` (older v5).

Export JSON MUST use JSON integers (not floats) for `version`, `added_at`, `created_at`, `first_zap_at`, `checked_at`, and JSON booleans for all flags: the Flutter importer uses Dart casts (`as int?`, `as bool?`, assignment to typed fields) that THROW on `5.0` or `"true"`.

### 3.3 `MeetupBadge` JSON (owned by badges domain; reproduced because backup + sync carry it verbatim)

Key order as written by `toJson()` and exact types:

| key | type | fromJson default / strictness |
|---|---|---|
| `id` | String | REQUIRED (`as String`, null throws) |
| `meetupName` | String | REQUIRED |
| `date` | String (Dart `toIso8601String`, e.g. `2026-10-11T19:00:00.000Z` for UTC, no suffix for local) | REQUIRED, `DateTime.parse` |
| `iconPath` | String | REQUIRED (backup import pre-fills `assets/badge_icon.png` if null/empty; sync does NOT) |
| `coverUrl` | String | `''` |
| `blockHeight` | int | `0` (`as int?` — a float throws) |
| `signerNpub` | String | `''` |
| `meetupEventId` | String | `''` |
| `delivery` | String: `nfc`, `rolling_qr`, or `organizer` (organizer marker badges, `isOrganizer = true`, written by `CoAttendanceService.recordOrganizerAttendance`, coattendance_service.dart:1147). The model comment `// 'nfc' oder 'rolling_qr'` is stale | `json['delivery'] as String? ?? 'nfc'` — any string is accepted verbatim (no enum validation); only a non-string throws. Native importer (backup and sync) MUST accept `organizer` and SHOULD keep unknown values as-is (raw String, not a closed enum that rejects/coerces), so round-trips stay lossless |
| `sig` | String (128 hex) | `''` |
| `sigId` | String (64 hex) | `''` |
| `adminPubkey` | String (64 hex) | `''` |
| `sigVersion` | int | `0` |
| `sigContent` | String | `''` |
| `claimSig` | String | `''` |
| `claimEventId` | String | `''` |
| `claimPubkey` | String | `''` |
| `claimTimestamp` | int | `0` |
| `isRetroactive` | bool | `false` |
| `presenceVerified` | bool | `true` |
| `isOrganizer` | bool | `false` |
| `isEvent` | bool | `false` |
| `lat` | double (Dart writes `0.0`, `49.97`) | `(as num?)?.toDouble() ?? 0` |
| `lng` | double | same |

Derived (needed here):
- `identityKey(b)`: `meetupEventId` non-empty → `"evt:" + meetupEventId`; else `sigId` non-empty → `"sig:" + sigId`; else `"nd:" + meetupName.trim().toLowerCase() + "|" + yyyy-MM-dd` of `date` in the DateTime's own zone (UTC if the string had `Z`/offset, else device-local).
- `unionKey(b) = b.isOrganizer ? "org:" + identityKey(b) : identityKey(b)`.

### 3.4 Sync envelope (plaintext inside each chunk)

```json
{"v":1,"i":<chunk index>,"n":<chunk count>,"gen":<generation>,"badges":[<MeetupBadge.toJson()>, ...]}
```
Exact key order `v, i, n, gen, badges`; no whitespace (Dart `jsonEncode`). Parsing (`parseEnvelope`): must be a JSON object; `v` must equal `1` (else FormatException "Hülle v… wird nicht unterstützt"); `badges` must be a list (else FormatException); each element that is an object is parsed with `MeetupBadge.fromJson` inside try/catch (bad entries are dropped individually); `i` → `(num?)?.toInt() ?? 0`, `n` → `?? 1`, `gen` → `?? 0`. `i`/`n` are NOT used by the reader for anything (no completeness check).

### 3.5 Enums

```kotlin
enum class RestoreDecision { FRESH, SAME_IDENTITY, ADD_TO_IDENTITY, REPLACES_IDENTITY }   // log names: fresh, sameIdentity, addToIdentity, replacesIdentity
private enum class SyncAttempt { OK, NO_SIGNER, NIP44_BLOCKED, HELD, FAILED }             // only FAILED marks dirty
```

### 3.6 Platform proof entry (stored list in pref `platform_proofs`)
`{"platform": String, "username": String, "proof_sig": String, "created_at": int}` — PlatformProofService reads with hard casts (`as String`, `as int`); a malformed entry makes its whole `getSavedProofs()` return `[]`.

### 3.7 Admin entry
`{"npub": String, "meetup": String, "name": String, "added_at": int}`; `fromJson` defaults `''`/`0`.

### 3.8 Former-collection entry (badges domain; written when replace promotes the buffer)
`{"at": <unix s>, "reason": "replaced"|"cleared"|"rotated", "npub": <former npub or "">, "blob": "<b64 iv>:<b64 ct>"}` in pref `badges_former` (+ secure copy). Replace uses `reason = "replaced"`, `npub = existingNpub` (lower-cased from the hold).

---

## 4. Persistence

Flutter uses `SharedPreferences` (Android file `FlutterSharedPreferences.xml`, every key prefixed `flutter.`; string lists stored as `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!" + JSON` (older: same prefix without `!` + base64 Java-serialized list)) and `flutter_secure_storage` 10.x. Native: Preferences DataStore with the SAME logical key names (no `flutter.` prefix), secure values via the secure-storage domain. If the native app ships as an in-place update of applicationId `space.einundzwanzig.meetup`, the migration domain must import these keys.

### 4.1 Keys owned by this domain (BadgeSyncService)

| Key | Type | Writer | Reader | Semantics |
|---|---|---|---|---|
| `badge_sync_dirty` | bool | trigger (on false/exception), `_syncOnce` (`!pushed`), syncNow (false on nip44Blocked) | `retryIfDirty` | last relay run failed → retry on start/resume |
| `badge_sync_chunk_hashes` | List<String> | `_push` (after loop) | `_push` | `contentHash` (64 lowercase hex) per chunk index of last push |
| `badge_sync_last_gen` | int | `_fetchRemote` (max remote gen if > 0), `_push` (used generation) | `_push` | lower bound for next generation |
| `badge_restore_hold` | bool | `beginRestoreHold` (true), `settleRestore` (remove) | `settleRestore`, `pendingFrom`, `_isHeld` | identity switch in progress; NOTHING is published while true |
| `badge_restore_from` | String (trimmed, lower-case npub or `''`) | `beginRestoreHold` | `settleRestore`, `pendingFrom` | identity owning the active collection before the switch |
| `badge_restore_expect` | List<String> (lower-case npubs); legacy String tolerated | `beginRestoreHold` | `settleRestore` | identities the backup may switch to |
| `badge_restore_proofs` | bool | `clearIdentityProofs` (true) | `settleRestore` | old identity's proofs already cleared |
| `badge_restore_clear` | bool | `markRestoreClear` (true), `beginRestoreHold`/`clearRestoreMark`/`settleRestore` (remove) | `settleRestore` | empty replace pending: archive+clear old collection once the switch is proven |

`forgetLocalState()` removes `badge_sync_chunk_hashes`, `badge_sync_last_gen`, `badge_sync_dirty`.

### 4.2 Keys written/read but owned elsewhere

| Key | Store | Owner | Access here |
|---|---|---|---|
| `badges_encrypted_incoming` (`MeetupBadge.incomingPrefsKey`) | prefs | badges | settleRestore reads: "staged" = value non-null and contains `:` |
| `platform_proofs` | prefs (JSON string) | platform proofs | restore writes (replace: validated list if non-empty; additive: union); `clearIdentityProofs` removes |
| `humanity_verified` | prefs bool | humanity | restore sets `false`; clearIdentityProofs sets `false` |
| `humanity_needs_reverification` | prefs bool | humanity | restore sets `true`; clearIdentityProofs removes |
| `humanity_receipt_id` | prefs String | humanity | restore sets if non-empty; clear removes |
| `humanity_first_zap_at` | prefs int | humanity | restore sets if > 0; clear removes |
| `humanity_checked_at` | prefs int | humanity | restore sets if > 0; clear removes |
| `nickname`, `full_name`, `telegram`, `nostr`, `twitter`, `nostr_verified`, `admin_verified`, `is_admin`, `admin_via_portal`, `admin_via_vouch`, `admin_via_seed`, `review_demo`, `home_meetup`, `favorite_meetups`, `promotion_source` | prefs | profile (`UserProfile.save()`) | restore saves profile |
| `signing_mode`, `amber_pubkey_hex`, `amber_npub`, `nip07_pubkey_hex`, `nip07_npub`, `nip46_pubkey_hex`, `nip46_npub`, `nip46_bunker_uri` | prefs | signing | via `SigningService.restore*`/`useLocalMode` |
| `admin_registry_cache`, `admin_registry_timestamp` | prefs | admin registry | via `AdminRegistry.addAdmin` / `forceRefresh` |
| `nostr_nsec_key`, `nostr_npub_key`, `nostr_priv_hex` | secure storage | key store | export reads; restore `saveKeys`; settle reads priv/npub |
| `nip46_client_sk` | secure storage | key store | `restoreNip46` deletes it (session key never travels) |
| badge file keys (`badges_encrypted`, `badge_encryption_key`, …) | both | badges | via `loadBadges/saveBadges/stageIncoming/promoteIncoming/discardIncoming` |

### 4.3 In-memory (process) state

- `BackupService.busy: Boolean` (shared by export and import).
- `BadgeSync`: `busy`, `rerun`, `epoch: Int` (incremented by every `beginRestoreHold`), `suspended`, `pendingWhileSuspended`, test counter `runs`. A process restart resets these (tests use `debugResetProcess()` to simulate a crash); prefs survive.

No migrations inside this domain besides the import-side compatibility listed in §3.

---

## 5. Network

Backup: no network, except the admin-registry revalidation (`AdminRegistry.forceRefresh()`, admin domain) after restoring `admin_registry`, and indirectly the humanity re-verification on next start (humanity domain).

Badge sync — dedicated relay list, independent of the user's relay settings:

```kotlin
val SYNC_RELAYS = listOf("wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.snort.social")
```

| Operation | Concurrency | Connect timeout | Response timeout | Success criterion |
|---|---|---|---|---|
| Fetch (`_fetchFromRelay`) | all 4 relays in parallel | 8 s | 8 s waiting for `EOSE` | relay counted as "answered" only if EOSE arrived in time |
| Publish (`_publish`) | sequential per relay, per chunk; stop at the first relay that answers `OK true` | 5 s | 8 s waiting for `OK` (timeout ⇒ false) | ≥ 1 relay accepted the chunk |

Fetch protocol:
- Sub id: `"badgesync-" + 16 lowercase hex chars` (8 CSPRNG bytes).
- Send `["REQ", subId, {"kinds":[30078],"authors":["<my hex pubkey>"],"#d":["badges-0","badges-1",…,"badges-15"]}]`. No `limit`, no `since`.
- Incoming frames > 512 KiB (524 288 chars) are dropped unparsed (RelaySocket M4).
- `["EVENT", <any subId>, event]` → keep only if signature-verified (§6.4), `answersFilter`, and `replaceableCreatedAt != null`; then for each `d` tag whose value starts with `badges-` and parses to an index 0..15, keep the event with strictly greater `created_at` per index.
- `["EOSE", …]` → complete with the per-index map. Socket closed (no `CLOSE` message sent).
- Socket error → error; socket closed before EOSE → error ("Relay ohne EOSE"); timeout → error. Errors are logged and counted as "not answered".
- If NO relay answered → pull fails (`null`) → run counts as failed (dirty). Partial answers are fine.

Publish protocol: connect, listen for `["OK", <event id>, <bool>, …]` matching the event id, send `["EVENT", event]`, complete with the bool; error/close → false; close socket.

No NIP-42 AUTH handling, no retries within a run (retry = dirty flag on next start/resume/trigger), no caching besides chunk hashes, no rate limiting.

OkHttp: one `WebSocket` per relay per operation (mirrors Flutter); a shared `OkHttpClient` with `pingInterval` unset is fine. Close with code 1000.

---

## 6. Nostr

### 6.1 Event layout (one per chunk) — NIP-78 app data, addressable (NIP-01 parameterized replaceable, kind 30000–39999)

```json
{
  "kind": 30078,
  "pubkey": "<own hex pubkey>",
  "created_at": <signer time, unix s>,
  "tags": [["d", "badges-<i>"], ["client", "einundzwanzig-meetup-app"]],
  "content": "<NIP-44 v2 payload (base64)>",
  "id": "<NIP-01 sha256>",
  "sig": "<BIP-340 schnorr>"
}
```
Tag order exactly as above. `i` in `0..15` (`maxChunks = 16`). Content is NIP-44 **version 2** encryption to the author's OWN pubkey (conversation key = ECDH(own priv, own pub)), of the UTF-8 string `base64(gzip(utf8(envelopeJson)))`.

Note: kind 30078 is also used by the app for the admin list (`d = einundzwanzig-admins`) and the reputation event (`d = einundzwanzig-reputation`); the `badges-*` d-tags do not collide.

### 6.2 Replaceable semantics
- Per `d` index, the newest event wins: per relay strictly greater `created_at`; across relays strictly greater `created_at` (ties keep the first in relay-list order: nos.lol, primal, nostr.mom, snort).
- Events with `created_at` not an int, `< 0`, or `> now + 600 s` are ignored (`replaceableCreatedAt`), so a future-dated event cannot win forever.
- Chunks are never deleted (no NIP-09). When the chunk count shrinks, higher-index chunks keep old content; the reader unions them in, which is harmless because merging is union-only.

### 6.3 Signing / encryption path
All via the signing domain (`SigningService`), mode-dependent: local key (in-process NIP-44 + schnorr), Amber (NIP-55 `nip44Encrypt`/`nip44Decrypt`/`sign_event` with `pubkey` = own hex), NIP-46 bunker (`nip44_encrypt`/`nip44_decrypt`/`sign_event`), NIP-07 (web only; on Android it cannot sign → sync noSigner/blocked).

Prerequisite owned by the signing domain: when connecting Amber, the app requests the permissions `nip44_encrypt` and `nip44_decrypt` (besides `sign_event` kinds incl. 30078) so the sync runs through Amber's ContentResolver without popups; the NIP-46 `connect` perms string is `get_public_key,nip44_encrypt,nip44_decrypt,sign_event:21000,…,sign_event:30078,…` (see signing spec). Without these, every sync run would prompt.

Before every run a probe `canNip44()` encrypts the literal `e21-nip44-probe` to self and decrypts it; mismatch or any exception ⇒ `NIP44_BLOCKED` (logged under tag `Nip44Probe`, never shows UI). With Amber this can prompt the user unless permissions are remembered.

### 6.4 Verification of fetched events (must match Flutter / RelaySocket)
1. Raw must be a JSON object.
2. Recompute NIP-01 id `sha256(json([0,pubkey,created_at,kind,tags,content]))` and verify BIP-340 signature over it; mismatch ⇒ drop (counted, not thrown).
3. `answersFilter(event, filter)`: `kind ∈ kinds`, `pubkey ∈ authors`, and for `#d`: event has a tag `["d", v, …]` with `v ∈ wanted list`.
4. `replaceableCreatedAt` (above).
5. Decryption failures / parse failures per chunk are logged and the chunk ignored.

---

## 7. Crypto & encoding

### 7.1 Backup key derivation (byte-exact; existing backups depend on it)

PBKDF2-HMAC-SHA256, password = UTF-8 bytes of the password string exactly as typed (no trim, no normalization), salt = 32 random bytes, iterations = **600 000**, dkLen = **32**.

```dart
static const int _pbkdf2Iterations = 600000;
static const int _saltLengthBytes = 32;
static const int _keyLengthBytes = 32; // AES-256
...
final passwordBytes = utf8.encode(password);
final fast = await pbkdf2Fast(password: passwordBytes, salt: salt, iterations: _pbkdf2Iterations, keyBytes: _keyLengthBytes);
if (fast != null) return enc.Key(fast);               // web only (WebCrypto); always null on mobile (pbkdf2_fast_stub.dart)
final bytes = await compute(pbkdf2Dart, Pbkdf2Request(password: passwordBytes, salt: salt, iterations: _pbkdf2Iterations, keyBytes: _keyLengthBytes));
```
Core (standard RFC 8018):
```dart
final hmac = Hmac(sha256, password);
// U1 = HMAC(P, S || INT_32_BE(i)); Ui = HMAC(P, U(i-1)); T = U1 ^ ... ^ Uc
```
Kotlin: use BouncyCastle `PKCS5S2ParametersGenerator(SHA256Digest())` with `init(passwordUtf8Bytes, salt, 600000)` and `generateDerivedParameters(256)`, or a hand-written loop over `javax.crypto.Mac("HmacSHA256")`. Do NOT use `SecretKeyFactory("PBKDF2WithHmacSHA256")` with `PBEKeySpec(char[])` unless you have proven it encodes the char[] as UTF-8 for non-ASCII/surrogate pairs — the test vector with `Gruß-Ähre-müde-😀` must pass. Run on `Dispatchers.Default`; expect ~1–2 s.

Platform variants: on Android/iOS `pbkdf2Fast` resolves to `pbkdf2_fast_stub.dart` and always returns `null`, so the effective mobile path is always `compute(pbkdf2Dart, ...)` (the pure-Dart core in `pbkdf2_dart.dart`). `pbkdf2_fast_web.dart` (WebCrypto) exists only for the web build, produces bit-identical output, and is out of scope for the native port — there is no "fast path then fallback" to reproduce; native calls a single PBKDF2 implementation.

### 7.2 Backup encryption

```dart
final salt = _generateSalt();                      // 32 bytes Random.secure()
final key = await _deriveKey(password, salt);
final iv = enc.IV.fromSecureRandom(16);            // 16-byte IV (NOT 12!)
final encrypter = enc.Encrypter(enc.AES(key, mode: enc.AESMode.gcm));
final encrypted = encrypter.encrypt(jsonString, iv: iv);
final finalPayload = "enc_v2:${base64Encode(salt)}:${iv.base64}:${encrypted.base64}";
```
`encrypt` 5.0.3 AES-GCM = pointycastle `GCMBlockCipher(AESEngine())` with `AEADParameters(key, 128, iv, <empty AAD>)`; output = ciphertext ‖ 16-byte tag. With a 16-byte IV, GCM derives J0 = GHASH(IV ‖ pad ‖ [len(IV) in bits]_64) (standard SP 800-38D; verified in pointycastle 3.9.1 `_computeInitialCounter`). Kotlin: `Cipher.getInstance("AES/GCM/NoPadding")` + `GCMParameterSpec(128, iv16)`, no AAD — verify on-device that the provider accepts 16-byte IVs (vector in fixtures); otherwise use BouncyCastle `GCMBlockCipher`/`GCMModeCipher` which supports any IV length.

Decryption: `encrypter.decrypt64(cipherText, iv: iv)` = base64-decode → GCM decrypt+verify → `utf8.decode(allowMalformed: true)`. Tag mismatch throws → "wrong password".

Legacy `enc_v1`: `key = sha256(utf8(password))` (32 bytes), `iv = base64Decode(parts[1])` (accept any length), same AES-GCM, no salt/iterations.

base64 decoding on import: Dart `base64Decode` accepts the standard and URL-safe alphabets; native should use a lenient decoder (`java.util.Base64.getMimeDecoder()` is NOT right; use `Base64.getDecoder()` and fall back to `getUrlDecoder()`), and treat any decode error as "wrong password / corrupt".

### 7.3 Sync codec (wire format inside NIP-44)

```dart
// push
final sealed = await SigningService.nip44Encrypt(me, base64Encode(BadgeSyncCodec.deflate(plain)));
// fetch
final plain = await SigningService.nip44Decrypt(me, content);
final env = BadgeSyncFormat.parseEnvelope(BadgeSyncCodec.inflate(base64Decode(plain)));
```
- `deflate(text) = gzip(utf8.encode(text))` — RFC 1952 gzip (dart:io `gzip.encode`, default level 6; web: `archive` GZipEncoder). Kotlin `GZIPOutputStream`. Exact gzip bytes differ between implementations (header mtime/OS byte) — irrelevant, only decodability matters.
- `inflate(bytes) = utf8.decode(gzip.decode(bytes))` (strict UTF-8). Kotlin `GZIPInputStream`.
- Platform variants: mobile uses `badge_sync_codec_io.dart` (`dart:io` gzip) — this is the variant to port. `badge_sync_codec_web.dart` (`package:archive` GZipEncoder/GZipDecoder) is web-build only and out of scope; both emit/accept standard RFC 1952 gzip, so chunks written by a web client decode with `GZIPInputStream` and vice versa. Native needs no `archive` dependency.
- base64 standard with padding.

### 7.4 Hashes (local state only — not on the wire)

```dart
static String chunkHash(String plaintext) => sha256.convert(utf8.encode(plaintext)).toString();   // lowercase hex
static String contentHash(String plaintext) {
  final env = parseEnvelope(plaintext);
  return chunkHash(jsonEncode({'v': 1, 'badges': env.badges.map((b) => b.toJson()).toList()}));
}
```
`contentHash` deliberately excludes `i`, `n`, `gen` so a new generation does not re-publish unchanged chunks. Stored hashes are only compared with hashes the same device computes; a native canonical JSON that differs from Dart's only causes one-time re-publication after an in-place upgrade.

### 7.5 NIP-44 payload size estimate (chunk boundary measure) — replicate verbatim

```dart
static int payloadCharsForGzip(int gzipBytes) {
  // NIP-44: base64(1 + 32 + gepolstert + 32).
  final padded = _padLen(gzipBytes);
  return ((1 + 32 + padded + 32 + 2) ~/ 3) * 4;
}
static int _padLen(int len) {
  if (len <= 32) return 32;
  var power = 1;
  while (power < len) { power <<= 1; }
  final chunk = power <= 256 ? 32 : power ~/ 8;
  return chunk * ((len - 1) ~/ chunk + 1);
}
```
Quirk: the real NIP-44 plaintext is the base64 of the gzip bytes (≈ 4/3 × longer) so true payloads are larger than estimated (up to ~44 000 chars for a "30 000" chunk). Keep the quirk for parity (chunking is not wire-relevant, but relay size acceptance was probed with these sizes). `chunkPayloadChars = 30000`.

### 7.6 Key consistency check (Security Audit N3)

```dart
static bool _backupKeyConsistent(String nsec, String npub, String privHex) {
  try {
    final privFromNsec = Nip19.decodePrivkey(nsec.trim()).toLowerCase();
    final priv = privHex.trim().toLowerCase();
    if (privFromNsec != priv) return false;
    if (!RegExp(r'^[0-9a-f]{64}$').hasMatch(priv)) return false;
    final derivedNpub = Nip19.encodePubkey(Keychain(priv).public);   // x-only BIP-340 pubkey, bech32 "npub"
    return derivedNpub == npub.trim();                               // case-sensitive compare
  } catch (_) { return false; }
}
```

### 7.7 Local-key agreement during settle

```dart
if (mode != SigningMode.local) return true;
priv = (SecureKeyStore.getPrivHex() ?? '').trim().toLowerCase();
if (priv.isEmpty) return true;
return Nip19.encodePubkey(Keychain(priv).public).trim().toLowerCase() == now;   // exception → false
```

---

## 8. Business rules & algorithms

### 8.1 Export: which keys go into the file

```
hasLocalKey      = nsec != null && nsec.isNotEmpty
backupNpub       = hasLocalKey ? (keystoreNpub ?? '') : (activeNpub ?? keystoreNpub ?? '')
backupActiveNpub = (isExternal && hasLocalKey) ? (activeNpub ?? '') : ''
nostr.nsec       = nsec ?? ''              // exported whenever it exists, even in external mode (mixed state)
nostr.priv_hex   = hasLocalKey ? (privHex ?? '') : ''
nostr.has_key    = hasLocalKey
nostr.signing_mode = mode.name             // local | amber | nip07 | nip46
nostr.nip46_bunker_uri = nip46Session?['bunker_uri'] ?? ''   // NEVER the client session key
```

### 8.2 Backup identity

```
backupIdentityNpub(data): nostr = data['nostr']; if not a map → ''.
    active = trim(str(nostr['active_npub'] ?? '')); if non-empty → active; else trim(str(nostr['npub'] ?? ''))
backupIdentities(data): nostr not a map → []. Ordered set of lowercase(trim(x)) for x in [active_npub, npub] where trim(x) non-empty.
    e.g. {npub:A, active_npub:B} → [b, a]; {npub:A.upper, active_npub:A} → [a]
restoreDecision(existing, backup):
    e = lower(trim(existing)); b = lower(trim(backup))
    e empty → FRESH; b non-empty && b == e → SAME_IDENTITY; b empty → ADD_TO_IDENTITY; else REPLACES_IDENTITY
```
(Comparison on npub strings, case-insensitive.)

### 8.3 Fresh-mode profile construction
```
nickname = user.nickname ?? "Anon"; fullName ?? ""; homeMeetupId ?? "";
favoriteMeetupIds = (user.favoriteMeetupIds as List?)?.map(toString) ?? (homeMeetupId non-empty ? [homeMeetupId] : [])
isAdmin ?? false (→ adminViaVouch); isAdminVerified ?? false; isNostrVerified ?? false; nostrNpub ?? ""; telegramHandle ?? ""; twitterHandle ?? ""
```
These cached flags are re-verified later by the admin/humanity domains (Security Audit C2); do not treat them as trusted.

### 8.4 `mergeProfile(device, userData)` (additive modes)

```
fill(current, incoming) = current.trim() non-empty ? current : (str(incoming ?? '').trim() empty ? current : str(incoming))
nickname  = device.hasCustomNickname ? device.nickname : (incomingNick.trim() empty ? device.nickname : incomingNick)
             // hasCustomNickname = trimmed nickname non-empty && != 'Anon'
backupFavs = userData.favoriteMeetupIds?.map(toString).filter(nonEmpty) ?? (homeMeetupId non-empty ? [homeMeetupId] : [])
favs      = ordered set(device.favorites.filter(nonEmpty) + backupFavs)
home      = device.homeMeetupId.trim() non-empty ? device.homeMeetupId : str(userData.homeMeetupId ?? '')
if home non-empty && home ∉ favs → favs.insert(0, home)
fullName/telegramHandle/twitterHandle = fill(...)
isAdminVerified, isNostrVerified, nostrNpub, hasNostrKey, adminViaPortal, adminViaVouch, adminViaSeed, isReviewDemo = device values (backup ignored)
```

### 8.5 Counting / union helpers

- `addedBadgeCount(before, merged)` = count of `merged` badges whose `unionKey` is not in `{unionKey(b) | b ∈ before}`.
- `unionPlatformProofs(device, incoming)`: map keyed by `lowercase(str(proof['platform'] ?? ''))`; device entries inserted first (later device duplicates overwrite earlier ones), incoming only `putIfAbsent`; non-map entries skipped; result = values in insertion order.
- `restoreBadges(list)`: `wrote = MeetupBadge.saveBadges(list, replaceUnreadable: true)`; if `!wrote && list.isNotEmpty` → `false`; else `true` (an empty list over an unreadable file is not an error; the unreadable file is left untouched). Logs "Badge-Datei schreiben: N Eingänge, M eindeutige Einträge; geschrieben: <bool>."

### 8.6 Nostr key restore (only `!additive && version >= 2 && nostr != null`)

```
hasKey = nostr.has_key ?? false; mode = str(nostr.signing_mode ?? 'local'); restoredLocalKey = false
if hasKey:
   nsec, npub, privHex = nostr.nsec ?? '', nostr.npub ?? '', nostr.priv_hex ?? ''
   allowLocalKey = !kIsWeb || kWebLocalNsecLoginEnabled          // native Android: always true
   if allowLocalKey && all three non-empty:
       if !backupKeyConsistent(nsec, npub, privHex):
           log security "Backup-Schlüssel inkonsistent …"; alignProfileWithKeystore(user)   // see below
       else: SecureKeyStore.saveKeys(nsec, npub, privHex); restoredLocalKey = true
externalRestored = false
if mode ∈ {amber, nip07, nip46}:
   externalNpub = non-empty(str(active_npub)) ? active_npub : str(npub)
   if externalNpub non-empty:
     try: nip07 → SigningService.restoreNip07(externalNpub)
          nip46 → SigningService.restoreNip46(npub=externalNpub, bunkerUri=str(nip46_bunker_uri ?? ''))   // no client key; empty/invalid URI throws
          amber → SigningService.restoreAmber(externalNpub)
          user.nostrNpub = externalNpub; user.isNostrVerified = true; user.hasNostrKey = restoredLocalKey; user.save(); externalRestored = true
     catch: log warn "Externer Signer (<mode>) aus dem Backup nicht wiederherstellbar"
if !externalRestored && restoredLocalKey:
   SigningService.useLocalMode(); user.nostrNpub = str(nostr.npub); isNostrVerified = true; hasNostrKey = true; user.save()
```
`alignProfileWithKeystore(user)`: keystore npub non-empty → `nostrNpub = it, isNostrVerified = true, hasNostrKey = true`; else `'' / false / false`; then `user.save()`.

### 8.7 Replace finalisation (only `REPLACES_IDENTITY`)

```
nowNpub = trim(SigningService.npub() ?? ''); wanted = backupIdentities(data); now = lower(nowNpub)
switched = identitySwitched(existingNpub, nowNpub) && (wanted.isEmpty || now ∈ wanted)
if !switched:
    unchanged = nowNpub non-empty && now == lower(trim(existingNpub))
    if unchanged: MeetupBadge.discardIncoming()
    throw (nowNpub empty ? "…ist gerade nicht lesbar…" : "…wurde nicht übernommen…")
BadgeSync.clearIdentityProofs()                     // sets badge_restore_proofs = true
if restored.isEmpty: badgesSaved = saveBadges([], discardLocked: true); if badgesSaved: clearRestoreMark()
else if !MeetupBadge.promoteIncoming(formerNpub: existingNpub): badgesSaved = false
if badgesSaved: user.save()
```

### 8.8 Admin registry restore (only `version >= 2 && admin_registry != null`)
For each entry: `AdminEntry.fromJson`; reject if `!NostrService.isValidNpub(npub)` (must start `npub1` and bech32-decode); `AdminRegistry.addAdmin(entry)` (throws for legacy organic names `^Organic \(\d+ Badges\)$` → counted rejected; duplicates by npub ignored; `added_at <= 0` → now). Afterwards `AdminRegistry.forceRefresh()` (relay revalidation; offline failure only logged). Applies in ALL decision modes (including additive).

### 8.9 Platform proofs restore (if list non-empty)
Keep only entries that are maps with `proof_sig` matching `^[0-9a-fA-F]{128}$` (format check only; no signature verification here). Additive: `merged = unionPlatformProofs(existingPrefsList, validated)`; write if non-empty. Non-additive (fresh/replace): write `validated` only if non-empty (otherwise leave prefs untouched). Stored as `jsonEncode(list)` of the ORIGINAL maps (all fields preserved).

### 8.10 Humanity proof restore
```
humanityAlready = additive && prefs.humanity_verified == true
if !humanityAlready && humanity_proof != null && humanity_proof.verified == true:
    humanity_verified = false; humanity_needs_reverification = true      // never trust the file directly (Audit 2 #4)
    receipt_event_id non-empty → humanity_receipt_id
    first_zap_at > 0 → humanity_first_zap_at;  checked_at > 0 → humanity_checked_at
```
The humanity domain re-checks the zap receipt on relays at the next start.

### 8.11 Restore hold state machine (BadgeSyncService)

```
beginRestoreHold(fromNpub='', expectNpubs=[], replace=false):
    epoch++
    settleRestore()
    pending = prefs.badge_restore_hold == true
    from = lower(trim(fromNpub))
    if pending:
        if !replace: throw "Ein Identitätswechsel ist noch nicht abgeschlossen. Lade das Backup der neuen Identität noch einmal."
        from = lower(trim(prefs.badge_restore_from ?? ''))           // keep the ORIGINAL owner; keep staged buffer
    else:
        MeetupBadge.discardIncoming(); prefs.remove(badge_restore_clear)
    expect = ordered set(lower(trim(n)) for n in expectNpubs if non-empty)
    write badge_restore_from=from, badge_restore_expect=expect, badge_restore_hold=true
    if any write failed: prefs.reload(); if !pending: remove hold; throw "Der Wechsel konnte nicht festgehalten werden."
    suspended = true

pendingFrom(): settleRestore(); hold != true → null; else lower(trim(badge_restore_from ?? ''))

endRestore(): suspended = false; settleRestore(); if pendingWhileSuspended: pendingWhileSuspended = false; trigger()

identitySwitched(from, now): c = lower(trim(now)); c empty → false; else c != lower(trim(from))

markRestoreClear(): MeetupBadge.discardIncoming(); set badge_restore_clear = true (failure → throw "Die leere Sammlung konnte nicht vorgemerkt werden.")
clearRestoreMark(): remove badge_restore_clear

clearIdentityProofs(): in parallel: humanity_verified=false, remove humanity_needs_reverification, humanity_receipt_id,
    humanity_first_zap_at, humanity_checked_at, platform_proofs; then badge_restore_proofs=true.
    any failure → prefs.reload(); throw "Die Nachweise der bisherigen Identität konnten nicht abgeräumt werden."

settleRestore():
    if suspended: return                                      // a restore in THIS process owns the buffer
    if hold != true: return
    from = lower(trim(badge_restore_from ?? '')); now = lower(trim(SigningService.npub() ?? ''))
    expect = badge_restore_expect (List → lower/trim each; legacy non-empty String → [it]; else [])
    staged = incoming != null && incoming.contains(':'); clear = badge_restore_clear == true
    switched = identitySwitched(from, now) && (expect.isEmpty || now ∈ expect)
    if switched && !localKeyAgrees(now): switched = false
    waiting = from non-empty && (staged || clear) && (now empty || (identitySwitched(from, now) && !switched))
    if waiting: log warn "Identität noch nicht die aus dem Backup, Riegel bleibt"; return
    if staged:
        if switched: if !MeetupBadge.promoteIncoming(formerNpub: from): log warn; return     // keep hold+buffer
        else: MeetupBadge.discardIncoming()
        if clear: remove badge_restore_clear; clear = false
    if clear:
        if !switched: remove badge_restore_clear
        elif !clearActiveWithoutSync(): log warn; return       // saveBadges([], discardLocked:true) with onBadgesSaved temporarily null
        else: remove badge_restore_clear
    if switched && badge_restore_proofs != true: clearIdentityProofs()
    if switched: forgetLocalState()
    remove badge_restore_hold, badge_restore_from, badge_restore_expect, badge_restore_proofs
```
While `badge_restore_hold` is true nothing is ever published (`_syncOnce` returns HELD).

### 8.12 Sync engine

```
init(): MeetupBadge.onBadgesSaved = trigger; SigningService.onSignerReady = trigger; trigger()

trigger():                              // fire-and-forget, never throws
    if suspended: pendingWhileSuspended = true; return
    launch { try { ok = syncNow(); if !ok: dirty = true } catch(e) { dirty = true; log debug } }

retryIfDirty(): if dirty != true return; try syncNow() catch log      // called at start and on app resume

syncNow(): Boolean
    if busy: rerun = true; return true
    busy = true; runs++
    try loop:
        rerun = false
        a = syncOnce()
        if rerun: continue
        if a == NIP44_BLOCKED: if rerun continue; dirty = false; if rerun continue; return true
        if a == NO_SIGNER || a == HELD: return true
        return a == OK
    finally busy = false

syncOnce():
    settleRestore(); if held: return HELD
    epoch0 = epoch
    me = SigningService.pubkeyHex(); null/empty → NO_SIGNER          // (an exception here propagates → trigger marks dirty)
    try { if !canNip44() return NIP44_BLOCKED } catch(UnsupportedError|SigningException) { return NIP44_BLOCKED }
    merged = pull(me, epoch0); null → FAILED
    if epoch0 != epoch → FAILED; if held → HELD
    pushed = push(me, merged, epoch0)
    dirty = !pushed
    return pushed ? OK : FAILED

pull(me, epoch0): List<Badge>?
    remote = fetchRemote(me); null → log "Kein Relay erreichbar…"; return null
    if epoch0 != epoch || held: return null
    local = MeetupBadge.loadBadges()
    if MeetupBadge.badgesUnreadable:
        if remote.isEmpty: log warn; return null
        if !saveBadges(remote, replaceUnreadable: true): return null
        return loadBadges()
    merged = BadgeSyncFormat.merge(local, remote)          // local wins ties
    if MeetupBadge.sameBadges(merged, local): return local // no write, no trigger
    if epoch0 != epoch || held: return null
    if !saveBadges(merged): return null                    // NOTE: triggers onBadgesSaved → rerun after this pass
    return merged

fetchRemote(me): List<Badge>?
    run fetchFromRelay on all relays in parallel; answered = number that completed without error
    answered == 0 → null
    byIndex = newest (strictly greater created_at) per d-index across relays
    badges = []; newestGen = 0
    for event in byIndex.values: content empty → skip
        try: plain = nip44Decrypt(me, content); env = parseEnvelope(inflate(base64Decode(plain)))
             badges += env.badges; newestGen = max(newestGen, env.generation)
        catch: log debug "Chunk nicht lesbar"
    if newestGen > 0: badge_sync_last_gen = newestGen
    return badges                                           // may contain duplicates; merge dedups

push(me, merged, epoch0): Boolean
    if epoch0 != epoch || held: return false
    if merged.isEmpty: return true                          // NEVER publish an empty list
    if chunkBadges(merged, measure = { it.length }).size > 16: log warn; return false   // stays dirty
    generation = clamp(nowUnixSeconds, lower = badge_sync_last_gen ?? 0, upper = 1 shl 62)
    plaintexts = BadgeSyncFormat.plaintexts(merged, generation, measure = { payloadCharsForGzip(gzip(it).size) })
    old = badge_sync_chunk_hashes ?? []; newHashes = []; ok = true
    for i in plaintexts.indices:
        if epoch0 != epoch || held: return false          // hashes NOT saved
        h = contentHash(plaintexts[i]); newHashes += h
        if i < old.size && old[i] == h: continue
        sealed = nip44Encrypt(me, base64(gzip(plaintexts[i])))
        signed = signEvent(kind 30078, tags [["d","badges-$i"],["client","einundzwanzig-meetup-app"]], content sealed)
        event = {id, pubkey, created_at, kind, tags, content, sig}
        accepted = first relay in SYNC_RELAYS order whose publish() returns true (exceptions logged, next relay)
        if !accepted: log warn "Chunk $i auf keinem Relay bestätigt"; ok = false
    badge_sync_chunk_hashes = newHashes; badge_sync_last_gen = generation
    return ok
```
(Exceptions from `nip44Encrypt`/`signEvent` inside push propagate → `syncNow` throws → `trigger` sets dirty.)

### 8.13 Chunking (`BadgeSyncFormat`) — must be deterministic

```dart
static List<MeetupBadge> sortBadges(Iterable<MeetupBadge> badges) {
  final list = badges.toList();
  list.sort((a, b) { final byDate = a.date.compareTo(b.date); if (byDate != 0) return byDate; return a.id.compareTo(b.id); });
  return list;
}
static List<List<MeetupBadge>> chunkBadges(List<MeetupBadge> badges, int Function(String) measure) {
  if (badges.isEmpty) return const [];
  final sorted = sortBadges(badges);
  final chunks = <List<MeetupBadge>>[];
  var current = <MeetupBadge>[];
  int payloadLen(int index, int total) => measure(envelopeJson(badges: current, index: index, total: total, generation: 0));
  for (final badge in sorted) {
    current.add(badge);
    final total = chunks.length + 2;
    if (payloadLen(chunks.length, total) > chunkPayloadChars) {   // 30000
      final last = current.removeLast();
      if (current.isEmpty) { chunks.add([last]); current = <MeetupBadge>[]; continue; }
      chunks.add(current);
      current = [last];
    }
  }
  if (current.isNotEmpty) chunks.add(current);
  return chunks;
}
static List<String> plaintexts(badges, generation, measure) {
  final chunks = chunkBadges(badges, measure);
  return [for (var i = 0; i < chunks.length; i++) envelopeJson(badges: chunks[i], index: i, total: chunks.length, generation: generation)];
}
```
Date compare = instant compare (microsecond precision). Id compare = UTF-16 code-unit lexicographic (Kotlin `String.compareTo` matches). Sort must be stable-irrelevant (keys are total except identical (date,id) pairs, which the union removes beforehand except organizer/participant pairs).

### 8.14 Merge rule (`BadgeSyncFormat.merge` = `sortBadges(unionByIdentity(local, remote))`)

`unionByIdentity(base, extra)`: insertion-ordered map keyed by `unionKey`; for each badge of `base` then `extra`: absent → put; present → `existing = preferVariant(existing, badge)` (position unchanged). `preferVariant(a, b)` returns:
1. the one with non-empty `claimSig` if exactly one has it;
2. else the one with `presenceVerified == true` if they differ;
3. else the earlier `date`;
4. else the smaller `claimTimestamp`;
5. else the smaller `id`;
6. else `a` (the base/local one).

`sameBadges(a, b)`: sort both lists of `jsonEncode(toJson())` strings; equal length and element-wise equal.

---

## 9. Security measures to preserve

1. PBKDF2-HMAC-SHA256, 600 000 iterations, 32-byte random salt per backup, AES-256-GCM with random 16-byte IV (SecurityChangelog Fix 1). Never reuse salt/IV. Legacy `enc_v1` decrypt-only.
2. Minimum backup password length 8 enforced on export only.
3. Bunker (NIP-46) client session key (`nip46_client_sk`) NEVER exported; on restore it is deleted and an old `nip46_client_sk` in the file is ignored (restored identity cannot sign until re-pairing; `canSign()` false).
4. Amber/NIP-07/NIP-46 backups contain no private key (only npub + mode); local key exported only if it actually exists.
5. N3 key consistency: nsec ⇔ priv_hex ⇔ npub must agree before anything is written to the keystore; on mismatch the profile is re-aligned to the existing keystore identity.
6. H4 (web only): local nsec from a backup not stored in the browser. Native Android: allowed.
7. Audit 2 #1: restored admin npubs validated (npub format) and the registry is force-revalidated against signed relay events; legacy "Organic (N Badges)" entries rejected.
8. Audit 2 #4: platform proofs require a 128-hex signature (format); humanity proof restored only as `needs_reverification` (never directly verified).
9. Audit C2: profile admin/verified flags from a backup are caches; re-verified by their owners.
10. Identity switch is crash-safe: staging buffer + persisted hold; nothing is published under a pubkey until the keystore provably carries a backup identity and the local private key matches it; proofs of the old identity are cleared before new ones are written; old collection archived ("replaced"), never deleted.
11. Sync never publishes an empty list; never overwrites an unreadable local file (remote copy written only via `replaceUnreadable` and the old file is preserved by the badges domain); export refuses when badges are unreadable (protects reset).
12. Sync content is NIP-44 encrypted to self; fetched events are signature-verified, filter-checked, future-timestamp-capped (+600 s), frame size ≤ 512 KiB.
13. Export/import single-flight (`busy`), overlay closed exactly once (black-screen bug #18), and export never returns `true` without a file (reset would wipe keys).
14. No logging of keys, payloads or plaintext (only counts/status). Keep it that way.

---

## 10. Tests & vectors to port

Fixture file: `android-native/spec/fixtures/08-backup-sync-vectors.json` (PBKDF2 list, `enc_v2`/`enc_v1` deterministic files with fixed salt/iv, envelope + `chunkHash` + `contentHash`, `payloadCharsForGzip` table, restoreDecision cases).

### 10.1 `test/pbkdf2_equality_test.dart` (PBKDF2, salt = UTF-8 `einundzwanzig-salt-32-bytes-lang!` (33 bytes), 32-byte key unless noted)
| password | iterations | expected hex |
|---|---|---|
| `korrekt pferd batterie klammer` | 1 | `070375026d4efd90f55f8c596f9804c9d59e8b261c226982aea5b2076a95bb8a` |
| same | 2 | `f92df020004a77d443c83df50dd1899ad67b97d28b7d21b9481a78e5cb86885a` |
| same | 4096 | `730ecb5f9ff2ce548af5ef16149284b4b0e96be1a1c57f1192bafe12bae7b0c9` |
| same | 100000 | `22772726dbae2cda50c4f036ecce698ad1def22782eaab08d439742d531e0166` |
| `passwort` | 600000 | `c32f5c29f83c8c3a2250ecd4ca726a7766d74edfd8cb74d2cf95e7d4654ce58e` |
| `Gruß-Ähre-müde-😀` | 1000 | `090f088c774831b951cdff271be383fe5c34b45a5e925f5cf465eda21affa3a1` |
| `passwort` (64-byte key) | 1000 | `546f47b1fbba44eb9b8f88a78a65a017911b0f1d744ad7aff4742981e7af99d8ca31938c361ececdd17b1f0eeefadfec9df608589f378a985dc1424b6b1481ba` |
| `einundzwanzig-test-passwort` (pbkdf2_web_equality_test) | 600000 | `e69c19ea83dcda0b6f2e010dfaa087359d3d4b5ad4ba925b012cbfa9b80e271c` |
Plus: iterations 0 → IllegalArgumentException; keyBytes 0 → IllegalArgumentException. (Re-verified with Python hashlib.)

### 10.2 New native tests (from fixtures)
- Decrypt `enc_v2.file_content` with password → exact `plaintext`; derived key = `f7aca35c82775f0f96aa035ff53905b9fc45119fb3487033a40470c2e85c1a98`.
- Encrypt `plaintext` with injected salt `00..1f` and IV `a0..af` → exactly `enc_v2.file_content`.
- Decrypt `enc_v1.file_content` (key = SHA-256(password) = `f005f1351d30f32c05b38dcf8256f4606797d6cfe9abef01754edb82b6ef6e15`).
- Wrong password → `WrongPasswordOrCorrupt`; `enc_v2:` with 3 parts → same error; plain JSON passes through.
- `contentHash(envelope)` = `4643efcb79ba4c0d440bc78f2fbd5c65c77c981ec73da9567428e9ba6dca6b85`; `chunkHash(envelope)` = `76c395529b1ff32d9896eb0ae452009a17d53f3c531c2b913ebea1169fc1ec3a` (requires Dart-identical JSON for the sample badge).
- `payloadCharsForGzip`: 0→132, 32→132, 33→172, 65→216, 100→260, 256→428, 257→516, 1000→1452, 1025→1796, 5000→6916, 20000→27396, 22000→32856.
- Release gate: decrypt a REAL `.21bkp` produced by the current Flutter app (store as `fixtures/flutter-real.21bkp` with its password) and the reverse (native-made file imported in Flutter).

### 10.3 `test/badge_sync_codec_test.dart`
- gzip round trip of `{"v":1,"badges":[{"id":"x"}]}`; compressed ≠ raw UTF-8.
- `List.filled(200,'meetup').join(',')` compresses smaller and round-trips.

### 10.4 `test/badge_sync_format_test.dart` (badge helper: `meetupName = 'M <id>'`, `iconPath = ''`, `claimSig = 'sig<id>'`/`''`, `claimPubkey = 'pk'`/`''`, optional 800×`x` filler in `sig` and `sigContent`; measure = plaintext length)
1. sort: b(5/2), a(5/2), c(5/1) → `[c, a, b]`.
2. envelope round trip: index 1, total 2, gen 42 → parsed index 1, total 2, gen 42, ids `[x, y]`, first claimTimestamp 100.
3. 40 long badges (daily dates) → > 1 chunk, every chunk envelope (gen 0) ≤ 30000 chars, no badge lost.
4. determinism: reversed input → identical plaintexts (gen 7).
5. merge local `[local]` + remote `[remote]` → `[local, remote]`.
6. same id: claimed wins from either side.
7. same claim: presenceVerified=true wins; both verified → the LOCAL instance (identity) kept.
8. `chunkHash` stable; differs when gen changes (3 vs 4).
9. per-event union: two claimed scans same `meetupEventId` 19:00 vs 20:00 → keep the 19:00 one from both sides; unclaimed 19:00 vs claimed 20:00 → keep claimed `…-2`; organizer marker of the same evening is kept separately (2 results).

### 10.5 `test/backup_restore_decision_test.dart`
- `backupIdentityNpub`: `{npub:A, active_npub:B}` → B; `{npub:' A ', active_npub:''}` → A; no nostr / `nostr:null` → `''`.
- `backupIdentities`: `{A, active B}` → `[b, a]` (lowercased); `{npub:A.upper, active:A}` → `[a]`; no nostr → `[]`.
- `restoreDecision`: ('',A)→FRESH; (A,A)→SAME; (A,B)→REPLACES; (A,'')→ADD; (A.upper, A)→SAME.
- `mergeProfile`: device {Ada, fullName '', home bonn, favs [bonn], npub A, adminViaPortal} + backup {Alt, 'Ada Lovelace', koeln, [koeln, bonn], npub1other} → nickname Ada, fullName 'Ada Lovelace', home bonn, favs ⊇ {bonn, koeln}, npub A, adminViaPortal true, adminViaVouch false.
- `addedBadgeCount`: before [evt-bonn], merged [evt-bonn (other id), evt-koeln] → 1.
- `unionPlatformProofs`: device telegram/'device' + incoming telegram/'backup', x/'extra' → 2 entries, telegram keeps 'device'.

### 10.6 `test/backup_restore_badges_test.dart` (badge storage integration)
- Corrupt badge key value (`kein-schluessel`) and no file → `restoreBadges([a])` true; suspect key moved to `badge_encryption_key_suspect`; new 64-hex key; load → `[a]`.
- Unreadable secure storage with an existing file → `restoreBadges([a])` false; file unchanged.
- Empty backup over an unreadable file → true; file unchanged.
- Normal → `[a, b]`.
- Variants of one event (`event-a` + rotating id) + `event-b` → 2 stored; log contains "3 Eingänge, 2 eindeutige Einträge".
- Same id, different `meetupEventId` → both kept.

### 10.7 `test/badge_sync_suspend_test.dart` (hold state machine; npubs `npub1aaaa…`, `npub1bbbb…`, `npub1cccc…` are syntactically fake — the logic only string-compares them)
1. No hold: one trigger → 1 run.
2. Held: two triggers → 0 runs; `endRestore` → exactly 1 run.
3. Hold + end without triggers → 0 runs.
4. Hold without identity change (crash simulated) → settle discards buffer, removes hold, file stays `[bleibt]`.
5. from A, keystore B, buffer `[neu]`, hashes `['x']` → settle promotes: load `[neu]`, buffer gone, hold gone, `badge_sync_chunk_hashes` null, former = 1 entry (reason `replaced`, npub A, badges `[alt]`).
6. npub unreadable → hold stays, buffer stays, file `[bleibt]`; `identitySwitched(A,'')` false, `(A,B)` true.
7. Pending switch blocks a non-replace `beginRestoreHold('')` (throws), state unchanged.
8. `markRestoreClear` discards an older buffer and sets `badge_restore_clear`.
9. Keystore C, expected [B] → hold stays, buffer stays, file `[alt]`.
10. Replace continuation: privHex `ab` (bad) → hold stays, `pendingFrom` = A; second replace keeps `badge_restore_from` = A and buffer; after fixing the key, `endRestore` → hold gone, file `[neu2]`, former npub A `[alt]`.
11. Mixed backup expects [B, C], keystore C, no priv → switch accepted, file `[neu]`.
12. priv must match npub (`ab` with npub B) → hold stays, file `[alt]`.
13. Switch clears old proofs (`humanity_verified` false, `platform_proofs` null, `badge_restore_proofs` not true).
14. Proofs already cleared by restore (flag set), new proofs written → settle keeps them.
15. New `beginRestoreHold(fromNpub: B)` after a crash settles first: file `[neu]`, former A `[alt]`.
16. Empty replace: no npub → hold stays, file `[alt]`; after keystore B → file empty, hold/clear gone, former `[alt]`.
17. In-process restore: settle does nothing (buffer + hold stay).
18. `forgetLocalState` removes hashes, gen, dirty.

### 10.8 `test/stage_a_security_test.dart` group N3
Random keypair: consistent → true; npub of another key → false; priv_hex of another key → false; `zz-not-hex` → false.

### 10.9 `test/badge_reputation_regression_test.dart` (restore + sync merge don't inflate trust score; uses real signed kind 21000/21002 test events)
Monthly visits (same id, different events) both kept; rolling-QR duplicates collapse to 1; participant + organizer marker kept separately (2) and marker scores 0; `merge(baseline, baseline+rescans+markers)` → 7 entries, reputation equal to baseline, stable over re-import. (Owned jointly with trust-score domain.)

---

## 11. Android capabilities & recommended APIs

| Need | API / library |
|---|---|
| Save backup file | Storage Access Framework: `ActivityResultContracts.CreateDocument("application/octet-stream")` with suggested name; write via `ContentResolver.openOutputStream`. No storage permission. |
| Share fallback | `Intent.ACTION_SEND` (type `application/octet-stream`, `EXTRA_STREAM` via `FileProvider` URI of a file in `cacheDir/backup/`, `EXTRA_SUBJECT`, `EXTRA_TEXT`, `FLAG_GRANT_READ_URI_PERMISSION`), `Intent.createChooser(intent, title, PendingIntent(…BroadcastReceiver).intentSender)` to detect the chosen target (`EXTRA_CHOSEN_COMPONENT`); no callback before the chooser activity returns ⇒ "dismissed" ⇒ `false`. Delete the cache file later. |
| Pick backup | `ActivityResultContracts.OpenDocument()` with `arrayOf("*/*")`; read all bytes (files are a few KB–MB). |
| PBKDF2 / AES-GCM / SHA-256 | BouncyCastle (`bcprov-jdk18on`, in catalog) or JCA `Mac`/`Cipher` (see §7); `SecureRandom` for salt/IV. |
| gzip | `java.util.zip.GZIPOutputStream` / `GZIPInputStream`. |
| base64 | `java.util.Base64` (standard, padded). |
| Relay WebSocket | OkHttp `WebSocket` (catalog `okhttp`). |
| Schnorr / bech32 / NIP-44 | secp256k1-kmp (catalog) via the nostr-core / signing domains. |
| Prefs | Preferences DataStore. |
| Background | In-process coroutines on `@ApplicationScope`; app-resume hook via `ProcessLifecycleOwner` (ON_START → `retryIfDirty`). Optional `WorkManager` one-time work with `NetworkType.CONNECTED` for dirty retries in LOCAL-key mode only (Amber/bunker may need foreground interaction). |
| Permissions | `INTERNET` only. No runtime permissions. |

---

## 12. Cross-domain dependencies

Calls OUT of this domain:
- **Badges / badge storage** (`MeetupBadge`): `loadBadges`, `badgesUnreadable`, `saveBadges(list, replaceUnreadable, discardLocked)`, `stageIncoming`, `promoteIncoming(formerNpub)`, `discardIncoming`, `incomingPrefsKey`, `unionByIdentity`, `unionKey`, `sameBadges`, `toJson/fromJson`, hook `onBadgesSaved`; former-collections archive UI (`badge_wallet`) is where replaced collections surface.
- **Signing** (`SigningService`): `getMode`, `npub`, `pubkeyHex`, `isExternalSigner`, `nip46SessionForBackup`, `restoreAmber/restoreNip07/restoreNip46`, `useLocalMode`, `canNip44`, `nip44Encrypt/Decrypt`, `signEvent`, hook `onSignerReady`. Must request Amber/NIP-46 permissions `nip44_encrypt`, `nip44_decrypt`, `sign_event:30078` at connect time.
- **Other kind-30078 users** (reputation `d=einundzwanzig-reputation`, admin registry `d=einundzwanzig-admins`, nickname lookup): they always filter by their own `#d`, so the `badges-*` events never leak into them; keep it that way in native queries.
- **Key store** (`SecureKeyStore`): `getNsec/getNpub/getPrivHex`, `saveKeys`.
- **Nostr core**: NIP-19 encode/decode, BIP-340 keys, event id/signature verification (`RelaySocket.verifiedEvent/answersFilter/replaceableCreatedAt`), NIP-44 v2 (local signer), `NostrService.isValidNpub/shortenNpub`.
- **Profile** (`UserProfile.load/save`, `hasCustomNickname`).
- **Admin registry** (`getAdminList`, `addAdmin`, `forceRefresh`, `AdminEntry`).
- **Platform proofs** (`getSavedProofs`; pref `platform_proofs`).
- **Humanity proof** (`getStatus`; prefs `humanity_*`; re-verification at next start).
- **Logging** (`AppLogger` tags `BackupRestore`, `BackupService`, `Backup`, `App`, `BadgeSync`, `Nip44Probe`).
- **l10n**, **share origin** (iPad only; not needed on Android).

Calls INTO this domain:
- App startup: `BadgeSync.init()` + `retryIfDirty()` after the first badge load.
- Home: resume → `retryIfDirty()`; settings rows → export/import; reset flow → export result.
- Intro, identity setup: import (result → navigation); identity secure sheet → export.
- Badge storage: `onBadgesSaved` → `trigger()`. Signing: `onSignerReady` → `trigger()`.
- Settings tour: target key for the backup row.
- Migration domain (if in-place upgrade): must carry the §4 keys.

---

## 13. Proposed Kotlin modules & public API

```
:data:backup      package space.einundzwanzig.meetup.data.backup
:data:badgesync   package space.einundzwanzig.meetup.data.badgesync
:feature:backup   package space.einundzwanzig.meetup.feature.backup   (dialogs, overlay, SAF/share launchers, coordinator host)
```

```kotlin
// ---------- :data:backup ----------
object BackupCrypto {
    const val PBKDF2_ITERATIONS = 600_000; const val SALT_BYTES = 32; const val KEY_BYTES = 32; const val IV_BYTES = 16
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray   // throws IAE for <1
    suspend fun encryptV2(json: String, password: String, salt: ByteArray = random(32), iv: ByteArray = random(16)): String
    suspend fun open(fileContent: String, password: suspend () -> String?): OpenResult
}
sealed interface BackupContainer { data class V2(val salt: ByteArray, val iv: ByteArray, val ct: ByteArray) : BackupContainer
                                   data class V1(val iv: ByteArray, val ct: ByteArray) : BackupContainer
                                   data class Plain(val json: String) : BackupContainer
    companion object { fun parse(content: String): BackupContainer } }        // prefix detection, split(':')
sealed interface OpenResult { data class Ok(val json: String) : OpenResult; data object Cancelled : OpenResult; data object WrongPasswordOrCorrupt : OpenResult }

object BackupFileName { fun of(nickname: String, now: java.time.LocalDateTime): String }

class BackupDocumentBuilder @Inject constructor(/* profile, badges, keystore, signing, adminRegistry, proofs, humanity */) {
    suspend fun build(): BuildResult            // BuildResult.Ok(json) | BuildResult.BadgesUnreadable
}

object RestorePolicy {                           // pure, unit-tested (§8.2–8.5, 7.6)
    fun backupIdentityNpub(data: JsonObject): String
    fun backupIdentities(data: JsonObject): List<String>
    fun restoreDecision(existingNpub: String, backupNpub: String): RestoreDecision
    fun mergeProfile(device: UserProfile, user: JsonObject): UserProfile
    fun addedBadgeCount(before: List<MeetupBadge>, merged: List<MeetupBadge>): Int
    fun unionPlatformProofs(device: List<JsonElement>, incoming: List<JsonElement>): List<JsonObject>
    fun backupKeyConsistent(nsec: String, npub: String, privHex: String): Boolean
    fun isValidProofSig(sig: String): Boolean    // ^[0-9a-fA-F]{128}$
}

class RestoreEngine @Inject constructor(/* deps */) {
    suspend fun restore(data: JsonObject, confirm: suspend (RestoreConfirmation) -> Boolean): RestoreOutcome
}
sealed interface RestoreConfirmation { data class Replace(val existingShort: String, val backupShort: String) : RestoreConfirmation
                                       data class AddUnknown(val existingShort: String) : RestoreConfirmation }
sealed interface RestoreOutcome { data object Cancelled : RestoreOutcome
    data class Merged(val added: Int) : RestoreOutcome
    data class Restored(val badges: Int, val nostrKey: Boolean, val platformProofs: Int?, val humanity: Boolean) : RestoreOutcome
    data class Failed(val message: String) : RestoreOutcome }

// ---------- :data:badgesync ----------
object BadgeSyncFormat {
    const val KIND = 30078; const val MAX_CHUNKS = 16; const val CHUNK_PAYLOAD_CHARS = 30_000
    fun dTag(i: Int): String = "badges-$i"
    fun sortBadges(badges: Iterable<MeetupBadge>): List<MeetupBadge>
    fun envelopeJson(badges: List<MeetupBadge>, index: Int, total: Int, generation: Long): String   // Dart-identical JSON
    fun chunkBadges(badges: List<MeetupBadge>, measure: (String) -> Int): List<List<MeetupBadge>>
    fun plaintexts(badges: List<MeetupBadge>, generation: Long, measure: (String) -> Int): List<String>
    fun chunkHash(plaintext: String): String
    fun contentHash(plaintext: String): String
    fun payloadCharsForGzip(gzipBytes: Int): Int
    fun parseEnvelope(plaintext: String): Envelope     // throws on v != 1 / not object / no badges list
    fun merge(local: List<MeetupBadge>, remote: List<MeetupBadge>): List<MeetupBadge>
}
data class Envelope(val index: Int, val total: Int, val generation: Long, val badges: List<MeetupBadge>)
object BadgeSyncCodec { fun deflate(text: String): ByteArray; fun inflate(data: ByteArray): String }

interface BadgeSync {                               // singleton, @ApplicationScope
    fun init()                                      // register hooks + trigger()
    fun trigger()
    suspend fun retryIfDirty()
    suspend fun syncNow(): Boolean
    // restore hold API (used by RestoreEngine)
    suspend fun beginRestoreHold(fromNpub: String = "", expectNpubs: List<String> = emptyList(), replace: Boolean = false)
    suspend fun pendingFrom(): String?
    suspend fun endRestore()
    suspend fun settleRestore()
    suspend fun markRestoreClear(); suspend fun clearRestoreMark()
    suspend fun clearIdentityProofs(); suspend fun forgetLocalState()
    fun identitySwitched(from: String, now: String): Boolean
}
interface SyncRelayClient {                         // OkHttp implementation; fakeable in tests
    suspend fun fetchChunks(relay: String, filter: JsonObject): Map<Int, NostrEvent>   // throws if no EOSE in 8 s
    suspend fun publish(relay: String, event: NostrEvent): Boolean                      // 5 s connect, 8 s OK
}

// ---------- :feature:backup ----------
@Singleton class BackupCoordinator @Inject constructor(...) {
    val ui: StateFlow<BackupUiState>                 // None | PasswordDialog(isExport) | Loading | ConfirmReplace | ConfirmAdd
    suspend fun export(): Boolean                    // single-flight with import (shared Mutex.tryLock → false)
    suspend fun restore(): Boolean
}
@Composable fun BackupHost(coordinator: BackupCoordinator, snackbarHostState: SnackbarHostState)  // registers SAF/share launchers once
```
Use a `Mutex` + `rerun` flag (or a conflated `Channel<Unit>` worker) to reproduce `syncNow`'s single-flight-with-rerun; `epoch` as `AtomicInteger`. Expose an optional `Flow<Unit>` "badgesChangedBySync" so the wallet/home refresh (Flutter has no such signal — an allowed UX improvement).

JSON: use `kotlinx.serialization` `JsonObject` for reading backups/envelopes (lenient, unknown keys ignored). For WRITING badge/envelope/backup JSON use a hand-ordered `buildJsonObject` (key order as §3) with ints as JSON integers, doubles via Dart-compatible formatting, and dates via the badges domain's Dart-compatible ISO formatter.

---

## 14. Risks / compatibility pitfalls

1. **AES-GCM with a 16-byte IV** for backups (badge storage uses 12). Some providers/APIs assume 12-byte nonces; verify with the fixture on real devices or use BouncyCastle.
2. **PBKDF2 password encoding**: must be UTF-8 bytes of the exact string (no trim/NFC). JCA `PBEKeySpec(char[])` encoding is provider-dependent; the emoji vector must pass.
3. **Dart type strictness on import**: Flutter `fromJson` throws on floats where ints are expected (`blockHeight`, `sigVersion`, `claimTimestamp`, `version`, `added_at`, `created_at`, `first_zap_at`, `checked_at`) and on null required strings (`id`, `meetupName`, `date`, `iconPath`). A single bad badge aborts a Flutter backup import and silently drops the badge from a sync chunk on Flutter devices. Native writers must emit exact JSON types.
4. **Date strings**: badge `date` must be written so Dart's `DateTime.parse` yields the same instant AND the same UTC/local flavour (it affects `identityKey`'s day fallback and therefore dedup). Coordinate with the badges domain.
5. **Union key / preferVariant parity**: any deviation changes which variant survives a merge across Flutter and native devices → ping-pong re-publication and possibly trust-score differences. Port `unionByIdentity`/`preferVariant` exactly (insertion-ordered map, tie → base).
6. **Failed chunk never retried (Flutter bug)**: `_push` records the hash of a chunk even when no relay accepted it, so the next (dirty) retry sees "unchanged" and skips it; the chunk is only re-published when its content changes. Recommended native fix (wire-compatible): store the OLD hash (or `""`) at index `i` when publishing chunk `i` failed. Document the deviation.
7. **Scaling limit**: the pre-check measures raw JSON length; above 16 × 30 000 chars (~450+ badges) sync stops and stays dirty forever. Keep parity (Flutter devices could not read > 16 chunks anyway: readers only query `badges-0..15`).
8. **Single relay per chunk**: a chunk is published to the first relay that accepts it only. Fetch reads all four. Keep the same relay list and order; adding relays to the read side is compatible, removing is not.
9. **Envelope `v` must stay 1**; Flutter rejects other versions (the whole chunk is dropped). Do not add fields that change `v`; extra unknown keys are tolerated by Flutter's parser.
10. **NIP-44 must be v2 and to self**; Amber/bunker signers are asked twice per run for the probe plus once per chunk. If the user rejects, sync silently turns off until the next trigger (dirty cleared) — mirror, don't nag.
11. **Busy/overlay handling**: never return `true` from export without a persisted file or a chosen share target; the reset flow deletes keys on `true`.
12. **Partial writes on import failure**: Flutter may already have saved the profile (fresh/additive) or, in replace mode with an inconsistent key, saved the BACKUP's profile fields via `alignProfileWithKeystore` before failing (quirk). Native may pre-validate all badges/fields before writing anything (recommended; no format change). Callers must reload state after any import attempt.
13. **Hold persistence**: if a crash leaves `badge_restore_hold = true`, sync is blocked until the user re-imports the new identity's backup (replace) or the keystore settles. Make sure `settleRestore` runs at start (it runs inside every sync attempt and every `pendingFrom`).
14. **Hard-coded German strings** (success items, exception texts) — add new l10n keys in native; `backupCorrupt` is never shown in Flutter (masked by `backupWrongPassword`).
15. **In-place upgrade**: if the native APK replaces the Flutter app (same `space.einundzwanzig.meetup`), sync hashes/generation/hold keys live in `FlutterSharedPreferences` with the `flutter.` prefix and list encoding prefix; keys/badge blobs live in flutter_secure_storage's own encrypted format. Losing `badge_restore_*` during migration could unblock publishing mid-switch — migrate them or settle before upgrade.
16. **`resetBackupBody` text** claims badges cannot be restored via Nostr; with badge sync this is no longer strictly true (same nsec + NIP-44 signer restores them). Keep the text unless product decides otherwise.
17. **Humanity export quirk**: `getStatus()` reports `verified=false` while re-verification is pending, so a backup taken in that window does not carry the humanity proof.
18. **Platform-proof count** in the success message is the raw file count, not the number actually stored; platform proofs are only format-checked (128 hex), not signature-verified, on restore.
19. **`nip07` mode on Android**: a browser-extension backup restores identity + mode `nip07`; Android cannot sign in that mode (signing domain must treat it as "identity without signer").
20. **Base64 leniency**: Dart decodes URL-safe characters too; use a tolerant decoder on import.
