package space.einundzwanzig.meetup.data.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.data.profile.internal.DefaultLocalPictureImporter
import java.io.File
import java.io.IOException

/** File handling and ordering of the shared local picture import (spec 07 §8.8, spec 12 F14). */
class LocalPictureImporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000L
    private val time = object : TimeSource {
        override fun nowMillis(): Long = now
    }

    private class Profiles : NostrProfileService {
        var stored: String? = null
        var failSet = false
        override suspend fun fetchProfilePicture(pubkeyHex: String): String? = null
        override suspend fun fetchDisplayName(pubkeyHex: String): String? = null
        override suspend fun cachedDisplayNames(): Map<String, String> = emptyMap()
        override suspend fun fetchDisplayNames(pubkeysHex: Collection<String>): Map<String, String> = emptyMap()
        override suspend fun setLocalPicture(path: String) {
            if (failSet) throw IOException("prefs")
            stored = path
        }
        override suspend fun getLocalPicture(): String? = stored
        override suspend fun getExistingLocalPicture(): String? = stored
        override fun observeLocalPicture(): Flow<String?> = emptyFlow()
        override suspend fun clearCache() = Unit
    }

    private fun importer(profiles: Profiles, encode: (String, File) -> Unit = { _, f -> f.writeText("jpeg") }) =
        DefaultLocalPictureImporter(tmp.root, profiles, time, Dispatchers.Unconfined, encode)

    @Test
    fun `import stores the new file and then removes older pictures`() = runTest {
        val profiles = Profiles()
        val first = importer(profiles).import("content://a")
        now = 2_000L
        val second = importer(profiles).import("content://b")
        assertEquals(second, profiles.stored)
        assertTrue(File(second).isFile)
        assertFalse(File(first).exists())
        assertEquals(File(tmp.root, LocalPictureImporter.DIR), File(second).parentFile)
    }

    @Test
    fun `a migrated picture elsewhere in filesDir is removed, files outside are kept`() = runTest {
        val migrated = File(tmp.root, "profile/local_picture.png").apply { parentFile!!.mkdirs(); writeText("x") }
        val profiles = Profiles().apply { stored = migrated.absolutePath }
        importer(profiles).import("content://a")
        assertFalse(migrated.exists())

        val outside = tmp.newFolder("outside")
        val foreign = File(outside, "pic.jpg").apply { writeText("x") }
        val sandboxed = DefaultLocalPictureImporter(File(tmp.root, "files"), profiles, time, Dispatchers.Unconfined) { _, f -> f.writeText("j") }
        profiles.stored = foreign.absolutePath
        now = 3_000L
        sandboxed.import("content://b")
        assertTrue(foreign.exists())
    }

    @Test
    fun `failed setLocalPicture keeps the old picture and drops the new file`() = runTest {
        val profiles = Profiles()
        val first = importer(profiles).import("content://a")
        profiles.failSet = true
        now = 2_000L
        try {
            importer(profiles).import("content://b")
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(first, profiles.stored)
        assertTrue(File(first).isFile)
        assertEquals(listOf(File(first).name), File(tmp.root, LocalPictureImporter.DIR).list()!!.toList())
    }

    @Test
    fun `failed decode leaves pref and files untouched`() = runTest {
        val profiles = Profiles()
        val first = importer(profiles).import("content://a")
        now = 2_000L
        try {
            importer(profiles) { _, f -> f.writeText("partial"); throw IOException("Kein Bild") }.import("content://b")
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(first, profiles.stored)
        assertEquals(listOf(File(first).name), File(tmp.root, LocalPictureImporter.DIR).list()!!.toList())
    }
}
