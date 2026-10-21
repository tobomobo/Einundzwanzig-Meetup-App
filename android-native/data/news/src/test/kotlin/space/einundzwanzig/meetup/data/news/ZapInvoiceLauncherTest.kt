package space.einundzwanzig.meetup.data.news

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Wallet handoff of a zap invoice (spec 11 §2.4 step 4, §11). */
@RunWith(AndroidJUnit4::class)
class ZapInvoiceLauncherTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `intent is a plain ACTION_VIEW on lightning uri`() {
        val intent = ZapInvoiceLauncher.intent("lnbc1")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("lightning:lnbc1", intent.data.toString())
        assertNull(intent.component)
        assertFalse(intent.hasExtra(Intent.EXTRA_INTENT)) // not a chooser
    }

    @Test
    fun `no wallet installed - false`() {
        shadowOf(app).checkActivities(true)
        assertFalse(ZapInvoiceLauncher.open(app, "lnbc1"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `wallet installed - started with NEW_TASK from a non-Activity context`() {
        shadowOf(app).checkActivities(true)
        val wallet = ComponentName("com.example.wallet", "com.example.wallet.Pay")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(wallet)
        pm.addIntentFilterForActivity(
            wallet,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("lightning")
            },
        )
        assertTrue(ZapInvoiceLauncher.open(app, "lnbc1"))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("lightning:lnbc1", started.data.toString())
        assertNotEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `SecurityException from the wallet - false and logged to the given log`() {
        val log = testLog().apply { setVerbose(true) }
        val throwing = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent?) {
                throw SecurityException("Permission Denial")
            }
        }
        assertFalse(ZapInvoiceLauncher.open(throwing, "lnbc1", log))
        val entry = log.entries.value.single { it.tag == "NewsZap" }
        assertTrue(entry.message, entry.message.startsWith("Wallet-Start fehlgeschlagen"))
    }
}
