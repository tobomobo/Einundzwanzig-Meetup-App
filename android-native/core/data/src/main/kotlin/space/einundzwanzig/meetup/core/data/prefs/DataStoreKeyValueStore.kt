package space.einundzwanzig.meetup.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import space.einundzwanzig.meetup.core.data.internal.DataLog
import java.io.IOException

/**
 * [KeyValueStore] on top of one Preferences [DataStore] (production file: `app_prefs`).
 *
 * One-shot read failures are logged and read as an empty store; write failures are logged and
 * reported as `false`. Because DataStore reads the file before every write, a store that cannot
 * be read cannot be overwritten either, so a transient read error never turns into data loss.
 *
 * The `observe*` flows never complete on an I/O error and never publish a fake empty state:
 * they keep the last value and retry with exponential backoff ([OBSERVE_RETRY_MAX_DELAY_MS]).
 */
internal class DataStoreKeyValueStore(
    private val dataStore: DataStore<Preferences>,
) : KeyValueStore {

    override suspend fun get(key: String): PrefValue? = PreferencesCodec.decode(snapshot(), key)

    override suspend fun getAll(): Map<String, PrefValue> = PreferencesCodec.decodeAll(snapshot())

    override suspend fun contains(key: String): Boolean = snapshot().asMap().keys.any { it.name == key }

    override suspend fun keys(): Set<String> = snapshot().asMap().keys.mapTo(LinkedHashSet()) { it.name }

    override suspend fun edit(block: KeyValueEditor.() -> Unit): Boolean = try {
        dataStore.edit { preferences -> PreferencesEditor(preferences).block() }
        true
    } catch (e: IOException) {
        DataLog.e(TAG, "Writing preferences failed", e)
        false
    }

    override fun observe(key: String): Flow<PrefValue?> =
        observedData().map { PreferencesCodec.decode(it, key) }.distinctUntilChanged()

    override fun observeAll(): Flow<Map<String, PrefValue>> =
        observedData().map(PreferencesCodec::decodeAll).distinctUntilChanged()

    private suspend fun snapshot(): Preferences = safeData().first()

    /** [DataStore.data] that survives I/O errors by re-subscribing after a backoff. */
    private fun observedData(): Flow<Preferences> = dataStore.data.retryWhen { cause, attempt ->
        if (cause !is IOException) return@retryWhen false
        DataLog.e(TAG, "Reading preferences failed (attempt ${attempt + 1}); retrying", cause)
        delay(observeRetryDelayMs(attempt))
        true
    }

    /** For one-shot reads only: an unreadable store reads as empty. */
    private fun safeData(): Flow<Preferences> = dataStore.data.catch { e ->
        if (e !is IOException) throw e
        DataLog.e(TAG, "Reading preferences failed", e)
        emit(emptyPreferences())
    }

    private class PreferencesEditor(private val preferences: MutablePreferences) : KeyValueEditor {
        override fun get(key: String): PrefValue? = PreferencesCodec.decode(preferences, key)

        override fun keys(): Set<String> = preferences.asMap().keys.mapTo(LinkedHashSet()) { it.name }

        override fun contains(key: String): Boolean = preferences.asMap().keys.any { it.name == key }

        override fun set(key: String, value: PrefValue) = PreferencesCodec.write(preferences, key, value)

        override fun remove(key: String) = PreferencesCodec.remove(preferences, key)

        override fun clear() = preferences.clear()
    }

    internal companion object {
        private const val TAG = "KeyValueStore"
        private const val OBSERVE_RETRY_BASE_DELAY_MS = 500L

        /** Upper bound of the retry delay of the `observe*` flows. */
        const val OBSERVE_RETRY_MAX_DELAY_MS = 30_000L

        /** 0.5 s, 1 s, 2 s, ... capped at [OBSERVE_RETRY_MAX_DELAY_MS]. */
        fun observeRetryDelayMs(attempt: Long): Long =
            minOf(OBSERVE_RETRY_MAX_DELAY_MS, OBSERVE_RETRY_BASE_DELAY_MS shl attempt.coerceIn(0, 6).toInt())
    }
}
