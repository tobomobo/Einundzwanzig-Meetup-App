# :data:migration

One-time import of the Flutter app's on-device data (same `applicationId`
`space.einundzwanzig.meetup`) into the native stores. Implements spec 14 §4 (and the
widget-file rules of §3.2/§4.3.3). Binding resolutions: `spec/00-contradictions.md` items 2, 3, 6.

- `FlutterSharedPreferences.xml` → `KeyValueStore` (`app_prefs`), key names without `flutter.`,
  same types. All encodings: `<string>/<boolean>/<long>/<int>/<float>/<set>`, JSON list prefix
  (`…Lu!` + Dart `jsonEncode`), legacy Java-serialized list (class allow-list), double prefix
  (`Double.toString`), BigInteger (reported, not copied).
- flutter_secure_storage → `SecureStore` (`secure_prefs`), same key names, in every storage era:
  E0 plaintext prefs keys, E1 9.2.4 EncryptedSharedPreferences (incl. the **F1 orphans** the
  10.x migration skipped), E1 custom AES-CBC fallback, E2 10.3.4 AES-GCM, and `_BACKUP` entries
  of an interrupted 10.x migration.
- `HomeWidgetPreferences.xml` and `e21_widget.xml` are **never touched** (they stay the native
  widget/router stores, spec 14 §3.2); `InternalHomeWidgetPreferences.xml` is deleted after the
  import.
- `diagnostic_log` / `diagnostic_verbose` go to `AppLogger.importLegacy` (never into `KeyValueStore`).
- `local_profile_picture` (image_picker path in `cacheDir`) is copied to
  `filesDir/profile/local_picture.<ext>` and the new path stored; missing file → key omitted.

State: `noBackupFilesDir/flutter_migration/state.json` (`AtomicFile`, names/counts only, never
values) — survives an app reset (F6). An unparsable state file is copied once to
`state.json.corrupt` and replaced conservatively: Flutter files present **and** native stores
non-empty → `IMPORTED_WITH_ERRORS` with `STATE_FILE_CORRUPT` and the automatic attempt limit
reached (secure keys present natively count as imported, absent ones stay pending), so nothing the
user deleted natively is re-imported until `retryNow()`/`abandonPendingSecrets()`; otherwise a
normal `NOT_STARTED`. Ciphertext snapshot of the secure-storage files in
`noBackupFilesDir/flutter_migration/snapshot/` before any Tink/Keystore call (each file via
`.tmp` + fsync + rename, `.complete` marker last; repeated on every attempt until the marker
exists; completed files are never overwritten).

## Public API (`space.einundzwanzig.meetup.data.migration`)

| Type | Use |
|---|---|
| `FlutterMigration` (Hilt `@Singleton`) | `gate: MigrationGate`; `suspend ensureMigrated(): MigrationReport` (idempotent, process-wide mutex, runs detached in a process-wide scope so a cancelled caller never aborts the import, never throws except the caller's own cancellation, releases the gate once the attempt finished); `retryNow()` (Settings → Diagnose "Alte Daten erneut übernehmen", ignores the 10-attempt limit and safe mode, also retries abandoned secrets); `abandonPendingSecrets()` (Settings → Diagnose, only after user confirmation: gives up on every pending secret → `IMPORTED` + warning `ABANDONED:<names>`, Flutter files kept for `retryNow`); `currentReport()`; `pendingLegacySecrets(): Set<String>`; `cleanupIfEligible(): CleanupOutcome`; `onAppReset()` (detached from the caller, records `CLEANED` before deleting); constants `MAX_AUTOMATIC_ATTEMPTS = 10`, `MIN_STARTS_BEFORE_CLEANUP = 3` |
| `MigrationGate` (same singleton, injectable directly) | `isComplete`, `suspend await()` (starts the migration if nobody has) |
| `MigrationCompletedHook` (`fun interface`, contribute with `@Binds @IntoSet`) | runs after every import attempt that wrote data, after the mutex is released and the gate is complete (a hook may await the gate / read the stores), e.g. `MeetupWidget().updateAll(context)` in `:feature:widget`; failures are logged and ignored |
| `MigrationReport` | `state`, `attempts`, `eras: Set<StorageEra>`, `counts` (`COUNT_*` keys), `failedKeys`, `recovered` (secure recovery names), `warnings` (codes), `startedAt`, `finishedAt`, `isSettled` |
| `MigrationState` | `NOT_STARTED`, `NOT_NEEDED`, `IMPORTED`, `IMPORTED_WITH_ERRORS`, `CLEANED` |
| `StorageEra` | `E0`, `E1` (ESP), `E1_CBC`, `E2` |
| `CleanupOutcome` | `Cleaned`, `NothingToDo`, `NotYet(reason)` (`GATE`, `STATE`, `STARTS`, `ABANDONED`, `RELEASE`, `SECRET_MISSING:<key>` (only `badge_encryption_key`), `SECRET_UNREADABLE:<key>`, `IDENTITY`, `BADGES`) |

Everything else (`legacy.*` readers, `internal.*`) is `internal`.

## Wiring (downstream)

```kotlin
// :app Application.onCreate
appScope.launch(io) { flutterMigration.ensureMigrated() }
// MainActivity: bounded wait, a hanging keystore2 binder call must not keep the splash forever
// (pending secrets stay protected by the Unreadable mapping, see below)
lifecycleScope.launch { withTimeoutOrNull(20.seconds) { migrationGate.await() }; splashDone = true }
installSplashScreen().setKeepOnScreenCondition { !splashDone || … }
// Glance provideGlance, every Worker, AppStartupTask runner:
migrationGate.await()
// App reset (spec 12 §2.5.12): FIRST, then wipe KeyValueStore / SecureStore
flutterMigration.onAppReset()
keyValueStore.clear(); secureStore.deleteAll(); …
// From the release AFTER the first native one, once per start:
flutterMigration.cleanupIfEligible()
```

- **Pending secrets.** While `pendingLegacySecrets()` contains a key, the owning domain must treat
  it as *unreadable* (Dart `resetOnError: false`) and never generate a replacement key/identity.
  It covers `IMPORTED_WITH_ERRORS` (failed names; every known key if the secure-storage or prefs
  file was unreadable) and `NOT_STARTED` with Flutter files present (aborted attempt, corrupt
  state file).
- **Store barrier.** `:core:data` owns `migration.MigrationGate`, `LegacySecretStatus` and
  the `@Ungated` qualifier. Production `KeyValueStore` operations (including flow collection)
  and `SecureStore` operations await the gate. Pending secret reads return
  `Unreadable(PENDING_MIGRATION)`; writes/deletes cannot replace them. This module binds both
  interfaces to `DefaultFlutterMigration`, which uses the ungated stores to avoid awaiting its
  own import. The existing `:data:migration` gate extends the core interface for compatibility.
- **Recovery copies** are written to `SecureStore` as `legacy_esp/<key>`, `legacy_backup/<key>`,
  `legacy_recovered/<key>` (same prefixes as `RecoverySource` in `:data:identity`).
  `IdentityRecovery` (`:data:identity`) offers the identity ones. A recovery entry is **never
  overwritten**: when the name already holds a different value (e.g. a native-conflict copy and a
  third distinct `_BACKUP` value both target `legacy_recovered/<key>`), the value goes to the first
  free overflow slot `<name>#2`, `#3`, … (up to `#16`). `IdentityRecovery` reads and resets every
  slot and splits mixed slots by the actual public key. Adopting or discarding one candidate
  preserves other parked identities. Badge domain: every 64-hex value
  among primary, `legacy_*/badge_*` and `*_suspect` secrets is a candidate key for opening blobs
  (Dart `_candidateKeys`); the badge and portal owners delete their own recovery copies on reset.
- **Reset** must call `onAppReset()` **before** wiping the native stores, so the Flutter files can
  never be re-imported (F6/R8): it writes `CLEANED` first, then deletes, so a process death at any
  point leaves either the old state with the old stores, or `CLEANED`. The state file itself is never
  deleted. An incomplete delete (`RESET_*_INCOMPLETE`) is finished by a later `cleanupIfEligible()`.
- **Safe mode (spec 14 R6).** Two attempts in a row that died (process kill, native crash or hang
  inside AndroidKeyStore/Tink) switch automatic attempts to safe mode (`ATTEMPT_DIED`, `SAFE_MODE`):
  prefs, E0 plaintext and rolling-QR secrets are imported, no Keystore/Tink call is made, every
  encrypted secure-storage entry stays pending. Only `retryNow()` runs the full path again.
- **Permanent failures.** A missing ESP master alias is reported as `ESP_MASTER_KEY_MISSING`.
  Settings → Diagnose should then (or after `TOO_MANY_ATTEMPTS`) offer `abandonPendingSecrets()`
  behind a confirmation, otherwise the pending names (all 19 inventory keys when the ESP/secure file
  is unreadable) stay unreadable for the domains forever.
- **Cleanup** waits for a later release: the `versionCode` of the first native start is recorded
  (`firstNativeVersionCode`), and `cleanupIfEligible()` returns `NotYet("RELEASE")` while the
  installed `versionCode` is not greater. It only requires `badge_encryption_key` to be still present among the imported secrets;
  rolling-QR secrets, `badge_reset_pending`, portal tokens, pending keys and a logged-out identity
  are deleted in normal use. Any imported secret that reads `Unreadable` blocks it.

## Resolution rules (spec 14 §4.3.4 / §4.8)

- Native value already present → never overwritten; differing Flutter value → `legacy_recovered/<key>`.
- Main custom-cipher entry primary; `_BACKUP` primary if main missing/undecryptable; differing
  `_BACKUP` → `legacy_backup/<key>`.
- E0 plaintext (`nostr_nsec_key`, `nostr_npub_key`, `nostr_priv_hex`) with
  `secure_migration_done != true` wins (overwrites, as Dart); `secure_migration_done=true` is
  copied only when those writes verified. `rqr_session_seed` / `rqr_session_base_payload` →
  `*_secure`. Plaintext sources are removed from `FlutterSharedPreferences.xml` only after a
  verified read-back.
- A custom-cipher entry (main or `_BACKUP`) that exists but cannot be decrypted is never replaced
  by an ESP value: the key stays pending (`failedKeys`) and the ESP value is parked under
  `legacy_esp/<key>` (warning `ESP_HELD_BACK:<key>`); a later retry imports the custom value.
- ESP value with no custom value: identity keys (`nostr_*`, `easyauth_*`, `nip46_client_sk`,
  `portal_api_token*`) primary only if the ESP group's npub equals `nostr`/`amber_npub`/`nip46_npub`
  in the Flutter prefs, else `legacy_esp/<key>`; other keys primary. ESP differing from a
  custom value → `legacy_esp/<key>`.
- Obsolete keys (§4.3.2) skipped; undecodable values reported (`UNDECODABLE_PREF:<key>`), never fatal.
- CBC trial decryption accepted only as strict UTF-8 passing the per-key validator of §4.4; GCM
  values that fail it are flagged (`IMPLAUSIBLE:<key>`), not rejected. nsec/npub/priv mismatch →
  `IDENTITY_INCONSISTENT`.

## Keystore seam

`legacy.LegacyKeystore` (internal) wraps the read-only Android Keystore operations (RSA OAEP /
PKCS#1 unwrap of the AES-128 storage key, ESP master-key AEAD, alias deletion at cleanup). It never
creates an alias. Tests use a software implementation (`FakeLegacyKeystore`) with real RSA and a
software ESP master key; Flutter-format XML files are written into the Robolectric app's
`shared_prefs/`.

## Tests

`./tools/gradlew-locked :data:migration:testDebugUnitTest` — Robolectric, vectors from
`spec/fixtures/14-platform-migration-vectors.json` (read in place): `flutter_shared_prefs`,
`fss_v10_gcm`, `fss_v9_cbc`, `badge_blob`, `home_widget_prefs`, `fss_constants`,
`secure_key_inventory`, `prefs_key_inventory`, `obsolete_prefs_keys`, `migration_routing`
(data-driven, one end-to-end scenario per row). The diagnostic log import first registers every
imported secret value (nsec, private/NIP-46 keys, ncryptsec, portal token, rolling-QR seed/payload,
badge keys, incl. recovery copies) with the log redactor, and records `logImported` at once (the
import is not idempotent).
