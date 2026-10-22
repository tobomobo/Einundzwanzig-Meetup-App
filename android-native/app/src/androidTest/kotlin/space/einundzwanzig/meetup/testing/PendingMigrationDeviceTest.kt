package space.einundzwanzig.meetup.testing

import android.os.SystemClock
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoints
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.MainActivity
import space.einundzwanzig.meetup.feature.onboarding.R as OnboardingR
import space.einundzwanzig.meetup.feature.settings.R as SettingsR

/** Runs through the real Activity/system splash, with a pending identity and exhausted retries. */
@RunWith(AndroidJUnit4::class)
class PendingMigrationDeviceTest {
    @Test fun unreadableIdentityCanOpenDiagnosticsBeforeTheSystemSplashTimeout(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPoints.get(context.applicationContext, DeviceParityEntryPoint::class.java)
        withTimeout(60_000) { entry.migration().gate.await() }
        val state = AtomicFile(File(context.noBackupFilesDir, "flutter_migration/state.json"))
        val original = state.readFully()
        try {
            val fixture = JSONObject(String(original, Charsets.UTF_8))
                .put("state", "IMPORTED_WITH_ERRORS")
                .put("attempts", 20)
                .put("finishedAt", System.currentTimeMillis())
                .put("failedKeys", JSONArray(listOf("nostr_nsec_key")))
                .put("importedSecrets", JSONArray())
                .put("abandoned", JSONArray())
            state.writeBytes(fixture.toString().toByteArray())
            assertEquals(setOf("nostr_nsec_key"), entry.migration().pendingLegacySecrets())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var diagnostics = ""
                var logTitle = ""
                scenario.onActivity {
                    diagnostics = it.getString(OnboardingR.string.native_onboarding_storage_diagnostics)
                    logTitle = it.getString(SettingsR.string.native_log_title)
                }
                // The previous keep condition hid this indefinitely; 20 s migration timeout never ran.
                var node: AccessibilityNodeInfo? = awaitText(diagnostics)
                while (node != null && !node.isClickable) node = node.parent
                assertTrue("Diagnostics action must be clickable", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                awaitText(logTitle)
            }
            Unit
        } finally {
            state.writeBytes(original)
        }
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            // Compose exposes virtual children but does not implement the provider's text-search
            // operation on every version. Traverse the real accessibility tree, like UiAutomator.
            findText(automation.rootInActiveWindow, text)?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Real Activity did not expose '$text' within 10 seconds")
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
        for (index in 0 until node.childCount) {
            findText(node.getChild(index), text)?.let { return it }
        }
        return null
    }

    private fun AtomicFile.writeBytes(bytes: ByteArray) {
        val stream = startWrite()
        try {
            stream.write(bytes)
            finishWrite(stream)
        } catch (e: Exception) {
            failWrite(stream)
            throw e
        }
    }
}
