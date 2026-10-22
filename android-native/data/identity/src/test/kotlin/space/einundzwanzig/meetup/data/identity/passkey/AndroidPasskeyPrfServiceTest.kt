package space.einundzwanzig.meetup.data.identity.passkey

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Direct calls must be safe for migrated passkey identities on the app's oldest Android APIs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27])
class AndroidPasskeyPrfServiceTest {
    private val service: AndroidPasskeyPrfService
        get() = AndroidPasskeyPrfService(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `passkeys are unavailable below Android 9`() = runTest {
        assertFalse(service.isSupported())
    }

    @Test
    fun `direct registration returns an unavailable error before contacting Credential Manager`() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertUnsupported {
            service.register(activity, "01".repeat(32), "Test")
        }
    }

    @Test
    fun `migrated credential unlock returns an unavailable error before contacting Credential Manager`() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertUnsupported {
            service.unlock(activity, listOf("bWlncmF0ZWQtY3JlZGVudGlhbA"))
        }
    }

    private suspend fun assertUnsupported(action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected PasskeyPrfException, got $failure", failure is PasskeyPrfException)
        failure as PasskeyPrfException
        assertEquals(PasskeyPrfException.UNSUPPORTED, failure.code)
        assertTrue(failure.isUnavailable)
    }
}
