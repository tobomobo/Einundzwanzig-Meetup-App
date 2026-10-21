package space.einundzwanzig.meetup.data.migration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import space.einundzwanzig.meetup.core.crypto.AesGcmBlob
import space.einundzwanzig.meetup.core.crypto.Hex
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.secure.SecureRead
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.migration.internal.DefaultFlutterMigration
import space.einundzwanzig.meetup.data.migration.internal.LegacyLogImporter
import space.einundzwanzig.meetup.data.migration.legacy.FlutterPrefs
import space.einundzwanzig.meetup.data.migration.legacy.Fss
import space.einundzwanzig.meetup.data.migration.legacy.RsaMode
import space.einundzwanzig.meetup.data.migration.legacy.SharedPrefsFiles
import java.io.File

/** Spec 14 §4.7–§4.11 orchestrator: state machine, routing, conflict rules, gate, cleanup and reset (F6). */
@RunWith(AndroidJUnit4::class)
class FlutterMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = FlutterDevice(context)
    private val keystore = FakeLegacyKeystore()
    private val kv = InMemoryKeyValueStore()
    private val secure = InMemorySecureStore()
    private val logger = DiagnosticLogger()
    private val importedLogs = mutableListOf<Pair<String?, Boolean?>>()
    private val hookReports = mutableListOf<MigrationReport>()
    private val time = object : TimeSource {
        override fun nowMillis() = 1_786_000_000_000L
    }

    private val v10 = MigrationFixtures.vectors.obj("fss_v10_gcm")
    private val aes = Hex.decode(v10.str("aes128KeyHex"))
    private val fixtureNsec = v10.arr("entries")[0].str("plaintext")
    private val fixtureBadgeKey = v10.arr("entries")[1].str("plaintext")
    private val fixtureToken = v10.arr("entries")[2].str("plaintext")
    private val badgeBlob = MigrationFixtures.vectors.obj("badge_blob")

    private val alice = KeyPair.fromPrivateKeyHex("3".repeat(64))
    private val bob = KeyPair.fromPrivateKeyHex("4".repeat(64))

    @Before
    fun setUp() = device.reset()

    /** A new instance = a new process start. */
    private fun migration(secureStore: SecureStore = secure) = DefaultFlutterMigration(
        context = context,
        keyValueStore = kv,
        secureStore = secureStore,
        keystore = keystore,
        logImporter = LegacyLogImporter { raw, verbose -> importedLogs += raw to verbose },
        hooks = setOf(MigrationCompletedHook { hookReports += it }),
        timeSource = time,
        log = logger,
        io = Dispatchers.IO,
    )

    private fun writeKeys() = device.write(Fss.KEYS, mapOf(Fss.WRAPPED_V10_GCM to FakeLegacyKeystore.wrapB64(aes, RsaMode.OAEP)))

    private fun writeE2(values: Map<String, String>, extra: Map<String, String> = emptyMap()) {
        writeKeys()
        device.write(Fss.DATA, values.entries.associate { (k, v) -> Fss.ENTRY + k to FssWriter.gcmEntry(aes, v) } + extra)
    }

    private fun writeFixtureE2() = device.write(
        Fss.DATA,
        v10.arr("entries").associate { it.str("xmlName") to it.str("storedValue") },
    ).also { writeKeys() }

    private fun flutterPrefs(): Map<String, Any> = SharedPrefsFiles(context).read(FlutterPrefs.FILE)

    private fun stateFile() = File(context.noBackupFilesDir, "flutter_migration/state.json")

    // ------------------------------------------------------------------ detection / gate

    @Test
    fun freshInstallIsNotNeededAndReleasesTheGate() = runBlocking {
        val m = migration()
        assertFalse(m.gate.isComplete)
        val r = m.ensureMigrated()
        assertEquals(MigrationState.NOT_NEEDED, r.state)
        assertTrue(m.gate.isComplete)
        assertTrue(stateFile().exists())
        assertTrue(kv.getAll().isEmpty())
        assertTrue(hookReports.isEmpty())
        // Later Flutter files never trigger an import any more.
        writeFixtureE2()
        assertEquals(MigrationState.NOT_NEEDED, migration().ensureMigrated().state)
        assertTrue(secure.entries.value.isEmpty())
    }

    @Test
    fun interruptedWriteBackupAloneCountsAsFlutterData() = runBlocking {
        device.write(FlutterPrefs.FILE, mapOf("flutter.nickname" to "Satoshi"), bak = true)
        assertEquals(MigrationState.IMPORTED, migration().ensureMigrated().state)
        assertEquals("Satoshi", kv.getString("nickname"))
    }

    @Test
    fun gateAwaitRunsTheMigration() = runBlocking {
        device.write(FlutterPrefs.FILE, mapOf("flutter.nickname" to "Satoshi"))
        val m = migration()
        m.gate.await()
        assertTrue(m.gate.isComplete)
        assertEquals("Satoshi", kv.getString("nickname"))
    }

    // ------------------------------------------------------------------ E2 import

    @Test
    fun e2ImportCopiesPrefsAndSecretsAndKeepsFlutterFiles() = runBlocking {
        device.writeRaw(FlutterPrefs.FILE, MigrationFixtures.vectors.obj("flutter_shared_prefs").str("xml"))
        writeFixtureE2()
        device.writeRaw("HomeWidgetPreferences", MigrationFixtures.vectors.obj("home_widget_prefs").str("xml"))
        device.write("InternalHomeWidgetPreferences", mapOf("callbackHandle" to 12L))
        device.write("e21_widget", mapOf("pending_target" to "news"))
        val widgetBytes = File(device.sharedPrefs, "HomeWidgetPreferences.xml").readBytes()

        val m = migration()
        val r = m.ensureMigrated()

        assertEquals(MigrationState.IMPORTED, r.state)
        assertEquals(setOf(StorageEra.E2), r.eras)
        assertTrue(r.failedKeys.isEmpty())
        assertEquals(1, r.attempts)
        // Prefs: same names (without flutter.) and types.
        assertEquals("Satoshi", kv.getString("nickname"))
        assertEquals(false, kv.getBool("haptic_enabled"))
        assertEquals(1786000000123L, kv.getInt("admin_registry_timestamp"))
        assertEquals(listOf("Berlin", "München"), kv.getStringList("favorite_meetups"))
        assertEquals(48.137154, kv.getDouble("rqr_session_lat")!!, 0.0)
        assertNull(kv.get("other.not_flutter"))
        assertEquals(9, r.counts[MigrationReport.COUNT_PREFS_COPIED])
        // Secrets: flutter_secure_storage names.
        assertEquals(SecureRead.Present(fixtureNsec), secure.readChecked("nostr_nsec_key"))
        assertEquals(SecureRead.Present(fixtureBadgeKey), secure.readChecked("badge_encryption_key"))
        assertEquals(SecureRead.Present(fixtureToken), secure.readChecked("portal_api_token"))
        assertEquals(3, r.counts[MigrationReport.COUNT_SECRETS_IMPORTED])
        assertTrue(kv.keys().none { it.startsWith("nostr_nsec") })
        // Flutter files stay until cleanup; widget stores untouched; internal widget prefs deleted.
        assertTrue(device.exists(FlutterPrefs.FILE) && device.exists(Fss.DATA) && device.exists(Fss.KEYS))
        assertArrayEquals(widgetBytes, File(device.sharedPrefs, "HomeWidgetPreferences.xml").readBytes())
        assertTrue(device.exists("e21_widget"))
        assertFalse(device.exists("InternalHomeWidgetPreferences"))
        // Snapshot holds ciphertext files only.
        val snapshot = File(context.noBackupFilesDir, "flutter_migration/snapshot").list()!!.toSet()
        assertEquals(setOf("FlutterSecureStorage.xml", "FlutterSecureKeyStorage.xml"), snapshot)
        // Hook ran once; gate released.
        assertEquals(1, hookReports.size)
        assertTrue(m.gate.isComplete)

        // Idempotent: a second process start does nothing but count the start.
        val again = migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, again.state)
        assertEquals(1, again.attempts)
        assertEquals(1, hookReports.size)
        assertTrue(migration().pendingLegacySecrets().isEmpty())
    }

    @Test
    fun nativeValuesAreNeverOverwritten() = runBlocking {
        device.write(FlutterPrefs.FILE, mapOf("flutter.nickname" to "Flutter", "flutter.telegram" to "@t"))
        writeE2(mapOf("badge_encryption_key" to "a".repeat(64), "portal_api_token" to "1|same"))
        kv.setString("nickname", "Native")
        secure.write("badge_encryption_key", "b".repeat(64))
        secure.write("portal_api_token", "1|same")

        val r = migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, r.state)
        assertEquals("Native", kv.getString("nickname"))
        assertEquals("@t", kv.getString("telegram"))
        assertEquals(1, r.counts[MigrationReport.COUNT_PREFS_KEPT_NATIVE])
        assertEquals("b".repeat(64), secure.read("badge_encryption_key"))
        assertEquals("a".repeat(64), secure.read("legacy_recovered/badge_encryption_key"))
        assertEquals(listOf("legacy_recovered/badge_encryption_key"), r.recovered)
    }

    @Test
    fun backupEntryIsPrimaryWhenMainEntryIsUndecryptable() = runBlocking {
        writeE2(
            mapOf("badge_pending_key" + Fss.BACKUP to "c".repeat(64)),
            extra = mapOf(Fss.ENTRY + "badge_pending_key" to "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n"),
        )
        val r = migration().ensureMigrated()
        assertEquals("c".repeat(64), secure.read("badge_pending_key"))
        assertEquals(MigrationState.IMPORTED, r.state)
    }

    // ------------------------------------------------------------------ E0 routing

    @Test
    fun e0PlaintextKeysGoToSecureStoreOnlyAndAreRemovedAfterVerification() = runBlocking {
        device.write(
            FlutterPrefs.FILE,
            linkedMapOf(
                "flutter.nostr_nsec_key" to alice.nsec,
                "flutter.nostr_npub_key" to alice.npub,
                "flutter.nostr_priv_hex" to alice.privateKeyHex,
                "flutter.rqr_session_seed" to "ab".repeat(32),
                "flutter.rqr_session_base_payload" to "{\"p\":1}",
                "flutter.nickname" to "Anon",
                "flutter.badges" to FlutterPrefs.JSON_LIST + "[\"{}\"]",
            ),
        )
        val r = migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, r.state)
        assertTrue(StorageEra.E0 in r.eras)
        assertEquals(alice.nsec, secure.read("nostr_nsec_key"))
        assertEquals(alice.npub, secure.read("nostr_npub_key"))
        assertEquals(alice.privateKeyHex, secure.read("nostr_priv_hex"))
        assertEquals("ab".repeat(32), secure.read("rqr_session_seed_secure"))
        assertEquals("{\"p\":1}", secure.read("rqr_session_base_payload_secure"))
        assertEquals(true, kv.getBool("secure_migration_done"))
        assertEquals(listOf("{}"), kv.getStringList("badges"))
        for (k in listOf("nostr_nsec_key", "nostr_npub_key", "nostr_priv_hex", "rqr_session_seed", "rqr_session_base_payload")) {
            assertNull(k, kv.get(k))
            assertFalse(k, flutterPrefs().containsKey("flutter.$k"))
        }
        assertEquals("Anon", flutterPrefs()["flutter.nickname"])
        // No secret value reaches the diagnostic log.
        val logText = logger.entries.value.joinToString("\n") { it.message }
        assertFalse(logText.contains(alice.nsec) || logText.contains(alice.privateKeyHex) || logText.contains(alice.npub))
        assertTrue(logText.contains("Flutter-Daten übernommen"))
    }

    @Test
    fun failedSecureWriteKeepsPlaintextAndRetriesOnTheNextStart() = runBlocking {
        device.write(FlutterPrefs.FILE, mapOf("flutter.nostr_nsec_key" to alice.nsec, "flutter.nickname" to "Anon"))
        secure.failWrite = { it == "nostr_nsec_key" }
        val first = migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED_WITH_ERRORS, first.state)
        assertEquals(listOf("nostr_nsec_key"), first.failedKeys)
        assertEquals("Anon", kv.getString("nickname"))
        assertNull(kv.getBool("secure_migration_done"))
        assertTrue(flutterPrefs().containsKey("flutter.nostr_nsec_key"))
        assertEquals(setOf("nostr_nsec_key"), migration().pendingLegacySecrets())

        secure.failWrite = { false }
        val second = migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, second.state)
        assertEquals(2, second.attempts)
        assertEquals(alice.nsec, secure.read("nostr_nsec_key"))
        assertEquals(true, kv.getBool("secure_migration_done"))
        assertFalse(flutterPrefs().containsKey("flutter.nostr_nsec_key"))
        assertTrue(migration().pendingLegacySecrets().isEmpty())
    }

    // ------------------------------------------------------------------ errors, retries, gate on failure

    @Test
    fun unreadableKeystoreGivesImportedWithErrorsAndRetriesUpToTheLimit() = runBlocking {
        device.write(FlutterPrefs.FILE, mapOf("flutter.nickname" to "Anon"))
        writeFixtureE2()
        keystore.oaepPresent = false
        keystore.pkcs1Present = false
        val first = migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED_WITH_ERRORS, first.state)
        assertEquals(setOf("nostr_nsec_key", "badge_encryption_key", "portal_api_token"), first.failedKeys.toSet())
        assertEquals("Anon", kv.getString("nickname"))
        assertTrue(secure.entries.value.isEmpty())

        repeat(12) { migration().ensureMigrated() }
        assertEquals(FlutterMigration.MAX_AUTOMATIC_ATTEMPTS, migration().currentReport().attempts)

        // Manual retry from Settings ignores the limit; with the Keystore back everything imports.
        keystore.oaepPresent = true
        val manual = migration().retryNow()
        assertEquals(MigrationState.IMPORTED, manual.state)
        assertEquals(FlutterMigration.MAX_AUTOMATIC_ATTEMPTS + 1, manual.attempts)
        assertEquals(fixtureNsec, secure.read("nostr_nsec_key"))
        assertEquals(fixtureToken, secure.read("portal_api_token"))
    }

    @Test
    fun gateIsReleasedWhenTheMigrationThrows() = runBlocking {
        writeFixtureE2()
        val broken = object : SecureStore by secure {
            override suspend fun readChecked(key: String): SecureRead = throw IllegalStateException("boom")
        }
        val m = migration(broken)
        val r = m.ensureMigrated()
        assertTrue(m.gate.isComplete)
        assertEquals(MigrationState.IMPORTED_WITH_ERRORS, r.state)
        assertTrue("EXCEPTION:IllegalStateException" in r.warnings)
        // The next start retries and succeeds.
        assertEquals(MigrationState.IMPORTED, migration().ensureMigrated().state)
        assertEquals(fixtureBadgeKey, secure.read("badge_encryption_key"))
    }

    @Test
    fun unreadableEspIsReportedAsPending() = runBlocking {
        device.write(Fss.DATA, FssWriter.espEntries(keystore.masterAeadForWriting(), mapOf("nostr_nsec_key" to alice.nsec)))
        keystore.espMasterKey = null
        val r = migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED_WITH_ERRORS, r.state)
        assertTrue("<esp>" in r.failedKeys)
        assertTrue("nostr_nsec_key" in migration().pendingLegacySecrets())
    }

    // ------------------------------------------------------------------ F1: ESP orphans

    @Test
    fun f1EspOrphansAreRecoveredWhenThePrefsStillReferenceTheIdentity() = runBlocking {
        val blob = AesGcmBlob.seal(Hex.decode(badgeBlob.str("keyHex")), badgeBlob.str("plaintextJson"))
        device.write(FlutterPrefs.FILE, mapOf("flutter.nostr" to alice.npub, "flutter.badges_encrypted" to blob))
        writeKeys()
        // 1.7.x created a new empty GCM store next to the orphaned ESP entries.
        val esp = FssWriter.espEntries(
            keystore.masterAeadForWriting(),
            mapOf(
                "nostr_nsec_key" to alice.nsec,
                "nostr_npub_key" to alice.npub,
                "nostr_priv_hex" to alice.privateKeyHex,
                "badge_encryption_key" to badgeBlob.str("keyHex"),
            ),
        )
        device.write(Fss.DATA, esp + mapOf(Fss.ENTRY + "badge_reset_pending" to FssWriter.gcmEntry(aes, "1")))

        val r = migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, r.state)
        assertEquals(setOf(StorageEra.E1, StorageEra.E2), r.eras)
        assertEquals(alice.nsec, secure.read("nostr_nsec_key"))
        assertEquals(alice.privateKeyHex, secure.read("nostr_priv_hex"))
        assertEquals(badgeBlob.str("keyHex"), secure.read("badge_encryption_key"))
        assertEquals("1", secure.read("badge_reset_pending"))
        assertEquals(1, r.counts[MigrationReport.COUNT_BADGE_BLOBS])
        assertEquals(1, r.counts[MigrationReport.COUNT_BADGE_BLOBS_OPENED])
        assertEquals(4, r.counts[MigrationReport.COUNT_ESP_ENTRIES])
        assertTrue(r.recovered.isEmpty())
    }

    @Test
    fun espIdentityOfAnotherNpubIsOnlyParked() = runBlocking {
        device.write(FlutterPrefs.FILE, mapOf("flutter.nostr" to bob.npub))
        writeKeys()
        val esp = FssWriter.espEntries(keystore.masterAeadForWriting(), mapOf("nostr_nsec_key" to alice.nsec, "easyauth_password_wrap_npub" to alice.npub))
        device.write(Fss.DATA, esp + mapOf(Fss.ENTRY + "nostr_nsec_key" to FssWriter.gcmEntry(aes, bob.nsec)))

        val r = migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, r.state)
        assertEquals(bob.nsec, secure.read("nostr_nsec_key"))
        assertNull(secure.read("easyauth_password_wrap_npub"))
        assertEquals(alice.nsec, secure.read("legacy_esp/nostr_nsec_key"))
        assertEquals(alice.npub, secure.read("legacy_esp/easyauth_password_wrap_npub"))
        assertEquals(setOf("legacy_esp/nostr_nsec_key", "legacy_esp/easyauth_password_wrap_npub"), r.recovered.toSet())
    }

    // ------------------------------------------------------------------ files outside shared_prefs, log

    @Test
    fun profilePictureIsCopiedIntoFilesDir() = runBlocking {
        val picked = File(context.cacheDir, "scaled_me.JPG").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        device.write(FlutterPrefs.FILE, mapOf("flutter.local_profile_picture" to picked.absolutePath))
        migration().ensureMigrated()
        val stored = kv.getString("local_profile_picture")!!
        assertEquals(File(context.filesDir, "profile/local_picture.jpg").absolutePath, stored)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(stored).readBytes())
    }

    @Test
    fun missingProfilePictureOmitsTheKey() = runBlocking {
        device.write(FlutterPrefs.FILE, mapOf("flutter.local_profile_picture" to File(context.cacheDir, "gone.jpg").absolutePath))
        val r = migration().ensureMigrated()
        assertNull(kv.get("local_profile_picture"))
        assertTrue("PROFILE_PICTURE_MISSING" in r.warnings)
    }

    @Test
    fun diagnosticLogGoesToAppLoggerNotToKeyValueStore() = runBlocking {
        device.write(FlutterPrefs.FILE, mapOf("flutter.diagnostic_log" to "[]", "flutter.diagnostic_verbose" to true))
        migration().ensureMigrated()
        assertEquals(listOf<Pair<String?, Boolean?>>("[]" to true), importedLogs)
        assertNull(kv.get("diagnostic_log"))
        assertNull(kv.get("diagnostic_verbose"))
        migration().ensureMigrated()
        assertEquals(1, importedLogs.size)
    }

    @Test
    fun obsoleteAndUndecodableKeysAreNotCopied() = runBlocking {
        device.write(
            FlutterPrefs.FILE,
            mapOf(
                "flutter.vouching_consensus_cache" to "x",
                "flutter.tile_order" to FlutterPrefs.JSON_LIST + "[",
                "flutter.tile_hidden" to FlutterPrefs.JSON_LIST + "[\"a\"]",
            ),
        )
        val r = migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, r.state)
        assertEquals(setOf("tile_hidden"), kv.keys())
        assertTrue("UNDECODABLE_PREF:tile_order" in r.warnings)
        assertEquals(1, r.counts[MigrationReport.COUNT_PREFS_SKIPPED])
    }

    // ------------------------------------------------------------------ reset (F6) and cleanup

    @Test
    fun appResetDeletesFlutterDataAndPreventsAReimport() = runBlocking {
        device.write(FlutterPrefs.FILE, mapOf("flutter.nickname" to "Anon", "flutter.nostr_nsec_key" to alice.nsec))
        writeFixtureE2()
        device.write("HomeWidgetPreferences", mapOf("block" to "1"))
        device.write("e21_widget", mapOf("pending_target" to "news"))
        File(context.filesDir, "Rajdhani-Bold.ttf").writeText("font")
        File(context.cacheDir, "share_plus").apply { mkdirs(); File(this, "x.png").writeText("x") }
        val m = migration()
        m.ensureMigrated()

        // Spec 12 reset: native stores cleared, then the migration hook.
        kv.clear()
        secure.deleteAll()
        m.onAppReset()

        assertEquals(MigrationState.CLEANED, m.currentReport().state)
        assertFalse(device.exists(FlutterPrefs.FILE))
        assertFalse(device.exists(Fss.DATA))
        assertFalse(device.exists(Fss.KEYS))
        assertTrue(device.exists("HomeWidgetPreferences"))
        assertTrue(device.exists("e21_widget"))
        assertFalse(File(context.filesDir, "Rajdhani-Bold.ttf").exists())
        assertFalse(File(context.cacheDir, "share_plus").exists())
        assertFalse(File(context.noBackupFilesDir, "flutter_migration/snapshot").exists())
        assertEquals(1, keystore.aliasesDeleted)
        assertTrue(stateFile().exists())

        // Even if Flutter files reappeared, a later start never re-imports (F6).
        writeFixtureE2()
        assertEquals(MigrationState.CLEANED, migration().ensureMigrated().state)
        assertTrue(kv.getAll().isEmpty())
        assertTrue(secure.entries.value.isEmpty())
    }

    @Test
    fun resetBeforeTheMigrationRanAlsoBlocksTheImport() = runBlocking {
        writeFixtureE2()
        migration().onAppReset()
        assertEquals(MigrationState.CLEANED, migration().ensureMigrated().state)
        assertTrue(secure.entries.value.isEmpty())
    }

    @Test
    fun cleanupWaitsForThreeStartsAndVerifiedData() = runBlocking {
        val blob = AesGcmBlob.seal(Hex.decode(badgeBlob.str("keyHex")), badgeBlob.str("plaintextJson"))
        device.write(FlutterPrefs.FILE, mapOf("flutter.badges_encrypted" to blob))
        writeE2(
            mapOf(
                "nostr_nsec_key" to alice.nsec,
                "nostr_npub_key" to alice.npub,
                "nostr_priv_hex" to alice.privateKeyHex,
                "badge_encryption_key" to badgeBlob.str("keyHex"),
            ),
        )
        migration().ensureMigrated()
        assertEquals(CleanupOutcome.NotYet("STARTS"), migration().cleanupIfEligible())
        migration().ensureMigrated()
        val third = migration()
        third.ensureMigrated()

        // An imported secret that vanished blocks the cleanup.
        secure.delete("nostr_npub_key")
        assertEquals(CleanupOutcome.NotYet("SECRET_MISSING:nostr_npub_key"), third.cleanupIfEligible())
        // A wrong npub blocks it as well.
        secure.write("nostr_npub_key", bob.npub)
        assertEquals(CleanupOutcome.NotYet("IDENTITY"), third.cleanupIfEligible())
        secure.write("nostr_npub_key", alice.npub)
        // Badges must open.
        kv.setString("badges_encrypted", AesGcmBlob.seal(ByteArray(32) { 9 }, "[]"))
        assertEquals(CleanupOutcome.NotYet("BADGES"), third.cleanupIfEligible())
        kv.setString("badges_encrypted", blob)

        assertEquals(CleanupOutcome.Cleaned, third.cleanupIfEligible())
        assertEquals(MigrationState.CLEANED, third.currentReport().state)
        assertFalse(device.exists(FlutterPrefs.FILE) || device.exists(Fss.DATA) || device.exists(Fss.KEYS))
        assertEquals(1, keystore.aliasesDeleted)
        assertEquals(CleanupOutcome.NothingToDo, third.cleanupIfEligible())
        // Imported data is untouched.
        assertEquals(alice.nsec, secure.read("nostr_nsec_key"))
    }

    @Test
    fun cleanupRefusesWhileErrorsArePending() = runBlocking {
        writeFixtureE2()
        keystore.oaepPresent = false
        repeat(3) { migration().ensureMigrated() }
        assertEquals(CleanupOutcome.NotYet("STATE"), migration().cleanupIfEligible())
        assertTrue(device.exists(Fss.DATA))
        assertEquals(CleanupOutcome.NothingToDo, run { device.reset(); migration().ensureMigrated(); migration().cleanupIfEligible() })
    }

    @Test
    fun stateFileHoldsNamesNeverValues() = runBlocking {
        device.write(FlutterPrefs.FILE, mapOf("flutter.nostr_nsec_key" to alice.nsec))
        writeE2(mapOf("portal_api_token" to "1|supersecret"))
        migration().ensureMigrated()
        val json = stateFile().readText()
        assertTrue(json.contains("\"state\":\"IMPORTED\""))
        assertTrue(json.contains("\"version\":1"))
        assertFalse(json.contains(alice.nsec))
        assertFalse(json.contains("supersecret"))
        val snapshot = File(context.noBackupFilesDir, "flutter_migration/snapshot")
        assertFalse(File(snapshot, "FlutterSharedPreferences.xml").exists())
        assertTrue(File(snapshot, "FlutterSecureStorage.xml").exists())
    }

    @Test
    fun storedPrefValueTypesSurviveTheCopy() = runBlocking {
        device.write(
            FlutterPrefs.FILE,
            linkedMapOf(
                "flutter.badge_restore_expect" to "npub1legacy",
                "flutter.chat_last_read" to FlutterPrefs.JSON_LIST + "[\"room|1786000000\"]",
                "flutter.news_last_read_ts" to 1786000000L,
            ),
        )
        migration().ensureMigrated()
        assertEquals(PrefValue.StringValue("npub1legacy"), kv.get("badge_restore_expect"))
        assertEquals(PrefValue.StringListValue(listOf("room|1786000000")), kv.get("chat_last_read"))
        assertEquals(PrefValue.IntValue(1786000000L), kv.get("news_last_read_ts"))
    }
}
