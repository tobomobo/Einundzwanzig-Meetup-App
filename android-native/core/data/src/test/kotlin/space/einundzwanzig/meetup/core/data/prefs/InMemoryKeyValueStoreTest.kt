package space.einundzwanzig.meetup.core.data.prefs

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore

/** The test double must behave like the production store, plus failure injection. */
class InMemoryKeyValueStoreTest : KeyValueStoreContract() {

    override fun newStore(): KeyValueStore = InMemoryKeyValueStore()

    @Test
    fun initialValuesAreVisible() = runTest {
        val store = InMemoryKeyValueStore(mapOf("signing_mode" to PrefValue.StringValue("amber")))
        assertEquals("amber", store.getString("signing_mode"))
    }

    @Test
    fun failAllWritesLeavesStoreUnchanged() = runTest {
        val store = InMemoryKeyValueStore()
        store.setString("a", "1")
        store.failAllWrites = true
        assertFalse(store.setString("a", "2"))
        assertFalse(store.remove("a"))
        assertFalse(store.clear())
        assertEquals("1", store.getString("a"))
        store.failAllWrites = false
        assertTrue(store.setString("a", "2"))
        assertEquals("2", store.getString("a"))
    }

    @Test
    fun failingKeyFailsTheWholeTransaction() = runTest {
        val store = InMemoryKeyValueStore()
        store.failingKeys = setOf("badges_encrypted")
        val ok = store.edit {
            setString("badges_encrypted_preserved", "p")
            setString("badges_encrypted", "x")
        }
        assertFalse(ok)
        assertNull(store.getString("badges_encrypted_preserved"))
        assertTrue(store.setString("other", "fine"))
    }

    @Test
    fun storedListsAreDefensiveCopies() = runTest {
        val store = InMemoryKeyValueStore()
        val list = mutableListOf("a")
        store.setStringList("l", list)
        list += "b"
        assertEquals(listOf("a"), store.getStringList("l"))
        assertEquals(PrefValue.StringListValue(listOf("a")), store.entries.value["l"])
    }
}
