package space.einundzwanzig.meetup.core.ui.security

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** Security Audit M3: FLAG_SECURE while secrets are visible, auto-clearing secret clipboard. */
@RunWith(AndroidJUnit4::class)
class SecurityTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val clipboard: ClipboardManager get() = context.getSystemService(ClipboardManager::class.java)

    private fun isSecure(): Boolean =
        rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun `flag secure is set while a secret is shown and reference counted`() {
        var dialog by mutableStateOf(true)
        var screen by mutableStateOf(true)
        rule.setContent {
            if (screen) SecureScreenEffect()
            if (dialog) SecureScreenEffect()
        }
        rule.waitForIdle()
        assertTrue(isSecure())

        dialog = false
        rule.waitForIdle()
        assertTrue(isSecure())

        screen = false
        rule.waitForIdle()
        assertFalse(isSecure())
    }

    @Test
    fun `disabled effect does nothing`() {
        rule.setContent { SecureScreenEffect(enabled = false) }
        rule.waitForIdle()
        assertFalse(isSecure())
    }

    @Before
    fun foreground() {
        SecretClipboard.resetForTest()
        SecretClipboard.isInBackground = { false }
    }

    @After
    fun restore() {
        SecretClipboard.resetForTest()
        SecretClipboard.isInBackground = { false }
    }

    private fun clipText(): String? = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    @Test
    fun `secret copy is flagged sensitive and cleared after 45 s`() {
        SecretClipboard.copySecret(context, "nsec1geheim")
        assertEquals("nsec1geheim", clipText())
        val extras = clipboard.primaryClip!!.description.extras
        assertTrue(extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))

        shadowOf(Looper.getMainLooper()).idleFor(44, TimeUnit.SECONDS)
        assertEquals("nsec1geheim", clipText())
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertTrue(clipText().isNullOrEmpty())
    }

    @Test
    fun `clipboard changed by the user is left alone`() {
        SecretClipboard.copySecret(context, "nsec1geheim")
        SecretClipboard.copyPlain(context, "Einkaufsliste")
        shadowOf(Looper.getMainLooper()).idleFor(46, TimeUnit.SECONDS)
        assertEquals("Einkaufsliste", clipText())
    }

    @Test
    fun `plain copy is not sensitive`() {
        SecretClipboard.copyPlain(context, "npub1abc", label = "npub")
        assertEquals("npub1abc", clipText())
        assertNull(clipboard.primaryClip!!.description.extras)
    }

    @Test
    fun `timer in the background clears on the next resume within 10 minutes`() {
        SecretClipboard.isInBackground = { true }
        SecretClipboard.copySecret(context, "nsec1geheim")
        shadowOf(Looper.getMainLooper()).idleFor(46, TimeUnit.SECONDS)
        assertEquals("nsec1geheim", clipText())

        SecretClipboard.isInBackground = { false }
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MINUTES)
        SecretClipboard.onProcessResumed()
        assertTrue(clipText().isNullOrEmpty())
    }

    @Test
    fun `resume after the retry window leaves the clipboard alone`() {
        SecretClipboard.isInBackground = { true }
        SecretClipboard.copySecret(context, "nsec1geheim")
        shadowOf(Looper.getMainLooper()).idleFor(45 + 11 * 60, TimeUnit.SECONDS)
        SecretClipboard.isInBackground = { false }
        SecretClipboard.onProcessResumed()
        assertEquals("nsec1geheim", clipText())
    }

    @Test
    fun `resume before the due time does not clear early`() {
        SecretClipboard.copySecret(context, "nsec1geheim")
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        SecretClipboard.onProcessResumed()
        assertEquals("nsec1geheim", clipText())
        shadowOf(Looper.getMainLooper()).idleFor(36, TimeUnit.SECONDS)
        assertTrue(clipText().isNullOrEmpty())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `secure dialog properties force the secure window policy`() {
        assertEquals(SecureFlagPolicy.SecureOn, SecureWindows.dialogProperties().securePolicy)
        assertEquals(SecureFlagPolicy.SecureOn, SecureWindows.bottomSheetProperties().securePolicy)
    }

    @Test
    fun `secure dialog marks the activity window while shown`() {
        var open by mutableStateOf(true)
        rule.setContent {
            if (open) {
                SecureDialog(onDismissRequest = {}) { Text("nsec1…") }
            }
        }
        rule.waitForIdle()
        assertTrue(isSecure())
        open = false
        rule.waitForIdle()
        assertFalse(isSecure())
    }
}
