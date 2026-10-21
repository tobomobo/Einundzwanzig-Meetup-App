package space.einundzwanzig.meetup.data.migration

/**
 * One-time import of the Flutter app's data (same `applicationId`) into the native stores
 * (spec 14 §4): `FlutterSharedPreferences.xml` → `KeyValueStore`, flutter_secure_storage (all
 * storage eras E0/E1/E2, including the EncryptedSharedPreferences entries that the Flutter 10.x
 * migration orphaned, spec 14 F1) → `SecureStore`.
 *
 * Usage:
 * - `Application.onCreate` launches [ensureMigrated] in `@ApplicationScope` (IO); `MainActivity`
 *   keeps the splash screen until [gate] completes (bounded, e.g. `withTimeoutOrNull(20s)`, so a
 *   hanging Keystore call cannot keep the splash forever); widgets and workers call [MigrationGate.await].
 * - The app reset (spec 12) calls [onAppReset] **first**, then clears the native stores (F6/R8).
 * - Every start may call [cleanupIfEligible]; it refuses (`RELEASE`) within the first native release.
 *
 * State lives in `noBackupFilesDir/flutter_migration/state.json` (never in `app_prefs`), so an app
 * reset cannot trigger a re-import. Nothing here ever logs a value (spec 14 §4.10).
 */
interface FlutterMigration {

    /** Completes when the current process has finished (or skipped) the migration. */
    val gate: MigrationGate

    /**
     * Runs the migration if it has not run yet (idempotent, process-wide mutex). After an
     * `IMPORTED_WITH_ERRORS` result it retries every secret not yet imported, once per process
     * start and at most [MAX_AUTOMATIC_ATTEMPTS] times. After two attempts in a row that died
     * (process kill or native crash inside AndroidKeyStore/Tink, spec 14 R6), automatic attempts run
     * in *safe mode* (warning `SAFE_MODE`): prefs, E0 plaintext and rolling-QR secrets only, every
     * encrypted secure-storage entry stays pending until [retryNow]. The attempt runs in a process-wide scope:
     * cancelling the caller only stops its wait, never the import. Never throws (except the
     * caller's own cancellation); always completes [gate] once the attempt has finished.
     */
    suspend fun ensureMigrated(): MigrationReport

    /**
     * Manual retry from Settings → Diagnose ("Alte Daten erneut übernehmen"): like
     * [ensureMigrated] but ignores the automatic attempt limit and safe mode (always the full
     * Keystore/Tink path). Also retries secrets given up with [abandonPendingSecrets] (they are
     * never reported as pending again, whatever the outcome). No-op for `NOT_NEEDED`/`CLEANED`.
     */
    suspend fun retryNow(): MigrationReport

    /**
     * Settings → Diagnose, **after an explicit user confirmation**: gives up on every secret that
     * is still pending (e.g. the ESP master key is gone for good, warning `ESP_MASTER_KEY_MISSING`,
     * or the 10-attempt limit is reached), so the domains may create a new identity / NIP-46 session
     * / badge key. Sets state `IMPORTED` with warning `ABANDONED:<names>`; [pendingLegacySecrets]
     * is empty afterwards. The Flutter files are kept (cleanup waits) so [retryNow] can still import
     * them; a value the user created meanwhile is never overwritten (it goes to `legacy_recovered/…`).
     * No-op unless the state is `IMPORTED_WITH_ERRORS`.
     */
    suspend fun abandonPendingSecrets(): MigrationReport

    /** The persisted report (state `NOT_STARTED` if the migration never ran). */
    suspend fun currentReport(): MigrationReport

    /**
     * flutter_secure_storage key names that exist in the Flutter files but could not be imported
     * yet. A domain must treat these keys as **unreadable** (never generate a replacement), like
     * Dart `resetOnError: false`. For `IMPORTED_WITH_ERRORS` the failed names (all known keys
     * when the secure-storage or prefs file was unreadable, or the state file was corrupt and
     * the key is absent natively); for `NOT_STARTED` with Flutter files present (an attempt
     * aborted before finishing) every legacy secret name; otherwise empty.
     */
    suspend fun pendingLegacySecrets(): Set<String>

    /**
     * Deletes the Flutter legacy files, Keystore aliases and the snapshot when the migration is
     * verified (spec 14 §4.7 "Cleanup"): state `IMPORTED`, ≥ 3 native app starts, every imported
     * secret not unreadable (`badge_encryption_key` also present; secrets the domains delete in
     * normal use may be gone), the local identity (if any) signs and matches its npub, the badge
     * collection (if any) opens, and no secret was given up ([abandonPendingSecrets]). Returns
     * `NotYet("GATE")` before this process' migration finished, and `NotYet("RELEASE")` while the
     * installed `versionCode` is not greater than the one of the first native start (spec 14 §4.7:
     * not before the release after the native one). Records `CLEANED` before deleting.
     */
    suspend fun cleanupIfEligible(): CleanupOutcome

    /**
     * Part of the full app reset (spec 12 §2.5.12, F6). Call it **before** wiping `KeyValueStore`
     * / `SecureStore`: it first records `CLEANED`, then deletes every Flutter legacy file, alias and
     * the snapshot, so neither a process death halfway nor a later start can re-import the identity
     * the user just deleted. Runs detached from the caller (a cancelled ViewModel scope cannot skip
     * it). Repeats an incomplete delete when already `CLEANED`. The state file itself survives.
     */
    suspend fun onAppReset()

    companion object {
        /** Automatic retries of an `IMPORTED_WITH_ERRORS` migration (spec 14 §4.7). */
        const val MAX_AUTOMATIC_ATTEMPTS: Int = 10

        /** Native app starts required before [cleanupIfEligible] may delete the Flutter data. */
        const val MIN_STARTS_BEFORE_CLEANUP: Int = 3
    }
}

/**
 * Barrier for code that must not touch `KeyValueStore`/`SecureStore` before the Flutter data has
 * been imported (widgets, workers, anything that can start the process without an activity).
 */
interface MigrationGate {
    /** `true` once the migration of this process has finished (successfully or not). */
    val isComplete: Boolean

    /** Suspends until the migration has finished; starts it if nobody has yet. Never throws. */
    suspend fun await()
}

/**
 * Optional hook run after an import attempt wrote data (state `IMPORTED` or
 * `IMPORTED_WITH_ERRORS`), e.g. `MeetupWidget().updateAll(context)` in `:feature:widget`.
 * Runs after the migration mutex is released and the [MigrationGate] is complete, so a hook may
 * await the gate, call [FlutterMigration.ensureMigrated] or read the stores.
 * Contribute with `@Binds @IntoSet`. Failures are logged and ignored.
 */
fun interface MigrationCompletedHook {
    suspend fun onMigrationImported(report: MigrationReport)
}

/** Persisted migration state (`state.json` `state`). */
enum class MigrationState {
    /** Never ran, or the last attempt died before it finished. */
    NOT_STARTED,

    /** Fresh install: no Flutter files were found. */
    NOT_NEEDED,

    /** Every Flutter value was imported (or deliberately routed to a recovery name). */
    IMPORTED,

    /** Some secrets (see [MigrationReport.failedKeys]) could not be read or written yet. */
    IMPORTED_WITH_ERRORS,

    /** The Flutter files are gone (cleanup or app reset). */
    CLEANED,
}

/** Flutter storage era detected on the device (spec 14 §4.1). */
enum class StorageEra {
    /** Plaintext secrets in `FlutterSharedPreferences.xml`. */
    E0,

    /** flutter_secure_storage 9.2.4 EncryptedSharedPreferences (Tink keysets present). */
    E1,

    /** flutter_secure_storage 9.2.4 custom AES-CBC fallback (ESP init failed on the device). */
    E1_CBC,

    /** flutter_secure_storage 10.3.4 AES-GCM entries. */
    E2,
}

/**
 * Outcome of a migration run (or the persisted state). Contains key **names** only, never values.
 *
 * @property counts per-category counters (see the `COUNT_*` constants).
 * @property failedKeys flutter_secure_storage key names (or `<prefs>` / `<esp>` markers) that are
 *   still missing.
 * @property recovered secure-store recovery names written (`legacy_esp/…`, `legacy_backup/…`,
 *   `legacy_recovered/…`); the owning domains offer them to the user.
 * @property warnings machine-readable codes such as `IMPLAUSIBLE:nostr_npub_key`,
 *   `IDENTITY_INCONSISTENT`, `UNDECODABLE_PREF:<key>`, `EXCEPTION:<class>`, `ATTEMPT_DIED`,
 *   `SAFE_MODE`, `ESP_MASTER_KEY_MISSING`, `ABANDONED:<names>`.
 */
data class MigrationReport(
    val state: MigrationState,
    val attempts: Int,
    val eras: Set<StorageEra>,
    val counts: Map<String, Int>,
    val failedKeys: List<String>,
    val recovered: List<String>,
    val warnings: List<String>,
    val startedAt: Long?,
    val finishedAt: Long?,
) {
    /** `true` for every state in which the Flutter data no longer needs to be read. */
    val isSettled: Boolean
        get() = state == MigrationState.NOT_NEEDED || state == MigrationState.IMPORTED || state == MigrationState.CLEANED

    companion object {
        const val COUNT_PREFS_COPIED = "prefsCopied"
        const val COUNT_PREFS_KEPT_NATIVE = "prefsKeptNative"
        const val COUNT_PREFS_SKIPPED = "prefsSkipped"
        const val COUNT_PREFS_UNDECODABLE = "prefsUndecodable"
        const val COUNT_SECRETS_IMPORTED = "secretsImported"
        const val COUNT_SECRETS_RECOVERY = "secretsRecovery"
        const val COUNT_SECRETS_FAILED = "secretsFailed"
        const val COUNT_ESP_ENTRIES = "espEntries"
        const val COUNT_BADGE_BLOBS = "badgeBlobs"
        const val COUNT_BADGE_BLOBS_OPENED = "badgeBlobsOpened"
    }
}

/** Result of [FlutterMigration.cleanupIfEligible]. */
sealed interface CleanupOutcome {
    /** The Flutter data was deleted now. */
    data object Cleaned : CleanupOutcome

    /** Nothing to clean (`NOT_NEEDED` or already `CLEANED`). */
    data object NothingToDo : CleanupOutcome

    /**
     * A precondition failed; [reason] is a code such as `GATE`, `STATE`, `STARTS`, `ABANDONED`,
     * `SECRET_MISSING:<key>`, `SECRET_UNREADABLE:<key>`, `IDENTITY`, `BADGES`.
     */
    data class NotYet(val reason: String) : CleanupOutcome
}
