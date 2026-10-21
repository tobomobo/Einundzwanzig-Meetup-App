package space.einundzwanzig.meetup.data.migration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.crypto.Hex
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.prefs.KeyValueEditor
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.secure.SecureRead
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.data.migration.legacy.FlutterPrefs
import space.einundzwanzig.meetup.data.migration.legacy.Fss
import space.einundzwanzig.meetup.data.migration.legacy.RsaMode
import java.io.File

/**
 * Review round 3: undecryptable custom entries vs. ESP, recovery-slot collisions, corrupt state
 * file, progress kept on exceptions, snapshot completion, log redaction, E1_CBC end to end.
 */
@RunWith(AndroidJUnit4::class)
class MigrationReviewFixes3Test {
    private val h = MigrationHarness(ApplicationProvider.getApplicationContext())
    private val alice = KeyPair.fromPrivateKeyHex("3".repeat(64))
    private val bob = KeyPair.fromPrivateKeyHex("4".repeat(64))
    private val carol = KeyPair.fromPrivateKeyHex("5".repeat(64))
    private val dave = KeyPair.fromPrivateKeyHex("6".repeat(64))

    // ------------------------------------------------------------------ undecryptable custom entry + ESP

    @Test
    fun espNeverReplacesAnUndecryptableCustomEntry() = runBlocking {
        val newKey = "e".repeat(64)
        val staleKey = "f".repeat(64)
        h.writeKeys()
        h.device.write(
            Fss.DATA,
            FssWriter.espEntries(h.keystore.masterAeadForWriting(), mapOf("badge_encryption_key" to staleKey)) +
                h.e2Entries(mapOf("badge_encryption_key" to newKey)),
        )
        h.keystore.oaepPresent = false // the wrapped GCM key cannot be unwrapped (transient)
        h.keystore.pkcs1Present = false

        val r = h.migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED_WITH_ERRORS, r.state)
        assertTrue("badge_encryption_key" in r.failedKeys)
        assertTrue("ESP_HELD_BACK:badge_encryption_key" in r.warnings)
        assertTrue("badge_encryption_key" in h.migration().pendingLegacySecrets())
        assertEquals(SecureRead.Absent, h.secure.readChecked("badge_encryption_key"))
        assertEquals(staleKey, h.secure.read("legacy_esp/badge_encryption_key"))

        // The wrapped key is readable again: the E2 value becomes primary.
        h.keystore.oaepPresent = true
        h.keystore.pkcs1Present = true
        val retried = h.migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, retried.state)
        assertEquals(newKey, h.secure.read("badge_encryption_key"))
        assertEquals(staleKey, h.secure.read("legacy_esp/badge_encryption_key"))
        assertTrue(h.migration().pendingLegacySecrets().isEmpty())
    }

    @Test
    fun matchingEspIdentityDoesNotReplaceAnUndecryptableE2Identity() = runBlocking {
        h.device.write(FlutterPrefs.FILE, mapOf("flutter.nostr" to alice.npub))
        h.writeKeys()
        h.device.write(
            Fss.DATA,
            FssWriter.espEntries(h.keystore.masterAeadForWriting(), mapOf("nostr_nsec_key" to alice.nsec, "nostr_npub_key" to alice.npub)) +
                h.e2Entries(mapOf("nostr_nsec_key" to bob.nsec)),
        )
        h.keystore.oaepPresent = false
        h.keystore.pkcs1Present = false
        val r = h.migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED_WITH_ERRORS, r.state)
        assertNull(h.secure.read("nostr_nsec_key"))
        assertEquals(alice.nsec, h.secure.read("legacy_esp/nostr_nsec_key"))
        // No custom entry for the npub: the matching ESP value is primary as before.
        assertEquals(alice.npub, h.secure.read("nostr_npub_key"))
        assertTrue("nostr_nsec_key" in h.migration().pendingLegacySecrets())
    }

    // ------------------------------------------------------------------ recovery slots

    @Test
    fun threeWayConflictWithANativeValueKeepsEveryValue() = runBlocking {
        // E0 plaintext alice, E2 main bob, _BACKUP carol; the user already has dave natively.
        h.device.write(FlutterPrefs.FILE, mapOf("flutter.nostr_nsec_key" to alice.nsec))
        h.writeE2(mapOf("nostr_nsec_key" to bob.nsec, "nostr_nsec_key" + Fss.BACKUP to carol.nsec))
        h.secure.write("nostr_nsec_key", dave.nsec)

        val r = h.migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, r.state)
        assertEquals(dave.nsec, h.secure.read("nostr_nsec_key"))
        assertEquals(bob.nsec, h.secure.read("legacy_backup/nostr_nsec_key"))
        assertEquals(alice.nsec, h.secure.read("legacy_recovered/nostr_nsec_key"))
        assertEquals(carol.nsec, h.secure.read("legacy_recovered/nostr_nsec_key#2"))
        assertEquals(
            setOf("legacy_recovered/nostr_nsec_key", "legacy_backup/nostr_nsec_key", "legacy_recovered/nostr_nsec_key#2"),
            r.recovered.toSet(),
        )
    }

    @Test
    fun retryAfterANativeValueAppearedNeverOverwritesARecoveryEntry() = runBlocking {
        h.device.write(FlutterPrefs.FILE, mapOf("flutter.nostr_nsec_key" to alice.nsec))
        h.writeE2(mapOf("nostr_nsec_key" to bob.nsec, "nostr_nsec_key" + Fss.BACKUP to carol.nsec))
        // First start: the plaintext write fails.
        val failingNsec = object : SecureStore by h.secure {
            override suspend fun write(key: String, value: String): Boolean =
                if (key == "nostr_nsec_key") false else h.secure.write(key, value)
        }
        val first = h.migration(failingNsec).ensureMigrated()
        assertEquals(MigrationState.IMPORTED_WITH_ERRORS, first.state)
        assertEquals(carol.nsec, h.secure.read("legacy_recovered/nostr_nsec_key"))
        // The user creates a new identity before the retry.
        h.secure.write("nostr_nsec_key", dave.nsec)

        val retried = h.migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, retried.state)
        assertEquals(dave.nsec, h.secure.read("nostr_nsec_key"))
        assertEquals(carol.nsec, h.secure.read("legacy_recovered/nostr_nsec_key"))
        assertEquals(alice.nsec, h.secure.read("legacy_recovered/nostr_nsec_key#2"))
        assertEquals(bob.nsec, h.secure.read("legacy_backup/nostr_nsec_key"))
        // Idempotent: a manual retry reuses the slots.
        h.migration().retryNow()
        assertNull(h.secure.read("legacy_recovered/nostr_nsec_key#3"))
    }

    // ------------------------------------------------------------------ corrupt state file

    @Test
    fun corruptStateFileNeverResurrectsALoggedOutIdentity() = runBlocking {
        h.device.write(FlutterPrefs.FILE, mapOf("flutter.nickname" to "Anon"))
        h.writeE2(mapOf("nostr_nsec_key" to alice.nsec, "badge_encryption_key" to "a".repeat(64)))
        assertEquals(MigrationState.IMPORTED, h.migration().ensureMigrated().state)
        // Native logout and nickname cleared.
        h.secure.delete("nostr_nsec_key")
        h.kv.remove("nickname")
        h.kv.setString("theme", "dark")
        h.stateFile.writeText("{broken")

        val m = h.migration()
        repeat(3) { m.currentReport() }
        val r = m.ensureMigrated()
        assertEquals(SecureRead.Absent, h.secure.readChecked("nostr_nsec_key"))
        assertNull(h.kv.getString("nickname"))
        assertEquals(MigrationState.IMPORTED_WITH_ERRORS, r.state)
        assertTrue("STATE_FILE_CORRUPT" in r.warnings)
        assertEquals(FlutterMigration.MAX_AUTOMATIC_ATTEMPTS, r.attempts)
        // Present natively = settled; the absent key stays protected until the user decides.
        val pending = h.migration().pendingLegacySecrets()
        assertFalse("badge_encryption_key" in pending)
        assertTrue("nostr_nsec_key" in pending)
        // Later starts do not import either; exactly one support copy of the broken file.
        h.migration().ensureMigrated()
        assertEquals(SecureRead.Absent, h.secure.readChecked("nostr_nsec_key"))
        assertEquals(listOf("state.json.corrupt"), h.migrationDir.list()!!.filter { it.startsWith("state.json.corrupt") })
    }

    @Test
    fun corruptStateFileWithoutNativeDataImportsNormally() = runBlocking {
        h.writeE2(mapOf("badge_encryption_key" to "a".repeat(64)))
        h.migrationDir.mkdirs()
        h.stateFile.writeText("{broken")
        val r = h.migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, r.state)
        assertEquals("a".repeat(64), h.secure.read("badge_encryption_key"))
    }

    // ------------------------------------------------------------------ exception keeps progress

    @Test
    fun exceptionAfterTheLogImportKeepsTheProgressAndNeverImportsTheLogTwice() = runBlocking {
        h.device.write(FlutterPrefs.FILE, mapOf("flutter.diagnostic_log" to "[]", "flutter.nickname" to "Anon"))
        h.writeE2(mapOf("badge_encryption_key" to "a".repeat(64), "portal_api_token" to "1|tok"))
        // The clock throws once, right after the log import (inside the attempt).
        var armed = false
        val base = h.now
        h.now = { if (armed && h.importedLogs.isNotEmpty()) { armed = false; throw IllegalStateException("clock") } else base() }
        armed = true
        val first = h.migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED_WITH_ERRORS, first.state)
        assertTrue("EXCEPTION:IllegalStateException" in first.warnings)
        assertEquals(1, h.importedLogs.size)
        // The portal logs out before the next start.
        h.secure.delete("portal_api_token")

        val second = h.migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, second.state)
        assertEquals(1, h.importedLogs.size)
        assertNull("imported secrets stay settled", h.secure.read("portal_api_token"))
        assertEquals("Anon", h.kv.getString("nickname"))
    }

    @Test
    fun exceptionInThePrefsTransactionKeepsTheImportedSecrets() = runBlocking {
        h.device.write(FlutterPrefs.FILE, mapOf("flutter.nickname" to "Anon"))
        h.writeE2(mapOf("portal_api_token" to "1|tok"))
        val throwing = object : KeyValueStore by h.kv {
            override suspend fun edit(block: KeyValueEditor.() -> Unit): Boolean = throw IllegalStateException("disk")
        }
        val first = h.migration(keyValueStore = throwing).ensureMigrated()
        assertEquals(MigrationState.IMPORTED_WITH_ERRORS, first.state)
        assertEquals("1|tok", h.secure.read("portal_api_token"))
        h.secure.delete("portal_api_token") // logout before the retry
        val second = h.migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, second.state)
        assertNull(h.secure.read("portal_api_token"))
        assertEquals("Anon", h.kv.getString("nickname"))
    }

    // ------------------------------------------------------------------ snapshot

    @Test
    fun interruptedSnapshotIsCompletedOnTheNextAttempt() = runBlocking {
        h.writeE2(mapOf("badge_encryption_key" to "a".repeat(64)))
        h.keystore.oaepPresent = false
        // A regular file where the snapshot dir belongs: the copy fails.
        h.migrationDir.mkdirs()
        h.snapshotDir.writeText("x")
        val first = h.migration().ensureMigrated()
        assertTrue("SNAPSHOT_FAILED" in first.warnings)
        assertEquals(MigrationState.IMPORTED_WITH_ERRORS, first.state)

        // An earlier copy that died halfway: dir present, a partial temp file, no marker.
        h.snapshotDir.delete()
        h.snapshotDir.mkdirs()
        File(h.snapshotDir, "FlutterSecureStorage.xml.tmp").writeText("<map")
        h.keystore.oaepPresent = true
        assertEquals(MigrationState.IMPORTED, h.migration().ensureMigrated().state)
        assertEquals(
            setOf("FlutterSecureStorage.xml", "FlutterSecureKeyStorage.xml", ".complete"),
            h.snapshotDir.list()!!.toSet(),
        )
        assertEquals(
            File(h.device.sharedPrefs, "FlutterSecureStorage.xml").readText(),
            File(h.snapshotDir, "FlutterSecureStorage.xml").readText(),
        )
    }

    @Test
    fun completedSnapshotFilesAreNeverOverwritten() = runBlocking {
        h.writeE2(mapOf("badge_encryption_key" to "a".repeat(64)))
        h.keystore.oaepPresent = false
        h.migration().ensureMigrated()
        val first = File(h.snapshotDir, "FlutterSecureStorage.xml").readText()
        h.writeE2(mapOf("badge_encryption_key" to "b".repeat(64)))
        h.keystore.oaepPresent = true
        h.migration().ensureMigrated()
        assertEquals(first, File(h.snapshotDir, "FlutterSecureStorage.xml").readText())
    }

    // ------------------------------------------------------------------ log redaction

    @Test
    fun importedSecretsAreRegisteredWithTheRedactorBeforeTheLogImport() = runBlocking {
        h.device.write(FlutterPrefs.FILE, mapOf("flutter.diagnostic_log" to "[]", "flutter.rqr_session_seed" to "ab".repeat(32)))
        h.writeE2(
            mapOf(
                "nostr_nsec_key" to alice.nsec,
                "nostr_npub_key" to alice.npub,
                "badge_encryption_key" to "c".repeat(64),
                "badge_encryption_key" + Fss.BACKUP to "d".repeat(64),
                "portal_api_token" to "1|token-value",
            ),
        )
        h.migration().ensureMigrated()
        assertEquals(1, h.importedLogs.size)
        val registered = h.registeredSecrets.toSet()
        for (v in listOf(alice.nsec, "c".repeat(64), "d".repeat(64), "1|token-value", "ab".repeat(32))) {
            assertTrue(v in registered)
        }
        assertFalse("npubs are public", alice.npub in registered)
    }

    // ------------------------------------------------------------------ cleanup release gate

    @Test
    fun cleanupWaitsForTheReleaseAfterTheFirstNativeOne() = runBlocking {
        h.writeE2(mapOf("badge_reset_pending" to "1"))
        repeat(3) { h.migration().ensureMigrated() }
        val m = h.migration().also { it.ensureMigrated() }
        assertEquals(CleanupOutcome.NotYet("RELEASE"), m.cleanupIfEligible())
        h.appVersion = 99 // downgrade/same build never qualifies
        assertEquals(CleanupOutcome.NotYet("RELEASE"), m.cleanupIfEligible())
        h.appVersion = 101
        assertEquals(CleanupOutcome.Cleaned, m.cleanupIfEligible())
    }

    // ------------------------------------------------------------------ E1_CBC end to end

    @Test
    fun e1CbcFallbackDeviceIsImported() = runBlocking {
        // 9.2.4 custom-CBC fallback: AES-128 key (fixture `fss_v9_cbc`) wrapped with RSA PKCS#1 under the v9 CBC name.
        // The fixture plaintext is a 65-char placeholder that the npub validator rejects, so real identity values are used.
        val cbcKey = Hex.decode(MigrationFixtures.vectors.obj("fss_v9_cbc").str("aes128KeyHex"))
        h.device.write(Fss.KEYS, mapOf(Fss.WRAPPED_V9_CBC to FakeLegacyKeystore.wrapB64(cbcKey, RsaMode.PKCS1)))
        h.device.write(
            Fss.DATA,
            mapOf(
                Fss.ENTRY + "nostr_nsec_key" to FssWriter.cbcEntry(cbcKey, alice.nsec),
                Fss.ENTRY + "nostr_npub_key" to FssWriter.cbcEntry(cbcKey, alice.npub),
                Fss.ENTRY + "badge_encryption_key" to FssWriter.cbcEntry(cbcKey, "a".repeat(64)),
                Fss.MARKER_STORAGE_ALGORITHM to "AES_CBC_PKCS7Padding",
            ),
        )
        h.keystore.oaepPresent = false // 9.2.4 wrapped its key with PKCS#1 only

        val r = h.migration().ensureMigrated()
        assertEquals(MigrationState.IMPORTED, r.state)
        assertEquals(setOf(StorageEra.E1_CBC), r.eras)
        assertEquals(alice.nsec, h.secure.read("nostr_nsec_key"))
        assertEquals(alice.npub, h.secure.read("nostr_npub_key"))
        assertEquals("a".repeat(64), h.secure.read("badge_encryption_key"))
    }
}
