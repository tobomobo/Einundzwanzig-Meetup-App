package space.einundzwanzig.meetup.core.camera

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QrImageDecoderTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `unreadable content uri fails without throwing`() = runBlocking {
        val result = QrImageDecoder.decode(context, Uri.parse("content://missing/x"), Dispatchers.Unconfined)
        assertTrue(result.toString(), result is QrImageResult.Failed)
    }

    @Test
    fun `missing file fails without throwing`() = runBlocking {
        val uri = Uri.fromFile(File(context.cacheDir, "does-not-exist.jpg"))
        val result = QrImageDecoder.decode(context, uri, Dispatchers.Unconfined)
        assertTrue(result.toString(), result is QrImageResult.Failed)
    }

    @Test
    fun `non-image file fails without throwing`() = runBlocking {
        val file = File(context.cacheDir, "not-an-image.jpg").apply { writeText("hello") }
        val result = QrImageDecoder.decode(context, Uri.fromFile(file), Dispatchers.Unconfined)
        assertTrue(result.toString(), result is QrImageResult.Failed)
    }

    @Test
    fun `sample size keeps the long edge at most the limit`() {
        assertEquals(1, QrImageDecoder.sampleSizeFor(2048, 1536, 2048))
        assertEquals(2, QrImageDecoder.sampleSizeFor(4000, 3000, 2048))
        assertEquals(8, QrImageDecoder.sampleSizeFor(12_240, 16_320, 2048))
    }
}
