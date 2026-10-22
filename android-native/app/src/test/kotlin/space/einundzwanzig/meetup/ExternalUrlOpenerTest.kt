package space.einundzwanzig.meetup

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.navigation.ExternalUrlOpener

/** Spec 12 §2.10 / Security Audit M2: only http(s) links with a host leave the app. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class ExternalUrlOpenerTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        shadowOf(context).clearNextStartedActivities()
    }

    @Test
    fun `non-http schemes are refused, nothing is started and only the host is logged`() {
        for (url in listOf(
            "javascript:alert('secret-1')",
            "intent://scan/#Intent;scheme=zxing;package=x;end",
            "file:///data/data/space.einundzwanzig.meetup/shared_prefs/x.xml",
            "nostr:npub1xyz",
            "https:///no-host",
        )) {
            assertFalse(url, ExternalUrlOpener.open(context, url))
            assertNull(url, shadowOf(context).nextStartedActivity)
        }
        assertTrue(AppLogger.entries.value.none { it.message.contains("secret-1") || it.message.contains("shared_prefs") })
    }

    @Test
    fun `https opens a custom tab view intent`() {
        assertTrue(ExternalUrlOpener.open(context, "https://einundzwanzig.space/podcast/"))
        val started = shadowOf(context).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://einundzwanzig.space/podcast/", started.dataString)
        assertTrue("custom tab extras", started.hasExtra("android.support.customtabs.extra.SESSION"))
        assertTrue("new task outside an activity", started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
