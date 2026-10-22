package space.einundzwanzig.meetup.core.common.log

import java.io.File

/**
 * Sharing the diagnostic log (Dart `LogScreen` copy/share actions). Copy and share always use
 * the **full** buffer ([DiagnosticLog.exportText]), not the filtered view.
 *
 * Large exports should be shared as a file: `Intent.EXTRA_TEXT` goes through a Binder
 * transaction (about 1 MB for everything in flight), and 800 entries can come close.
 */
object LogExport {
    /** `EXTRA_SUBJECT` of the share intent (hard-coded German in the Flutter app). */
    const val SHARE_SUBJECT = "21Meetup Diagnose-Log"

    /** File name for [writeToFile]. */
    const val FILE_NAME = "21meetup-diagnose-log.txt"

    /** Above this many UTF-8 bytes, share a file instead of `EXTRA_TEXT`. */
    const val INLINE_TEXT_LIMIT_BYTES = 100_000

    /** `true` if [text] is too large for `EXTRA_TEXT`. */
    fun shouldShareAsFile(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size > INLINE_TEXT_LIMIT_BYTES

    /**
     * Writes [text] as UTF-8 to `directory/fileName` (blocking — call on an I/O dispatcher) and
     * returns the file, e.g. for a `FileProvider` URI under `cacheDir`.
     */
    fun writeToFile(text: String, directory: File, fileName: String = FILE_NAME): File {
        directory.mkdirs()
        return File(directory, fileName).apply { writeText(text, Charsets.UTF_8) }
    }
}
