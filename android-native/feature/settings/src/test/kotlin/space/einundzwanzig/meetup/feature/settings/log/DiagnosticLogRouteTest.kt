package space.einundzwanzig.meetup.feature.settings.log

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import space.einundzwanzig.meetup.core.common.log.LogExport
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.feature.settings.FakeDiagnosticLog
import space.einundzwanzig.meetup.feature.settings.FakeNip44Probe
import space.einundzwanzig.meetup.feature.settings.R
import space.einundzwanzig.meetup.feature.settings.entry

/** Route-level effects of the log screen: clipboard, Sharesheet, FileProvider, fallbacks. */
@RunWith(AndroidJUnit4::class)
class DiagnosticLogRouteTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val navigator = BackRecorder()

    private fun show(log: FakeDiagnosticLog, writeClip: ((Context, String) -> Unit)? = null) {
        val vm = DiagnosticLogViewModel(log, FakeNip44Probe())
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    if (writeClip == null) DiagnosticLogRoute(vm) else DiagnosticLogRoute(vm, writeClip)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun clipboardText(): String? =
        app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun awaitCopiedSnackbar() {
        val copied = app.getString(R.string.native_log_copied)
        rule.waitUntil(3_000) { rule.onAllNodesWithText(copied).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun nextChooser(): Intent? = shadowOf(rule.activity).nextStartedActivity

    @Test
    fun `copy writes the full export to the clipboard and shows Log kopiert`() {
        val log = FakeDiagnosticLog(listOf(entry("INFO", "System", "App 1.7.2"), entry("WARN", "Chat", "langsam")))
        show(log)
        click(DiagnosticLogTags.COPY)
        assertEquals(log.exportText(), clipboardText())
        awaitCopiedSnackbar()
    }

    @Test
    fun `share opens the Sharesheet with the log text and subject`() {
        val log = FakeDiagnosticLog(listOf(entry("ERROR", "Badge", "Signatur ungültig")))
        show(log)
        click(DiagnosticLogTags.SHARE)
        val chooser = nextChooser()
        assertNotNull(chooser)
        chooser!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("21Meetup Diagnose-Log", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(log.exportText(), send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `a large log is shared as a txt file through the FileProvider`() {
        val big = "x".repeat(LogExport.INLINE_TEXT_LIMIT_BYTES / 10 + 10)
        val log = FakeDiagnosticLog(List(12) { entry("INFO", "Big", big, time = 1_760_000_000_000L + it) })
        assertTrue(LogExport.shouldShareAsFile(log.exportText()))
        show(log)
        click(DiagnosticLogTags.SHARE)
        rule.waitUntil(3_000) { shadowOf(rule.activity).peekNextStartedActivity() != null }
        val chooser = nextChooser()!!
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.fileprovider", uri.authority)
        assertEquals("21Meetup Diagnose-Log", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `without a share target the log goes to the clipboard with Log kopiert`() {
        shadowOf(app).checkActivities(true)
        val log = FakeDiagnosticLog(listOf(entry("INFO", "System", "App 1.7.2")))
        show(log)
        click(DiagnosticLogTags.SHARE)
        assertEquals(log.exportText(), clipboardText())
        awaitCopiedSnackbar()
    }

    @Test
    fun `a clipboard failure shows an error snackbar instead of crashing`() {
        val log = FakeDiagnosticLog(listOf(entry("INFO", "System", "App 1.7.2")))
        show(log) { _, _ -> throw RuntimeException("android.os.TransactionTooLargeException") }
        click(DiagnosticLogTags.COPY)
        val failed = app.getString(R.string.native_log_copy_failed)
        rule.waitUntil(3_000) { rule.onAllNodesWithText(failed).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText(app.getString(R.string.native_log_copied)).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag(DiagnosticLogTags.COPY).assertExists()
    }

    @Test
    fun `the share fallback survives a clipboard failure too`() {
        shadowOf(app).checkActivities(true)
        val log = FakeDiagnosticLog(listOf(entry("INFO", "System", "App 1.7.2")))
        show(log) { _, _ -> throw RuntimeException("android.os.TransactionTooLargeException") }
        click(DiagnosticLogTags.SHARE)
        val failed = app.getString(R.string.native_log_copy_failed)
        rule.waitUntil(3_000) { rule.onAllNodesWithText(failed).fetchSemanticsNodes().isNotEmpty() }
    }
}

private class BackRecorder : Navigator {
    var backs = 0
    override fun navigate(key: NavKey) = Unit
    override fun replaceTop(key: NavKey) = Unit
    override fun back() {
        backs++
    }
    override fun backTo(key: NavKey, inclusive: Boolean) = Unit
    override fun resetTo(key: NavKey) = Unit
    override fun navigateFromRoot(key: NavKey) = Unit
    override fun openUrl(url: String) = Unit
}
