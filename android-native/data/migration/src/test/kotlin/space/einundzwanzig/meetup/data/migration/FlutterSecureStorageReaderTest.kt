package space.einundzwanzig.meetup.data.migration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.crypto.Hex
import space.einundzwanzig.meetup.data.migration.legacy.FlutterSecureStorageReader
import space.einundzwanzig.meetup.data.migration.legacy.Fss
import space.einundzwanzig.meetup.data.migration.legacy.LegacyAesKey
import space.einundzwanzig.meetup.data.migration.legacy.LegacySecret
import space.einundzwanzig.meetup.data.migration.legacy.RsaMode
import space.einundzwanzig.meetup.data.migration.legacy.SecureKeyInventory
import space.einundzwanzig.meetup.data.migration.legacy.SharedPrefsFiles
import java.security.SecureRandom

/** Spec 14 §4.5 / §4.6.2–§4.6.4 / §4.11: flutter_secure_storage readers, fixtures `fss_v10_gcm`, `fss_v9_cbc`, `fss_constants`. */
@RunWith(AndroidJUnit4::class)
class FlutterSecureStorageReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = FlutterDevice(context)
    private val keystore = FakeLegacyKeystore()
    private val reader get() = FlutterSecureStorageReader(SharedPrefsFiles(context), keystore)
    private val v10 = MigrationFixtures.vectors.obj("fss_v10_gcm")
    private val v9 = MigrationFixtures.vectors.obj("fss_v9_cbc")
    private val v10Key = Hex.decode(v10.str("aes128KeyHex"))
    private val v9Key = Hex.decode(v9.str("aes128KeyHex"))

    @Before
    fun setUp() = device.reset()

    private fun key(bytes: ByteArray, name: String = Fss.WRAPPED_V10_GCM) = LegacyAesKey(bytes, name, RsaMode.OAEP)

    @Test
    fun constantsMatchFixture() {
        val c = MigrationFixtures.vectors.obj("fss_constants")
        assertEquals(c.str("entryPrefix"), Fss.PREFIX)
        assertEquals("shared_prefs/${Fss.DATA}.xml", c.str("dataFile"))
        assertEquals("shared_prefs/${Fss.KEYS}.xml", c.str("keyStorageFile"))
        assertEquals("shared_prefs/${Fss.CONFIG}.xml", c.str("configFile"))
        assertEquals("shared_prefs/${Fss.LEGACY_CONFIG}.xml", c.str("legacyGlobalConfigFile"))
        val w = c.obj("wrappedKeyPrefs")
        assertEquals(listOf(w.str("v10GcmKey"), w.str("v9GcmKeyTypo"), w.str("v9CbcKey")), Fss.WRAPPED_KEY_NAMES)
        assertEquals(c.str("backupSuffix"), Fss.BACKUP)
        assertEquals(c.str("migratedSuffix"), Fss.MIGRATED)
        assertEquals(c.obj("markers").str("storageAlgorithm"), Fss.MARKER_STORAGE_ALGORITHM)
        assertEquals(c.obj("keystoreAliases").str("espMasterKey"), Fss.ESP_MASTER_ALIAS)
        assertEquals("<pkg>.FlutterSecureStoragePluginKey", Fss.pkcs1Alias("<pkg>"))
        assertEquals("<pkg>.FlutterSecureStoragePluginKeyOAEP", Fss.oaepAlias("<pkg>"))
        assertEquals(listOf(Fss.ESP_KEY_KEYSET, Fss.ESP_VALUE_KEYSET), c.arr("espReservedEntries").map { it.jsonPrimitive.content })
    }

    @Test
    fun secureInventoryMatchesFixture() {
        val inv = MigrationFixtures.vectors.arr("secure_key_inventory").map { it.str("key") }
        assertEquals(19, inv.size)
        assertEquals(inv, SecureKeyInventory.KEYS.toList())
    }

    @Test
    fun v10GcmFixtureEntriesDecrypt() {
        device.write(Fss.DATA, v10.arr("entries").associate { it.str("xmlName") to it.str("storedValue") })
        val r = reader
        val custom = r.readCustomCipherEntries(r.dataEntries(), listOf(key(v9Key, Fss.WRAPPED_V9_CBC), key(v10Key)))
        for (e in v10.arr("entries")) {
            assertEquals(LegacySecret(e.str("plaintext"), LegacySecret.Source.GCM), custom.main[e.str("logicalKey")])
        }
        assertTrue(custom.failed.isEmpty())
        assertTrue(custom.backup.isEmpty())
    }

    @Test
    fun v9CbcFixtureEntryDecryptsOnlyWithTheRightKey() {
        // The fixture plaintext is a 65-char placeholder, not a valid npub (real ones have 63 chars), so the
        // strict §4.4 validator of nostr_npub_key rejects it. Store the same ciphertext under a key without a
        // format rule to check the CBC path byte-exactly, and check the validator rejection separately.
        device.write(
            Fss.DATA,
            mapOf(
                Fss.ENTRY + "unknown_key" to v9.str("storedValue"),
                Fss.ENTRY + v9.str("logicalKey") to v9.str("storedValue"),
            ),
        )
        val r = reader
        val ok = r.readCustomCipherEntries(r.dataEntries(), listOf(key(v10Key), key(v9Key, Fss.WRAPPED_V9_CBC)))
        assertEquals(LegacySecret(v9.str("plaintext"), LegacySecret.Source.CBC), ok.main["unknown_key"])
        assertNull(ok.main["nostr_npub_key"])
        assertEquals(listOf("nostr_npub_key"), ok.failed)
        val wrong = r.readCustomCipherEntries(r.dataEntries(), listOf(key(v10Key)))
        assertTrue(wrong.main.isEmpty())
        assertEquals(setOf("unknown_key", "nostr_npub_key"), wrong.failed.toSet())
    }

    @Test
    fun cbcEntryWithValidNpubIsAccepted() {
        val npub = space.einundzwanzig.meetup.core.crypto.KeyPair.fromPrivateKeyHex("5".repeat(64)).npub
        device.write(Fss.DATA, mapOf(Fss.ENTRY + "nostr_npub_key" to FssWriter.cbcEntry(v9Key, npub)))
        val r = reader
        assertEquals(LegacySecret(npub, LegacySecret.Source.CBC), r.readCustomCipherEntries(r.dataEntries(), listOf(key(v9Key))).main["nostr_npub_key"])
    }

    @Test
    fun cbcPlausibilityRejectsWrongKeysForNsec() {
        val nsec = space.einundzwanzig.meetup.core.crypto.KeyPair.fromPrivateKeyHex("6".repeat(64)).nsec
        val right = ByteArray(16).also(SecureRandom()::nextBytes)
        device.write(Fss.DATA, mapOf(Fss.ENTRY + "nostr_nsec_key" to FssWriter.cbcEntry(right, nsec)))
        val r = reader
        val data = r.dataEntries()
        // 2000 wrong keys: padding may pass by chance, strict UTF-8 + the nsec regex must not.
        val wrongKeys = List(2000) { key(ByteArray(16).also(SecureRandom()::nextBytes)) }
        val res = r.readCustomCipherEntries(data, wrongKeys)
        assertTrue(res.main.isEmpty())
        assertEquals(LegacySecret(nsec, LegacySecret.Source.CBC), r.readCustomCipherEntries(data, wrongKeys + key(right)).main["nostr_nsec_key"])
    }

    @Test
    fun backupEntriesAreSeparatedAndTagged() {
        val oldKey = ByteArray(16).also(SecureRandom()::nextBytes)
        device.write(
            Fss.DATA,
            linkedMapOf(
                Fss.ENTRY + "badge_encryption_key" to FssWriter.gcmEntry(v10Key, "a".repeat(64)),
                Fss.ENTRY + "badge_encryption_key" + Fss.BACKUP to FssWriter.cbcEntry(oldKey, "b".repeat(64)),
                Fss.ENTRY + "portal_api_token" + Fss.BACKUP to FssWriter.gcmEntry(v10Key, "1|tok"),
                Fss.ENTRY + "x" + Fss.MIGRATED to "true",
                Fss.MARKER_STORAGE_ALGORITHM to "AES_GCM_NoPadding",
            ),
        )
        val r = reader
        val res = r.readCustomCipherEntries(r.dataEntries(), listOf(key(v10Key), key(oldKey, Fss.WRAPPED_V9_CBC)))
        assertEquals(LegacySecret("a".repeat(64), LegacySecret.Source.GCM), res.main["badge_encryption_key"])
        assertEquals(LegacySecret("b".repeat(64), LegacySecret.Source.CBC_BACKUP), res.backup["badge_encryption_key"])
        assertEquals(LegacySecret("1|tok", LegacySecret.Source.GCM_BACKUP), res.backup["portal_api_token"])
        assertTrue(res.failed.isEmpty())
    }

    @Test
    fun candidateKeysUnwrapBothModesAndBackupNamesWithoutDuplicates() {
        val k1 = ByteArray(16).also(SecureRandom()::nextBytes)
        val k2 = ByteArray(16).also(SecureRandom()::nextBytes)
        device.write(
            Fss.KEYS,
            linkedMapOf(
                Fss.WRAPPED_V9_CBC to FakeLegacyKeystore.wrapB64(k2, RsaMode.PKCS1),
                Fss.WRAPPED_V10_GCM to FakeLegacyKeystore.wrapB64(k1, RsaMode.OAEP),
                Fss.WRAPPED_V10_GCM + Fss.BACKUP to FakeLegacyKeystore.wrapB64(k1, RsaMode.OAEP),
                Fss.WRAPPED_V9_GCM_TYPO to "not base64 !!!",
            ),
        )
        val keys = reader.candidateKeys(null)
        assertEquals(2, keys.size)
        assertTrue(keys[0].key.contentEquals(k1))
        assertEquals(RsaMode.OAEP, keys[0].rsa)
        assertTrue(keys[1].key.contentEquals(k2))
        assertEquals(RsaMode.PKCS1, keys[1].rsa)
        // A CBC marker puts the v9 CBC key first.
        assertEquals(Fss.WRAPPED_V9_CBC, reader.candidateKeys("AES_CBC_PKCS7Padding")[0].wrappedName)
        // Missing alias: nothing is unwrapped (and nothing is generated).
        keystore.oaepPresent = false
        keystore.pkcs1Present = false
        assertTrue(reader.candidateKeys(null).isEmpty())
    }

    @Test
    fun storageMarkerFromConfigFileOrDataFile() {
        assertNull(reader.storageAlgorithmMarker(emptyMap()))
        assertEquals("AES_CBC_PKCS7Padding", reader.storageAlgorithmMarker(mapOf(Fss.MARKER_STORAGE_ALGORITHM to "AES_CBC_PKCS7Padding")))
        device.write(Fss.CONFIG, mapOf(Fss.MARKER_STORAGE_ALGORITHM to "AES_GCM_NoPadding"))
        assertEquals("AES_GCM_NoPadding", reader.storageAlgorithmMarker(mapOf(Fss.MARKER_STORAGE_ALGORITHM to "AES_CBC_PKCS7Padding")))
    }

    // ------------------------------------------------------------------ ESP (E1, F1)

    @Test
    fun espEntriesInAMixedE1E2FileAreRead() {
        val esp = FssWriter.espEntries(
            keystore.masterAeadForWriting(),
            mapOf("nostr_nsec_key" to "nsec-old", "badge_encryption_key" to "c".repeat(64)),
        )
        device.write(
            Fss.DATA,
            esp + mapOf(
                Fss.ENTRY + "nostr_nsec_key" to FssWriter.gcmEntry(v10Key, "nsec-new"),
                Fss.MARKER_KEY_ALGORITHM to "RSA_ECB_PKCS1Padding",
            ),
        )
        val r = reader
        val data = r.dataEntries()
        val res = r.readEspEntries(data)!!
        assertTrue(res.readable)
        assertEquals(mapOf("nostr_nsec_key" to "nsec-old", "badge_encryption_key" to "c".repeat(64)), res.values)
        assertEquals(2, res.encryptedEntryCount)
        // The custom-cipher reader ignores the ESP entries.
        val custom = r.readCustomCipherEntries(data, listOf(key(v10Key)))
        assertEquals(mapOf("nostr_nsec_key" to LegacySecret("nsec-new", LegacySecret.Source.GCM)), custom.main)
        assertTrue(custom.failed.isEmpty())
    }

    @Test
    fun espWithoutKeysetsIsNull() {
        device.write(Fss.DATA, mapOf(Fss.ENTRY + "a" to "b"))
        assertNull(reader.readEspEntries(reader.dataEntries()))
    }

    @Test
    fun espWithLostMasterKeyIsUnreadableNotEmpty() {
        device.write(Fss.DATA, FssWriter.espEntries(keystore.masterAeadForWriting(), mapOf("nostr_nsec_key" to "x")))
        keystore.espMasterKey = null
        val res = reader.readEspEntries(reader.dataEntries())!!
        assertFalse(res.readable)
        assertEquals(1, res.encryptedEntryCount)
        assertTrue(res.values.isEmpty())
    }

    @Test
    fun espCleartextKeysetsWrittenWithoutKeystoreAreRead() {
        // AndroidKeysetManager stores keysets in cleartext when the Keystore was unusable.
        device.write(Fss.DATA, FssWriter.espEntries(null, mapOf("badge_pending_key" to "d".repeat(64))))
        keystore.espMasterKey = null
        assertEquals(mapOf("badge_pending_key" to "d".repeat(64)), reader.readEspEntries(reader.dataEntries())!!.values)
    }

    @Test
    fun espStringPayloadDecoding() {
        val bytes = "héllo".toByteArray()
        val p = java.nio.ByteBuffer.allocate(8 + bytes.size).putInt(0).putInt(bytes.size).put(bytes).array()
        assertEquals("héllo", FlutterSecureStorageReader.decodeEspString(p).get())
        val nul = "__NULL__".toByteArray()
        assertFalse(FlutterSecureStorageReader.decodeEspString(java.nio.ByteBuffer.allocate(16).putInt(0).putInt(8).put(nul).array()).isPresent)
        assertTrue(runCatching { FlutterSecureStorageReader.decodeEspString(java.nio.ByteBuffer.allocate(9).putInt(5).putInt(1).put(1).array()) }.isFailure)
    }

    @Test
    fun secretToStringNeverLeaksTheValue() {
        assertFalse(LegacySecret("nsec1secret", LegacySecret.Source.GCM).toString().contains("nsec1secret"))
        assertFalse(LegacyAesKey(byteArrayOf(1, 2), "n", RsaMode.OAEP).toString().contains("[1"))
    }

    @Test
    fun plausibilityTable() {
        val nsec = space.einundzwanzig.meetup.core.crypto.KeyPair.fromPrivateKeyHex("6".repeat(64)).nsec
        assertTrue(SecureKeyInventory.isPlausible("nostr_nsec_key", nsec))
        // The fixture's 65-char placeholder is flagged (GCM values are still imported, see SecretResolver).
        assertFalse(SecureKeyInventory.isPlausible("nostr_nsec_key", MigrationFixtures.vectors.obj("fss_v10_gcm").arr("entries")[0].str("plaintext")))
        assertFalse(SecureKeyInventory.isPlausible("nostr_nsec_key", "nsec1short"))
        assertTrue(SecureKeyInventory.isPlausible("badge_encryption_key", "  " + "A".repeat(64) + "\n"))
        assertFalse(SecureKeyInventory.isPlausible("badge_pending_key", " " + "a".repeat(64)))
        assertTrue(SecureKeyInventory.isPlausible("portal_api_token", "42|Zm9vYmFyYmF6"))
        assertFalse(SecureKeyInventory.isPlausible("portal_api_token", "a\u0001b"))
        assertTrue(SecureKeyInventory.isPlausible("badges_former", "[]"))
        assertFalse(SecureKeyInventory.isPlausible("badges_former", "{}"))
        assertTrue(SecureKeyInventory.isPlausible("badge_reset_pending", "1"))
        assertFalse(SecureKeyInventory.isPlausible("badge_reset_pending", "0"))
        assertTrue(SecureKeyInventory.isPlausible("easyauth_passkey_credential_id", "abc-_=="))
        assertTrue(SecureKeyInventory.isPlausible("badges_encrypted", MigrationFixtures.vectors.obj("badge_blob").str("blob")))
        // Every fixture validator is represented.
        val fixtureKeys = MigrationFixtures.vectors.arr("secure_key_inventory").map { it.str("key") }.toSet()
        assertEquals(fixtureKeys, SecureKeyInventory.KEYS)
    }
}
