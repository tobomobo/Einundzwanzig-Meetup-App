package space.einundzwanzig.meetup.core.data.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root: File by lazy { tmp.newFolder("files") }
    private val store: FileStore by lazy { FileStore.inDirectory(root, Dispatchers.IO) }
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class CacheEntry(val npub: String, val name: String? = null, val score: Int = 0)

    @Test
    fun writeAndReadBytesAndText() = runTest {
        val bytes = byteArrayOf(0, 1, 2, -1, 127)
        assertTrue(store.writeBytes("blob.bin", bytes))
        assertArrayEquals(bytes, store.readBytes("blob.bin"))
        assertTrue(store.writeText("note.txt", "Grüße ₿"))
        assertEquals("Grüße ₿", store.readText("note.txt"))
        assertTrue(store.exists("note.txt"))
    }

    @Test
    fun missingFileReadsAsNull() = runTest {
        assertNull(store.readBytes("nope.json"))
        assertNull(store.readText("dir/nope.json"))
        assertFalse(store.exists("nope.json"))
    }

    @Test
    fun overwriteReplacesContentAndLeavesNoTempFiles() = runTest {
        store.writeText("social/graph.json", "old content that is longer")
        store.writeText("social/graph.json", "new")
        assertEquals("new", store.readText("social/graph.json"))
        assertEquals(listOf("graph.json"), File(root, "social").list()!!.toList())
    }

    @Test
    fun nestedDirectoriesAreCreated() = runTest {
        assertTrue(store.writeText("a/b/c.json", "{}"))
        assertTrue(File(root, "a/b/c.json").isFile)
        assertEquals(File(root, "a/b/c.json"), store.resolve("a/b/c.json"))
    }

    @Test
    fun failedWriteReturnsFalseAndLeavesNoTempFile() = runTest {
        // The target is a non-empty directory, so the final rename fails.
        File(root, "busy/child").mkdirs()
        assertFalse(store.writeText("busy", "x"))
        assertTrue(File(root, "busy").isDirectory)
        assertEquals(listOf("busy"), root.list()!!.toList())
    }

    @Test
    fun deleteAndExists() = runTest {
        store.writeText("x.json", "1")
        assertTrue(store.delete("x.json"))
        assertFalse(store.exists("x.json"))
        assertTrue("deleting a missing file succeeds", store.delete("x.json"))
    }

    @Test
    fun listReturnsSortedRegularFilesWithoutTempFiles() = runTest {
        store.writeText("b.json", "b")
        store.writeText("a.json", "a")
        store.writeText("sub/c.json", "c")
        File(root, ".fs-a.json.123.tmp").writeText("stale")
        assertEquals(listOf("a.json", "b.json"), store.list())
        assertEquals(listOf("c.json"), store.list("sub"))
        assertEquals(emptyList<String>(), store.list("missing"))
    }

    @Test
    fun invalidPathsAreRejected() {
        listOf("", "/abs", "../escape", "a/../../b", "a//b", "./a", "a/", "a\\b").forEach { path ->
            assertThrows("path '$path'", IllegalArgumentException::class.java) { store.resolve(path) }
        }
    }

    @Test
    fun jsonRoundTrip() = runTest {
        val entries = listOf(CacheEntry("npub1a", "Alice", 3), CacheEntry("npub1b"))
        assertTrue(store.writeJson("names.json", ListSerializer(CacheEntry.serializer()), entries, json))
        assertEquals(entries, store.readJson("names.json", ListSerializer(CacheEntry.serializer()), json))
    }

    @Test
    fun undecodableJsonReadsAsNull() = runTest {
        store.writeText("broken.json", "{\"npub\":")
        store.writeText("wrong.json", "[1,2]")
        assertNull(store.readJson("broken.json", CacheEntry.serializer(), json))
        assertNull(store.readJson("wrong.json", CacheEntry.serializer(), json))
        assertNull(store.readJson("missing.json", CacheEntry.serializer(), json))
    }
}
