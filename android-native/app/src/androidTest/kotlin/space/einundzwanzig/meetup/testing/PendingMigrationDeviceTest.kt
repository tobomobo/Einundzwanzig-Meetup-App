package space.einundzwanzig.meetup.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.system.Os
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoints
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.MainActivity
import space.einundzwanzig.meetup.data.identity.SecureStorageUnreadableException
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashViewModel
import space.einundzwanzig.meetup.feature.onboarding.R as OnboardingR
import space.einundzwanzig.meetup.feature.settings.R as SettingsR

/** Runs through the real Activity/system splash, with a pending identity and exhausted retries. */
@RunWith(AndroidJUnit4::class)
class PendingMigrationDeviceTest {
    @Test fun unreadableIdentityCanOpenDiagnosticsBeforeTheSystemSplashTimeout(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPoints.get(context.applicationContext, DeviceParityEntryPoint::class.java)
        withTimeout(60_000) { entry.migration().ensureMigrated() }
        val state = File(context.noBackupFilesDir, "flutter_migration/state.json")
        val original = state.readBytes()
        try {
            val fixture = JSONObject(String(original, Charsets.UTF_8))
                .put("state", "IMPORTED_WITH_ERRORS")
                .put("attempts", 20)
                .put("finishedAt", System.currentTimeMillis())
                .put("failedKeys", JSONArray(listOf("nostr_nsec_key")))
                .put("importedSecrets", JSONArray())
                .put("abandoned", JSONArray())
            state.replaceFixture(fixture.toString().toByteArray())
            assertEquals(setOf("nostr_nsec_key"), entry.migration().pendingLegacySecrets())
            assertTrue("The production key store must see the pending fixture",
                runCatching { entry.keys().hasKey() }.exceptionOrNull() is SecureStorageUnreadableException)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
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
                } catch (error: Throwable) {
                    captureFailure(scenario, state)
                    throw error
                }
            }
        } finally {
            state.replaceFixture(original)
        }
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                // A fresh API 26 AVD shows the system's first immersive-mode explanation over
                // diagnostics. Confirm that specific window, as a user would; keep the deadline
                // and the actual app accessibility assertions unchanged.
                automation.windows.firstOrNull {
                    it.title?.toString() == "ImmersiveModeConfirmation" ||
                        it.root?.findResource("com.android.systemui:id/immersive_cling_title") != null
                }?.root?.findResource("android:id/ok", "com.android.systemui:id/ok")
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                // Compose exposes virtual children but does not implement the provider's text-search
                // operation on every version. Traverse the real accessibility tree, like UiAutomator.
                findText(automation.rootInActiveWindow, text)?.let { return it }
                SystemClock.sleep(100)
            }
            throw AssertionError("Real Activity did not expose '$text' within 10 seconds")
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    private fun AccessibilityNodeInfo.findResource(vararg ids: String): AccessibilityNodeInfo? {
        if (viewIdResourceName in ids && isVisibleToUser) return this
        for (index in 0 until childCount) {
            getChild(index)?.findResource(*ids)?.let { return it }
        }
        return null
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
        for (index in 0 until node.childCount) {
            findText(node.getChild(index), text)?.let { return it }
        }
        return null
    }

    private fun File.replaceFixture(bytes: ByteArray) {
        // A second AtomicFile would share production's .new name without its monitor. Its
        // openRead() can delete that in-progress fixture. Use a distinct temp name and one
        // filesystem rename, so concurrent readers always open a complete old or new report.
        val temp = File.createTempFile("state-device-fixture-", ".tmp", parentFile)
        try {
            FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
            Os.rename(temp.path, path)
        } finally {
            temp.delete()
        }
    }

    private fun captureFailure(scenario: ActivityScenario<MainActivity>, state: File) {
        // Save evidence before ActivityScenario closes the error screen. Only synthetic test
        // state and the currently visible UI are recorded; no secure-store values are read.
        runCatching {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "device-failures").apply { mkdirs() }
            val details = StringBuilder()
            scenario.onActivity { activity ->
                details.appendLine("splash=${ViewModelProvider(activity)[SplashViewModel::class.java].state.value}")
                details.appendLine("windowFocus=${activity.hasWindowFocus()} configuration=${activity.resources.configuration}")
            }
            details.appendLine("migration=${state.readText()}")
            fun tree(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null) return
                details.appendLine("${" ".repeat(depth)}${node.packageName} ${node.className} visible=${node.isVisibleToUser} text=${node.text} description=${node.contentDescription}")
                for (index in 0 until node.childCount) tree(node.getChild(index), depth + 1)
            }
            val automation = instrumentation.uiAutomation
            tree(automation.rootInActiveWindow, 0)
            File(directory, "pending-migration.txt").writeText(details.toString())
            automation.takeScreenshot()?.let { bitmap ->
                try {
                    FileOutputStream(File(directory, "pending-migration.png")).use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
            }
        }
    }
}
