package space.einundzwanzig.meetup.core.data.internal

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.IOException

/** DataStore file names (`files/datastore/<name>.preferences_pb`). */
internal object DataStoreFiles {
    /** Non-secret preferences (KeyValueStore). */
    const val APP_PREFS = "app_prefs"

    /** Keystore-encrypted secrets (SecureStore). */
    const val SECURE_PREFS = "secure_prefs"
}

/** What a Preferences DataStore does when its file cannot be parsed. */
internal enum class CorruptionPolicy {
    /**
     * Keep a copy of the corrupt file, then start with an empty store. For settings that have
     * safe defaults (`app_prefs`): the app keeps working.
     */
    RESET_KEEPING_COPY,

    /**
     * Keep a copy of the corrupt file and keep failing: every read and write throws the
     * `CorruptionException` (an [IOException]). For secrets (`secure_prefs`): an empty store would
     * make callers see "absent" and generate new identity or badge keys, which spec 01 §4.4 and
     * spec 02 §4.2 forbid (Flutter `resetOnError = false`).
     */
    FAIL_KEEPING_COPY,
}

/**
 * Creates a Preferences DataStore for [file] that handles corruption according to [policy].
 *
 * Either way the corrupt bytes are copied to `<file>.corrupt-<epochMillis>` first. Copies are
 * never overwritten, and identical bytes are kept only once (a failing store sees the same
 * corruption on every read), so a later, different corruption cannot destroy the copy that
 * still holds the original data.
 */
internal fun createPreferencesDataStore(
    file: File,
    scope: CoroutineScope,
    policy: CorruptionPolicy,
): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { error ->
            val copy = CorruptFileKeeper.preserve(file)
            when (policy) {
                CorruptionPolicy.RESET_KEEPING_COPY -> {
                    DataLog.e(TAG, "Corrupt preferences file ${file.name}; copy: ${copy?.name}; starting empty", error)
                    emptyPreferences()
                }
                CorruptionPolicy.FAIL_KEEPING_COPY -> {
                    if (copy != null) {
                        DataLog.e(TAG, "Corrupt preferences file ${file.name}; copy: ${copy.name}; store stays unreadable", error)
                    }
                    throw error
                }
            }
        },
        scope = scope,
        produceFile = { file },
    )

private const val TAG = "DataStore"

/** Keeps never-overwritten copies of corrupt store files next to them. */
internal object CorruptFileKeeper {

    /** Prefix of every copy of [file]: `<name>.corrupt`. */
    fun copyPrefix(file: File): String = file.name + ".corrupt"

    /** Every copy kept for [file], oldest name first. */
    fun copiesOf(file: File): List<File> =
        file.parentFile?.listFiles { f -> f.name.startsWith(copyPrefix(file)) }.orEmpty().sortedBy { it.name }

    /**
     * Copies [file] to a new `<name>.corrupt-<epochMillis>[-n]` file unless a copy with the same
     * bytes already exists. Returns the newly created copy, or `null` if none was created (same
     * bytes already kept, file missing, or the copy failed; failures are logged).
     */
    @Synchronized
    fun preserve(file: File, nowMillis: Long = System.currentTimeMillis()): File? = try {
        if (!file.exists()) {
            null
        } else {
            val bytes = file.readBytes()
            val alreadyKept = copiesOf(file).any { it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }
            if (alreadyKept) null else writeNewCopy(file, bytes, nowMillis)
        }
    } catch (e: IOException) {
        DataLog.e(TAG, "Could not keep a copy of corrupt ${file.name}", e)
        null
    }

    private fun writeNewCopy(file: File, bytes: ByteArray, nowMillis: Long): File {
        val base = "${copyPrefix(file)}-$nowMillis"
        var target = File(file.parentFile, base)
        var n = 1
        while (target.exists()) target = File(file.parentFile, "$base-${n++}")
        // Not under the copy prefix: a half-written copy must never count as kept.
        val tmp = File(file.parentFile, ".${target.name}.tmp")
        tmp.writeBytes(bytes)
        // copyTo(overwrite = false) semantics: never replace an existing copy.
        if (target.exists() || !tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Could not create ${target.name}")
        }
        return target
    }
}
