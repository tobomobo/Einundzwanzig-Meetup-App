package space.einundzwanzig.meetup.core.ui.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.ui.R
import space.einundzwanzig.meetup.core.ui.util.UiLog
import space.einundzwanzig.meetup.core.ui.util.findActivity
import java.io.File
import java.io.IOException

/**
 * The library's FileProvider (authority `<applicationId>.fileprovider`, paths in
 * `res/xml/core_ui_share_paths.xml`). A subclass so it cannot clash with another library's
 * `androidx.core.content.FileProvider` manifest entry.
 */
class ShareFileProvider : FileProvider(R.xml.core_ui_share_paths)

/**
 * Sharing via `ACTION_SEND` + chooser (Flutter `share_plus`). Files are written to
 * `cacheDir/share/` ([SHARE_DIR]) and handed out as content URIs with a temporary read grant.
 *
 * The fire-and-forget functions return whether a chooser could be started. The `…ForResult`
 * variants additionally report whether the user picked a target ([ShareResult], share_plus
 * `ShareResultStatus`), which the backup flow needs ("dismissed is not success", spec 08).
 */
object ShareHelper {
    /** Sub-directory of the cache dir exposed by [ShareFileProvider]. */
    const val SHARE_DIR = "share"

    /** Second exposed cache sub-directory, for backup files (spec 08 §11: `cacheDir/backup/`). */
    const val BACKUP_DIR = "backup"

    /** FileProvider authority for this app. */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** `cacheDir/share/`, created on demand. */
    fun shareDir(context: Context): File = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }

    /**
     * Writes [bitmap] as PNG to `cacheDir/share/[fileName]` (on IO) and returns its content URI.
     *
     * @throws IOException if the file cannot be written.
     */
    suspend fun writePng(context: Context, bitmap: Bitmap, fileName: String): Uri = withContext(Dispatchers.IO) {
        val file = File(shareDir(context), sanitize(fileName))
        file.outputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG-Kodierung fehlgeschlagen")
        }
        uriFor(context, file)
    }

    /**
     * Writes [bytes] to `cacheDir/share/[fileName]` (on IO) and returns its content URI
     * (backups, exports).
     */
    suspend fun writeFile(context: Context, bytes: ByteArray, fileName: String): Uri = withContext(Dispatchers.IO) {
        val file = File(shareDir(context), sanitize(fileName))
        file.writeBytes(bytes)
        uriFor(context, file)
    }

    /** `cacheDir/backup/`, created on demand (exposed by [ShareFileProvider] as well). */
    fun backupDir(context: Context): File = File(context.cacheDir, BACKUP_DIR).apply { mkdirs() }

    /** Content URI for a file inside [shareDir] or [backupDir]. */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /**
     * Opens the system share sheet for a file.
     *
     * @return false if no app can receive the share.
     */
    fun shareFile(
        context: Context,
        uri: Uri,
        mimeType: String,
        subject: String? = null,
        text: String? = null,
        chooserTitle: String? = null,
    ): Boolean {
        return startChooser(context, fileIntent(uri, mimeType, subject, text), chooserTitle)
    }

    /** Shares a PNG [bitmap] (writes it first). See [writePng] and [shareFile]. */
    suspend fun shareImage(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        subject: String? = null,
        text: String? = null,
    ): Boolean {
        val uri = writePng(context, bitmap, fileName)
        return shareFile(context, uri, "image/png", subject, text)
    }

    /** Shares plain text. */
    fun shareText(context: Context, text: String, subject: String? = null, chooserTitle: String? = null): Boolean {
        return startChooser(context, textIntent(text, subject), chooserTitle)
    }

    /**
     * Like [shareFile], but suspends until the share sheet closes and reports the outcome.
     * Needs a lifecycle-aware activity context; otherwise [ShareResult.Unavailable].
     */
    suspend fun shareFileForResult(
        context: Context,
        uri: Uri,
        mimeType: String,
        subject: String? = null,
        text: String? = null,
        chooserTitle: String? = null,
    ): ShareResult = startChooserForResult(context, fileIntent(uri, mimeType, subject, text), chooserTitle)

    /** Like [shareText], but suspends until the share sheet closes and reports the outcome. */
    suspend fun shareTextForResult(
        context: Context,
        text: String,
        subject: String? = null,
        chooserTitle: String? = null,
    ): ShareResult = startChooserForResult(context, textIntent(text, subject), chooserTitle)

    private suspend fun startChooserForResult(context: Context, send: Intent, title: String?): ShareResult =
        withContext(Dispatchers.Main) {
            val (token, chosen) = ShareChoices.register()
            try {
                val chooser = Intent.createChooser(send, title, ShareChoices.callback(context, token).intentSender)
                if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!launch(context, chooser)) return@withContext ShareResult.Unavailable
                val lifecycle = (context.findActivity() as? LifecycleOwner)?.lifecycle
                    ?: return@withContext ShareResult.Unavailable
                ShareChoices.awaitChoice(lifecycle, chosen)
            } finally {
                ShareChoices.unregister(token)
            }
        }

    private fun fileIntent(uri: Uri, mimeType: String, subject: String?, text: String?) =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            clipData = ClipData.newRawUri(subject ?: "", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun textIntent(text: String, subject: String?) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }

    private fun startChooser(context: Context, send: Intent, title: String?): Boolean {
        val chooser = Intent.createChooser(send, title).apply {
            if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(context, chooser)
    }

    private fun launch(context: Context, chooser: Intent): Boolean {
        return try {
            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            UiLog.warn("Share", "Kein Ziel zum Teilen verfügbar", e)
            false
        }
    }

    /** Keeps file names inside the share dir (no path separators). */
    internal fun sanitize(fileName: String): String =
        fileName.replace('/', '_').replace('\\', '_').ifBlank { "share.bin" }
}
