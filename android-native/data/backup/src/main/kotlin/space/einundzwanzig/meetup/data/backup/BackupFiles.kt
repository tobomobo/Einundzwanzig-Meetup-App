package space.einundzwanzig.meetup.data.backup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.data.badges.text.DartString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime

/** The backup file could not be read (I/O error, too large, or not valid UTF-8). */
class BackupReadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Stream I/O of `.21bkp` files. The UI obtains the streams from SAF
 * (`ContentResolver.openInputStream(uri)` after `OpenDocument`, `openOutputStream(uri)` after
 * `CreateDocument(MIME_TYPE)`) or from a share-cache file; this module never touches `Uri`s.
 */
object BackupFiles {
    /** File extension of backups. */
    const val EXTENSION: String = ".21bkp"

    /** MIME type for SAF `CreateDocument` and the share intent. */
    const val MIME_TYPE: String = "application/octet-stream"

    /** MIME filter for SAF `OpenDocument` (Flutter `FileType.any`). */
    val OPEN_MIME_TYPES: Array<String> = arrayOf("*/*")

    /**
     * Upper bound for a backup file. Real files are a few KB to a few MB (about 1 KB per badge).
     * The cap keeps an unrelated large file picked with the any-type filter from exhausting the
     * heap (bytes, UTF-16 text, decrypted copy and JSON tree all live at once).
     */
    const val MAX_FILE_BYTES: Int = 16 * 1024 * 1024

    /**
     * Reads the whole file and decodes it as strict UTF-8 (Dart `utf8.decode(bytes)`; a leading BOM
     * is dropped like Dart). Closes [input].
     *
     * @throws BackupReadException on I/O errors (also unchecked ones of content providers, e.g. a
     *   [SecurityException] after a revoked grant), files over [MAX_FILE_BYTES] or malformed UTF-8
     *   (Flutter: generic "Import fehlgeschlagen").
     */
    suspend fun read(input: InputStream, dispatcher: CoroutineDispatcher = Dispatchers.IO): String =
        withContext(dispatcher) {
            val bytes = try {
                input.use { readCapped(it) }
            } catch (e: BackupReadException) {
                throw e
            } catch (e: IOException) {
                throw BackupReadException("Datei konnte nicht gelesen werden.", e)
            } catch (e: SecurityException) {
                throw BackupReadException("Kein Zugriff auf die Datei.", e)
            } catch (e: RuntimeException) {
                // SAF providers may throw IllegalStateException, UncheckedIOException, ... while reading.
                throw BackupReadException("Datei konnte nicht gelesen werden.", e)
            }
            try {
                DartUtf8.decodeStrict(bytes)
            } catch (e: Exception) {
                throw BackupReadException("Datei ist kein gültiger UTF-8-Text.", e)
            }
        }

    /**
     * Opens and reads a file in one step, e.g. `read({ contentResolver.openInputStream(uri) })`.
     * [open] runs on [dispatcher]; a `null` stream, an [IOException] (incl. `FileNotFoundException`)
     * or a [SecurityException] from it become a [BackupReadException], like every error of
     * [read] (Flutter: generic "Import fehlgeschlagen").
     *
     * @throws BackupReadException if the file could not be opened or read.
     */
    suspend fun read(open: () -> InputStream?, dispatcher: CoroutineDispatcher = Dispatchers.IO): String {
        val input = withContext(dispatcher) {
            try {
                open()
            } catch (e: IOException) {
                throw BackupReadException("Datei konnte nicht geöffnet werden.", e)
            } catch (e: SecurityException) {
                throw BackupReadException("Kein Zugriff auf die Datei.", e)
            }
        } ?: throw BackupReadException("Datei konnte nicht geöffnet werden.")
        return read(input, dispatcher)
    }

    /**
     * Writes [content] as UTF-8 (no BOM, no trailing newline) and closes [output].
     *
     * @throws IOException if writing failed (the export must then not report success).
     */
    suspend fun write(output: OutputStream, content: String, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        withContext(dispatcher) {
            output.use {
                it.write(DartUtf8.encode(content))
                it.flush()
            }
        }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val hint = runCatching { input.available() }.getOrDefault(0).coerceIn(0, MAX_FILE_BYTES)
        val out = ByteArrayOutputStream(maxOf(hint, 16 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_FILE_BYTES) throw BackupReadException("Datei ist zu groß für ein Backup.")
        }
        return out.toByteArray()
    }
}

/**
 * Suggested export file name (spec 08 §2.4 step 8):
 * `Backup_<yyyy-MM-dd_HHmm>_21MeetupApp_<safeNick>.21bkp`.
 */
object BackupFileName {
    /**
     * [nickname] is trimmed (Dart `trim`); empty → `Anon`; every UTF-16 code unit outside
     * `[A-Za-z0-9_-]` becomes one `_` (an emoji surrogate pair gives `__`, like Dart's `replaceAll`).
     * [now] is the device-local wall time.
     */
    fun of(nickname: String, now: LocalDateTime): String {
        val trimmed = DartString.trim(nickname)
        val safeNick = if (trimmed.isEmpty()) {
            "Anon"
        } else {
            buildString(trimmed.length) {
                for (c in trimmed) append(if (c.isSafe()) c else '_')
            }
        }
        val stamp = buildString {
            append(now.year.toString().padStart(4, '0'))
            append('-').append(now.monthValue.pad2())
            append('-').append(now.dayOfMonth.pad2())
            append('_').append(now.hour.pad2()).append(now.minute.pad2())
        }
        return "Backup_${stamp}_21MeetupApp_$safeNick${BackupFiles.EXTENSION}"
    }

    private fun Char.isSafe(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '_' || this == '-'

    private fun Int.pad2(): String = toString().padStart(2, '0')
}

/** Password rules of the backup dialogs (spec 08 §2.2). No trimming, no character classes. */
object BackupPasswordRules {
    /** `BackupService.minPasswordLength` (independent of the ncryptsec minimum). */
    const val MIN_PASSWORD_LENGTH: Int = 8

    /** At least [MIN_PASSWORD_LENGTH] UTF-16 code units (Dart `String.length`). */
    fun longEnough(password: String): Boolean = password.length >= MIN_PASSWORD_LENGTH

    /** Both entries are equal and not empty. */
    fun matches(password: String, confirm: String): Boolean = password.isNotEmpty() && password == confirm

    /** Export: both rules; import: always enabled (old backups may have shorter passwords). */
    fun canSubmit(isExport: Boolean, password: String, confirm: String = ""): Boolean =
        !isExport || (longEnough(password) && matches(password, confirm))
}
