package space.einundzwanzig.meetup.feature.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.share.ShareHelper
import space.einundzwanzig.meetup.core.ui.share.ShareResult
import java.io.File

/** File handling of the share fallback (spec 08 §2.4 step 11). */
@RunWith(AndroidJUnit4::class)
class ShareBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File get() = ShareHelper.backupDir(context)

    @Test
    fun `older backups are deleted and the name is sanitized`() = runTest {
        File(dir, "old.21bkp").writeText("old")
        var shared: File? = null
        val result = shareBackup(context, "a/b\\c.21bkp", "data".toByteArray()) { file ->
            shared = file
            ShareResult.Success(null)
        }
        assertEquals(ShareResult.Success(null), result)
        assertEquals("a_b_c.21bkp", shared!!.name)
        assertEquals(dir.canonicalPath, shared!!.parentFile!!.canonicalPath)
        assertArrayEquals("data".toByteArray(), shared!!.readBytes())
        assertEquals(listOf("a_b_c.21bkp"), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun `dismissed share deletes the file`() = runTest {
        var shared: File? = null
        val result = shareBackup(context, "x.21bkp", "data".toByteArray()) { file ->
            shared = file
            assertTrue(file.exists())
            ShareResult.Dismissed
        }
        assertEquals(ShareResult.Dismissed, result)
        assertFalse(shared!!.exists())
    }
}
