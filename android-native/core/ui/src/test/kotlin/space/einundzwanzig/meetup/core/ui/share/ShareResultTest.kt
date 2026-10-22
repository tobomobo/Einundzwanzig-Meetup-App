package space.einundzwanzig.meetup.core.ui.share

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Chooser outcome ("chosen" vs "dismissed", spec 14 §2 share_plus row, spec 08 step 12). */
@RunWith(AndroidJUnit4::class)
class ShareResultTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val target = ComponentName("org.example.mail", "org.example.mail.Compose")

    /** Strongly held: LifecycleRegistry only keeps a weak reference to its owner. */
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun resumedLifecycle(): LifecycleRegistry =
        owner.registry.apply { currentState = Lifecycle.State.RESUMED }

    /** FileProvider caches its path strategy statically, with the cache dir of an earlier test. */
    @Before
    fun clearFileProviderCache() {
        clearFileProviderPathCache()
    }

    @Test
    fun `receiver completes the waiting share with the chosen component`() {
        val (token, chosen) = ShareChoices.register()
        val intent = Intent().putExtra(ShareChooserReceiver.EXTRA_TOKEN, token).putExtra(Intent.EXTRA_CHOSEN_COMPONENT, target)
        ShareChooserReceiver().onReceive(app, intent)
        assertTrue(chosen.isCompleted)
        assertEquals(target, chosen.getCompleted())
        ShareChoices.unregister(token)
    }

    @Test
    fun `callback pending intent targets the non exported receiver explicitly`() {
        val pending = ShareChoices.callback(app, 7)
        val sent = shadowOf(pending).savedIntent
        assertEquals(ShareChooserReceiver::class.java.name, sent.component!!.className)
        assertEquals(7, sent.getIntExtra(ShareChooserReceiver.EXTRA_TOKEN, -1))
        assertTrue(shadowOf(pending).isBroadcast)
    }

    @Test
    fun `choice while the chooser is open is a success`() = runTest {
        val lifecycle = resumedLifecycle()
        val (token, chosen) = ShareChoices.register()
        val result = async { ShareChoices.awaitChoice(lifecycle, chosen) }
        runCurrent()
        lifecycle.currentState = Lifecycle.State.STARTED
        runCurrent()
        ShareChoices.onChosen(token, target)
        runCurrent()
        assertEquals(ShareResult.Success(target), result.await())
        ShareChoices.unregister(token)
    }

    @Test
    fun `chooser closed without a choice is dismissed`() = runTest {
        val lifecycle = resumedLifecycle()
        val (token, chosen) = ShareChoices.register()
        val result = async { ShareChoices.awaitChoice(lifecycle, chosen) }
        runCurrent()
        lifecycle.currentState = Lifecycle.State.CREATED
        runCurrent()
        lifecycle.currentState = Lifecycle.State.RESUMED
        advanceTimeBy(ShareChoices.GRACE_MS + 1)
        assertEquals(ShareResult.Dismissed, result.await())
        assertFalse(result.await().isNotDismissed)
        ShareChoices.unregister(token)
    }

    @Test
    fun `late broadcast inside the grace period still counts`() = runTest {
        val lifecycle = resumedLifecycle()
        val (token, chosen) = ShareChoices.register()
        val result = async { ShareChoices.awaitChoice(lifecycle, chosen) }
        runCurrent()
        lifecycle.currentState = Lifecycle.State.STARTED
        runCurrent()
        lifecycle.currentState = Lifecycle.State.RESUMED
        advanceTimeBy(ShareChoices.GRACE_MS / 2)
        ShareChoices.onChosen(token, null)
        runCurrent()
        assertEquals(ShareResult.Success(null), result.await())
        ShareChoices.unregister(token)
    }

    @Test
    fun `activity never covered is unavailable, which counts as shared`() = runTest {
        val lifecycle = resumedLifecycle()
        val (token, chosen) = ShareChoices.register()
        val result = async { ShareChoices.awaitChoice(lifecycle, chosen) }
        advanceTimeBy(ShareChoices.LEAVE_TIMEOUT_MS + 1)
        assertEquals(ShareResult.Unavailable, result.await())
        assertTrue(result.await().isNotDismissed)
        ShareChoices.unregister(token)
    }

    @Test
    fun `backup dir is exposed through the file provider`() {
        val file = java.io.File(ShareHelper.backupDir(app), "x.21bkp").apply { writeText("x") }
        val uri = ShareHelper.uriFor(app, file)
        assertEquals("content://${app.packageName}.fileprovider/backup/x.21bkp", uri.toString())
    }
}

/** Clears `FileProvider`'s static path-strategy cache (Robolectric gives every test a new cache dir). */
internal fun clearFileProviderPathCache() {
    val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
    field.isAccessible = true
    (field.get(null) as MutableMap<*, *>).clear()
}
