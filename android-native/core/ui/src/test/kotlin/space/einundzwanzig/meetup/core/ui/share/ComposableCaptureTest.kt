package space.einundzwanzig.meetup.core.ui.share

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposableCaptureTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `share card size is rendered at pixel ratio 3 and the helper view is removed`() {
        lateinit var capturer: ComposableCapturer
        rule.setContent { capturer = rememberComposableCapturer() }
        rule.waitForIdle()
        val childrenBefore = (rule.activity.window.decorView as android.view.ViewGroup).childCount

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val result: Deferred<Bitmap> = scope.async {
            capturer.capture(width = ShareCardWidth, height = ShareCardHeight, pixelRatio = 3f, settleMillis = 0) {
                Box(Modifier.fillMaxSize().background(Color(0xFFF7931A)))
            }
        }
        var rounds = 0
        while (!result.isCompleted && rounds++ < 200) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
        }
        val bitmap = result.getCompleted()
        assertEquals(1080, bitmap.width)
        assertEquals(1680, bitmap.height)
        assertEquals(Color(0xFFF7931A).toArgb(), bitmap.getPixel(540, 840))
        assertEquals(childrenBefore, (rule.activity.window.decorView as android.view.ViewGroup).childCount)
        assertFalse(bitmap.isRecycled)
    }

    private companion object {
        val ShareCardWidth = androidx.compose.ui.unit.Dp(360f)
        val ShareCardHeight = androidx.compose.ui.unit.Dp(560f)
    }
}
