package space.einundzwanzig.meetup.core.common.log

import android.content.Context
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.DartJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Persistence behind [DiagnosticLogger]. All functions are blocking and are only called on an
 * I/O thread (or synchronously from the crash handler).
 */
interface LogStore {
    /**
     * Reads the stored buffer, oldest first. Unreadable single entries are skipped and counted.
     *
     * @throws IOException if the storage itself cannot be read; the logger then calls
     *   [quarantine] and continues with the entries recorded since process start (Dart: "corrupt
     *   buffer").
     */
    fun readEntries(): StoredEntries

    /**
     * Moves an unreadable stored buffer aside (replacing an older one that was set aside), so the
     * next [writeEntries] cannot destroy it. Does nothing if there is no stored buffer.
     *
     * @throws IOException if the buffer cannot be moved; the logger then stops persisting for the
     *   rest of the process instead of overwriting it.
     */
    fun quarantine()

    /** Replaces the stored buffer with [entries] (already capped by the logger). */
    fun writeEntries(entries: List<LogEntry>)

    /** Removes the stored buffer (Dart `prefs.remove('diagnostic_log')`). */
    fun clearEntries()

    /** The persisted verbose flag (Dart `diagnostic_verbose`, default `false`). */
    fun readVerbose(): Boolean

    /** Persists the verbose flag. */
    fun writeVerbose(on: Boolean)
}

/** Result of [LogStore.readEntries]. */
data class StoredEntries(val entries: List<LogEntry>, val corruptEntries: Int = 0) {
    companion object {
        val EMPTY = StoredEntries(emptyList())
    }
}

/**
 * Stores the buffer as JSON lines (one [LogEntry.toJsonLine] per line, UTF-8) in
 * `<directory>/diagnostic_log.jsonl`, and the verbose flag in `<directory>/diagnostic_verbose`.
 *
 * Writes go to a temporary file that is synced and renamed over the old one, so a crash during
 * a write leaves either the old or the new buffer — never half a file.
 */
class FileLogStore(private val directory: File) : LogStore {

    private val logFile = File(directory, LOG_FILE_NAME)
    private val tempFile = File(directory, "$LOG_FILE_NAME.tmp")
    private val verboseFile = File(directory, VERBOSE_FILE_NAME)

    override fun readEntries(): StoredEntries {
        if (!logFile.exists()) return StoredEntries.EMPTY
        val entries = ArrayList<LogEntry>()
        var corrupt = 0
        logFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val entry = try {
                    LogEntry.fromJson(DartJson.decode(line) as JsonObject)
                } catch (_: Exception) {
                    null
                }
                if (entry == null) corrupt++ else entries += entry
            }
        }
        return StoredEntries(entries, corrupt)
    }

    override fun writeEntries(entries: List<LogEntry>) {
        if (entries.isEmpty()) return clearEntries()
        directory.mkdirs()
        FileOutputStream(tempFile).use { out ->
            val writer = out.bufferedWriter(Charsets.UTF_8)
            for (entry in entries) {
                writer.write(entry.toJsonLine())
                writer.write('\n'.code)
            }
            writer.flush()
            out.fd.sync()
        }
        if (!tempFile.renameTo(logFile)) {
            tempFile.delete()
            throw IOException("Could not replace $logFile")
        }
    }

    override fun clearEntries() {
        tempFile.delete()
        if (logFile.exists() && !logFile.delete()) throw IOException("Could not delete $logFile")
    }

    /** Renames `diagnostic_log.jsonl` to `diagnostic_log.jsonl.unreadable`. */
    override fun quarantine() {
        if (!logFile.exists()) return
        val target = File(directory, UNREADABLE_FILE_NAME)
        if (target.exists() && !target.delete()) throw IOException("Could not replace $target")
        if (!logFile.renameTo(target)) throw IOException("Could not move $logFile aside")
    }

    override fun readVerbose(): Boolean =
        verboseFile.exists() && verboseFile.readText(Charsets.UTF_8).trim() == "true"

    override fun writeVerbose(on: Boolean) {
        directory.mkdirs()
        verboseFile.writeText(on.toString(), Charsets.UTF_8)
    }

    companion object {
        const val LOG_FILE_NAME = "diagnostic_log.jsonl"
        const val VERBOSE_FILE_NAME = "diagnostic_verbose"

        /** Where [quarantine] keeps the last unreadable buffer. */
        const val UNREADABLE_FILE_NAME = "$LOG_FILE_NAME.unreadable"

        /** The store in the app's private `filesDir`. */
        fun forContext(context: Context): FileLogStore = FileLogStore(context.filesDir)
    }
}

/** A [LogStore] in memory, for tests and previews. */
class InMemoryLogStore(
    entries: List<LogEntry> = emptyList(),
    verbose: Boolean = false,
) : LogStore {
    @Volatile
    var stored: List<LogEntry>? = entries.takeIf { it.isNotEmpty() }
        private set

    @Volatile
    var verbose: Boolean = verbose
        private set

    /** Number of [writeEntries] calls so far. */
    @Volatile
    var writes: Int = 0
        private set

    /** The buffer moved aside by the last [quarantine], or `null`. */
    @Volatile
    var quarantined: List<LogEntry>? = null
        private set

    /** When set, [readEntries] throws it (simulates unreadable storage). */
    @Volatile
    var failReadsWith: IOException? = null

    /** When set, [readVerbose] throws it. */
    @Volatile
    var failVerboseReadsWith: IOException? = null

    /** When set, [quarantine] throws it. */
    @Volatile
    var failQuarantineWith: IOException? = null

    override fun readEntries(): StoredEntries {
        failReadsWith?.let { throw it }
        return StoredEntries(stored.orEmpty())
    }

    override fun quarantine() {
        failQuarantineWith?.let { throw it }
        val current = stored ?: return
        quarantined = current
        stored = null
    }

    override fun writeEntries(entries: List<LogEntry>) {
        writes++
        stored = entries.toList().takeIf { it.isNotEmpty() }
    }

    override fun clearEntries() {
        stored = null
    }

    override fun readVerbose(): Boolean {
        failVerboseReadsWith?.let { throw it }
        return verbose
    }

    override fun writeVerbose(on: Boolean) {
        verbose = on
    }
}
