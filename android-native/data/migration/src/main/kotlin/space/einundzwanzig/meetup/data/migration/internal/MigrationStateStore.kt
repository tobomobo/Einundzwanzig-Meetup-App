package space.einundzwanzig.meetup.data.migration.internal

import android.util.AtomicFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.einundzwanzig.meetup.data.migration.MigrationReport
import space.einundzwanzig.meetup.data.migration.MigrationState
import space.einundzwanzig.meetup.data.migration.StorageEra
import java.io.File
import java.io.IOException

/**
 * `noBackupFilesDir/flutter_migration/state.json` (spec 14 §4.7). Holds names and counts only,
 * never values. Written with [AtomicFile] so a crash never leaves a half-written state.
 */
@Serializable
internal data class PersistedState(
    @SerialName("version") val version: Int = 1,
    @SerialName("state") val state: MigrationState = MigrationState.NOT_STARTED,
    @SerialName("attempts") val attempts: Int = 0,
    @SerialName("startedAt") val startedAt: Long? = null,
    @SerialName("finishedAt") val finishedAt: Long? = null,
    @SerialName("counts") val counts: Map<String, Int> = emptyMap(),
    @SerialName("failedKeys") val failedKeys: List<String> = emptyList(),
    @SerialName("recovered") val recovered: List<String> = emptyList(),
    @SerialName("warnings") val warnings: List<String> = emptyList(),
    @SerialName("eras") val eras: List<StorageEra> = emptyList(),
    /** Secure keys written by the import; cleanup checks they are still readable. */
    @SerialName("importedSecrets") val importedSecrets: List<String> = emptyList(),
    /** Native app starts counted since the import (cleanup needs ≥ 3). */
    @SerialName("nativeStarts") val nativeStarts: Int = 0,
    /** The Flutter prefs were copied (retries only touch secrets). */
    @SerialName("prefsImported") val prefsImported: Boolean = false,
    /** The Flutter diagnostic log was handed to AppLogger.importLegacy. */
    @SerialName("logImported") val logImported: Boolean = false,
    @SerialName("cleanedAt") val cleanedAt: Long? = null,
) {
    fun toReport(): MigrationReport = MigrationReport(
        state = state,
        attempts = attempts,
        eras = eras.toSet(),
        counts = counts,
        failedKeys = failedKeys,
        recovered = recovered,
        warnings = warnings,
        startedAt = startedAt,
        finishedAt = finishedAt,
    )
}

internal class MigrationStateStore(private val dir: File) {

    private val file = AtomicFile(File(dir, FILE_NAME))

    /** The stored state; a missing file is `NOT_STARTED`. An unreadable file is kept and also read as `NOT_STARTED`. */
    fun read(): PersistedState {
        val base = file.baseFile
        if (!base.exists() && !File(base.path + ".new").exists() && !File(base.path + ".bak").exists()) return PersistedState()
        return try {
            JSON.decodeFromString(PersistedState.serializer(), String(file.readFully(), Charsets.UTF_8))
        } catch (e: Exception) {
            // Keep the broken file for support; a re-import is safe because native values are never overwritten.
            runCatching { base.copyTo(File(dir, "$FILE_NAME.corrupt-${System.nanoTime()}"), overwrite = false) }
            PersistedState(warnings = listOf("STATE_FILE_CORRUPT"))
        }
    }

    /** Writes [state] atomically. */
    @Throws(IOException::class)
    fun write(state: PersistedState) {
        dir.mkdirs()
        val out = file.startWrite()
        try {
            out.write(JSON.encodeToString(PersistedState.serializer(), state).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    companion object {
        const val DIR_NAME = "flutter_migration"
        const val FILE_NAME = "state.json"
        const val SNAPSHOT_DIR = "snapshot"

        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}
