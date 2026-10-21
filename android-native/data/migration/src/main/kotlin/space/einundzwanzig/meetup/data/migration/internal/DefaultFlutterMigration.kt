package space.einundzwanzig.meetup.data.migration.internal

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
import space.einundzwanzig.meetup.data.identity.keys.RecoverySource
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
) : FlutterMigration, MigrationGate {

    private val mutex = Mutex()
    private val done = CompletableDeferred<Unit>()

    @Volatile
    private var startCounted = false

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

    override suspend fun pendingLegacySecrets(): Set<String> = withContext(io) {
        val st = stateStore.read()
        if (st.state != MigrationState.IMPORTED_WITH_ERRORS) return@withContext emptySet()
        val named = st.failedKeys.filterNot { it.startsWith("<") }.toMutableSet()
        // The ESP/secure file could not be read at all: every known key that was not imported may exist there.
        if (FAILED_ESP in st.failedKeys || FAILED_FSS in st.failedKeys) named += SecureKeyInventory.KEYS - st.importedSecrets.toSet()
        named
    }

    override suspend fun cleanupIfEligible(): CleanupOutcome = mutex.withLock {
        withContext(io) { cleanupLocked() }
    }

    override suspend fun onAppReset() = mutex.withLock {
        withContext(io) {
            val st = stateStore.read()
            if (st.state == MigrationState.CLEANED) return@withContext
            val filesOk = files.deleteAllLegacyFiles()
            val aliasesOk = keystore.deleteLegacyAliases()
            val warnings = st.warnings + listOfNotNull(
                "RESET_DELETE_INCOMPLETE".takeIf { !filesOk },
                "RESET_ALIAS_DELETE_INCOMPLETE".takeIf { !aliasesOk },
            )
            // CLEANED even if a delete failed: a reset must never be undone by a later import (F6).
            stateStore.write(st.copy(state = MigrationState.CLEANED, cleanedAt = timeSource.nowMillis(), warnings = warnings))
            log.info(TAG, "App-Reset: Flutter-Altdaten entfernt (vollstaendig: ${filesOk && aliasesOk})")
        }
    }

    // ---------------------------------------------------------------- run

    private suspend fun run(manual: Boolean): MigrationReport {
        try {
            return mutex.withLock { withContext(io) { runLocked(manual) } }
        } catch (e: CancellationException) {
            throw e // gate stays open; the next await() runs the migration again
        } catch (e: Throwable) {
            log.warn(TAG, "Migration abgebrochen: ${e.javaClass.simpleName}")
            return runCatching { stateStore.read().toReport() }.getOrElse { PersistedState().toReport() }
        } finally {
            // Release the gate also on failure (the app must start), but not on cancellation.
            if (currentCoroutineContext().isActive) done.complete(Unit)
        }
    }

    private suspend fun runLocked(manual: Boolean): MigrationReport {
        var st = stateStore.read()
        if (!startCounted) {
            startCounted = true
            st = st.copy(nativeStarts = st.nativeStarts + 1)
            runCatching { stateStore.write(st) }
        }
        return when (st.state) {
            MigrationState.NOT_NEEDED, MigrationState.IMPORTED, MigrationState.CLEANED -> st.toReport()
            MigrationState.IMPORTED_WITH_ERRORS ->
                if (!manual && st.attempts >= MAX_AUTOMATIC_ATTEMPTS) st.toReport() else attempt(st, retryOnly = st.failedKeys.toSet())
            MigrationState.NOT_STARTED -> when {
                !files.anyFlutterDataPresent() -> {
                    val now = timeSource.nowMillis()
                    st.copy(state = MigrationState.NOT_NEEDED, startedAt = now, finishedAt = now).also { stateStore.write(it) }.toReport()
                }
                !manual && st.attempts >= MAX_AUTOMATIC_ATTEMPTS -> st.copy(
                    state = MigrationState.IMPORTED_WITH_ERRORS,
                    warnings = (st.warnings + "TOO_MANY_ATTEMPTS").distinct(),
                    failedKeys = (st.failedKeys + FAILED_FSS).distinct(),
                ).also { stateStore.write(it) }.toReport()
                else -> attempt(st, retryOnly = null)
            }
        }
    }

    /**
     * Steps 3–9. [retryOnly] = `null` for the first import; otherwise only these failed names
     * (`<prefs>`, `<esp>`, `<fss>` markers or secure key names) are retried.
     */
    private suspend fun attempt(previous: PersistedState, retryOnly: Set<String>?): MigrationReport {
        val startedAt = timeSource.nowMillis()
        var st = previous.copy(attempts = previous.attempts + 1, startedAt = startedAt, finishedAt = null)
        stateStore.write(st) // an attempt that dies (process kill) is counted
        val warnings = mutableListOf<String>()
        val failed = LinkedHashSet<String>()
        val counts = LinkedHashMap<String, Int>()
        try {
            // 3. Snapshot (first attempt only) before any ESP / Tink call.
            if (!files.snapshotDir.exists()) runCatching { files.snapshotSecureStorage() }.onFailure { warnings += "SNAPSHOT_FAILED" }

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
            val keys = reader.candidateKeys(reader.storageAlgorithmMarker(data))
            val custom = reader.readCustomCipherEntries(data, keys)
            val esp: EspEntries? = runCatching { reader.readEspEntries(data) }.getOrElse {
                warnings += "ESP_EXCEPTION:${it.javaClass.simpleName}"
                EspEntries(emptyMap(), emptyList(), readable = false, encryptedEntryCount = 1)
            }
            if (esp != null) {
                counts[COUNT_ESP_ENTRIES] = esp.encryptedEntryCount
                if (!esp.readable && esp.encryptedEntryCount > 0) {
                    failed += FAILED_ESP
                    warnings += "ESP_UNREADABLE"
                }
            }

            // 6. Resolve (§4.8).
            val resolved = SecretResolver.resolve(
                SecretSources(
                    main = custom.main,
                    backup = custom.backup,
                    esp = esp?.values.orEmpty(),
                    plaintext = plan.plaintextSecrets,
                    referencedNpubs = plan.referencedNpubs,
                ),
            )
            warnings += resolved.warnings
            val unresolved = (custom.failed.map { it.removeSuffix(Fss.BACKUP) } + esp?.failed.orEmpty())
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

            // Restrict a retry to the names that failed before.
            val inScope: (String) -> Boolean = { key ->
                retryOnly == null || key in retryOnly ||
                    ((FAILED_ESP in retryOnly || FAILED_FSS in retryOnly) && key !in previous.importedSecrets)
            }

            // 8. Write: secrets first.
            val imported = LinkedHashSet(previous.importedSecrets)
            val recovered = LinkedHashSet(previous.recovered)
            var secretsImported = 0
            for ((key, secret) in resolved.primary) {
                if (!inScope(key)) continue
                when (val r = writeSecret(key, secret.value)) {
                    WriteResult.Imported -> { imported += key; secretsImported++ }
                    is WriteResult.Recovered -> { recovered += r.name; failed -= key }
                    WriteResult.Failed -> failed += key
                }
            }
            for ((name, value) in resolved.recovery) {
                val key = name.substringAfter('/')
                if (!inScope(key)) continue
                when (writeRecovery(name, value)) {
                    true -> recovered += name
                    false -> { failed += key; warnings += "RECOVERY_WRITE_FAILED:$name" }
                }
            }
            // A failed name that is now settled by a recovery copy counts as handled.
            failed.removeAll { it in imported }

            // Prefs in one transaction (first attempt, or a retry after the transaction failed).
            var prefsImported = previous.prefsImported
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

            var logImported = previous.logImported
            if (!logImported && (plan.diagnosticLog != null || plan.diagnosticVerbose != null)) {
                runCatching { logImporter.import(plan.diagnosticLog, plan.diagnosticVerbose) }
                    .onSuccess { logImported = true }
                    .onFailure { warnings += "LOG_IMPORT_FAILED:${it.javaClass.simpleName}" }
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

            st = st.copy(
                state = if (failed.isEmpty()) MigrationState.IMPORTED else MigrationState.IMPORTED_WITH_ERRORS,
                finishedAt = timeSource.nowMillis(),
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
            st = st.copy(
                state = MigrationState.IMPORTED_WITH_ERRORS,
                finishedAt = timeSource.nowMillis(),
                failedKeys = (previous.failedKeys + failed + FAILED_FSS).distinct(),
                warnings = (previous.warnings + warnings + "EXCEPTION:${e.javaClass.simpleName}").distinct(),
            )
        }
        stateStore.write(st)
        val report = st.toReport()
        logSummary(report, retryOnly != null)
        for (hook in hooks) {
            runCatching { hook.onMigrationImported(report) }.onFailure { log.warn(TAG, "Hook fehlgeschlagen: ${it.javaClass.simpleName}") }
        }
        return report
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
                    val name = SecretResolver.recoveryName(RecoverySource.RECOVERED, key)
                    if (writeRecovery(name, value)) WriteResult.Recovered(name) else WriteResult.Failed
                }
            SecureRead.Absent -> if (writeVerified(key, value)) WriteResult.Imported else WriteResult.Failed
            is SecureRead.Unreadable -> WriteResult.Failed // never overwrite an unreadable entry
        }

    /** Writes a recovery entry unless the same value is already there. `false` if it could not be stored. */
    private suspend fun writeRecovery(name: String, value: String): Boolean =
        when (val current = secureStore.readChecked(name)) {
            is SecureRead.Present -> current.value == value || writeVerified(name, value)
            SecureRead.Absent -> writeVerified(name, value)
            is SecureRead.Unreadable -> false
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

    // ---------------------------------------------------------------- cleanup

    private suspend fun cleanupLocked(): CleanupOutcome {
        val st = stateStore.read()
        when (st.state) {
            MigrationState.NOT_NEEDED, MigrationState.CLEANED -> return CleanupOutcome.NothingToDo
            MigrationState.IMPORTED -> Unit
            else -> return CleanupOutcome.NotYet("STATE")
        }
        if (st.nativeStarts < MIN_STARTS_BEFORE_CLEANUP) return CleanupOutcome.NotYet("STARTS")
        for (key in st.importedSecrets) {
            if (secureStore.readChecked(key) !is SecureRead.Present) return CleanupOutcome.NotYet("SECRET_MISSING:$key")
        }
        if (!identitySigns()) return CleanupOutcome.NotYet("IDENTITY")
        if (!badgesOpen()) return CleanupOutcome.NotYet("BADGES")

        val filesOk = files.deleteAllLegacyFiles()
        val aliasesOk = keystore.deleteLegacyAliases()
        val warnings = st.warnings + listOfNotNull(
            "CLEANUP_DELETE_INCOMPLETE".takeIf { !filesOk },
            "CLEANUP_ALIAS_DELETE_INCOMPLETE".takeIf { !aliasesOk },
        )
        stateStore.write(st.copy(state = MigrationState.CLEANED, cleanedAt = timeSource.nowMillis(), warnings = warnings))
        log.info(TAG, "Flutter-Altdaten entfernt (vollstaendig: ${filesOk && aliasesOk})")
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
    }
}
