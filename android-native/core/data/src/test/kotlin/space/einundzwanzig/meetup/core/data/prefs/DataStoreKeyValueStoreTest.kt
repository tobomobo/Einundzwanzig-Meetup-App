package space.einundzwanzig.meetup.core.data.prefs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.data.TestDataStores
import space.einundzwanzig.meetup.core.data.internal.CorruptFileKeeper
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Contract + persistence tests for the DataStore-backed store (production `app_prefs` setup). */
@RunWith(AndroidJUnit4::class)
class DataStoreKeyValueStoreTest : KeyValueStoreContract() {

    private val stores = TestDataStores()

    override fun newStore(): KeyValueStore = DataStoreKeyValueStore(stores.open(stores.newFile()))

    @After
    fun tearDown() = stores.closeAll()

    @Test
    fun valuesSurviveReopening() = runTest {
        val file = stores.newFile()
        val first = DataStoreKeyValueStore(stores.open(file))
        first.setString("signing_mode", "nip46")
        first.setInt("badge_sync_last_gen", 42)
        first.setStringList("favorite_meetups", listOf("wien", "graz", "wien"))
        first.setDouble("rqr_session_lng", 9.14321)
        first.setBool("secure_migration_done", true)
        stores.closeAll()

        val reopened = DataStoreKeyValueStore(stores.open(file))
        assertEquals("nip46", reopened.getString("signing_mode"))
        assertEquals(42L, reopened.getInt("badge_sync_last_gen"))
        assertEquals(listOf("wien", "graz", "wien"), reopened.getStringList("favorite_meetups"))
        assertEquals(9.14321, reopened.getDouble("rqr_session_lng")!!, 0.0)
        assertEquals(true, reopened.getBool("secure_migration_done"))
    }

    @Test
    fun listsAreStoredAsTaggedJsonByteArrays() {
        val encoded = PreferencesCodec.encodeList(listOf("b", "a", "b"))
        assertEquals(PreferencesCodec.LIST_FORMAT_JSON, encoded[0])
        assertEquals("[\"b\",\"a\",\"b\"]", encoded.decodeToString(startIndex = 1))
        assertEquals(listOf("b", "a", "b"), PreferencesCodec.decodeList(encoded))
        assertNull("unknown format byte", PreferencesCodec.decodeList(byteArrayOf(0x02, '['.code.toByte(), ']'.code.toByte())))
        assertNull("not a JSON string array", PreferencesCodec.decodeList(byteArrayOf(0x01) + "[1,{}]".encodeToByteArray()))
        assertNull("empty", PreferencesCodec.decodeList(ByteArray(0)))
    }

    @Test
    fun corruptFileIsKeptAsideAndStoreStartsEmpty() = runTest {
        val file = stores.newFile()
        file.parentFile!!.mkdirs()
        val firstCorruption = byteArrayOf(0x0a, 0x7f, 0x01, 0x02, 0x03) // truncated protobuf
        file.writeBytes(firstCorruption)
        val store = DataStoreKeyValueStore(stores.open(file))

        assertEquals(emptyMap<String, PrefValue>(), store.getAll())
        val copy = CorruptFileKeeper.copiesOf(file).single()
        assertTrue(copy.name.startsWith(file.name + ".corrupt-"))
        assertTrue(copy.readBytes().contentEquals(firstCorruption))

        assertTrue("store is usable again", store.setString("a", "b"))
        assertEquals("b", store.getString("a"))
        stores.closeAll()

        // A later corruption gets its own copy; the first one is never overwritten.
        val secondCorruption = byteArrayOf(0x0a, 0x7f, 0x09)
        file.writeBytes(secondCorruption)
        assertEquals(emptyMap<String, PrefValue>(), DataStoreKeyValueStore(stores.open(file)).getAll())
        val copies = CorruptFileKeeper.copiesOf(file)
        assertEquals(2, copies.size)
        assertTrue(copies.any { it.readBytes().contentEquals(firstCorruption) })
        assertTrue(copies.any { it.readBytes().contentEquals(secondCorruption) })
    }

    @Test
    fun corruptCopiesGetUniqueNamesEvenInTheSameMillisecond() {
        val file = stores.newFile()
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1))
        val a = CorruptFileKeeper.preserve(file, nowMillis = 42)!!
        assertNull("same bytes are kept once", CorruptFileKeeper.preserve(file, nowMillis = 42))
        file.writeBytes(byteArrayOf(2))
        val b = CorruptFileKeeper.preserve(file, nowMillis = 42)!!
        assertEquals(file.name + ".corrupt-42", a.name)
        assertEquals(file.name + ".corrupt-42-1", b.name)
        assertTrue(a.readBytes().contentEquals(byteArrayOf(1)))
        assertTrue(b.readBytes().contentEquals(byteArrayOf(2)))
    }

    @Test
    fun observeSurvivesIoErrorsWithoutPublishingAnEmptyStore() = runTest {
        val backing = stores.open(stores.newFile())
        val writer = DataStoreKeyValueStore(backing)
        assertTrue(writer.setString("app_theme", "light"))
        val flaky = FlakyDataStore(backing)
        flaky.failures.set(2)

        DataStoreKeyValueStore(flaky).observeString("app_theme").test {
            // Before the fix the flow emitted the default (null) and completed after the error.
            assertEquals("light", awaitItem())
            assertTrue(writer.setString("app_theme", "dark"))
            assertEquals("dark", awaitItem())
            assertEquals("two failed collections + the retry that stays", 3, flaky.collections.get())
            assertTrue(writer.setString("app_theme", "system"))
            assertEquals("system", awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun observeRetryBackoffIsCapped() {
        assertEquals(500L, DataStoreKeyValueStore.observeRetryDelayMs(0))
        assertEquals(1_000L, DataStoreKeyValueStore.observeRetryDelayMs(1))
        assertEquals(30_000L, DataStoreKeyValueStore.observeRetryDelayMs(6))
        assertEquals(30_000L, DataStoreKeyValueStore.observeRetryDelayMs(Long.MAX_VALUE))
    }

    @Test
    fun failedWriteReturnsFalseAndKeepsOldValue() = runTest {
        val file = stores.newFile()
        val store = DataStoreKeyValueStore(stores.open(file))
        assertTrue(store.setString("badges_encrypted", "iv:ct"))
        val dir = file.parentFile!!
        assumeTrue("needs a non-root user to make the directory read-only", dir.setWritable(false))
        try {
            assertFalse(store.setString("badges_encrypted", "iv2:ct2"))
            assertFalse(store.edit { remove("badges_encrypted") })
            assertEquals("iv:ct", store.getString("badges_encrypted"))
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun containsSeesKeysOfEveryType() = runTest {
        val store = newStore()
        store.setStringList("l", emptyList())
        store.setDouble("d", 0.0)
        assertTrue(store.contains("l"))
        assertTrue(store.contains("d"))
        assertEquals(setOf("l", "d"), store.keys())
    }
}

/**
 * Delegates to [delegate]; the next [failures] collections emit the current preferences once and
 * then fail with an [IOException], like a transient read error of the store file.
 */
private class FlakyDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    val failures = AtomicInteger()
    val collections = AtomicInteger()

    override val data: Flow<Preferences> = flow {
        collections.incrementAndGet()
        if (failures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            emit(delegate.data.first())
            throw IOException("Injected read failure")
        }
        emitAll(delegate.data)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        delegate.updateData(transform)
}
