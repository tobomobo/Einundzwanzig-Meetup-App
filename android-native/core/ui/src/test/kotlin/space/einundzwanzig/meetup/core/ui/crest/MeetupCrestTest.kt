package space.einundzwanzig.meetup.core.ui.crest

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Crest watermark/face behaviour of spec 03 §2.5. */
@RunWith(AndroidJUnit4::class)
class MeetupCrestTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeSource : MeetupCrestSource {
        val logos = MutableStateFlow<Map<String, String>>(emptyMap())
        var ensureCalls = 0
        val requested = mutableListOf<Pair<String, String?>>()

        override fun crestUrl(meetupName: String, imageUrl: String?): Flow<String?> {
            requested += meetupName to imageUrl
            return logos.map { imageUrl?.trim()?.takeIf { it.isNotEmpty() } ?: it[meetupName.substringBefore(',').trim()] }
        }

        override fun ensureLoaded() {
            ensureCalls++
        }
    }

    @Test
    fun `watermark without own image triggers the shared logo fetch`() {
        val source = FakeSource()
        rule.setContent {
            CompositionLocalProvider(LocalMeetupCrestSource provides source) {
                MeetupCrestWatermark("Wien, AT", Modifier.size(100.dp))
                MeetupCrestWatermark("Graz, AT", Modifier.size(100.dp), imageUrl = "https://x/y.png")
            }
        }
        rule.waitForIdle()
        assertEquals(1, source.ensureCalls)
        assertEquals(listOf("Wien, AT" to null, "Graz, AT" to "https://x/y.png"), source.requested)
    }

    @Test
    fun `face shows the fallback until a crest is known and never fetches itself`() {
        val source = FakeSource()
        rule.setContent {
            CompositionLocalProvider(LocalMeetupCrestSource provides source) {
                MeetupCrestFace("Wien, AT", imageUrl = null, modifier = Modifier.size(64.dp)) { Text("WI") }
            }
        }
        rule.onNodeWithText("WI").assertExists()
        assertEquals(0, source.ensureCalls)
    }

    @Test
    fun `watermark geometry follows the Flutter alignment`() {
        val side = CrestWatermarkGeometry.side(200f, 1.05f)
        assertEquals(210f, side, 1e-4f)
        val offset = CrestWatermarkGeometry.offset(200f, 300f, side)
        assertEquals((200f - 210f) * 1.15f / 2f, offset.x, 1e-3f)
        assertEquals((300f - 210f) * 0.75f / 2f, offset.y, 1e-3f)
    }

    @Test
    fun `initials of meetup names`() {
        assertEquals("BK", crestInitials("Bad Kissingen, DE"))
        assertEquals("WI", crestInitials("Wien"))
        assertEquals("?", crestInitials(" , AT"))
    }
}
