package space.einundzwanzig.meetup.core.data.secure

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** flutter_secure_storage semantics shared by every [SecureStore] implementation. */
abstract class SecureStoreContract {

    /** A fresh, empty store. */
    protected abstract fun newStore(): SecureStore

    @Test
    fun writeThenReadRoundTrips() = runTest {
        val store = newStore()
        assertTrue(store.write("nostr_priv_hex", "a".repeat(64)))
        assertEquals("a".repeat(64), store.read("nostr_priv_hex"))
        assertEquals(SecureRead.Present("a".repeat(64)), store.readChecked("nostr_priv_hex"))
    }

    @Test
    fun absentKeyReadsAsNullAndAbsent() = runTest {
        val store = newStore()
        assertNull(store.read("nostr_nsec_key"))
        assertEquals(SecureRead.Absent, store.readChecked("nostr_nsec_key"))
        assertFalse(store.containsKey("nostr_nsec_key"))
    }

    @Test
    fun overwriteReplacesValue() = runTest {
        val store = newStore()
        store.write("badge_encryption_key", "1".repeat(64))
        store.write("badge_encryption_key", "2".repeat(64))
        assertEquals("2".repeat(64), store.read("badge_encryption_key"))
    }

    @Test
    fun emptyAndUnicodeValuesRoundTrip() = runTest {
        val store = newStore()
        store.write("empty", "")
        store.write("unicode", "nsec1… Grüße ₿ 🧡")
        assertEquals("", store.read("empty"))
        assertTrue(store.containsKey("empty"))
        assertEquals("nsec1… Grüße ₿ 🧡", store.read("unicode"))
    }

    @Test
    fun largeValuesRoundTrip() = runTest {
        val store = newStore()
        // The badge store mirrors blobs and the 500 KiB archive here.
        val big = buildString { repeat(700 * 1024 / 8) { append("QUJDRA==") } }
        assertTrue(store.write("badges_former", big))
        assertEquals(big, store.read("badges_former"))
    }

    @Test
    fun deleteRemovesOnlyThatKey() = runTest {
        val store = newStore()
        store.write("nostr_nsec_key", "nsec1x")
        store.write("nip46_client_sk", "f".repeat(64))
        assertTrue(store.delete("nostr_nsec_key"))
        assertEquals(SecureRead.Absent, store.readChecked("nostr_nsec_key"))
        assertFalse(store.containsKey("nostr_nsec_key"))
        assertEquals("f".repeat(64), store.read("nip46_client_sk"))
        assertTrue("deleting an absent key succeeds", store.delete("nostr_nsec_key"))
    }

    @Test
    fun deleteAllRemovesEverything() = runTest {
        val store = newStore()
        store.write("a", "1")
        store.write("b", "2")
        assertTrue(store.deleteAll())
        assertEquals(emptyMap<String, String>(), store.readAll())
        assertFalse(store.containsKey("a"))
    }

    @Test
    fun readAllReturnsEveryEntrySortedByKey() = runTest {
        val store = newStore()
        store.write("nostr_npub_key", "npub1x")
        store.write("badge_pending_key", "b".repeat(64))
        store.write("badge_encryption_key", "a".repeat(64))
        val all = store.readAll()
        assertEquals(
            mapOf(
                "badge_encryption_key" to "a".repeat(64),
                "badge_pending_key" to "b".repeat(64),
                "nostr_npub_key" to "npub1x",
            ),
            all,
        )
        assertEquals(listOf("badge_encryption_key", "badge_pending_key", "nostr_npub_key"), all.keys.toList())
    }

    @Test
    fun writeAllStoresEveryEntry() = runTest {
        val store = newStore()
        store.write("nostr_nsec_key", "nsec1old")
        val keys = mapOf(
            "nostr_nsec_key" to "nsec1new",
            "nostr_npub_key" to "npub1new",
            "nostr_priv_hex" to "0".repeat(64),
        )
        assertTrue(store.writeAll(keys))
        assertEquals(keys, store.readAll())
        assertTrue(store.writeAll(emptyMap()))
    }

    @Test
    fun checkedBulkReadsSucceedOnAHealthyStore() = runTest {
        val store = newStore()
        store.write("nostr_npub_key", "npub1x")
        assertEquals(Result.success(true), store.containsKeyChecked("nostr_npub_key"))
        assertEquals(Result.success(false), store.containsKeyChecked("nostr_nsec_key"))
        assertEquals(mapOf("nostr_npub_key" to "npub1x"), store.readAllChecked().getOrThrow())
    }

    @Test
    fun presentNeverPrintsTheSecret() {
        assertEquals("Present(<redacted>)", SecureRead.Present("nsec1secret").toString())
    }
}
