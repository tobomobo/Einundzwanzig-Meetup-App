package space.einundzwanzig.meetup.data.migration.internal

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.crypto.AesGcmBlob
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.crypto.Schnorr
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.secure.SecureRead
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.data.migration.CleanupOutcome
import space.einundzwanzig.meetup.data.migration.FlutterMigration
import space.einundzwanzig.meetup.data.migration.FlutterMigration.Companion.MAX_AUTOMATIC_ATTEMPTS
import space.einundzwanzig.meetup.data.migration.FlutterMigration.Companion.MIN_STARTS_BEFORE_CLEANUP
import space.einundzwanzig.meetup.data.migration.MigrationCompletedHook
import space.einundzwanzig.meetup.data.migration.MigrationGate
import space.einundzwanzig.meetup.data.migration.MigrationReport
import space.einundzwanzig.meetup.data.migration.MigrationReport.Companion.COUNT_BADGE_BLOBS
import space.einundzwanzig.meetup.data.migration.MigrationReport.Companion.COUNT_BADGE_BLOBS_OPENED
import space.einundzwanzig.meetup.data.migration.MigrationReport.Companion.COUNT_ESP_ENTRIES
import space.einundzwanzig.meetup.data.migration.MigrationReport.Companion.COUNT_PREFS_COPIED
import space.einundzwanzig.meetup.data.migration.MigrationReport.Companion.COUNT_PREFS_KEPT_NATIVE
import space.einundzwanzig.meetup.data.migration.MigrationReport.Companion.COUNT_PREFS_SKIPPED
import space.einundzwanzig.meetup.data.migration.MigrationReport.Companion.COUNT_PREFS_UNDECODABLE
import space.einundzwanzig.meetup.data.migration.MigrationReport.Companion.COUNT_SECRETS_FAILED
import space.einundzwanzig.meetup.data.migration.MigrationReport.Companion.COUNT_SECRETS_IMPORTED
import space.einundzwanzig.meetup.data.migration.MigrationReport.Companion.COUNT_SECRETS_RECOVERY
import space.einundzwanzig.meetup.data.migration.MigrationState
import space.einundzwanzig.meetup.data.migration.StorageEra
import space.einundzwanzig.meetup.data.migration.legacy.DecodedPrefs
import space.einundzwanzig.meetup.data.migration.legacy.EspEntries
import space.einundzwanzig.meetup.data.migration.legacy.FlutterPrefs
import space.einundzwanzig.meetup.data.migration.legacy.FlutterSecureStorageReader
import space.einundzwanzig.meetup.data.migration.legacy.Fss
import space.einundzwanzig.meetup.data.migration.legacy.LegacyKeystore
import space.einundzwanzig.meetup.data.migration.legacy.LegacySecret
import space.einundzwanzig.meetup.data.migration.legacy.SecureKeyInventory
import space.einundzwanzig.meetup.data.migration.legacy.decodeFlutterPrefs
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrator of spec 14 §4.7. The Flutter files stay read-only until the post-verification step
 * (removal of routed plaintext secrets, `InternalHomeWidgetPreferences`) and the cleanup / reset.
 * Logs names and counts only (§4.10).
 */
@Singleton
internal class DefaultFlutterMigration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyValueStore: KeyValueStore,
    private val secureStore: SecureStore,
    private val keystore: LegacyKeystore,
    private val logImporter: LegacyLogImporter,
    private val hooks: Set<@JvmSuppressWildcards MigrationCompletedHook>,
    private val timeSource: TimeSource,
    private val log: DiagnosticLog,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val versionCode: AppVersionCode,
) : FlutterMigration, MigrationGate {

    private val mutex = Mutex()
    private val done = CompletableDeferred<Unit>()

    /**
     * Process-lifetime scope the import runs in. Callers only await the result, so a cancelled
     * caller (Glance session, stopped Worker, ViewModel scope) can never abort an attempt halfway
     * and leave later callers with a released gate over empty or partial stores.
     */
    private val scope = CoroutineScope(SupervisorJob() + io)

    @Volatile
    private var startCounted = false

    /**
     * An automatic attempt already ran in this process. Later [ensureMigrated]/[await] callers
     * (widget, worker, a second launch path) get the persisted report instead of a second retry,
     * so `IMPORTED_WITH_ERRORS` is retried once per app start (spec 14 §4.7).
     */
    @Volatile
    private var automaticAttemptDone = false

    internal val files = LegacyFiles(context)
    internal val stateStore = MigrationStateStore(File(context.noBackupFilesDir, MigrationStateStore.DIR_NAME))
    private val reader = FlutterSecureStorageReader(files.prefsFiles, keystore)

    // ---------------------------------------------------------------- gate

    override val gate: MigrationGate get() = this

    override val isComplete: Boolean get() = done.isCompleted

    override suspend fun await() {
        if (!done.isCompleted) ensureMigrated()
    }

    // ---------------------------------------------------------------- public API

    override suspend fun ensureMigrated(): MigrationReport = run(manual = false)

    override suspend fun retryNow(): MigrationReport = run(manual = true)

    override suspend fun currentReport(): MigrationReport = withContext(io) { stateStore.read().toReport() }

    override suspend fun pendingLegacySecrets(): Set<String> = withContext(io) { pendingOf(stateStore.read()) }

    private fun pendingOf(st: PersistedState): Set<String> = when (st.state) {
        // No attempt finished (aborted before the state was written, or a corrupt state file, which
        // reads as NOT_STARTED): every secret the Flutter files may hold is still pending.
        MigrationState.NOT_STARTED ->
            if (files.anyFlutterDataPresent()) ALL_LEGACY_SECRETS else emptySet()
        MigrationState.IMPORTED_WITH_ERRORS -> {
            val imported = st.importedSecrets.toSet()
            val named = st.failedKeys.filterNot { it.startsWith("<") }.toMutableSet()
            // The ESP/secure file could not be read at all: every known key that was not imported may exist there.
            if (FAILED_ESP in st.failedKeys || FAILED_FSS in st.failedKeys) named += SecureKeyInventory.KEYS - imported
            // The Flutter prefs could not be read: the E0 plaintext and rolling-QR secrets may exist there.
            if (FAILED_PREFS in st.failedKeys) named += PREFS_ROUTED_SECRETS - imported
            named - st.abandoned.toSet()
        }
        // IMPORTED also after abandonPendingSecrets(): the user gave up on the rest.
        MigrationState.NOT_NEEDED, MigrationState.IMPORTED, MigrationState.CLEANED -> emptySet()
    }

    override suspend fun abandonPendingSecrets(): MigrationReport = detachedLocked {
        val st = stateStore.read()
        if (st.state != MigrationState.IMPORTED_WITH_ERRORS) return@detachedLocked st.toReport()
        val pending = pendingOf(st).sorted()
        val next = st.copy(
            state = MigrationState.IMPORTED,
            abandoned = (st.abandoned + pending).distinct(),
            warnings = (st.warnings + "ABANDONED:${pending.joinToString(",")}").distinct(),
        )
        stateStore.write(next)
        log.warn(TAG, "Nicht übernommene Altdaten aufgegeben: ${pending.joinToString()}")
        next.toReport()
    }

    override suspend fun cleanupIfEligible(): CleanupOutcome {
        // Never before this process' migration finished. Also required once the stores await the gate:
        // the cleanup reads them while holding the mutex the gate's attempt needs (deadlock otherwise).
        if (!done.isCompleted) return CleanupOutcome.NotYet("GATE")
        return detachedLocked { cleanupLocked() }
    }

    override suspend fun onAppReset() = detachedLocked {
        val st = stateStore.read()
        // 1. CLEANED first (F6/R8): if the process dies during the deletes below, or right after them,
        //    no later start can import the Flutter data again, whatever is still on disk.
        if (st.state != MigrationState.CLEANED) {
            runCatching { stateStore.write(st.copy(state = MigrationState.CLEANED, cleanedAt = timeSource.nowMillis())) }
                .onFailure { log.warn(TAG, "App-Reset: Status nicht geschrieben (${it.javaClass.simpleName})") }
        }
        // 2. Delete (also repeats an earlier incomplete delete when already CLEANED).
        deleteLegacyData("RESET")
        log.info(TAG, "App-Reset: Flutter-Altdaten entfernt")
    }

    /**
     * Runs [block] under the mutex in the process-wide [scope]: a cancelled caller (ViewModel scope)
     * only stops waiting; the reset/cleanup itself is never skipped or cut off halfway.
     */
    private suspend fun <T> detachedLocked(block: suspend () -> T): T =
        scope.async { mutex.withLock { block() } }.await()

    /** Deletes the Flutter files, aliases and snapshot; records `<prefix>_*_INCOMPLETE` warnings. `true` if complete. */
    private fun deleteLegacyData(prefix: String): Boolean {
        val filesOk = runCatching { files.deleteAllLegacyFiles() }.getOrDefault(false)
        val aliasesOk = runCatching { keystore.deleteLegacyAliases() }.getOrDefault(false)
        if (!filesOk || !aliasesOk) {
            runCatching {
                val st = stateStore.read()
                val warnings = st.warnings + listOfNotNull(
                    "${prefix}_DELETE_INCOMPLETE".takeIf { !filesOk },
                    "${prefix}_ALIAS_DELETE_INCOMPLETE".takeIf { !aliasesOk },
                )
                stateStore.write(st.copy(warnings = warnings.distinct()))
            }
        }
        return filesOk && aliasesOk
    }

    // ---------------------------------------------------------------- run

    /** Result of one locked run: the report and whether an import attempt wrote data (→ hooks). */
    private class Outcome(val report: MigrationReport, val attempted: Boolean)

    /**
     * Runs the migration detached from the caller in [scope]; the caller only awaits the result,
     * so cancelling it never aborts an attempt (the gate is released only after a finished run).
     */
    private suspend fun run(manual: Boolean): MigrationReport = scope.async { runDetached(manual) }.await()

    private suspend fun runDetached(manual: Boolean): MigrationReport {
        val outcome = try {
            mutex.withLock { runLocked(manual) }
        } catch (e: CancellationException) {
            throw e // only if the process-wide scope itself is cancelled; the gate stays open
        } catch (e: Throwable) {
            log.warn(TAG, "Migration abgebrochen: ${e.javaClass.simpleName}")
            Outcome(runCatching { stateStore.read().toReport() }.getOrElse { PersistedState().toReport() }, attempted = false)
        }
        // Release the gate also on failure (the app must start). Outside the mutex, before the hooks:
        // a hook may await the gate, call ensureMigrated() or read the (gated) stores.
        done.complete(Unit)
        if (outcome.attempted) {
            for (hook in hooks) {
                try {
                    hook.onMigrationImported(outcome.report)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.warn(TAG, "Hook fehlgeschlagen: ${e.javaClass.simpleName}")
                }
            }
        }
        return outcome.report
    }

    private suspend fun runLocked(manual: Boolean): Outcome {
        var st = stateStore.read()
        if (st.corrupt) st = replaceCorruptState(st)
        if (!startCounted) {
            startCounted = true
            st = st.copy(
                nativeStarts = st.nativeStarts + 1,
                firstNativeVersionCode = st.firstNativeVersionCode ?: currentVersionCode(),
            )
            runCatching { stateStore.write(st) }
        }
        // An attempt that started but never finished died (process kill, native crash in
        // AndroidKeyStore/Tink, or a hang the user ended by killing the app). After
        // SAFE_MODE_AFTER_DEATHS such attempts in a row, automatic attempts skip every Keystore/Tink
        // call (spec 14 R6); only retryNow() runs the full path again.
        if (st.attempts > 0 && st.startedAt != null && st.finishedAt == null &&
            (st.state == MigrationState.NOT_STARTED || st.state == MigrationState.IMPORTED_WITH_ERRORS)
        ) {
            val died = st.diedAttempts + 1
            st = st.copy(
                diedAttempts = died,
                safeMode = st.safeMode || died >= SAFE_MODE_AFTER_DEATHS,
                finishedAt = timeSource.nowMillis(), // counted once
                warnings = (st.warnings + "ATTEMPT_DIED").distinct(),
            )
            runCatching { stateStore.write(st) }
            log.warn(TAG, "Vorheriger Migrationsversuch abgebrochen ($died in Folge)")
        }
        if (!manual && automaticAttemptDone) return Outcome(st.toReport(), attempted = false)
        if (!manual) automaticAttemptDone = true
        try {
            return when (st.state) {
                MigrationState.IMPORTED ->
                    // A manual retry after abandonPendingSecrets() tries the given-up secrets once more.
                    if (manual && st.abandoned.any { it !in st.importedSecrets }) {
                        Outcome(attempt(st, retry = true, safe = false), attempted = true)
                    } else {
                        Outcome(st.toReport(), attempted = false)
                    }
                MigrationState.NOT_NEEDED, MigrationState.CLEANED -> Outcome(st.toReport(), attempted = false)
                MigrationState.IMPORTED_WITH_ERRORS ->
                    if (!manual && st.attempts >= MAX_AUTOMATIC_ATTEMPTS) {
                        Outcome(st.toReport(), attempted = false)
                    } else {
                        Outcome(attempt(st, retry = true, safe = !manual && st.safeMode), attempted = true)
                    }
                MigrationState.NOT_STARTED -> when {
                    !files.anyFlutterDataPresent() -> {
                        val now = timeSource.nowMillis()
                        val r = st.copy(state = MigrationState.NOT_NEEDED, startedAt = now, finishedAt = now).also { stateStore.write(it) }
                        Outcome(r.toReport(), attempted = false)
                    }
                    !manual && st.attempts >= MAX_AUTOMATIC_ATTEMPTS -> {
                        val r = st.copy(
                            state = MigrationState.IMPORTED_WITH_ERRORS,
                            warnings = (st.warnings + "TOO_MANY_ATTEMPTS").distinct(),
                            failedKeys = (st.failedKeys + presentSourceMarkers()).distinct(),
                        ).also { stateStore.write(it) }
                        Outcome(r.toReport(), attempted = false)
                    }
                    else -> Outcome(attempt(st, retry = false, safe = !manual && st.safeMode), attempted = true)
                }
            }
        } catch (e: CancellationException) {
            // Defensive (the run is detached): an aborted attempt must not count as this start's attempt.
            if (!manual) automaticAttemptDone = false
            throw e
        }
    }

    /**
     * A `state.json` that cannot be parsed has lost which secrets were imported. Re-importing
     * blindly would bring back what the user deleted natively since (logout, an app reset whose
     * delete was incomplete, F6/R8), because absent native keys are filled. So when the Flutter
     * files still exist **and** the native stores hold data, the migration evidently ran before:
     * record `IMPORTED_WITH_ERRORS` with the attempt limit reached (no automatic re-import; only
     * [retryNow] or [abandonPendingSecrets] after a user decision). Secure keys present natively count
     * as imported; the others stay pending. Otherwise (nothing native yet) it is a normal
     * `NOT_STARTED`.
     */
    private suspend fun replaceCorruptState(corrupt: PersistedState): PersistedState {
        val nativeSecure = secureStore.readAllChecked().getOrNull()
        val nativePrefs = runCatching { keyValueStore.getAll() }.getOrNull()
        // Unreadable stores count as holding data (conservative).
        val nativeData = (nativeSecure?.isNotEmpty() ?: true) || (nativePrefs?.isNotEmpty() ?: true)
        val now = timeSource.nowMillis()
        val next = if (files.anyFlutterDataPresent() && nativeData) {
            PersistedState(
                state = MigrationState.IMPORTED_WITH_ERRORS,
                attempts = MAX_AUTOMATIC_ATTEMPTS,
                startedAt = now,
                finishedAt = now,
                failedKeys = presentSourceMarkers(),
                warnings = corrupt.warnings,
                importedSecrets = ALL_LEGACY_SECRETS.filter { nativeSecure?.containsKey(it) == true },
                prefsImported = nativePrefs?.isNotEmpty() ?: true,
                logImported = true, // unknown: never risk importing the Flutter log twice
            )
        } else {
            PersistedState(warnings = corrupt.warnings)
        }
        runCatching { stateStore.write(next) }
            .onFailure { log.warn(TAG, "Status nach Beschädigung nicht geschrieben (${it.javaClass.simpleName})") }
        log.warn(TAG, "Migrationsstatus beschädigt, ersetzt durch ${next.state}")
        return next
    }

    private fun currentVersionCode(): Long? = runCatching { versionCode.current() }.getOrNull()

    /**
     * Steps 3–9. On a [retry] every secret that is not yet settled is (re)written: primary keys
     * not in `importedSecrets`, recovery names not in `recovered`. Scoping by what is settled (not
     * by the failed names) matters when a source became readable only now, e.g. the Flutter prefs
     * (E0 plaintext keys, rqr routes, the npubs that make ESP identity keys primary). Already
     * imported keys are skipped so a native change after the import (logout) is never undone.
     *
     * A [safe] attempt (after repeated dead attempts, spec 14 R6) makes no Keystore/Tink call: it
     * imports the prefs, the E0 plaintext and rolling-QR secrets and leaves every encrypted
     * flutter_secure_storage entry pending.
     */
    private suspend fun attempt(previous: PersistedState, retry: Boolean, safe: Boolean): MigrationReport {
        val startedAt = timeSource.nowMillis()
        var st = previous.copy(attempts = previous.attempts + 1, startedAt = startedAt, finishedAt = null)
        stateStore.write(st) // an attempt that dies (process kill) is counted
        val warnings = mutableListOf<String>()
        val failed = LinkedHashSet<String>()
        val counts = LinkedHashMap<String, Int>()
        // Progress survives an exception later in the attempt (the catch path persists it).
        val imported = LinkedHashSet(previous.importedSecrets)
        val recovered = LinkedHashSet(previous.recovered)
        var prefsImported = previous.prefsImported
        var logImported = previous.logImported
        try {
            // 3. Snapshot before any ESP / Tink call; repeated until one ran to the end.
            if (!files.snapshotComplete) runCatching { files.snapshotSecureStorage() }.onFailure { warnings += "SNAPSHOT_FAILED" }

            // 4. Prefs.
            val prefs: DecodedPrefs = try {
                decodeFlutterPrefs(files.prefsFiles.read(FlutterPrefs.FILE))
            } catch (e: Exception) {
                warnings += "PREFS_UNREADABLE:${e.javaClass.simpleName}"
                failed += FAILED_PREFS
                DecodedPrefs(emptyMap(), emptyList())
            }
            val plan = PrefsRouting.plan(prefs)
            warnings += plan.warnings

            // 5. Secrets.
            val data: Map<String, Any> = try {
                reader.dataEntries()
            } catch (e: Exception) {
                warnings += "FSS_UNREADABLE:${e.javaClass.simpleName}"
                failed += FAILED_FSS
                emptyMap()
            }
            if (safe) warnings += "SAFE_MODE"
            val keys = if (safe) emptyList() else reader.candidateKeys(reader.storageAlgorithmMarker(data))
            val custom = reader.readCustomCipherEntries(data, keys) // no keys: every entry reads as failed
            val esp: EspEntries? = when {
                !safe -> runCatching { reader.readEspEntries(data) }.getOrElse {
                    warnings += "ESP_EXCEPTION:${it.javaClass.simpleName}"
                    EspEntries(emptyMap(), emptyList(), readable = false, encryptedEntryCount = 1)
                }
                Fss.ESP_KEY_KEYSET in data || Fss.ESP_VALUE_KEYSET in data -> {
                    failed += FAILED_ESP
                    null
                }
                else -> null
            }
            if (esp != null) {
                counts[COUNT_ESP_ENTRIES] = esp.encryptedEntryCount
                if (!esp.readable && esp.encryptedEntryCount > 0) {
                    failed += FAILED_ESP
                    warnings += "ESP_UNREADABLE"
                    // Permanent (alias gone): no retry can help; the user decides via abandonPendingSecrets().
                    if (esp.masterKeyMissing) warnings += "ESP_MASTER_KEY_MISSING"
                }
            }

            // 6. Resolve (§4.8).
            val undecryptable = custom.failed.map { it.removeSuffix(Fss.BACKUP) }.toSet()
            val resolved = SecretResolver.resolve(
                SecretSources(
                    main = custom.main,
                    backup = custom.backup,
                    esp = esp?.values.orEmpty(),
                    undecryptable = undecryptable,
                    plaintext = plan.plaintextSecrets,
                    referencedNpubs = plan.referencedNpubs,
                ),
            )
            warnings += resolved.warnings
            val unresolved = (undecryptable + esp?.failed.orEmpty())
                .filter { it !in resolved.primary }
                .distinct()
            failed += unresolved

            // 7. Diagnostic badge probe.
            val allSecretValues = resolved.primary.values.map { it.value } + resolved.recovery.values
            val probe = BadgeProbe.probe(
                BadgeProbe.blobs(
                    prefStrings = BadgeProbe.PREF_BLOB_KEYS.associateWith { (plan.copy[it] as? PrefValue.StringValue)?.value },
                    secureCopy = resolved.primary["badges_encrypted"]?.value,
                    formerJson = resolved.primary["badges_former"]?.value ?: (plan.copy["badges_former"] as? PrefValue.StringValue)?.value,
                ),
                BadgeProbe.candidateKeys(allSecretValues),
            )
            counts[COUNT_BADGE_BLOBS] = probe.blobs
            counts[COUNT_BADGE_BLOBS_OPENED] = probe.opened
            probe.unopened.forEach { warnings += "BADGE_BLOB_UNOPENED:$it" }

            // A retry skips only what is already settled (writeSecret never overwrites a differing native value).
            val settledPrimary = if (retry) previous.importedSecrets.toSet() else emptySet()
            val settledRecovery = if (retry) previous.recovered.toSet() else emptySet()

            // 8. Write: secrets first.
            var secretsImported = 0
            for ((key, secret) in resolved.primary) {
                if (key in settledPrimary) continue
                when (val r = writeSecret(key, secret.value)) {
                    WriteResult.Imported -> { imported += key; secretsImported++ }
                    is WriteResult.Recovered -> { recovered += r.name; failed -= key }
                    WriteResult.Failed -> failed += key
                }
            }
            for ((name, value) in resolved.recovery) {
                val key = RecoveryNames.keyOf(name)
                if (name in settledRecovery) continue
                when (val written = writeRecovery(name, value)) {
                    null -> { failed += key; warnings += "RECOVERY_WRITE_FAILED:$name" }
                    else -> recovered += written
                }
            }
            // A failed name that is now settled by a recovery copy counts as handled.
            failed.removeAll { it in imported }

            // Prefs in one transaction (first attempt, or a retry after the transaction failed).
            val e0Failed = PrefsRouting.E0_IDENTITY_KEYS.any { it in plan.plaintextSecrets && it !in imported }
            if (prefsImported && !e0Failed && PrefsRouting.SECURE_MIGRATION_DONE in plan.copy) {
                // Retry that finally routed the E0 keys: set the flag the first attempt had to hold back.
                keyValueStore.edit {
                    if (!contains(PrefsRouting.SECURE_MIGRATION_DONE)) set(PrefsRouting.SECURE_MIGRATION_DONE, PrefValue.BoolValue(true))
                }
            }
            if (!prefsImported && FAILED_PREFS !in failed) {
                val copy = LinkedHashMap(plan.copy)
                if (e0Failed) copy.remove(PrefsRouting.SECURE_MIGRATION_DONE)
                plan.profilePicturePath?.let { path ->
                    files.copyProfilePicture(path)?.let { copy[PrefsRouting.LOCAL_PROFILE_PICTURE] = PrefValue.StringValue(it) }
                        ?: warnings.add("PROFILE_PICTURE_MISSING")
                }
                var kept = 0
                val ok = keyValueStore.edit {
                    for ((k, v) in copy) {
                        if (contains(k)) kept++ else set(k, v)
                    }
                }
                if (ok) {
                    prefsImported = true
                    counts[COUNT_PREFS_COPIED] = copy.size - kept
                    counts[COUNT_PREFS_KEPT_NATIVE] = kept
                } else {
                    failed += FAILED_PREFS
                }
            }
            counts[COUNT_PREFS_SKIPPED] = plan.skipped.size
            counts[COUNT_PREFS_UNDECODABLE] = prefs.undecodable.size

            if (!logImported && (plan.diagnosticLog != null || plan.diagnosticVerbose != null)) {
                // Imported secrets first: 64-hex keys, seeds and tokens have no redaction pattern.
                val secretValues = resolved.primary.filterKeys { it in REDACTED_SECRET_KEYS }.values.map { it.value } +
                    resolved.recovery.filterKeys { RecoveryNames.keyOf(it) in REDACTED_SECRET_KEYS }.values
                runCatching {
                    logImporter.registerSecrets(secretValues.filter { it.isNotBlank() }.distinct())
                    logImporter.import(plan.diagnosticLog, plan.diagnosticVerbose)
                }
                    .onSuccess { logImported = true }
                    .onFailure { warnings += "LOG_IMPORT_FAILED:${it.javaClass.simpleName}" }
                if (logImported) {
                    // The log import is not idempotent: record it (and the progress so far) at once, so
                    // neither a later exception nor a process death can make a retry import it twice.
                    runCatching {
                        stateStore.write(
                            st.copy(
                                importedSecrets = imported.toList(),
                                recovered = recovered.toList(),
                                prefsImported = prefsImported,
                                logImported = true,
                            ),
                        )
                    }
                }
            }

            // 9. Post-verification removals from the Flutter prefs (only verified routed plaintext).
            val removable = plan.plaintextSourceKeys.filter { (target, _) -> target in imported }.values
            if (removable.isNotEmpty()) {
                val ok = runCatching {
                    context.getSharedPreferences(FlutterPrefs.FILE, Context.MODE_PRIVATE).edit().apply {
                        removable.forEach { remove(FlutterPrefs.KEY_PREFIX + it) }
                    }.commit()
                }.getOrDefault(false)
                if (!ok) warnings += "PLAINTEXT_REMOVE_FAILED"
            }
            runCatching { files.deleteInternalHomeWidgetPrefs() }

            counts[COUNT_SECRETS_IMPORTED] = (previous.counts[COUNT_SECRETS_IMPORTED] ?: 0) + secretsImported
            counts[COUNT_SECRETS_RECOVERY] = recovered.size
            counts[COUNT_SECRETS_FAILED] = failed.count { !it.startsWith("<") }

            // After abandonPendingSecrets() a manual retry never brings the pending lock back.
            val abandonedMode = previous.state == MigrationState.IMPORTED && previous.abandoned.isNotEmpty()
            st = st.copy(
                state = if (failed.isEmpty() || abandonedMode) MigrationState.IMPORTED else MigrationState.IMPORTED_WITH_ERRORS,
                finishedAt = timeSource.nowMillis(),
                diedAttempts = 0,
                // A full attempt that finished shows the Keystore/Tink path no longer kills the process.
                safeMode = previous.safeMode && safe,
                abandoned = previous.abandoned.filterNot { it in imported },
                counts = previous.counts + counts,
                failedKeys = failed.toList(),
                recovered = recovered.toList(),
                warnings = (previous.warnings + warnings).distinct(),
                eras = (previous.eras + detectEras(plan, custom.main, keys.map { it.wrappedName }, esp)).distinct(),
                importedSecrets = imported.toList(),
                prefsImported = prefsImported,
                logImported = logImported,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val abandonedMode = previous.state == MigrationState.IMPORTED && previous.abandoned.isNotEmpty()
            st = st.copy(
                state = if (abandonedMode) MigrationState.IMPORTED else MigrationState.IMPORTED_WITH_ERRORS,
                finishedAt = timeSource.nowMillis(),
                diedAttempts = 0,
                // Only markers for sources that exist: an E0-only device has no secure-storage file.
                failedKeys = (previous.failedKeys + failed + presentSourceMarkers()).filterNot { it in imported }.distinct(),
                warnings = (previous.warnings + warnings + "EXCEPTION:${e.javaClass.simpleName}").distinct(),
                // What this attempt already did stays recorded: a retry never repeats a log import
                // (not idempotent) and never fills a key the user deleted after it was imported.
                abandoned = previous.abandoned.filterNot { it in imported },
                importedSecrets = imported.toList(),
                recovered = recovered.toList(),
                prefsImported = prefsImported,
                logImported = logImported,
            )
        }
        stateStore.write(st)
        val report = st.toReport()
        logSummary(report, retry)
        return report // hooks run in runDetached, after the mutex and the gate are released
    }

    private sealed interface WriteResult {
        data object Imported : WriteResult
        data class Recovered(val name: String) : WriteResult
        data object Failed : WriteResult
    }

    /** Step 8: never overwrite a native value; verify every write by reading it back. */
    private suspend fun writeSecret(key: String, value: String): WriteResult =
        when (val current = secureStore.readChecked(key)) {
            is SecureRead.Present ->
                if (current.value == value) {
                    WriteResult.Imported
                } else {
                    writeRecovery(SecretResolver.recoveryName(RecoveryNames.RECOVERED, key), value)
                        ?.let { WriteResult.Recovered(it) } ?: WriteResult.Failed
                }
            SecureRead.Absent -> if (writeVerified(key, value)) WriteResult.Imported else WriteResult.Failed
            is SecureRead.Unreadable -> WriteResult.Failed // never overwrite an unreadable entry
        }

    /**
     * Parks [value] under the recovery [name], or under the first overflow slot `<name>#2`,
     * `#3`, … when [name] already holds a different value: a recovery entry is never overwritten
     * (e.g. a native-conflict copy and a third distinct `_BACKUP` value both target
     * `legacy_recovered/<key>`). A slot that already holds [value] is reused (idempotent retries).
     * Returns the name written, or `null` if it could not be stored.
     */
    private suspend fun writeRecovery(name: String, value: String): String? {
        var free: String? = null
        for (i in 1..RecoveryNames.MAX_OVERFLOW_SLOTS) {
            val slot = if (i == 1) name else "$name${RecoveryNames.OVERFLOW_SEPARATOR}$i"
            when (val current = secureStore.readChecked(slot)) {
                is SecureRead.Present -> if (current.value == value) return slot
                SecureRead.Absent -> if (free == null) free = slot
                is SecureRead.Unreadable -> Unit // never overwrite an unreadable entry
            }
            // The plain name is free: no overflow slot can hold the value (they are only used after it).
            if (i == 1 && free != null) break
        }
        return free?.takeIf { writeVerified(it, value) }
    }

    private suspend fun writeVerified(key: String, value: String): Boolean =
        secureStore.write(key, value) && (secureStore.readChecked(key) as? SecureRead.Present)?.value == value

    private fun detectEras(
        plan: PrefsPlan,
        main: Map<String, LegacySecret>,
        wrappedNames: List<String>,
        esp: EspEntries?,
    ): List<StorageEra> = buildList {
        if (PrefsRouting.E0_IDENTITY_KEYS.any { it in plan.plaintextSecrets }) add(StorageEra.E0)
        if (esp != null) add(StorageEra.E1)
        if (main.values.any { it.source == LegacySecret.Source.CBC }) add(StorageEra.E1_CBC)
        if (main.values.any { it.source == LegacySecret.Source.GCM } || Fss.WRAPPED_V10_GCM in wrappedNames) add(StorageEra.E2)
    }

    private fun logSummary(report: MigrationReport, retry: Boolean) {
        val eras = report.eras.joinToString("+").ifEmpty { "–" }
        val entries = (report.counts[COUNT_PREFS_COPIED] ?: 0) + (report.counts[COUNT_SECRETS_IMPORTED] ?: 0)
        val prefix = if (retry) "Migration (Wiederholung ${report.attempts})" else "Migration"
        val line = "$prefix: Flutter-Daten übernommen (Ära $eras, $entries Einträge, ${report.failedKeys.size} Fehler)"
        if (report.failedKeys.isEmpty()) {
            log.info(TAG, line)
        } else {
            log.warn(TAG, "$line — offen: ${report.failedKeys.joinToString()}")
        }
        if (report.recovered.isNotEmpty()) log.info(TAG, "Wiederherstellbare Altwerte: ${report.recovered.joinToString()}")
    }

    /** `<fss>` / `<prefs>` for the Flutter sources that exist on disk (exception and attempt-limit paths). */
    private fun presentSourceMarkers(): List<String> = listOfNotNull(
        FAILED_FSS.takeIf { files.prefsFiles.exists(Fss.DATA) || files.prefsFiles.exists(Fss.KEYS) },
        FAILED_PREFS.takeIf { files.prefsFiles.exists(FlutterPrefs.FILE) },
    )

    // ---------------------------------------------------------------- cleanup

    private suspend fun cleanupLocked(): CleanupOutcome {
        val st = stateStore.read()
        when (st.state) {
            MigrationState.NOT_NEEDED -> return CleanupOutcome.NothingToDo
            MigrationState.CLEANED -> {
                // Finish a delete that an earlier cleanup/reset could not complete (state is CLEANED: never re-imported).
                if (files.anyFlutterDataPresent() || files.snapshotDir.exists()) deleteLegacyData("CLEANUP")
                return CleanupOutcome.NothingToDo
            }
            MigrationState.IMPORTED -> Unit
            else -> return CleanupOutcome.NotYet("STATE")
        }
        if (st.nativeStarts < MIN_STARTS_BEFORE_CLEANUP) return CleanupOutcome.NotYet("STARTS")
        // Given-up secrets keep their Flutter copy so retryNow() can still import them.
        if (st.abandoned.any { it !in st.importedSecrets }) return CleanupOutcome.NotYet("ABANDONED")
        // Not before the release after the first native one: the Flutter files are the only fallback
        // should a device-specific native-store problem show up in that release.
        val first = st.firstNativeVersionCode
        val current = currentVersionCode()
        if (first == null || current == null || current <= first) return CleanupOutcome.NotYet("RELEASE")
        // Domains delete most imported secrets in normal use (rolling-QR session end, logout, one-shot
        // markers, pending keys), so `Absent` only blocks for durable ones. `Unreadable` always blocks:
        // the native store cannot be trusted with the only remaining copy yet. The identity and the
        // badge collection are verified functionally below.
        for (key in st.importedSecrets) {
            when (secureStore.readChecked(key)) {
                is SecureRead.Present -> Unit
                SecureRead.Absent -> if (key in CLEANUP_REQUIRED_SECRETS) return CleanupOutcome.NotYet("SECRET_MISSING:$key")
                is SecureRead.Unreadable -> return CleanupOutcome.NotYet("SECRET_UNREADABLE:$key")
            }
        }
        if (!identitySigns()) return CleanupOutcome.NotYet("IDENTITY")
        if (!badgesOpen()) return CleanupOutcome.NotYet("BADGES")

        // CLEANED first: a process death during the deletes can never lead to a re-import.
        stateStore.write(st.copy(state = MigrationState.CLEANED, cleanedAt = timeSource.nowMillis()))
        val complete = deleteLegacyData("CLEANUP")
        log.info(TAG, "Flutter-Altdaten entfernt (vollstaendig: $complete)")
        return CleanupOutcome.Cleaned
    }

    /** The local identity (if any) signs, verifies and matches its stored npub. */
    private suspend fun identitySigns(): Boolean {
        val priv = (secureStore.readChecked("nostr_priv_hex") as? SecureRead.Present)?.value?.trim()?.lowercase()
        val nsec = (secureStore.readChecked("nostr_nsec_key") as? SecureRead.Present)?.value?.trim()
        val npub = (secureStore.readChecked("nostr_npub_key") as? SecureRead.Present)?.value?.trim()
        if (priv == null && nsec == null) return true // no local identity (external signer or none)
        return runCatching {
            val pair = if (priv != null) KeyPair.fromPrivateKeyHex(priv) else KeyPair.fromNsec(nsec!!)
            if (nsec != null && Nip19.decodeNsec(nsec).lowercase() != pair.privateKeyHex) return false
            if (npub != null && npub != pair.npub) return false
            val msg = Sha256.digest("einundzwanzig-migration-check".toByteArray())
            Schnorr.verify(pair.sign(msg), msg, pair.publicKey)
        }.getOrDefault(false)
    }

    /** The primary badge collection (if any) opens with a stored 64-hex secret. */
    private suspend fun badgesOpen(): Boolean {
        val blob = keyValueStore.getString("badges_encrypted")?.takeIf { it.isNotEmpty() } ?: return true
        val keys = BadgeProbe.candidateKeys(secureStore.readAll().values)
        return keys.any { AesGcmBlob.openOrNull(blob, it) != null }
    }

    private companion object {
        const val TAG = "Migration"
        const val FAILED_PREFS = "<prefs>"
        const val FAILED_ESP = "<esp>"
        const val FAILED_FSS = "<fss>"

        /** Consecutive dead attempts after which automatic attempts run in safe mode (R6). */
        const val SAFE_MODE_AFTER_DEATHS = 2

        /** Imported secrets that no domain deletes in normal use; cleanup requires them to be `Present`. */
        val CLEANUP_REQUIRED_SECRETS: Set<String> = setOf("badge_encryption_key")

        /** Secure names the Flutter prefs file can feed (E0 plaintext identity, rolling-QR routes). */
        val PREFS_ROUTED_SECRETS: Set<String> = (PrefsRouting.E0_IDENTITY_KEYS + PrefsRouting.RQR_ROUTES.values).toSet()

        /** Secret values registered with the log redactor before the Flutter log is imported. */
        val REDACTED_SECRET_KEYS: Set<String> = setOf(
            "nostr_nsec_key", "nostr_priv_hex", "nip46_client_sk",
            "easyauth_password_ncryptsec", "easyauth_passkey_ncryptsec",
            "portal_api_token", "rqr_session_seed_secure", "rqr_session_base_payload_secure",
            "badge_encryption_key", "badge_encryption_key_suspect", "badge_pending_key", "badge_pending_key_suspect",
        )

        /** Every secure name a not-yet-imported Flutter install may hold. */
        val ALL_LEGACY_SECRETS: Set<String> = SecureKeyInventory.KEYS.toSet() + PREFS_ROUTED_SECRETS
    }
}
