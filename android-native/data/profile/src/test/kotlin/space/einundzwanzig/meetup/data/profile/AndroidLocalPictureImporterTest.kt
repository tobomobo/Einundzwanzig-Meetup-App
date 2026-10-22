package space.einundzwanzig.meetup.data.profile

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.data.profile.internal.DefaultLocalPictureImporter

/** Exercises the production decoder used after the home/profile photo picker returns. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidLocalPictureImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000L
    private val time = object : TimeSource {
        override fun nowMillis(): Long = now
    }
    private val profiles = Pictures()
    private val context: Context
        get() = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir(): File = tmp.root
        }

    private fun importer() = DefaultLocalPictureImporter(context, profiles, time, Dispatchers.Unconfined)

    private fun picture(name: String, width: Int, height: Int): String {
        val file = tmp.newFile(name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return Uri.fromFile(file).toString()
    }

    private fun dimensions(path: String): BitmapFactory.Options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, this)
    }

    @Test
    fun `gallery image is decoded scaled and saved as the local avatar`() = runTest {
        val saved = importer().import(picture("landscape.png", 800, 400))

        assertEquals(saved, profiles.stored)
        assertTrue(File(saved).isFile)
        val image = dimensions(saved)
        assertEquals(400, image.outWidth)
        assertEquals(200, image.outHeight)
        assertEquals("image/jpeg", image.outMimeType)
    }

    @Test
    fun `replacing an avatar preserves small image dimensions and removes the old copy`() = runTest {
        val previous = importer().import(picture("old.png", 800, 400))
        now = 2_000L
        val saved = importer().import(picture("portrait.png", 160, 240))

        assertEquals(saved, profiles.stored)
        assertFalse(File(previous).exists())
        val image = dimensions(saved)
        assertEquals(160, image.outWidth)
        assertEquals(240, image.outHeight)
    }

    @Test
    fun `unreadable gallery selection preserves the previous avatar`() = runTest {
        val previous = importer().import(picture("old.png", 80, 40))
        val bytes = File(previous).readBytes()
        val broken = tmp.newFile("broken.png").apply { writeText("not an image") }
        now = 2_000L

        try {
            importer().import(Uri.fromFile(broken).toString())
            fail("Expected unreadable image failure")
        } catch (_: IOException) {
        }

        assertEquals(previous, profiles.stored)
        assertTrue(bytes.contentEquals(File(previous).readBytes()))
        assertEquals(listOf(File(previous).name), File(tmp.root, LocalPictureImporter.DIR).list()!!.toList())
    }

    private class Pictures : NostrProfileService {
        var stored: String? = null
        override suspend fun fetchProfilePicture(pubkeyHex: String): String? = null
        override suspend fun fetchDisplayName(pubkeyHex: String): String? = null
        override suspend fun cachedDisplayNames(): Map<String, String> = emptyMap()
        override suspend fun fetchDisplayNames(pubkeysHex: Collection<String>): Map<String, String> = emptyMap()
        override suspend fun setLocalPicture(path: String) { stored = path }
        override suspend fun getLocalPicture(): String? = stored
        override suspend fun getExistingLocalPicture(): String? = stored
        override fun observeLocalPicture(): Flow<String?> = emptyFlow()
        override suspend fun clearCache() = Unit
    }
}
