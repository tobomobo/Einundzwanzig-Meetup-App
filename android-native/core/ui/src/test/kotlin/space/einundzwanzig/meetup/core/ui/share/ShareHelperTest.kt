package space.einundzwanzig.meetup.core.ui.share

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShareHelperTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun clearFileProviderCache() {
        clearFileProviderPathCache()
    }

    @Test
    fun `file names stay inside the share dir`() {
        assertEquals("a_b_c.png", ShareHelper.sanitize("a/b\\c.png"))
        assertEquals("share.bin", ShareHelper.sanitize("  "))
    }

    @Test
    fun `png is written to cache share and exposed through the app file provider`() = runBlocking {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val uri = ShareHelper.writePng(app, bitmap, "einundzwanzig_reputation.png")
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.fileprovider", uri.authority)
        assertTrue(File(File(app.cacheDir, ShareHelper.SHARE_DIR), "einundzwanzig_reputation.png").length() > 0)
    }

    @Test
    fun `image share intent grants read access and carries subject and text`() {
        val uri = Uri.parse("content://${app.packageName}.fileprovider/share/x.png")
        assertTrue(ShareHelper.shareFile(app, uri, "image/png", subject = "Einundzwanzig Reputation", text = "Meine verifizierte Meetup-Reputation"))
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertEquals("Einundzwanzig Reputation", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("Meine verifizierte Meetup-Reputation", send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun `text share`() {
        assertTrue(ShareHelper.shareText(app, "Hallo", subject = "21Meetup Diagnose-Log"))
        @Suppress("DEPRECATION")
        val send = shadowOf(app).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("Hallo", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("21Meetup Diagnose-Log", send.getStringExtra(Intent.EXTRA_SUBJECT))
    }
}
