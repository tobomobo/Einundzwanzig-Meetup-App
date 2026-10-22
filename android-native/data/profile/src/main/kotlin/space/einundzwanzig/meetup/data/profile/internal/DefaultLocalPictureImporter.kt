package space.einundzwanzig.meetup.data.profile.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.profile.LocalPictureImporter
import space.einundzwanzig.meetup.data.profile.LocalPictureImporter.Companion.DIR
import space.einundzwanzig.meetup.data.profile.LocalPictureImporter.Companion.MAX_SIZE
import space.einundzwanzig.meetup.data.profile.LocalPictureImporter.Companion.QUALITY
import space.einundzwanzig.meetup.data.profile.NostrProfileService
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * [LocalPictureImporter] on `BitmapFactory`. [encode] (test seam) writes the scaled JPEG of a
 * URI into a file. Order: encode new file → `setLocalPicture` → delete older files, so a failure
 * never leaves the pref pointing at a deleted file.
 */
internal class DefaultLocalPictureImporter(
    private val filesDir: File,
    private val profiles: NostrProfileService,
    private val time: TimeSource,
    private val io: CoroutineDispatcher,
    private val encode: (uri: String, target: File) -> Unit,
) : LocalPictureImporter {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        profiles: NostrProfileService,
        time: TimeSource,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(context.filesDir, profiles, time, io, { uri, target -> encodeScaledJpeg(context, uri, target) })

    override suspend fun import(uri: String): String {
        val dir = File(filesDir, DIR)
        val target = withContext(io) {
            dir.mkdirs()
            val file = File(dir, "local_${time.nowMillis()}.jpg")
            try {
                encode(uri, file)
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
            file
        }
        val previous = runCatching { profiles.getLocalPicture() }.getOrNull()
        try {
            profiles.setLocalPicture(target.absolutePath)
        } catch (e: Throwable) {
            withContext(io + kotlinx.coroutines.NonCancellable) { target.delete() }
            throw e
        }
        withContext(io) { cleanup(dir, target, previous) }
        return target.absolutePath
    }

    /** Keeps only [target]: other files in [dir] and a previous picture inside `filesDir` (migrated copy). */
    private fun cleanup(dir: File, target: File, previous: String?) {
        try {
            dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            if (!previous.isNullOrEmpty()) {
                val old = File(previous).canonicalFile
                val root = filesDir.canonicalFile
                if (old != target.canonicalFile && old.isFile && old.path.startsWith(root.path + File.separator)) old.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Altes Profilbild nicht gelöscht", e)
        }
    }

    private companion object {
        const val TAG = "LocalPicture"

        fun encodeScaledJpeg(context: Context, uri: String, target: File) {
            val source = Uri.parse(uri)
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                ?: throw IOException("Bild nicht lesbar")
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Kein Bild")
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_SIZE && bounds.outHeight / (sample * 2) >= MAX_SIZE) sample *= 2
            val decoded = resolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw IOException("Bild nicht lesbar")
            val scale = minOf(1f, MAX_SIZE.toFloat() / decoded.width, MAX_SIZE.toFloat() / decoded.height)
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                decoded
            }
            try {
                val ok = target.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out) }
                if (!ok) throw IOException("Bild nicht gespeichert")
            } finally {
                if (bitmap !== decoded) bitmap.recycle()
                decoded.recycle()
            }
        }
    }
}
