package space.einundzwanzig.meetup.core.data.file

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import space.einundzwanzig.meetup.core.data.internal.DataLog
import java.io.File

/**
 * App-private files (JSON caches, blobs) below one root directory; production root is
 * `Context.filesDir`, which is excluded from backups like everything else in this app.
 *
 * Paths are relative, `/`-separated (`"social/graph.json"`); absolute paths and `.`/`..`
 * segments are rejected with [IllegalArgumentException]. Every write is atomic: the bytes go to
 * a temporary file in the same directory, are fsync'ed and then renamed over the target, so a
 * crash leaves either the old or the new content, never a torn file. Concurrent writers to the
 * same path do not corrupt each other (last rename wins); read-modify-write sequences need the
 * caller's own lock.
 *
 * I/O errors are logged and reported as `null` / `false`; they are never thrown.
 */
interface FileStore {

    /** The content of [path], or `null` if it does not exist or cannot be read. */
    suspend fun readBytes(path: String): ByteArray?

    /** [readBytes] decoded as UTF-8 (malformed sequences become U+FFFD). */
    suspend fun readText(path: String): String? = readBytes(path)?.decodeToString()

    /** Atomically replaces [path] with [bytes], creating parent directories. */
    suspend fun writeBytes(path: String, bytes: ByteArray): Boolean

    /** Atomically replaces [path] with [text] encoded as UTF-8. */
    suspend fun writeText(path: String, text: String): Boolean = writeBytes(path, text.encodeToByteArray())

    /** Deletes the file at [path]. Deleting a missing file succeeds. */
    suspend fun delete(path: String): Boolean

    /** Whether a regular file exists at [path]. */
    suspend fun exists(path: String): Boolean

    /** Names of the regular files directly inside [directory] (`""` = root), sorted. */
    suspend fun list(directory: String = ""): List<String>

    /**
     * The absolute [File] for [path], for APIs that need one (FileProvider sharing, decoders).
     * Prefer the atomic write methods over writing to this file directly.
     */
    fun resolve(path: String): File

    companion object {
        /**
         * A [FileStore] rooted at [root]. Production code injects the singleton instead; this
         * factory is for tests (temporary folders) and for stores with their own root.
         */
        fun inDirectory(root: File, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): FileStore =
            DirectoryFileStore({ root }, ioDispatcher)
    }
}

/**
 * Reads [path] and decodes it with [json] (pass `AppJson` from `:core:network`).
 * Returns `null` when the file is missing, unreadable or not decodable (logged).
 */
suspend fun <T> FileStore.readJson(path: String, deserializer: DeserializationStrategy<T>, json: Json): T? {
    val text = readText(path) ?: return null
    return try {
        json.decodeFromString(deserializer, text)
    } catch (e: SerializationException) {
        // The exception message quotes the file content: log the type only.
        DataLog.w("FileStore", "Could not decode JSON file '$path' (${e.javaClass.simpleName})")
        null
    } catch (e: IllegalArgumentException) {
        DataLog.w("FileStore", "Could not decode JSON file '$path' (${e.javaClass.simpleName})")
        null
    }
}

/** Encodes [value] with [json] and atomically writes it to [path]. */
suspend fun <T> FileStore.writeJson(path: String, serializer: SerializationStrategy<T>, value: T, json: Json): Boolean =
    writeText(path, json.encodeToString(serializer, value))
