package space.einundzwanzig.meetup.data.migration

/**
 * One-time import of the Flutter app's data (same `applicationId`) into the native stores
 * (spec 14 §4): `FlutterSharedPreferences.xml` → `KeyValueStore`, flutter_secure_storage (all
 * storage eras E0/E1/E2, including the EncryptedSharedPreferences entries that the Flutter 10.x
 * migration orphaned, spec 14 F1) → `SecureStore`.
 *
 * Usage:
 * - `Application.onCreate` launches [ensureMigrated] in `@ApplicationScope` (IO); `MainActivity`
 *   keeps the splash screen until [gate] completes; widgets and workers call [MigrationGate.await].
 * - The app reset (spec 12) calls [onAppReset] **after** clearing the native stores (F6).
 * - A later release (not the first native one) calls [cleanupIfEligible] once per start.
 *
 * State lives in `noBackupFilesDir/flutter_migration/state.json` (never in `app_prefs`), so an app
 * reset cannot trigger a re-import. Nothing here ever logs a value (spec 14 §4.10).
 */
interface FlutterMigration {

    /** Completes when the current process has finished (or skipped) the migration. */
    val gate: MigrationGate

    /**
     * Runs the migration if it has not run yet (idempotent, process-wide mutex). Retries only the
     * failed secrets after an `IMPORTED_WITH_ERRORS` result, at most [MAX_AUTOMATIC_ATTEMPTS]
     * times. Never throws (except cancellation); always completes [gate].
     */
    suspend fun ensureMigrated(): MigrationReport

    /**
     * Manual retry from Settings → Diagnose ("Alte Daten erneut übernehmen"): like
     * [ensureMigrated] but ignores the automatic attempt limit. No-op for `NOT_NEEDED`/`CLEANED`.
     */
    suspend fun retryNow(): MigrationReport

    /** The persisted report (state `NOT_STARTED` if the migration never ran). */
    suspend fun currentReport(): MigrationReport

    /**
     * flutter_secure_storage key names that exist in the Flutter files but could not be imported
     * yet. A domain must treat these keys as **unreadable** (never generate a replacement), like
     * Dart `resetOnError: false`. Empty unless the state is `IMPORTED_WITH_ERRORS`.
     */
    suspend fun pendingLegacySecrets(): Set<String>

    /**
     * Deletes the Flutter legacy files, Keystore aliases and the snapshot when the migration is
     * verified (spec 14 §4.7 "Cleanup"): state `IMPORTED`, ≥ 3 native app starts, every imported
     * secret still readable, the local identity (if any) signs and matches its npub, and the
     * badge collection (if any) opens. Never call it in the first native release.
     */
    suspend fun cleanupIfEligible(): CleanupOutcome

    /**
     * Part of the full app reset (spec 12 §2.5.12, F6): unless already `CLEANED`, deletes every
     * Flutter legacy file, alias and the snapshot and records `CLEANED`, so a later start can never
     * re-import the identity the user just deleted. The state file itself survives.
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
 *   `IDENTITY_INCONSISTENT`, `UNDECODABLE_PREF:<key>`, `EXCEPTION:<class>`.
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

    /** A precondition failed; [reason] is a code such as `STATE`, `STARTS`, `SECRET_MISSING:<key>`, `IDENTITY`, `BADGES`. */
    data class NotYet(val reason: String) : CleanupOutcome
}
