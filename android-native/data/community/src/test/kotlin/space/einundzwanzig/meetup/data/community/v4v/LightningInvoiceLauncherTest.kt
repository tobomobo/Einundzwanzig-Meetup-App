package space.einundzwanzig.meetup.data.community.v4v

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class LightningInvoiceLauncherTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun noWalletReturnsFalseAndStartsNothing() {
        assertFalse(LightningInvoiceLauncher.openWithChooser(app, "lnbc1"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun walletGetsChooserWithLightningUri() {
        val wallet = ComponentName("org.example.wallet", "org.example.wallet.Pay")
        shadowOf(app.packageManager).apply {
            addActivityIfNotPresent(wallet)
            addIntentFilterForActivity(
                wallet,
                IntentFilter(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addDataScheme("lightning")
                },
            )
        }

        assertTrue(LightningInvoiceLauncher.openWithChooser(app, "lnbc1"))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        assertEquals("Lightning-Wallet wählen", started.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
        @Suppress("DEPRECATION")
        val target = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals("lightning:lnbc1", target.dataString)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun plainOpenStartsViewIntent() {
        assertTrue(LightningInvoiceLauncher.open(app, "lnbc2"))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("lightning:lnbc2", started.dataString)
    }
}
