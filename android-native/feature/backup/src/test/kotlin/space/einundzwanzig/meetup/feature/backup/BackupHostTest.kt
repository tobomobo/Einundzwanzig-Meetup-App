package space.einundzwanzig.meetup.feature.backup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarVisuals
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.backup.RestoreOutcome

/** [BackupHost] itself: platform attach/detach, SAF result routing and the snackbars. */
@RunWith(AndroidJUnit4::class)
class BackupHostTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val h = Harness(scope, Dispatchers.Unconfined)
    private val snackbar = SnackbarHostState()
    private var mounted by mutableStateOf(true)

    @After
    fun tearDown() = scope.cancel()

    private fun mount() {
        val vm = BackupHostViewModel(h.coordinator)
        rule.setContent { EinundzwanzigTheme { if (mounted) BackupHost(snackbar, vm) } }
        rule.waitForIdle()
    }

    private val visuals: AppSnackbarVisuals? get() = snackbar.currentSnackbarData?.visuals as? AppSnackbarVisuals

    /** Starts a restore and answers the real `OpenDocument` launch with [uri]. */
    private fun restoreWith(uri: String): () -> Boolean? {
        var result: Boolean? = null
        h.coordinator.launchRestore { result = it }
        rule.waitForIdle()
        val started = shadowOf(rule.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        shadowOf(rule.activity).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(Uri.parse(uri)))
        return { result }
    }

    @Test
    fun `attaches while mounted and detaches when removed`() {
        mount()
        assertTrue(h.coordinator.isAttached)
        mounted = false
        rule.waitForIdle()
        assertFalse(h.coordinator.isAttached)

        var result: Boolean? = null
        h.coordinator.launchRestore { result = it }
        rule.waitUntil { result != null }
        assertEquals(false, result)
        assertTrue(h.log.lines.any { it.contains("keine Oberfläche") })
    }

    @Test
    fun `picker result reaches the flow and shows the merge snackbar`() {
        h.documents.files["content://backup/9"] = VALID_BACKUP
        h.engine.outcome = RestoreOutcome.Merged(2)
        mount()
        val result = restoreWith("content://backup/9")

        rule.waitUntil { result() != null }
        assertEquals(true, result())
        rule.waitUntil { visuals != null }
        assertEquals("2 Badges hinzugefügt", visuals!!.message)
        assertEquals(Tone.Success, visuals!!.tone)
        assertEquals(BackupMessages.DEFAULT_DURATION_MILLIS, visuals!!.durationMillis)
    }

    @Test
    fun `unreadable file shows the red import snackbar`() {
        mount()
        val result = restoreWith("content://backup/missing")

        rule.waitUntil { result() != null }
        assertEquals(false, result())
        rule.waitUntil { visuals != null }
        assertEquals("Import fehlgeschlagen: Die Datei konnte nicht gelesen werden.", visuals!!.message)
        assertEquals(Tone.Error, visuals!!.tone)
    }

    @Test
    fun `cancelled picker ends the flow silently`() {
        mount()
        var result: Boolean? = null
        h.coordinator.launchRestore { result = it }
        rule.waitForIdle()
        val started = shadowOf(rule.activity).nextStartedActivityForResult
        shadowOf(rule.activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        rule.waitUntil { result != null }
        assertEquals(false, result)
        assertEquals(null, snackbar.currentSnackbarData)
    }
}
