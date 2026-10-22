package space.einundzwanzig.meetup.data.migration.legacy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.it_nomads.fluttersecurestorage.crypto.EncryptedSharedPreferences
import com.it_nomads.fluttersecurestorage.crypto.MasterKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/**
 * Spec 14 §4.11: actual AndroidKeyStore, RSA unwrap and Tink keysets, with the exact
 * EncryptedSharedPreferences writer vendored by flutter_secure_storage 10.3.4 in androidTest/java.
 * Those Apache-2.0 source files retain their implementation and license headers; only blank-line
 * whitespace is normalized.
 * Run only in the library's isolated instrumentation package, never against a user's app data.
 */
@RunWith(AndroidJUnit4::class)
class AndroidLegacyStorageTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files get() = SharedPrefsFiles(context)
    private val keystore get() = AndroidLegacyKeystore(context.packageName)
    private val reader get() = FlutterSecureStorageReader(files, keystore)

    @Before fun prepare() {
        check(context.packageName.contains("migration")) { "Requires isolated migration test package" }
        clean()
    }

    @After fun clean() {
        listOf(Fss.DATA, Fss.KEYS, Fss.CONFIG, Fss.LEGACY_CONFIG).forEach(context::deleteSharedPreferences)
        keystore.deleteLegacyAliases()
    }

    @Test fun flutterV9RsaPkcs1AndCbcReadWithActualAndroidKeyStore() {
        writeCustom(RsaMode.PKCS1, gcm = false, value = "ab".repeat(32))
        assertCustomValue("ab".repeat(32), LegacySecret.Source.CBC)
    }

    @Test fun flutterV10RsaOaepAndGcmReadWithoutRewritingFlutterFiles() {
        writeCustom(RsaMode.OAEP, gcm = true, value = "cd".repeat(32))
        val before = files.file(Fss.DATA).readBytes()
        val wrappedBefore = files.file(Fss.KEYS).readBytes()
        assertCustomValue("cd".repeat(32), LegacySecret.Source.GCM)
        assertArrayEquals(before, files.file(Fss.DATA).readBytes())
        assertArrayEquals(wrappedBefore, files.file(Fss.KEYS).readBytes())
    }

    @Test fun interruptedFlutterMigrationReadsBackupWithOldRsaKey() {
        writeCustom(RsaMode.PKCS1, gcm = false, value = "ef".repeat(32), backup = true)
        val data = reader.dataEntries()
        val result = reader.readCustomCipherEntries(data, reader.candidateKeys(null))
        assertTrue(result.failed.isEmpty())
        assertEquals("ef".repeat(32), result.backup.getValue("badge_encryption_key").value)
        assertEquals(LegacySecret.Source.CBC_BACKUP, result.backup.getValue("badge_encryption_key").source)
        assertTrue(result.main.isEmpty())
    }

    @Test fun flutterVendoredEspWriterIsReadWithRealKeystoreMasterKey() {
        writeEsp()
        val before = files.file(Fss.DATA).readBytes()
        val result = checkNotNull(reader.readEspEntries(reader.dataEntries()))
        assertTrue(result.readable)
        assertFalse(result.masterKeyMissing)
        assertTrue(result.failed.isEmpty())
        assertEquals("12".repeat(32), result.values["badge_encryption_key"])
        assertEquals("device-test-token", result.values["portal_api_token"])
        assertArrayEquals(before, files.file(Fss.DATA).readBytes())
    }

    @Test fun lostEspMasterKeyIsReportedAndNeverRegenerated() {
        writeEsp()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry(Fss.ESP_MASTER_ALIAS)
        val before = files.file(Fss.DATA).readBytes()
        val result = checkNotNull(reader.readEspEntries(reader.dataEntries()))
        assertFalse(result.readable)
        assertTrue(result.masterKeyMissing)
        assertTrue(result.values.isEmpty())
        assertFalse(ks.containsAlias(Fss.ESP_MASTER_ALIAS))
        assertArrayEquals(before, files.file(Fss.DATA).readBytes())
    }

    private fun writeEsp() {
        val master = MasterKey.Builder(context, Fss.ESP_MASTER_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(
            context, Fss.DATA, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        check(prefs.edit()
            .putString(Fss.ENTRY + "badge_encryption_key", "12".repeat(32))
            .putString(Fss.ENTRY + "portal_api_token", "device-test-token")
            .commit())
    }

    private fun writeCustom(mode: RsaMode, gcm: Boolean, value: String, backup: Boolean = false) {
        val alias = if (mode == RsaMode.OAEP) Fss.oaepAlias(context.packageName) else Fss.pkcs1Alias(context.packageName)
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
                .setEncryptionPaddings(if (mode == RsaMode.OAEP) KeyProperties.ENCRYPTION_PADDING_RSA_OAEP else KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=$alias"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(start.time).setCertificateNotAfter(end.time).build())
        }.generateKeyPair()
        val aes = SecretKeySpec(ByteArray(16).also(SecureRandom()::nextBytes), "AES")
        val rsa = Cipher.getInstance(if (mode == RsaMode.OAEP) "RSA/ECB/OAEPPadding" else "RSA/ECB/PKCS1Padding")
        if (mode == RsaMode.OAEP) rsa.init(Cipher.WRAP_MODE, pair.public, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        else rsa.init(Cipher.WRAP_MODE, pair.public)
        val suffix = if (backup) Fss.BACKUP else ""
        val keyName = (if (gcm) Fss.WRAPPED_V10_GCM else Fss.WRAPPED_V9_CBC) + suffix
        check(context.getSharedPreferences(Fss.KEYS, Context.MODE_PRIVATE).edit()
            .putString(keyName, Base64.encodeToString(rsa.wrap(aes), Base64.DEFAULT)).commit())
        val cipher = Cipher.getInstance(if (gcm) "AES/GCM/NoPadding" else "AES/CBC/PKCS7Padding").apply {
            init(Cipher.ENCRYPT_MODE, aes)
        }
        val stored = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(context.getSharedPreferences(Fss.DATA, Context.MODE_PRIVATE).edit()
            .putString(Fss.ENTRY + "badge_encryption_key" + suffix, Base64.encodeToString(stored, Base64.DEFAULT)).commit())
    }

    private fun assertCustomValue(expected: String, source: LegacySecret.Source) {
        val data = reader.dataEntries()
        val keys = reader.candidateKeys(reader.storageAlgorithmMarker(data))
        assertEquals(1, keys.size)
        val result = reader.readCustomCipherEntries(data, keys)
        assertTrue(result.failed.isEmpty())
        assertEquals(expected, result.main.getValue("badge_encryption_key").value)
        assertEquals(source, result.main.getValue("badge_encryption_key").source)
    }
}
