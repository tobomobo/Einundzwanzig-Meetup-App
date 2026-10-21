package space.einundzwanzig.meetup.core.data.secure

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore

/** The test double: same contract, plus the Dart test platforms' per-key failure injection. */
class InMemorySecureStoreTest : SecureStoreContract() {

    override fun newStore(): SecureStore = InMemorySecureStore()

    @Test
    fun initialValuesAreVisible() = runTest {
        val store = InMemorySecureStore(mapOf("nostr_nsec_key" to "nsec1x"))
        assertEquals("nsec1x", store.read("nostr_nsec_key"))
    }

    @Test
    fun brokenReadReportsUnreadableButReadAllStillWorks() = runTest {
        // Dart badge_key_retention_test `_BrokenRead`: read throws for every key, readAll does not.
        val store = InMemorySecureStore(mapOf("badge_encryption_key" to "a".repeat(64)))
        store.failRead = { true }
        val unreadable = store.readChecked("badge_encryption_key") as SecureRead.Unreadable
        assertEquals(SecureRead.Unreadable.Reason.STORAGE_IO, unreadable.reason)
        assertFalse(unreadable.isPermanent)
        assertNull(store.read("badge_encryption_key"))
        assertEquals(mapOf("badge_encryption_key" to "a".repeat(64)), store.readAll())
        assertTrue(store.containsKey("badge_encryption_key"))
    }

    @Test
    fun brokenReadFailsAbsentKeysTooSoNoNewKeyLooksSafe() = runTest {
        // Dart 'ein Lese-Fehler schreibt keinen neuen Schlüssel' runs on an EMPTY secure map.
        val store = InMemorySecureStore()
        store.failRead = { true }
        assertTrue(store.readChecked("badge_encryption_key") is SecureRead.Unreadable)
        assertTrue(store.readChecked("missing") is SecureRead.Unreadable)
        assertNull(store.read("missing"))
    }

    @Test
    fun failReadReasonIsConfigurable() = runTest {
        val store = InMemorySecureStore(mapOf("nostr_nsec_key" to "nsec1x"))
        store.failRead = { it == "nostr_nsec_key" }
        store.failReadReason = SecureRead.Unreadable.Reason.KEY_MISSING
        val unreadable = store.readChecked("nostr_nsec_key") as SecureRead.Unreadable
        assertEquals(SecureRead.Unreadable.Reason.KEY_MISSING, unreadable.reason)
        assertTrue(unreadable.isPermanent)
        assertEquals(SecureRead.Absent, store.readChecked("other"))
    }

    @Test
    fun brokenStoreFailsCheckedBulkReadsAndLenientOnesReadEmpty() = runTest {
        val store = InMemorySecureStore(mapOf("badge_encryption_key" to "a".repeat(64)))
        store.failReadAll = true
        assertTrue(store.readAllChecked().isFailure)
        assertTrue(store.containsKeyChecked("badge_encryption_key").isFailure)
        assertEquals(emptyMap<String, String>(), store.readAll())
        assertFalse(store.containsKey("badge_encryption_key"))
        store.failReadAll = false
        assertEquals(Result.success(true), store.containsKeyChecked("badge_encryption_key"))
    }

    @Test
    fun unwritableKeyFailsOnlyThatKey() = runTest {
        // Dart badge_storage_copy_test `_CopyUnwritable`.
        val store = InMemorySecureStore()
        store.failWrite = { it == "badges_encrypted" }
        assertFalse(store.write("badges_encrypted", "iv:ct"))
        assertTrue(store.write("badge_encryption_key", "b".repeat(64)))
        assertFalse(store.containsKey("badges_encrypted"))
    }

    @Test
    fun undeletableKeyStays() = runTest {
        // Dart badge_storage_copy_test `_CopyUndeletable`.
        val store = InMemorySecureStore(mapOf("badges_encrypted" to "iv:ct", "other" to "x"))
        store.failDelete = { it == "badges_encrypted" }
        assertFalse(store.delete("badges_encrypted"))
        assertEquals("iv:ct", store.read("badges_encrypted"))
        assertFalse(store.deleteAll())
        assertEquals(mapOf("badges_encrypted" to "iv:ct", "other" to "x"), store.entries.value)
        assertTrue(store.delete("other"))
    }

    @Test
    fun writeAllWithOneUnwritableKeyWritesNothing() = runTest {
        val store = InMemorySecureStore()
        store.failWrite = { it == "nostr_priv_hex" }
        assertFalse(store.writeAll(mapOf("nostr_nsec_key" to "nsec1x", "nostr_priv_hex" to "0".repeat(64))))
        assertEquals(emptyMap<String, String>(), store.entries.value)
    }
}
