# :data:backup

The `.21bkp` backup without UI: encryption, file container, the version-5 document, the pure
restore rules and the restore executor. Spec: `spec/08-backup-sync.md` (backup part: §2.4–§2.5,
§3.1–§3.2, §7.1–§7.2, §7.6, §8.1–§8.10, §9). Dart source: `lib/services/backup_service.dart`.
Package root: `space.einundzwanzig.meetup.data.backup`.

```kotlin
implementation(project(":data:backup"))   // exposes :core:common, :core:data, :core:crypto, :data:identity, :data:badges as api
```

The badge sync over Nostr is `:data:badgesync`. This module uses its restore-hold API and
`BadgeSyncFormat.merge`. Dialogs, the overlay, SAF/share launchers and snackbars belong to
`:feature:backup` (`BackupCoordinator` + `BackupHost`, spec 08 §13). The shared busy guard is
`BackupSession` here, because export and import are started from several features (home
settings, home reset, intro, identity setup) that may not depend on each other.

## Public API

| API | Purpose |
|---|---|
| `BackupExporter` (inject) | `buildDocument(): BuildResult` (`Ok(json, nickname)` / `BadgesUnreadable`). `export(password): ExportResult` (`Ready(fileName, content)` with `.bytes` / `BadgesUnreadable` / `PasswordTooShort`). The v5 JSON is byte-identical to Flutter (fixture-tested). Encryption runs on `@DefaultDispatcher` and takes about 1–2 s. Exceptions from the stores propagate: show `backupError(e.message)` |
| `BackupSession` (inject, singleton) | The one busy guard of spec 08 §9 #13 for export AND import. `tryRun { … }` returns `null` (block not run) while another flow runs; ignore that silently. `busy: StateFlow<Boolean>` |
| `RestoreEngine` (inject) | `restore(document, confirm: suspend (RestoreConfirmation) -> Boolean): RestoreOutcome`. It never throws except for cancellation (a failing `BadgeSync.endRestore` is logged; the persisted hold settles on the next sync run). Cancellable only until the confirmation; after it the write phase always completes. Parsing, validation and writes run on `@DefaultDispatcher`; only `confirm` runs in the caller's context, so it is safe to call from Main |
| `RestoreConfirmation` | `Replace(existingNpub, backupNpub)` (`existingShort`, `backupShort` = `"?"` if empty) → `backupReplaceTitle/Body/Confirm`. `AddUnknown(existingNpub)` → `backupAddUnknownTitle/Body/Confirm` |
| `RestoreOutcome` | `Cancelled` (nothing written). `Merged(added)` → `backupMergeAdded(added)` / `backupMergeSame`. `Restored(badges, nostrKey, platformProofs: Int?, humanity)` → `backupLoaded(items)` (`germanItems` = Flutter's German list). `Failed(error: RestoreError)` → `backupImportFailed(...)` |
| `RestoreError` | Typed cases with `.german` (the Flutter text): `SwitchPending`, `HoldNotPersisted`, `ClearNotMarked`, `ProofsNotCleared`, `ProofsNotSaved`, `StageFailed(count)`, `KeyUnreadable`, `KeyNotTaken`, `BadgesNotSaved(count)`, `InvalidContent(detail)`, `Other(message)`. Suggested native string keys: `backupErr*` (spec 08 §2.7) |
| `BackupCrypto` | `PBKDF2_ITERATIONS`, `SALT_BYTES`, `KEY_BYTES`, `IV_BYTES`. `pbkdf2Sha256(...)`. `suspend encryptV2(json, password)`. `suspend decrypt(container, password): OpenResult`. `suspend open(content, password: suspend () -> String?, onKeyDerivation: suspend (Boolean) -> Unit)`: the full Dart open step. It prompts only for encrypted files and wraps only the `enc_v2` PBKDF2 in the overlay callback (`true` then `false` exactly once) |
| `BackupContainer` | `parse(content)` → `V2(salt, iv, sealed)` / `V1(iv, sealed)` / `Plain(json)` / `Corrupt(format)`. `needsPassword`. A malformed `enc_*` file is `Corrupt`: Flutter asks for the password first and then shows `backupWrongPassword` |
| `OpenResult` | `Ok(json)` / `Cancelled` / `WrongPasswordOrCorrupt` (`backupWrongPassword`) |
| `BackupDocument` | `suspend parse(json, dispatcher = Dispatchers.Default): ParseResult` (CPU-heavy for MB-sized files, so it runs off the caller's thread; `parseBlocking(json)` is the same on the calling thread, for workers/tests only) (`Ok(document)` / `NotValid` → `backupNotValid` / `NotEinundzwanzig` → `backupNotEinundzwanzig`). `data: JsonObject`, `version` (`?? 1`), `CURRENT_VERSION = 5`, `APP_NAME`, `of(obj)` |
| `RestoreDecision` | `FRESH`, `SAME_IDENTITY`, `ADD_TO_IDENTITY`, `REPLACES_IDENTITY` (`logName`, `isAdditive`) |
| `RestorePolicy` (pure) | `backupIdentityNpub`, `backupIdentities`, `restoreDecision`, `freshProfile`, `mergeProfile`, `addedBadgeCount`, `unionPlatformProofs`, `validatedPlatformProofs`, `isValidProofSig`, `backupKeyConsistent` (N3), `shortenNpub` |
| `BackupFiles` | `suspend read(open: () -> InputStream?): String` (opens on IO; `null`, `IOException`, `SecurityException` → `BackupReadException`). `suspend read(input: InputStream): String` (strict UTF-8, BOM dropped, max `MAX_FILE_BYTES` = 16 MiB, closes the stream; every I/O error and every unchecked provider error while reading, e.g. `SecurityException`, `IllegalStateException`, `UncheckedIOException`, → `BackupReadException`). `suspend write(output, content)` (closes the stream). `EXTENSION`, `MIME_TYPE` (`application/octet-stream`), `OPEN_MIME_TYPES` (`*/*`) |
| `BackupFileName.of(nickname, now: LocalDateTime)` | `Backup_yyyy-MM-dd_HHmm_21MeetupApp_<safeNick>.21bkp`. Each UTF-16 unit outside `[A-Za-z0-9_-]` becomes `_` |
| `BackupPasswordRules` | `MIN_PASSWORD_LENGTH = 8`, `longEnough`, `matches`, `canSubmit(isExport, pw, confirm)` (live checklist / button state) |
| `BackupFormatException`, `BackupReadException` | |

## Feature wiring (`:feature:backup`)

Every flow runs inside the shared guard; `null` means another export/import is running (ignore
silently):

```kotlin
val result = session.tryRun { /* export or import flow below */ } ?: return false
```

Export (spec 08 §2.4):

```kotlin
when (val r = exporter.export(password)) {           // inside the overlay
    is ExportResult.Ready -> {
        // SAF CreateDocument(BackupFiles.MIME_TYPE) with r.fileName → openOutputStream(uri)
        BackupFiles.write(out, r.content)             // success → true
        // cancelled → share fallback: write r.bytes to cacheDir/backup/r.fileName, ACTION_SEND
    }
    ExportResult.BadgesUnreadable -> snackbar(badgesUnreadableBody, 8 s, floating); false
    ExportResult.PasswordTooShort -> false            // dialog prevents it
}
```

Import (spec 08 §2.5):

```kotlin
val content = BackupFiles.read({ contentResolver.openInputStream(uri) })  // BackupReadException → backupImportFailed
when (val o = BackupCrypto.open(content, password = { askPassword(isExport = false) },
        onKeyDerivation = { showOverlay(it) })) {
    OpenResult.Cancelled -> return false
    OpenResult.WrongPasswordOrCorrupt -> { snackbar(backupWrongPassword); return false }
    is OpenResult.Ok -> when (val p = BackupDocument.parse(o.json)) {
        ParseResult.NotValid -> backupImportFailed(backupNotValid)
        ParseResult.NotEinundzwanzig -> backupImportFailed(backupNotEinundzwanzig)
        is ParseResult.Ok -> engine.restore(p.document) { c -> showConfirm(c) }   // map RestoreOutcome
    }
}
```

The caller reloads its state after every import attempt (Flutter's home `_loadAll()`), also after
a failure. The busy flag, the strings and `backupItem*` / `backupErr*` resources are owned by the
feature. `HumanityReverify` (`:data:proofs`) reacts to the restored
`humanity_needs_reverification` flag by itself.

## Storage

The module owns no keys. It writes through the owning domains: `UserProfileRepository.save`
(profile prefs), `SecureKeyStore.saveKeys`, `SigningService.restoreAmber/restoreNip07/restoreNip46/useLocalMode`,
`BadgeRepository.saveBadges/stageIncoming/promoteIncoming/discardIncoming`,
`AdminRegistry.addAdmin/forceRefresh`, `PlatformProofRepository.replaceAllRaw/unionRaw`
(`platform_proofs`), and `HumanityProofRepository.restoreFromBackup` (`humanity_*`). The hold keys
(`badge_restore_*`) go through `BadgeSync`. Additive humanity checks use the ownership-filtered
repository status, so stale data from another identity cannot skip restoration.

## Tests

`tools/gradlew-locked :data:backup:testDebugUnitTest`: 101 JVM tests (several
600 000-round PBKDF2 runs).

- `BackupCryptoTest`: all 8 PBKDF2 vectors (`pbkdf2_equality_test`, `pbkdf2_web_equality_test`),
  `iterations`/`keyBytes` 0 → IAE, `enc_v2` decrypt + derived key, encrypt with fixed salt/IV ==
  fixture file, `enc_v1`, wrong password, malformed/3-part containers, tampering, plain passthrough,
  overlay callback (closed once also when cancelled mid-PBKDF2), URL-safe base64.
- `BackupRestoreDecisionTest`: all cases of `backup_restore_decision_test.dart`, the fixture
  `restoreDecision` table, fresh-profile defaults, N3 (`stage_a_security_test`), `shortenNpub`.
- `BackupRestoreBadgesTest`: all 6 cases of `backup_restore_badges_test.dart` on the real
  `BadgeRepository` (in-memory stores, incl. the log line "3 Eingänge, 2 eindeutige Einträge").
- `BackupExporterTest`: the document equals the fixture `enc_v2.plaintext` byte for byte, plus key
  selection (local / mixed / bunker), int types and key order, refusal on an unreadable wallet, and
  the file name.
- `BackupFilesTest`: file name, password rules, strict UTF-8, BOM, the opener overload.
- `BackupSessionTest`: single flight, silent `null` while busy, release on exception.
- `RestoreEngineTest`: fresh / same / add / replace (confirm, decline, key not taken, key
  unreadable, Amber, empty replace), local and mixed keys, an inconsistent key, a bunker with and
  without a valid URI, version 1, admin registry, platform proofs (replace + union), humanity,
  hold errors, a throwing `endRestore` (outcome kept), pre-validation of every optional section and of additive `favoriteMeetupIds`, cancellation before/after the
  confirmation, and an export → import round trip.
- `RestoreEndToEndTest`: the engine against the real `DefaultBadgeSync` (built reflectively, it is
  internal to `:data:badgesync`): fresh restore keeps `platform_proofs` and the pending humanity
  proof after `endRestore` and after a crash before it; replace keeps the new identity's proofs;
  every `RestoreHoldException` text in the badgesync source maps to a typed `RestoreError`.
- `FlutterBackupInteropTest`: decrypts a file generated by the unchanged Flutter
  `BackupService.createBackup`, restores its synthetic key/profile/wallet and re-exports every
  data section. Flutter's real importer independently consumes the native export. Fixture
  provenance and reproduction are in `spec/fixtures/backup-interop/README.md`.

## Deviations from Flutter

- **Validation before writing** (spec 08 §14 risk 12): `user` (must be an object), every badge,
  the fresh-mode profile fields, `user.favoriteMeetupIds` as list in additive mode, and every Dart cast of the later steps (`nostr` as object and its
  `has_key`/`nsec`/`npub`/`priv_hex` where Flutter reads them, `admin_registry` and
  `platform_proofs` as lists, `humanity_proof` as object with a bool `verified` and, where Flutter
  applies it, `receipt_event_id` as string and `first_zap_at`/`checked_at` as int, so `1.7e9` or
  `"1700000000"` fails instead of being dropped silently) are checked before
  the confirmation, the hold and any write. A malformed backup fails with `InvalidContent` and
  leaves nothing half-written. Flutter could save the profile, badges or even switch the identity
  first and then abort. Fields Flutter never reads in the given mode are not checked.
- **Fresh restore keeps its proofs** (Flutter bug, `badge_sync_service.dart` settle together with
  `backup_service.dart`): a fresh restore holds with `from = ""`; once the backup key is active,
  settling saw a switch without `badge_restore_proofs` and wiped the `platform_proofs` and
  `humanity_*` it had just restored (the snackbar still listed them, `HumanityReverify` never ran).
  Native calls `BadgeSync.clearIdentityProofs()` in fresh mode right after the key restore when the
  keystore switched, before the proofs are written. On a device without identity this only clears
  proofs that belong to nobody; it sets `badge_restore_proofs`, so settle keeps the restored proofs,
  also after a crash before `endRestore`. Report upstream.
- **Not cancellable after the confirmation**: the write phase runs under `NonCancellable` (Flutter
  could not be interrupted past the dialogs either; a Kotlin caller scope could).
- **Concurrent calls** of `RestoreEngine.restore` are serialized by the engine (a second import
  would otherwise interleave `beginRestoreHold`/`endRestore`). Features still use `BackupSession`.
- **Inconsistent key in replace mode**: Flutter's `_alignNostrIdentityWithKeystore` saved the
  backup's profile fields even though the switch then failed. Native aligns the profile in memory
  and saves it only in fresh mode. In replace mode the profile is saved only after a proven
  switch, as in the normal replace path.
- **Errors are typed** (`RestoreError`) instead of `Exception.toString()`. The bare message has no
  `"Exception: "` prefix (spec 08 §2.5 step 25).
- **Write failures of proofs** (`unionRaw`/`replaceAllRaw`/`restoreFromBackup` → `false`) produce
  `RestoreError.ProofsNotSaved`. Other proof sections still get their write attempt, already
  restored data stays available, and the UI explains that the backup must be loaded again.
  A failed proof write cannot become a successful restore or merge. The proofs module keeps an
  unreadable stored list untouched. Successful platform-proof counts include only validated entries.
- **File size cap** of 16 MiB on read (Flutter had none); real backups are KB to a few MB.
- `BackupCrypto.encryptV2` checks the salt and IV lengths (32/16).

Kept for parity: `active_npub` is not trimmed for the external-signer restore; additive mode never restores keys;
admin entries are restored in every mode; `nip46_client_sk` in a file is ignored.

## Known gaps

- `:data:badgesync` exposes no test factory, so `RestoreEndToEndTest` constructs the internal
  `DefaultBadgeSync` reflectively (constructor with 8 parameters, `Proxy` ports). A constructor
  change there breaks that test, not production. Requested upstream: a `BadgeSync` test factory
  and a typed `RestoreHoldException.reason` (the engine maps by the Flutter message today; the
  source-scanning test catches any drift).
- A JVM provider check of 16-byte GCM IVs on real devices is not needed: `:core:crypto` uses
  BouncyCastle `GCMBlockCipher`, which accepts any IV length.
