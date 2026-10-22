package space.einundzwanzig.meetup.core.data.secure

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.data.TestDataStores
import space.einundzwanzig.meetup.core.data.internal.CorruptFileKeeper
import space.einundzwanzig.meetup.core.data.internal.CorruptionPolicy
import space.einundzwanzig.meetup.core.data.secure.SecureRead.Unreadable.Reason
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Robolectric tests of the production secure store on DataStore. Robolectric has no
 * `AndroidKeyStore`, so the cipher uses [SoftwareKeyProvider]; see its KDoc.
 */
@RunWith(AndroidJUnit4::class)
class DataStoreSecureStoreTest : SecureStoreContract() {

    private val stores = TestDataStores()

    override fun newStore(): SecureStore = storeOn(stores.newFile()).first

    @After
    fun tearDown() = stores.closeAll()

    private fun storeOn(
        file: File,
        keys: SoftwareKeyProvider = SoftwareKeyProvider(),
    ): Triple<SecureStore, DataStore<Preferences>, SoftwareKeyProvider> {
        val dataStore = stores.open(file, CorruptionPolicy.FAIL_KEEPING_COPY)
        return Triple(DataStoreSecureStore(dataStore, AesGcmSecretCipher(keys), Dispatchers.IO), dataStore, keys)
    }

    /** Counts decryptions, i.e. Keystore round trips in production. */
    private class CountingCipher(private val inner: SecretCipher) : SecretCipher {
        val opens = AtomicInteger()

        override fun seal(plaintext: ByteArray, associatedData: ByteArray) = inner.seal(plaintext, associatedData)

        override fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray {
            opens.incrementAndGet()
            return inner.open(sealed, associatedData)
        }
    }

    private fun reasonOf(read: SecureRead): SecureRead.Unreadable.Reason? = (read as? SecureRead.Unreadable)?.reason

    private suspend fun raw(dataStore: DataStore<Preferences>, key: String): String? =
        dataStore.data.first()[stringPreferencesKey(key)]

    @Test
    fun storesBase64OfIvCiphertextAndTagNotThePlaintext() = runTest {
        val (store, dataStore) = storeOn(stores.newFile())
        val secret = "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        store.write("nostr_nsec_key", secret)

        val stored = raw(dataStore, "nostr_nsec_key")!!
        assertFalse(stored.contains("nsec1"))
        val sealed = Base64.getDecoder().decode(stored)
        assertEquals(AesGcmSecretCipher.IV_BYTES + secret.length + AesGcmSecretCipher.TAG_BYTES, sealed.size)
    }

    @Test
    fun sameValueEncryptsDifferentlyEachTime() = runTest {
        val (store, dataStore) = storeOn(stores.newFile())
        store.write("a", "same")
        val first = raw(dataStore, "a")
        store.write("a", "same")
        assertNotEquals(first, raw(dataStore, "a"))
        assertEquals("same", store.read("a"))
    }

    @Test
    fun valuesSurviveReopeningWithTheSameKey() = runTest {
        val file = stores.newFile()
        val keys = SoftwareKeyProvider()
        storeOn(file, keys).first.write("badge_encryption_key", "c".repeat(64))
        stores.closeAll()

        val (reopened) = storeOn(file, keys)
        assertEquals("c".repeat(64), reopened.read("badge_encryption_key"))
    }

    @Test
    fun tamperedCiphertextIsUnreadableButStillPresent() = runTest {
        val (store, dataStore) = storeOn(stores.newFile())
        store.write("badge_encryption_key", "d".repeat(64))
        store.write("nostr_npub_key", "npub1ok")
        val sealed = Base64.getDecoder().decode(raw(dataStore, "badge_encryption_key"))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        dataStore.edit { it[stringPreferencesKey("badge_encryption_key")] = Base64.getEncoder().encodeToString(sealed) }

        val read = store.readChecked("badge_encryption_key")
        assertEquals(Reason.AUTHENTICATION_FAILED, reasonOf(read))
        assertTrue((read as SecureRead.Unreadable).isPermanent)
        assertNull(store.read("badge_encryption_key"))
        assertTrue("an unreadable entry still exists", store.containsKey("badge_encryption_key"))
        assertEquals(mapOf("nostr_npub_key" to "npub1ok"), store.readAll())
    }

    @Test
    fun ciphertextsAreBoundToTheirKeyName() = runTest {
        val (store, dataStore) = storeOn(stores.newFile())
        store.write("badge_encryption_key", "e".repeat(64))
        store.write("badge_pending_key", "f".repeat(64))
        val a = raw(dataStore, "badge_encryption_key")!!
        val b = raw(dataStore, "badge_pending_key")!!
        dataStore.edit {
            it[stringPreferencesKey("badge_encryption_key")] = b
            it[stringPreferencesKey("badge_pending_key")] = a
        }
        assertEquals(Reason.AUTHENTICATION_FAILED, reasonOf(store.readChecked("badge_encryption_key")))
        assertEquals(Reason.AUTHENTICATION_FAILED, reasonOf(store.readChecked("badge_pending_key")))
    }

    @Test
    fun garbageValueIsUnreadableAndMalformed() = runTest {
        val (store, dataStore, keys) = storeOn(stores.newFile())
        dataStore.edit { it[stringPreferencesKey("x")] = "%%% not base64 %%%" }
        dataStore.edit { it[stringPreferencesKey("y")] = Base64.getEncoder().encodeToString(ByteArray(10)) }
        // Authentic ciphertext whose plaintext is not UTF-8.
        val notUtf8 = AesGcmSecretCipher(keys).seal(byteArrayOf(0xC3.toByte(), 0x28), "einundzwanzig-secure-store/v1:z".encodeToByteArray())
        dataStore.edit { it[stringPreferencesKey("z")] = Base64.getEncoder().encodeToString(notUtf8) }
        assertEquals(Reason.MALFORMED, reasonOf(store.readChecked("x")))
        assertEquals(Reason.MALFORMED, reasonOf(store.readChecked("y")))
        assertEquals(Reason.MALFORMED, reasonOf(store.readChecked("z")))
        assertEquals(emptyMap<String, String>(), store.readAll())
    }

    @Test
    fun lostKeystoreKeyMakesOldEntriesUnreadableAndNewWritesWork() = runTest {
        val file = stores.newFile()
        storeOn(file, SoftwareKeyProvider()).first.write("nostr_nsec_key", "nsec1old")
        stores.closeAll()

        // A different key = the Keystore entry was wiped and re-created.
        val (store) = storeOn(file, SoftwareKeyProvider())
        val read = store.readChecked("nostr_nsec_key")
        assertEquals(Reason.AUTHENTICATION_FAILED, reasonOf(read))
        assertTrue((read as SecureRead.Unreadable).isPermanent)
        assertNull(store.read("nostr_nsec_key"))
        assertTrue(store.write("badge_encryption_key", "9".repeat(64)))
        assertEquals(mapOf("badge_encryption_key" to "9".repeat(64)), store.readAll())
    }

    @Test
    fun deletedKeystoreAliasIsReportedAsKeyMissing() = runTest {
        val file = stores.newFile()
        val keys = SoftwareKeyProvider()
        storeOn(file, keys).first.write("nostr_nsec_key", "nsec1old")
        stores.closeAll()

        keys.missing = true
        val (store) = storeOn(file, keys)
        val read = store.readChecked("nostr_nsec_key")
        assertEquals(Reason.KEY_MISSING, reasonOf(read))
        assertTrue((read as SecureRead.Unreadable).isPermanent)
    }

    @Test
    fun unavailableKeystoreFailsWritesWithoutTouchingStoredValues() = runTest {
        val file = stores.newFile()
        val (store, dataStore, keys) = storeOn(file)
        store.write("badge_encryption_key", "a".repeat(64))
        val before = raw(dataStore, "badge_encryption_key")
        // A second store on the same file has no decryption cache, like a fresh process.
        val fresh = DataStoreSecureStore(dataStore, AesGcmSecretCipher(keys), Dispatchers.IO)

        keys.unavailable = true
        assertFalse(store.write("badge_encryption_key", "b".repeat(64)))
        val read = fresh.readChecked("badge_encryption_key")
        assertEquals(Reason.KEYSTORE_UNAVAILABLE, reasonOf(read))
        assertFalse((read as SecureRead.Unreadable).isPermanent)
        assertTrue(keys.invalidations > 0)
        assertEquals(before, raw(dataStore, "badge_encryption_key"))
        assertEquals("already decrypted values need no Keystore", "a".repeat(64), store.read("badge_encryption_key"))

        keys.unavailable = false
        assertEquals("transient failures are not cached", "a".repeat(64), fresh.read("badge_encryption_key"))
    }

    @Test
    fun unchangedEntriesAreDecryptedOnlyOnce() = runTest {
        val file = stores.newFile()
        val keys = SoftwareKeyProvider()
        storeOn(file, keys).first.writeAll(mapOf("badges_encrypted" to "iv:ct", "badge_encryption_key" to "a".repeat(64)))
        stores.closeAll()

        val dataStore = stores.open(file, CorruptionPolicy.FAIL_KEEPING_COPY)
        val cipher = CountingCipher(AesGcmSecretCipher(keys))
        val store = DataStoreSecureStore(dataStore, cipher, Dispatchers.IO)

        assertEquals(2, store.readAll().size)
        assertEquals(2, cipher.opens.get())
        store.readAll()
        assertEquals("iv:ct", store.read("badges_encrypted"))
        assertEquals("cached", 2, cipher.opens.get())

        // Own writes are cached as written.
        assertTrue(store.write("badges_encrypted", "iv2:ct2"))
        assertEquals("iv2:ct2", store.read("badges_encrypted"))
        assertEquals(2, cipher.opens.get())

        // A ciphertext changed behind the store's back is decrypted again.
        val sealed = Base64.getDecoder().decode(raw(dataStore, "badge_encryption_key"))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        dataStore.edit { it[stringPreferencesKey("badge_encryption_key")] = Base64.getEncoder().encodeToString(sealed) }
        assertEquals(mapOf("badges_encrypted" to "iv2:ct2"), store.readAll())
        assertEquals(3, cipher.opens.get())
        // ...and its permanent failure is remembered too.
        assertEquals(Reason.AUTHENTICATION_FAILED, reasonOf(store.readChecked("badge_encryption_key")))
        assertEquals(3, cipher.opens.get())

        assertTrue(store.delete("badges_encrypted"))
        assertEquals(SecureRead.Absent, store.readChecked("badges_encrypted"))
        assertTrue(store.deleteAll())
        assertEquals(emptyMap<String, String>(), store.readAll())
    }

    @Test
    fun corruptFileIsNeverResetAndEveryCorruptVersionIsKept() = runTest {
        val file = stores.newFile()
        file.parentFile!!.mkdirs()
        val firstCorruption = byteArrayOf(0x0a, 0x7f, 0x01, 0x02, 0x03) // truncated protobuf
        file.writeBytes(firstCorruption)

        val (store) = storeOn(file)
        val read = store.readChecked("nostr_nsec_key")
        assertEquals("a corrupt store must not read as absent", Reason.STORAGE_CORRUPT, reasonOf(read))
        assertTrue((read as SecureRead.Unreadable).isPermanent)
        assertTrue(store.readChecked("badge_encryption_key") is SecureRead.Unreadable)
        assertFalse("no new secret can replace the lost one", store.write("nostr_nsec_key", "nsec1new"))
        assertFalse(store.deleteAll())
        assertTrue(store.readAllChecked().isFailure)
        assertTrue(store.containsKeyChecked("nostr_nsec_key").isFailure)
        assertTrue("the corrupt file itself is untouched", file.readBytes().contentEquals(firstCorruption))

        val copies = CorruptFileKeeper.copiesOf(file)
        assertEquals("repeated reads keep one copy of the same bytes", 1, copies.size)
        assertTrue(copies.single().readBytes().contentEquals(firstCorruption))
        stores.closeAll()

        // A second, different corruption must not overwrite the copy with the original data.
        val secondCorruption = byteArrayOf(0x0a, 0x7f, 0x09)
        file.writeBytes(secondCorruption)
        val (reopened) = storeOn(file)
        assertEquals(Reason.STORAGE_CORRUPT, reasonOf(reopened.readChecked("nostr_nsec_key")))
        val all = CorruptFileKeeper.copiesOf(file)
        assertEquals(2, all.size)
        assertTrue(all.any { it.readBytes().contentEquals(firstCorruption) })
        assertTrue(all.any { it.readBytes().contentEquals(secondCorruption) })
    }

    @Test
    fun writeAllIsAllOrNothing() = runTest {
        val (store, _, keys) = storeOn(stores.newFile())
        store.write("nostr_nsec_key", "nsec1old")
        keys.unavailable = true
        assertFalse(store.writeAll(mapOf("nostr_nsec_key" to "nsec1new", "nostr_npub_key" to "npub1new")))
        keys.unavailable = false
        assertEquals(mapOf("nostr_nsec_key" to "nsec1old"), store.readAll())
    }
}
