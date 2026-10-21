package space.einundzwanzig.meetup.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import space.einundzwanzig.meetup.core.data.internal.CorruptionPolicy
import space.einundzwanzig.meetup.core.data.internal.createPreferencesDataStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Creates production-configured Preferences DataStores in the Robolectric app's files dir and
 * shuts them down after each test (DataStore allows one active instance per file).
 */
internal class TestDataStores {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val jobs = mutableListOf<Job>()

    /** A fresh file name, unique within the test JVM. */
    fun newFile(): File = context.preferencesDataStoreFile("test_${COUNTER.incrementAndGet()}_${System.nanoTime()}")

    /**
     * Opens [file] with production corruption handling ([CorruptionPolicy.RESET_KEEPING_COPY] =
     * `app_prefs`, [CorruptionPolicy.FAIL_KEEPING_COPY] = `secure_prefs`) on an IO-dispatched scope.
     */
    fun open(file: File, policy: CorruptionPolicy = CorruptionPolicy.RESET_KEEPING_COPY): DataStore<Preferences> {
        val job = Job()
        jobs += job
        return createPreferencesDataStore(file, CoroutineScope(Dispatchers.IO + job), policy)
    }

    /** Shuts down every DataStore opened so far and waits until their files are released. */
    fun closeAll() = runBlocking {
        jobs.forEach { it.cancelAndJoin() }
        jobs.clear()
    }

    private companion object {
        val COUNTER = AtomicInteger()
    }
}
